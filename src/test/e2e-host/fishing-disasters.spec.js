import assert from 'node:assert/strict';
import { expect, test } from '@drownek/plugwright';
import nbt from 'prismarine-nbt';
import { Vec3 } from 'vec3';
import { click, nextScreen, observe, observeSounds, ready, restoreObserved, seed, until, waitForQa, walkTo } from './qa.js';

const mainMenu = '/arcevents menu';
const idle = { phase: 'idle', participants: 0, queue: 0 };
const dock = new Vec3(0.5, 65, 15.5);
const castTarget = new Vec3(0.5, 63.7, 25.5);
const landing = new Vec3(0.5, 65, 10.5);
const camp = new Vec3(0.5, 65, 0.5);
const campTrader = new Vec3(8.5, 65, -1.5);
const coinNbt = Buffer.from([0xed, 0xa0, 0xbd, 0xed, 0xb2, 0xb0]); // U+1F4B0 in Java's modified UTF-8.

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

function componentText(component) {
  if (typeof component === 'string') return component;
  if (Array.isArray(component)) return component.map(componentText).join('');
  return (component?.text ?? '') + componentText(component?.extra ?? []);
}

function observeFishingSidebar(bot) {
  let title = '';
  let displayedObjective;
  const rows = new Map();
  const onObjective = ({ name, action, displayText }) => {
    if (name !== 'arc_sidebar') return;
    title = action === 1 ? '' : componentText(nbt.simplify(displayText));
    if (action === 1) rows.clear();
  };
  const onDisplay = ({ position, name }) => {
    if (position === 1) displayedObjective = name;
  };
  const onScorePacket = (score, metadata, packet) => {
    if (metadata?.name !== 'scoreboard_score' || !score) return;
    const { scoreName, itemName, display_name: displayName } = score;
    if (scoreName === 'arc_sidebar' && displayName) {
      const row = componentText(nbt.simplify(displayName));
      // The JS NBT reader decodes ordinary UTF-8. Verify the actual Java wire bytes before restoring the glyph.
      rows.set(itemName, packet?.includes(coinNbt) ? row.replace(/\uFFFD{6}/gu, '💰') : row);
    }
  };
  const listeners = {
    scoreboard_objective: onObjective,
    scoreboard_display_objective: onDisplay,
    packet: onScorePacket,
  };
  for (const [name, listener] of Object.entries(listeners)) bot._client.on(name, listener);
  return {
    visible: () => displayedObjective === 'arc_sidebar' && title === 'Islands of the Deep',
    hasRow: (line) => [...rows.values()].some((row) => row.includes(line)),
    snapshot: () => ({ title, displayedObjective, rows: [...rows.values()] }),
    stop() {
      for (const [name, listener] of Object.entries(listeners)) bot._client.removeListener(name, listener);
    },
  };
}

test('Fishing enters through the public menu, picks up and feeds a defeated catch for coins, and restores the original player', async ({ player, createPlayer }) => {
  await ready('fishing');
  const admin = await createPlayer();
  await admin.makeOp();
  const observed = observe(player);
  const observedSounds = observeSounds(player);
  const { bot } = player;
  const sidebar = observeFishingSidebar(bot);
  const displayText = bot.registry.entitiesByName.text_display.metadataKeys.indexOf('text');
  const interactionKeys = bot.registry.entitiesByName.interaction.metadataKeys;
  const hurtEntities = new Set();
  const removedEntities = new Set();
  const injuryParticles = [];
  const markHurt = ({ entityId }) => hurtEntities.add(entityId);
  const markRemoved = ({ entityIds }) => entityIds.forEach((id) => removedEntities.add(id));
  const markInjury = (packet) => {
    if (packet.particle?.type === 'damage_indicator') injuryParticles.push(packet);
  };
  bot._client.on('hurt_animation', markHurt);
  bot._client.on('entity_destroy', markRemoved);
  bot._client.on('world_particles', markInjury);
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
    await until(() => observed.world === 'minecraft:arcevents_fishing_archipelago_v2' && bot.heldItem?.name === 'fishing_rod' &&
      tagged(bot.heldItem, 'FISHING_ROD', match), 'The dedicated fishing world or its selected expedition rod did not arrive');
    assert.equal(bot.inventory.slots[40], null, 'Origin hotbar remained exposed inside Fishing');
    try {
      await until(() => sidebar.visible() && sidebar.hasRow('Expedition coins 0💰'),
        'The Fishing sidebar did not show a starting balance with the coin glyph');
    } catch (failure) {
      throw new Error(`${failure.message}: ${JSON.stringify(sidebar.snapshot())}`, { cause: failure });
    }

    await walkTo(player, dock);
    await until(() => bot.blockAt(castTarget)?.name === 'water' &&
      [-17, 9].every((x) => bot.blockAt(new Vec3(x, 86, 47))?.name === 'cobblestone_stairs'),
    'The harbour gables or their open casting water did not arrive');
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
    await expect(player).toHaveReceivedMessage(/Catch ashore\..*strike with LMB/, { since: releaseSince });
    const catchEntity = await until(() => !bot.inventory.slots[40] && bot.nearestEntity((entity) =>
      ['silverfish', 'spider', 'salmon'].includes(entity.name) && entity.position.distanceTo(landing) < 1),
    'Releasing the caught item did not replace it with a native encounter on the dock');
    await until(() => bot.quickBarSlot === 1 && tagged(bot.heldItem, 'FISHING_WEAPON', match),
      'Releasing the catch did not select the expedition knuckles');
    assert.equal(catchEntity.name, 'silverfish', 'The first landed camp catch was not the clam');
    const attributeSince = admin.getMessageBufferIndex();
    admin.chat(`/execute in minecraft:arcevents_fishing_archipelago_v2 run attribute ${catchEntity.uuid} minecraft:scale base get`);
    await expect(admin).toHaveReceivedMessage(/scale.*1\.8/i, { since: attributeSince, timeout: 5000 });
    // This Mineflayer decoder misnames several 1.21.11 attribute IDs. Match the independently verified native value on this entity.
    await until(() => Object.values(catchEntity.attributes ?? {}).some(({ value }) => Math.abs(value - 1.8) < .001),
      'The client did not receive the smaller visual scale on the camp clam');
    const hitbox = await until(() => bot.nearestEntity((entity) => entity.name === 'interaction' &&
      entity.position.distanceTo(catchEntity.position) < .75 &&
      Math.abs(entity.metadata[interactionKeys.indexOf('width')] - 1.8) < .001 &&
      Math.abs(entity.metadata[interactionKeys.indexOf('height')] - 1.35) < .001),
    'The clam did not receive its separate, larger client hitbox');
    const startingHealth = bot.health;
    await until(() => bot.entities[catchEntity.id]?.position.distanceTo(landing) > 1,
      'The first island catch never fled from the angler');
    let lastStrike = 0;
    let lastCatchPosition = catchEntity.position.clone();
    const existingEntities = new Set(Object.keys(bot.entities).map(Number));
    try {
      await until(async () => {
        const target = bot.entities[catchEntity.id];
        if (!target) return true;
        lastCatchPosition = target.position.clone();
        const clickable = bot.entities[hitbox.id];
        if (!clickable) return false;
        const distance = target.position.distanceTo(bot.entity.position);
        await bot.lookAt(target.position.offset(0, 0.8, 0), true);
        bot.setControlState('forward', distance > 2.0);
        if (distance <= 2.7 && Date.now() - lastStrike >= 800) {
          bot.attack(clickable);
          lastStrike = Date.now();
        }
        return false;
      }, 'Pursuing the catch and striking it did not defeat the encounter', 18000);
    } finally {
      bot.setControlState('forward', false);
    }
    await until(() => !bot.entities[catchEntity.id], 'The defeated catch remained in the world');
    assert.ok(hurtEntities.has(catchEntity.id), 'No hurt animation was sent for the damaged catch');
    assert.ok(injuryParticles.length > 0, 'The accepted melee attack did not produce a visible damage particle');
    // The JVM check covers the sound key. Confirm the unique encounter hurt gain and pitch on the wire.
    assert.ok(observedSounds.sounds.some(({ name, volume, pitch }) => name.endsWith('entity.cod.hurt') ||
      (Math.abs(volume - .85) < .001 && Math.abs(pitch - 1) < .001)),
      `The accepted melee attack did not play the catch hurt sound; heard=${JSON.stringify(observedSounds.sounds.slice(-12))}`);
    assert.ok(bot.health >= startingHealth, 'A fleeing camp catch hurt its pursuer');

    const newAtCatch = (entity, name) => entity.name === name && !existingEntities.has(entity.id) &&
      entity.position.distanceTo(lastCatchPosition) < 3;
    const itemDisplayKeys = bot.registry.entitiesByName.item_display.metadataKeys;
    const displayItem = itemDisplayKeys.indexOf('item_stack');
    const body = await until(() => bot.nearestEntity((entity) => newAtCatch(entity, 'item_display') &&
      entity.metadata[displayItem]?.itemId === bot.registry.itemsByName.nautilus_shell.id),
      'The defeated catch did not leave a ground model');
    const pickup = await until(() => bot.nearestEntity((entity) =>
      entity.name === 'interaction' && entity.position.distanceTo(body.position) < 1 &&
      Math.abs(entity.metadata[interactionKeys.indexOf('height')] - .55) < .001),
    'The ground model did not receive a nearby clickable target');
    const rotation = body.metadata[itemDisplayKeys.indexOf('left_rotation')];
    assert.ok(Math.abs(rotation?.x - Math.SQRT1_2) < .001,
      'The ground model did not lie flat on the surface');
    assert.equal(bot.inventory.slots[40], null, 'Killing the catch issued it before pickup');
    await until(() => sidebar.hasRow('Defeated 1/3') && sidebar.hasRow('Expedition coins 0💰'),
      'The kill did not advance the quota while leaving the balance unpaid');
    if (bot.entity.position.distanceTo(pickup.position) > 1.8) {
      await walkTo(player, pickup.position, () => bot.entity.position.distanceTo(pickup.position) < 1.8);
    }
    await bot.activateEntityAt(pickup, pickup.position.offset(0, .15, 0));
    bot._client.write('use_entity', { target: pickup.id, mouse: 0, hand: 0, sneaking: false });
    await until(() => bot.quickBarSlot === 4 && bot.heldItem?.name === 'nautilus_shell' &&
      bot.heldItem.count === 1 && tagged(bot.heldItem, 'FISHING_CATCH_BAG', match),
    'Right-clicking the ground catch did not pick one clam into the selected hand');
    // Paper may resync metadata after cancelling the pickup interaction. Mineflayer can recreate an untyped entry.
    await until(() => [body, pickup].every(({ id, name }) => removedEntities.has(id) && bot.entities[id]?.name !== name),
      'Pickup did not remove the ground model and its clickable target from the client');
    await until(() => sidebar.hasRow('Carrying 1'), 'The sidebar did not reflect the picked-up catch');
    assert.ok(sidebar.hasRow('Expedition coins 0💰'), 'Pickup credited coins before the trader was fed');

    await walkTo(player, landing);
    await walkTo(player, camp);
    await walkTo(player, campTrader.offset(-2, 0, 0));
    const trader = await until(() => bot.nearestEntity((entity) => entity.name === 'villager' &&
      entity.position.distanceTo(campTrader) < 1), 'The camp trader did not spawn at its stall');
    bot.setQuickBarSlot(0);
    const traderSince = observed.screens.length;
    await bot.activateEntity(trader);
    const traderMenu = await nextScreen(observed, traderSince, 'armory');
    assert.ok(traderMenu.actions.every(({ action }) => !/\/(feed|trophy)$/.test(action?.id ?? '')),
      'The trader menu still has a shortcut around the physical handover');
    assert.ok(tagged(bot.inventory.slots[40], 'FISHING_CATCH_BAG', match) && sidebar.hasRow('Expedition coins 0💰'),
      'Opening the trader with the rod handed over a catch from another slot');
    bot.setQuickBarSlot(4);
    await bot.activateEntityAt(trader, trader.position.offset(0, 1.1, 0));
    await bot.activateEntity(trader);
    await until(() => !bot.inventory.slots[40] && sidebar.hasRow('Expedition coins 10💰'),
      'Right-clicking the trader with the clam did not consume it and pay 10 coins');

    const since = observed.screens.length;
    player.chat(mainMenu);
    const leaveId = 'action_menu_event_evacuate_name';
    click(player, await nextScreen(observed, since, leaveId), leaveId);
    await restoreObserved(player, observed, before);
    await until(() => !sidebar.visible(), 'The Fishing sidebar remained active after leaving');
    await waitForQa(admin, 'status', idle);
    await waitForQa(admin, `player ${player.username}`, { status: 'none', recovery: false });
    restored = true;
  } finally {
    if (!restored && bot.entity) player.chat('/arcevents leave');
    bot.removeListener('entityUpdate', handleHint);
    bot._client.removeListener('hurt_animation', markHurt);
    bot._client.removeListener('entity_destroy', markRemoved);
    bot._client.removeListener('world_particles', markInjury);
    sidebar.stop();
    observedSounds.stop();
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
