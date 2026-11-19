import assert from 'node:assert/strict';
import { expect, test } from '@drownek/plugwright';
import { Vec3 } from 'vec3';
import { click, nextScreen, observe, ready, restoreObserved, seed, until, waitForQa, walkTo } from './qa.js';

const mainMenu = '/arcevents menu';
const idle = { phase: 'idle', participants: 0, queue: 0 };
const dock = new Vec3(0.5, 65, 15.5);
const castTarget = new Vec3(0.5, 63.7, 25.5);
const landing = new Vec3(0.5, 65, 10.5);

async function openMode(player, observed, mode, actionId) {
  const mainId = `action_arcade_${mode}_name`;
  const since = observed.screens.length;
  player.chat(mainMenu);
  const main = await nextScreen(observed, since, mainId);
  const modeSince = observed.screens.length;
  click(player, main, mainId);
  return nextScreen(observed, modeSince, actionId);
}

const tagged = (item, kind, match) => {
  const data = JSON.stringify([item?.components, item?.nbt]);
  return data.includes(kind) && data.includes(match);
};

test('Fishing enters through the public menu, lands a native bite, releases the catch, and restores the original player', async ({ player, createPlayer }) => {
  await ready('fishing');
  const admin = await createPlayer();
  await admin.makeOp();
  const observed = observe(player);
  const { bot } = player;
  const displayText = bot.registry.entitiesByName.text_display.metadataKeys.indexOf('text');
  let reeled = false;
  let releaseHint = false;
  let restored = false;
  const handleHint = (entity) => {
    if (entity.name !== 'text_display') return;
    const text = JSON.stringify(entity.metadata[displayText]) ?? '';
    if (!reeled && text.includes('Fish on!') && text.includes('RMB') && text.includes('Reel in')) {
      reeled = true;
      bot.activateItem();
    }
    if (text.includes('RMB on ground') && text.includes('Release')) releaseHint = true;
  };
  try {
    await waitForQa(admin, 'status', idle);
    const before = await seed(admin, player, observed, 3);
    const startId = 'action_arcade_fishing_start_name';
    click(player, await openMode(player, observed, 'fishing', startId), startId);
    const { match } = await waitForQa(admin, 'status', { game: 'fishing', phase: 'active', arena_id: 'fishing', participants: 1 }, 20000);
    await until(() => observed.world === 'minecraft:arcevents_fishing_archipelago' && bot.heldItem?.name === 'fishing_rod' &&
      tagged(bot.heldItem, 'FISHING_ROD', match), 'The dedicated fishing world or its selected expedition rod did not arrive');
    assert.equal(bot.inventory.slots[40], null, 'Origin hotbar remained exposed inside Fishing');

    await walkTo(player, dock);
    await until(() => bot.blockAt(castTarget)?.name === 'water' &&
      bot.blockAt(new Vec3(-2, 72, 37))?.name === 'spruce_stairs', 'The authored harbour or its casting water did not arrive');
    await bot.lookAt(castTarget, true);
    const catchSince = player.getMessageBufferIndex();
    bot.on('entityUpdate', handleHint);
    bot.activateItem();
    const hook = await until(() => Object.values(bot.entities).find((entity) =>
      entity.name === 'fishing_bobber' && bot.blockAt(entity.position)?.name === 'water'), 'The ordinary cast did not send a bobber in the water');
    await expect(player).toHaveReceivedMessage(/You caught .+\./, { since: catchSince, timeout: 25000 });
    assert.ok(reeled, 'A catch appeared without a native bite and retrieval');
    await until(() => !bot.entities[hook.id] && bot.heldItem?.name === 'tropical_fish' &&
      tagged(bot.heldItem, 'FISHING_LIVE_CATCH', match), 'Retrieval did not consume the hook and issue its match-bound live catch');
    await until(() => releaseHint, 'The live catch did not show its native release hint');

    await walkTo(player, landing.offset(0, 0, 2));
    const floor = bot.blockAt(landing.offset(0, -1, 0));
    assert.equal(floor?.name, 'oak_planks', 'The dry landing block did not arrive');
    const releaseSince = player.getMessageBufferIndex();
    await bot.activateBlock(floor, new Vec3(0, 1, 0), new Vec3(0.5, 1, 0.5));
    await expect(player).toHaveReceivedMessage(/Catch ashore\. LMB to attack\./, { since: releaseSince });
    await until(() => !bot.inventory.slots[40] && bot.nearestEntity((entity) =>
      ['silverfish', 'spider', 'salmon'].includes(entity.name) && entity.position.distanceTo(landing) < 1),
    'Releasing the caught item did not replace it with a native encounter on the dock');

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
    bot.removeListener('entityUpdate', handleHint);
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
    await until(() => observed.world === 'minecraft:arcevents_disasters_coast' && peerObserved.world === observed.world &&
      [player, peer].every((entry) => entry.bot.game.gameMode === 'adventure' && entry.bot.inventory.items().length === 0),
      'The roster did not enter the dedicated arena with its survival loadout');
    await until(() => player.bot.blockAt(new Vec3(-14, 72, 0))?.name === 'waxed_weathered_cut_copper' &&
      player.bot.blockAt(new Vec3(19, 69, 0))?.name === 'smooth_sandstone',
      'The coastal arena shelter and elevated platform did not arrive');

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
