import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { isDeepStrictEqual } from 'node:util';
import { expect, test } from '@drownek/plugwright';
import nbt from 'prismarine-nbt';
import { Vec3 } from 'vec3';
import { waitForQa } from './qa.js';

const mainMenu = '/arcevents menu';
const idle = { phase: 'idle', participants: 0, queue: 0 };
const dock = new Vec3(0.5, 65, 16.5);
const castTarget = new Vec3(0.5, 63.7, 24.5);

async function until(probe, message, timeout = 10000) {
  const deadline = Date.now() + timeout;
  do {
    const result = await probe();
    if (result) return result;
    await new Promise((resolve) => setTimeout(resolve, 50));
  } while (Date.now() < deadline);
  assert.fail(message);
}

async function ready(mode) {
  const logPath = new URL('../../../build/plugwright-host/logs/latest.log', import.meta.url);
  await until(async () => {
    const log = await readFile(logPath, 'utf8');
    assert.doesNotMatch(log, /ArcEvents (?:startup preparation failed closed|failed closed during startup)/);
    return log.includes(`ArcEvents arena ready id=${mode} world=`);
  }, `ArcEvents did not publish its ready ${mode} arena`, 60000);
}

function observe(player) {
  const client = player.bot._client;
  const observed = { screens: [], world: player.bot._getDimensionName() };
  const dialog = ({ dialog }) => {
    if (dialog.data) observed.screens.push(nbt.simplify(dialog.data));
  };
  // Paper can change worlds while keeping the same dimension type.
  const respawn = ({ worldState }) => { observed.world = worldState.name; };
  client.on('show_dialog', dialog);
  client.on('respawn', respawn);
  observed.stop = () => {
    client.removeListener('show_dialog', dialog);
    client.removeListener('respawn', respawn);
  };
  return observed;
}

const button = (screen, id) => screen.actions?.find((action) => action.action?.id?.endsWith(`/${id}`));
const nextScreen = (observed, since, id) => until(
  () => observed.screens.slice(since).findLast((screen) => button(screen, id)), `Native dialog did not expose ${id}`,
);

function click(player, screen, id) {
  assert.equal(screen.after_action, 'none');
  assert.equal(screen.inputs?.length ?? 0, 0);
  const params = { id: button(screen, id).action.id, nbt: { type: 'end' } };
  const client = player.bot._client;
  // Paper 1.21.11 expects a length-prefixed optional TAG_End for buttons without inputs.
  const wire = client.serializer.createPacketBuffer({ name: 'custom_click_action', params });
  assert.equal(wire[0], 0x41);
  assert.equal(wire.subarray(-2).toString('hex'), '0100');
  client.write('custom_click_action', params);
}

async function openMode(player, observed, mode, actionId) {
  const mainId = `action_arcade_${mode}_name`;
  const since = observed.screens.length;
  player.chat(mainMenu);
  const main = await nextScreen(observed, since, mainId);
  const modeSince = observed.screens.length;
  click(player, main, mainId);
  return nextScreen(observed, modeSince, actionId);
}

function inventory(player) {
  return player.bot.inventory.slots.map((item) => item && ({
    name: item.name,
    count: item.count,
    nbt: item.nbt,
    components: [...(item.components ?? [])].sort((a, b) => a.type.localeCompare(b.type)),
  }));
}

async function seed(admin, player, observed, count) {
  const marker = `Arcade-state-${count}`;
  const origin = new Vec3(count * 4 + 0.25, -60, 18.75);
  admin.chat(`/tp ${player.username} ${origin.x} ${origin.y} ${origin.z}`);
  admin.chat(`/item replace entity ${player.username} hotbar.4 with minecraft:diamond[custom_name='{"text":"${marker}"}'] ${count}`);
  admin.chat(`/item replace entity ${player.username} armor.head with minecraft:golden_helmet`);
  admin.chat(`/item replace entity ${player.username} weapon.offhand with minecraft:shield`);
  const { bot } = player;
  await until(() => {
    const slots = bot.inventory.slots;
    return bot.entity.onGround && bot.entity.position.distanceTo(origin) < 0.001 && slots[40]?.name === 'diamond' && slots[40].count === count &&
      JSON.stringify(slots[40]).includes(marker) && slots[5]?.name === 'golden_helmet' && slots[45]?.name === 'shield';
  }, `${player.username} did not reach the seeded origin state`);
  bot.setQuickBarSlot(7);
  return { slots: inventory(player), selected: bot.quickBarSlot, position: origin, world: observed.world, gameMode: bot.game.gameMode };
}

async function restoreObserved(player, observed, before) {
  const { bot } = player;
  await until(() => observed.world === before.world && bot.entity.position.distanceTo(before.position) < 0.001 &&
    bot.game.gameMode === before.gameMode && bot.quickBarSlot === before.selected && isDeepStrictEqual(inventory(player), before.slots),
    `${player.username} did not return to the original world, position and inventory`, 15000);
}

async function walkTo(player, point, stopped = () => false) {
  const { bot } = player;
  await bot.lookAt(point.offset(0, bot.entity.eyeHeight, 0), true);
  bot.setControlState('forward', true);
  try {
    await until(() => stopped() || bot.entity.position.distanceTo(point) < 0.45,
      `${player.username} did not walk to ${point}`, 8000);
  } finally {
    bot.setControlState('forward', false);
  }
}

const tagged = (item, kind, match) => {
  const data = JSON.stringify([item?.components, item?.nbt]);
  return data.includes(kind) && data.includes(match);
};

test('Fishing enters through the public menu, lands a native bite, and restores the original player', async ({ player, createPlayer }) => {
  await ready('fishing');
  const admin = await createPlayer();
  await admin.makeOp();
  const observed = observe(player);
  const { bot } = player;
  let reeled = false;
  let restored = false;
  const reel = (message) => {
    if (reeled || !String(message).includes('Right-click to reel in.')) return;
    reeled = true;
    bot.activateItem();
  };
  try {
    await waitForQa(admin, 'status', idle);
    const before = await seed(admin, player, observed, 3);
    const startId = 'action_arcade_fishing_start_name';
    click(player, await openMode(player, observed, 'fishing', startId), startId);
    const { match } = await waitForQa(admin, 'status', { game: 'fishing', phase: 'active', arena_id: 'fishing', participants: 1 }, 20000);
    await until(() => observed.world === 'minecraft:arcevents_fishing_v4' && bot.heldItem?.name === 'fishing_rod' &&
      tagged(bot.heldItem, 'FISHING_ROD', match), 'The dedicated fishing world or its selected expedition rod did not arrive');
    assert.equal(bot.inventory.slots[40], null, 'Origin hotbar remained exposed inside Fishing');

    await walkTo(player, dock);
    await until(() => bot.blockAt(castTarget)?.name === 'water', 'Generated casting water did not arrive');
    await bot.lookAt(castTarget, true);
    const catchSince = player.getMessageBufferIndex();
    bot.on('actionBar', reel);
    bot.activateItem();
    const hook = await until(() => Object.values(bot.entities).find((entity) =>
      entity.name === 'fishing_bobber' && bot.blockAt(entity.position)?.name === 'water'), 'The ordinary cast did not send a bobber in the water');
    await expect(player).toHaveReceivedMessage(/Caught: .+RMB the ground/, { since: catchSince, timeout: 25000 });
    assert.ok(reeled, 'A catch appeared without a native bite and retrieval');
    await until(() => !bot.entities[hook.id] && bot.heldItem?.name === 'tropical_fish' &&
      tagged(bot.heldItem, 'FISHING_LIVE_CATCH', match), 'Retrieval did not consume the hook and issue its match-bound live catch');

    const since = observed.screens.length;
    player.chat(mainMenu);
    const leaveId = 'action_menu_event_evacuate_name';
    click(player, await nextScreen(observed, since, leaveId), leaveId);
    await restoreObserved(player, observed, before);
    await waitForQa(admin, 'status', idle);
    await waitForQa(admin, `player ${player.username}`, { status: 'none', recovery: false });
    restored = true;
  } finally {
    if (!restored && bot.entity) player.chat('/arcevents leave');
    bot.removeListener('actionBar', reel);
    observed.stop();
  }
});

test('Disasters starts from the public queue, awards the meteor survivor, and restores both players', async ({ player, createPlayer }) => {
  await ready('disasters');
  const peer = await createPlayer();
  const admin = await createPlayer();
  await admin.makeOp();
  const observed = observe(player);
  const peerObserved = observe(peer);
  let restored = false;
  try {
    await waitForQa(admin, 'status', idle);
    const before = await seed(admin, player, observed, 1);
    const peerBefore = await seed(admin, peer, peerObserved, 2);
    const joinId = 'action_arcade_join_name';
    click(player, await openMode(player, observed, 'disasters', joinId), joinId);
    await waitForQa(admin, 'status', { queue: 1, phase: 'idle' });
    click(peer, await openMode(peer, peerObserved, 'disasters', joinId), joinId);
    await waitForQa(admin, 'status', { queue: 2, phase: 'idle' });
    const startId = 'action_arcade_start_name';
    const since = player.getMessageBufferIndex();
    click(player, await openMode(player, observed, 'disasters', startId), startId);
    const { match } = await waitForQa(admin, 'status', { game: 'disasters', phase: 'active', arena_id: 'disasters', participants: 2 }, 20000);
    await expect(player).toHaveReceivedMessage(/Meteors in \d+s/, { since, timeout: 5000 });
    await until(() => observed.world === 'minecraft:arcevents_disasters' && peerObserved.world === observed.world &&
      [player, peer].every((entry) => entry.bot.game.gameMode === 'adventure' && entry.bot.inventory.items().length === 0),
      'The roster did not enter the dedicated arena with its survival loadout');

    // Keep moving across the open center while the peer stays in the marked circles.
    const result = () => player.messageBuffer.slice(since).some((line) => line.includes('Winners:'));
    const escaped = async () => {
      const waypoints = [[6, 0], [6, -6], [-5, -6], [-5, 5], [6, 5]];
      for (const [x, z] of waypoints) {
        await walkTo(player, new Vec3(x + 0.5, 65, z + 0.5), result);
        if (result()) return;
      }
    };
    await Promise.all([
      escaped(),
      until(() => peer.bot.game.gameMode === 'spectator', 'Stationary player survived the native meteor impacts', 14000),
    ]);
    await expect(player).toHaveReceivedMessage(new RegExp(`Winners: ${player.username}$`), { since, timeout: 5000 });
    await waitForQa(admin, 'status', { match, phase: 'resolving' }, 5000);
    await Promise.all([restoreObserved(player, observed, before), restoreObserved(peer, peerObserved, peerBefore)]);
    await waitForQa(admin, 'status', idle);
    for (const entry of [player, peer]) await waitForQa(admin, `player ${entry.username}`, { status: 'none', recovery: false });
    restored = true;
  } finally {
    for (const entry of [player, peer]) if (!restored && entry.bot.entity) entry.chat('/arcevents leave');
    observed.stop();
    peerObserved.stop();
  }
});
