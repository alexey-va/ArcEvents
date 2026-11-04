import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { expect, test } from '@drownek/plugwright';
import nbt from 'prismarine-nbt';
import { Vec3 } from 'vec3';
import { readQa, waitForQa } from './qa.js';

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const itemState = (player) => JSON.stringify(player.bot.inventory.slots.map((item) => item && {
  name: item.name, count: item.count, nbt: item.nbt, components: item.components,
}));
const enemies = (player) => Object.values(player.bot.entities).filter((entity) => /^(zombie|husk)$/.test(entity.name));

async function until(probe, message, timeout = 10000) {
  const deadline = Date.now() + timeout;
  do {
    const result = await probe();
    if (result) return result;
    await pause(100);
  } while (Date.now() < deadline);
  assert.fail(message);
}

function watchDialogs(player) {
  const observed = { screens: [], clears: 0 };
  const show = ({ dialog }) => observed.screens.push(dialog.data ? nbt.simplify(dialog.data) : { registry: dialog });
  const clear = () => { observed.clears += 1; };
  player.bot._client.on('show_dialog', show);
  player.bot._client.on('clear_dialog', clear);
  observed.stop = () => {
    player.bot._client.removeListener('show_dialog', show);
    player.bot._client.removeListener('clear_dialog', clear);
  };
  return observed;
}

function buttons(value) {
  if (!value || typeof value !== 'object') return [];
  if (value.action?.id && value.label) return [value];
  return Object.values(value).flatMap(buttons);
}

const button = (screen, id) => buttons(screen).find((entry) => entry.action.id.endsWith(`/${id}`));
const nextDialog = (observed, since, predicate) => until(
  () => observed.screens.slice(since).find(predicate), 'Paper did not send the expected native dialog');

function click(player, screen, id, label) {
  const selected = button(screen, id);
  assert.ok(selected, `Native dialog has no ${id} button`);
  assert.match(JSON.stringify(selected.label), new RegExp(label));
  assert.equal(screen.inputs?.length ?? 0, 0, 'This native click helper supports buttons without inputs');
  // Paper 1.21.11: ByteBufCodecs.optionalTagCodec().apply(lengthPrefixed(65536)).
  // Buttons without inputs need no response; minecraft-data 3.116.0's
  // option+anonymousNbt plus TAG_End yields the correct length=1, absent-tag bytes.
  const params = { id: selected.action.id, nbt: { type: 'end' } };
  const wire = player.bot._client.serializer.createPacketBuffer({ name: 'custom_click_action', params });
  assert.equal(wire[0], 0x41, 'Native click did not use the 1.21.11 play packet id');
  assert.equal(wire.subarray(-2).toString('hex'), '0100', 'Native absent payload must be length-prefixed TAG_End');
  console.log(`[Native dialog] ${player.username} action=${id} payload=${wire.subarray(-2).toString('hex')}`);
  player.bot._client.write('custom_click_action', params);
}

async function solo(player, admin, observed) {
  const since = observed.screens.length;
  player.chat('/arcevents zombies');
  const screen = await nextDialog(observed, since, (entry) => button(entry, 'action_arcade_zombies_solo_name'));
  assert.equal(screen.after_action, 'none', 'Native actions must keep the current dialog open');
  click(player, screen, 'action_arcade_zombies_solo_name', 'Play solo');
  // No queue command or second dialog open is needed after this actual button click.
  await waitForQa(admin, 'status', { game: 'zombies', phase: 'active', participants: 1 }, 30000);
  await until(() => /FIVE_SEVEN/i.test(JSON.stringify(player.bot.inventory.slots[36])), 'Solo did not issue its starting firearm');
}

async function teleport(admin, player, position) {
  admin.chat(`/execute at ${player.username} run tp ${player.username} ${position.x} ${position.y} ${position.z}`);
  await until(() => player.bot.entity.position.distanceTo(position) < .75, `${player.username} did not reach the fixture position`);
}

async function shootEnemy(admin, player, enemy, reposition = true) {
  if (reposition) {
    const towardArena = new Vec3(-enemy.position.x, 0, -enemy.position.z).normalize().scaled(2.5);
    await teleport(admin, player, enemy.position.plus(towardArena));
  }
  player.bot.setQuickBarSlot(0);
  for (let i = 0; i < 10 && player.bot.entities[enemy.id]; i++) {
    const current = player.bot.entities[enemy.id];
    await player.bot.lookAt(current.position.offset(0, 1.7, 0), true);
    player.bot.attack(current);
    await pause(250);
  }
  assert.ok(!player.bot.entities[enemy.id], 'Accepted native firearm hits did not remove the owned zombie');
}

async function ready() {
  const log = new URL('../../../build/plugwright-host/logs/latest.log', import.meta.url);
  for (let i = 0; i < 240; i++) {
    const text = await readFile(log, 'utf8');
    assert.ok(!/ArcEvents startup failed closed|ArcEvents failed closed during startup/.test(text), 'ArcEvents failed to start');
    if (/ArcEvents enabled .*arenaReady=true redisConnected=true/.test(text)) return;
    await pause(250);
  }
  assert.fail('ArcEvents did not become ready');
}

async function connected(createPlayer, prefix) {
  return createPlayer({ username: `${prefix}${Math.floor(Math.random() * 100000)}` });
}

async function start(players, admin) {
  for (const player of players) {
    player.chat('/arcevents join');
    await expect(player).toHaveReceivedMessage(/You joined the queue/);
  }
  // The ordinary first queue member starts the session through creator controls.
  players[0].chat('/arcevents start zombies');
  await waitForQa(admin, 'status', { game: 'zombies', phase: 'active', participants: players.length }, 30000);
  for (const player of players) {
    // The authoritative phase and the inventory update travel in separate native packets.
    for (let i = 0; i < 100 && !/FIVE_SEVEN/i.test(JSON.stringify(player.bot.inventory.slots[36])); i++) await pause(50);
    const item = player.bot.inventory.slots[36];
    assert.ok(item && /FIVE_SEVEN/i.test(JSON.stringify(item)), 'Starting event firearm was not issued');
  }
}

async function leave(player, original, admin) {
  player.chat('/arcevents leave');
  await until(() => itemState(player) === original, 'Exact inventory restoration failed', 15000);
  assert.equal(itemState(player), original, 'Exact inventory restoration failed');
  await until(async () => {
    const state = await readQa(admin, `player ${player.username}`);
    return state.recovery === 'false' && ['none', 'restored', 'disconnected'].includes(state.status);
  }, 'Exact restoration did not release the player from active participation');
}

async function idle(admin) {
  await waitForQa(admin, 'status', { host: 'ready', phase: 'idle' });
}

test('Zombies Play solo starts directly and leaves an unrelated squad member queued', async ({ player, createPlayer }) => {
  await ready();
  const admin = await connected(createPlayer, 'ZombieQA');
  const peer = await connected(createPlayer, 'ZombiePeer');
  await admin.makeOp();
  admin.chat(`/give ${player.username} minecraft:diamond[custom_name='{"text":"Zombie-state-marker"}'] 3`);
  await expect(admin).toHaveReceivedMessage(/Gave/);
  await pause(150);
  const original = itemState(player);
  const peerOriginal = itemState(peer);
  const soloDialogs = watchDialogs(player);
  const squadDialogs = watchDialogs(peer);
  try {
    await waitForQa(admin, 'status', { phase: 'idle', queue: 0 });
    peer.chat('/arcevents zombies');
    const squad = await nextDialog(squadDialogs, 0, (screen) => button(screen, 'action_arcade_zombies_team_name'));
    assert.equal(squad.after_action, 'none');
    const refreshSince = squadDialogs.screens.length;
    const clearsBefore = squadDialogs.clears;
    click(peer, squad, 'action_arcade_zombies_team_name', 'Form a squad');
    const busy = await nextDialog(squadDialogs, refreshSince, (screen) => /Updating participation/.test(JSON.stringify(screen.body)));
    assert.equal(busy.after_action, 'none');
    assert.equal(buttons(busy).filter((entry) => entry.action.id.includes('/action_')).length, 0, 'Busy dialog still exposes a queue action');
    const queued = await nextDialog(squadDialogs, refreshSince, (screen) => button(screen, 'action_arcade_start_name') && button(screen, 'action_arcade_leave_name'));
    assert.equal(queued.after_action, 'none');
    assert.match(JSON.stringify(queued.body), /queued/i);
    assert.equal(squadDialogs.clears, clearsBefore, 'Joining the squad closed the native dialog before rerendering it');
    await waitForQa(admin, 'status', { queue: 1 });

    await solo(player, admin, soloDialogs);
    await waitForQa(admin, 'status', { game: 'zombies', phase: 'active', participants: 1, queue: 1 });
    await waitForQa(admin, `player ${peer.username}`, { status: 'none', zombies_status: '-' });
    assert.equal(itemState(peer), peerOriginal, 'Solo consumed or changed the unrelated queued player');
    const peerRefresh = squadDialogs.screens.length;
    peer.chat('/arcevents zombies');
    const stillQueued = await nextDialog(squadDialogs, peerRefresh, (screen) => button(screen, 'action_arcade_leave_name'));
    assert.match(JSON.stringify(stillQueued.body), /queued/i);

    const enemy = await until(() => enemies(player)[0], 'No native owned zombie appeared during the first wave', 30000);
    assert.ok(player.bot.health > 0, 'The solo player died before the first encounter');
    await shootEnemy(admin, player, enemy);
    await waitForQa(admin, `player ${player.username}`, { zombies_points: 600 });
    await leave(player, original, admin);
    await idle(admin);
    await waitForQa(admin, 'status', { queue: 1 });
    assert.equal(itemState(peer), peerOriginal, 'Solo cleanup changed the unrelated queue member');
    peer.chat('/arcevents leave');
    await waitForQa(admin, 'status', { queue: 0 });
  } finally {
    if (player.bot.entity) player.chat('/arcevents leave');
    if (peer.bot.entity) peer.chat('/arcevents leave');
    soloDialogs.stop();
    squadDialogs.stop();
    peer.bot.quit();
    admin.bot.quit();
  }
});

test('Zombies co-op keeps the remaining survivor active after a teammate leaves', async ({ player, createPlayer }) => {
  await ready();
  const teammate = await connected(createPlayer, 'ZombieMate');
  const admin = await connected(createPlayer, 'ZombieQA');
  await admin.makeOp();
  const original = itemState(player);
  const teammateOriginal = itemState(teammate);
  try {
    await start([player, teammate], admin);
    await leave(teammate, teammateOriginal, admin);
    await waitForQa(admin, `player ${player.username}`, { status: 'alive', zombies_status: 'alive' });
    await waitForQa(admin, 'status', { game: 'zombies', phase: 'active', alive: 1 });
    assert.ok(/FIVE_SEVEN/i.test(JSON.stringify(player.bot.inventory.slots[36])), 'Remaining survivor lost the event loadout');
    await leave(player, original, admin);
    await idle(admin);
  } finally {
    if (player.bot.entity) player.chat('/arcevents leave');
    if (teammate.bot.entity) teammate.chat('/arcevents leave');
    teammate.bot.quit();
    admin.bot.quit();
  }
});

test('Zombies native window breach and Shift repair lead to one paid physical box pickup', async ({ player, createPlayer }) => {
  await ready();
  const admin = await connected(createPlayer, 'ZombieQA');
  await admin.makeOp();
  admin.chat(`/give ${player.username} minecraft:diamond[custom_name='{"text":"Zombie-box-state"}'] 3`);
  await expect(admin).toHaveReceivedMessage(/Gave/);
  await pause(150);
  const original = itemState(player);
  const observed = watchDialogs(player);
  const windows = [
    { x: -25, z: -15, dx: 1, dz: 0 }, { x: 25, z: -15, dx: -1, dz: 0 },
    { x: -25, z: 5, dx: 1, dz: 0 }, { x: 25, z: 5, dx: -1, dz: 0 },
    { x: -7, z: 25, dx: 0, dz: -1 }, { x: 7, z: 25, dx: 0, dz: -1 },
  ];
  const inward = (window, position) => (position.x - window.x - .5) * window.dx + (position.z - window.z - .5) * window.dz;
  const lids = new Map();
  const frames = new Map();
  const lidUpdate = (packet) => {
    if (packet.location.y === 65 && packet.location.z === -22 && [-5, -4].includes(packet.location.x)) lids.set(packet.location.x, packet.byte2);
  };
  const frameUpdate = (packet) => {
    if (!frames.has(packet.entityId)) frames.set(packet.entityId, new Set());
    frames.get(packet.entityId).add(JSON.stringify(packet.metadata));
  };
  player.bot._client.on('block_action', lidUpdate);
  player.bot._client.on('entity_metadata', frameUpdate);
  try {
    await solo(player, admin, observed);
    await waitForQa(admin, 'status', { zombies_boards: '3,3,3,3,3,3', zombies_box: 'idle' });
    const enemy = await until(() => enemies(player)[0], 'No native actor reached an authored window', 30000);
    const windowIndex = windows.findIndex((window) => Math.hypot(enemy.position.x - window.x - .5, enemy.position.z - window.z - .5) < 3);
    assert.ok(windowIndex >= 0, 'The owned zombie did not spawn at a v2 window');
    const window = windows[windowIndex];
    const center = new Vec3(window.x + .5, 66.2, window.z + .5);
    const gateCells = [-1, 0, 1].map((offset) => new Vec3(window.x + window.dz * offset, 65, window.z - window.dx * offset));
    const nativeBoards = () => Object.values(player.bot.entities).filter((entity) => entity.name === 'block_display' && entity.position.distanceTo(center) < 2.5).length;
    const collisionIs = (name) => gateCells.every((position) => player.bot.blockAt(position)?.name === name);
    const waitBoards = (count) => until(async () => {
      const state = await readQa(admin);
      return state.zombies_boards?.split(',')[windowIndex] === String(count);
    }, `Window did not reach ${count}/3 boards`, 6000);
    await teleport(admin, player, new Vec3(center.x + window.dx * 6, 65, center.z + window.dz * 6));
    await until(() => nativeBoards() === 3 && collisionIs('spruce_fence'), 'Initial three boards or native window collision are missing');
    assert.ok(inward(window, player.bot.entities[enemy.id].position) < .25, 'The zombie bypassed the intact window');
    for (const count of [2, 1, 0]) {
      await waitBoards(count);
      await until(() => nativeBoards() === count, `Native window display count did not reach ${count}`);
      await until(() => collisionIs(count === 0 ? 'air' : 'spruce_fence'), `Native window collision disagrees with ${count}/3 boards`);
    }
    await until(() => {
      const actor = player.bot.entities[enemy.id];
      return actor && inward(window, actor.position) >= 1.1;
    }, 'The native zombie did not cross its fully breached window');
    await shootEnemy(admin, player, enemy, false);
    await waitForQa(admin, `player ${player.username}`, { zombies_points: 600 });
    const second = await until(() => enemies(player)[0], 'The first wave did not expose a second owned zombie');
    await shootEnemy(admin, player, second);
    await waitForQa(admin, `player ${player.username}`, { zombies_points: 700 });

    // Disposable fixture cleanup follows two real firearm kills and the observed breach.
    // Select only this fixture world's owned actors, then exercise the controller's
    // foreign-world cleanup without native damage cancellation or extra kill awards.
    const cleanupMarker = admin.getMessageBufferIndex();
    admin.chat('/execute in minecraft:arcevents_zombies_zero_v2 as @e[type=minecraft:zombie,tag=arcevents-zombies] in minecraft:overworld run tp @s 0 -60 0');
    await expect(admin).toHaveReceivedMessage(/Teleported/, { since: cleanupMarker, timeout: 5000 });
    await waitForQa(admin, 'status', { zombies_phase: 'intermission', zombies_alive: 0, zombies_pending: 0 });
    await waitForQa(admin, `player ${player.username}`, { zombies_points: 875 });

    await teleport(admin, player, new Vec3(center.x + window.dx * 2, 65, center.z + window.dz * 2));
    await player.bot.lookAt(center, true);
    player.bot.setControlState('sneak', true);
    await pause(600);
    player.bot.setControlState('sneak', false);
    await pause(250);
    await waitBoards(0);
    assert.ok(collisionIs('air'), 'A short interrupted hold repaired the window');
    player.bot.setControlState('sneak', true);
    for (const count of [1, 2, 3]) {
      await waitBoards(count);
      await until(() => nativeBoards() === count && collisionIs('spruce_fence'), `Shift+aim did not restore native board ${count}/3`);
    }
    player.bot.setControlState('sneak', false);
    await waitForQa(admin, `player ${player.username}`, { zombies_points: 875 });

    await teleport(admin, player, new Vec3(-4, 65, -19));
    const chest = await until(() => player.bot.blockAt(new Vec3(-5, 65, -22)), 'The physical mystery chest chunk is unavailable');
    assert.equal(chest.name, 'chest');
    assert.equal(player.bot.blockAt(new Vec3(-4, 65, -22))?.name, 'chest', 'Mystery box is not a native double chest');
    const originalWeapon = JSON.stringify(player.bot.inventory.slots[36]);
    const balance = Number((await readQa(admin, `player ${player.username}`)).zombies_points);
    await player.bot.activateBlock(chest);
    await waitForQa(admin, 'status', { zombies_box: 'rolling' });
    await waitForQa(admin, `player ${player.username}`, { zombies_points: balance - 650, zombies_weapon: 'five_seven' });
    await until(() => [-5, -4].every((x) => lids.get(x) > 0), 'Both native mystery chest lids did not open');
    const display = await until(() => Object.values(player.bot.entities).find((entity) => entity.name === 'item_display' && entity.position.distanceTo(new Vec3(-4, 67, -21.2)) < .5),
      'The physical box did not create its item display');
    assert.equal(JSON.stringify(player.bot.inventory.slots[36]), originalWeapon, 'The rolling weapon was granted before pickup');
    await player.bot.activateBlock(chest);
    await waitForQa(admin, `player ${player.username}`, { zombies_points: balance - 650, zombies_weapon: 'five_seven' });
    await until(() => frames.get(display.id)?.size >= 3, 'Native item metadata did not cycle during the mystery roll');
    await waitForQa(admin, 'status', { zombies_box: 'ready' });
    assert.equal(JSON.stringify(player.bot.inventory.slots[36]), originalWeapon, 'Ready weapon was granted without owner pickup');
    assert.equal(player.bot.currentWindow, null, 'Mystery interaction opened a native storage inventory');
    const beforeClaim = JSON.parse(itemState(player));
    await player.bot.activateBlock(chest);
    await waitForQa(admin, 'status', { zombies_box: 'idle' });
    const claimed = await until(async () => {
      const state = await readQa(admin, `player ${player.username}`);
      return state.zombies_weapon !== 'five_seven' && state.zombies_weapon !== '-' && state;
    }, 'Owner pickup did not change the authoritative weapon');
    await until(() => JSON.stringify(player.bot.inventory.slots[36] ?? null).toLowerCase().includes(claimed.zombies_weapon), 'The claimed native firearm was not delivered to its weapon slot');
    const afterClaim = JSON.parse(itemState(player));
    const changed = afterClaim.flatMap((item, index) => JSON.stringify(item) === JSON.stringify(beforeClaim[index]) ? [] : [index]);
    assert.ok(changed.includes(36), 'Pickup did not replace the starting weapon');
    assert.ok(changed.every((index) => [36, 9, 10, 11, 12].includes(index)), `Pickup changed unrelated inventory slots: ${changed}`);
    assert.equal(afterClaim.filter((item) => JSON.stringify(item).toLowerCase().includes(claimed.zombies_weapon)).length, 1, 'Pickup duplicated the selected weapon');
    assert.ok(!/FIVE_SEVEN/i.test(itemState(player)), 'Pickup left the replaced weapon in another inventory slot');
    const pickedUp = itemState(player);
    await player.bot.activateBlock(chest);
    await player.bot.activateBlock(chest);
    await waitForQa(admin, `player ${player.username}`, { zombies_points: balance - 650, zombies_weapon: claimed.zombies_weapon });
    await waitForQa(admin, 'status', { zombies_box: 'idle' });
    await until(() => [-5, -4].every((x) => lids.get(x) === 0) && !player.bot.entities[display.id], 'Claim did not close both lids and remove the roll display');
    assert.equal(itemState(player), pickedUp, 'Repeated pickup granted or replaced another item');
    assert.ok(!Object.values(player.bot.entities).some((entity) => entity.name === 'item' && entity.position.distanceTo(chest.position) < 4), 'Mystery pickup created a dropped-item overflow');
    await leave(player, original, admin);
    await idle(admin);
  } finally {
    player.bot.setControlState('sneak', false);
    if (player.bot.entity) player.chat('/arcevents leave');
    observed.stop();
    player.bot._client.removeListener('block_action', lidUpdate);
    player.bot._client.removeListener('entity_metadata', frameUpdate);
    admin.bot.quit();
  }
});
