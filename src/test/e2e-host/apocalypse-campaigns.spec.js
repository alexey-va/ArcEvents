import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { expect, test } from '@drownek/plugwright';
import { Vec3 } from 'vec3';

const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const inventory = p => JSON.stringify(p.bot.inventory.slots.map(item => item && {
  name: item.name, count: item.count,
  components: [...(item.components ?? [])].sort((a, b) => a.type.localeCompare(b.type)),
}));
const count = (p, name) => p.bot.inventory.items().filter(item => item.name === name).reduce((n, item) => n + item.count, 0);
const expedition = player => `minecraft:arcevents_campaign_${player.bot.player.uuid.replaceAll('-', '')}`;

async function until(probe, description, timeout = 30000) {
  const deadline = Date.now() + timeout;
  do {
    const result = await probe();
    if (result) return result;
    await pause(100);
  } while (Date.now() < deadline);
  assert.fail(description);
}

async function ready() {
  await until(async () => /ArcEvents Apocalypse ready world=arcevents_apocalypse_v2/.test(
    await readFile(new URL('../../../build/plugwright-host/logs/latest.log', import.meta.url), 'utf8')),
  'The common Apocalypse world did not finish startup', 60000);
}

function watchWorld(player) {
  let current = 'minecraft:overworld';
  const install = () => {
    const client = player.bot._client;
    client.on('respawn', ({ worldState }) => { current = worldState.name; });
    client.on('login', ({ worldState }) => { if (worldState?.name) current = worldState.name; });
  };
  install();
  return { current: () => current, install };
}

async function setWorld(player, observation, command, world) {
  player.chat(command);
  await until(() => observation.current() === world, `${player.username} did not enter ${world}`, 55000);
}

async function grant(admin, player, item, total = 1) {
  const since = admin.getMessageBufferIndex();
  admin.chat(`/give ${player.username} minecraft:${item} ${total}`);
  await expect(admin).toHaveReceivedMessage(/Gave/, { since });
  await until(() => count(player, item) === total, `${player.username} did not receive fixture ${item}`);
}

async function say(player, command, reply) {
  const since = player.getMessageBufferIndex();
  player.chat(command);
  await expect(player).toHaveReceivedMessage(reply, { since, timeout: 12000 });
}

async function leave(player, observation, origin) {
  const since = player.getMessageBufferIndex();
  await setWorld(player, observation, '/events leave', 'minecraft:overworld');
  await until(() => inventory(player) === origin, `${player.username}'s exact origin inventory was not restored`);
  await expect(player).toHaveReceivedMessage(/You returned from the apocalypse/i, { since, timeout: 12000 });
}

test('Apocalypse personal expeditions isolate guest saves and let a companion reopen the empty world', async ({ player, createPlayer }) => {
  await ready();
  const admin = await createPlayer({username: `RealmQA${Math.floor(Math.random() * 100000)}`});
  const guest = await createPlayer({username: `RealmPeer${Math.floor(Math.random() * 100000)}`});
  await admin.makeOp();
  await player.makeOp();
  const ownerWorld = watchWorld(player), guestWorld = watchWorld(guest);
  const realm = expedition(player), otherRealm = expedition(guest);
  await grant(admin, player, 'emerald', 2);
  await grant(admin, guest, 'diamond', 3);
  const ownerOrigin = inventory(player), guestOrigin = inventory(guest);
  let ownerOnline = true;
  try {
    await setWorld(player, ownerWorld, '/events apocalypse my', realm);
    assert.equal(count(player, 'emerald'), 0, 'Origin inventory leaked into personal Apocalypse');
    await say(guest, `/events apocalypse join ${player.username}`, /You need an invitation/i);
    assert.equal(inventory(guest), guestOrigin, 'A denied admission changed the guest inventory');
    await say(player, `/events apocalypse invite ${guest.username}`, /lasts five minutes/i);
    await setWorld(guest, guestWorld, `/events apocalypse join ${player.username}`, realm);
    assert.equal(count(guest, 'diamond'), 0, 'Guest entered with items from the lobby');
    await grant(admin, guest, 'amethyst_shard', 4);
    await grant(admin, player, 'copper_ingot', 2);
    const beforeDeath = inventory(guest);
    let died = false;
    guest.bot.once('death', () => { died = true; });
    await say(admin, `/minecraft:kill ${guest.username}`, /Killed/i);
    await until(() => died && guest.bot.isAlive && guestWorld.current() === realm && inventory(guest) === beforeDeath,
      'Dying in the personal campaign did not return the survivor with the same inventory');
    await say(player, `/events apocalypse companion ${guest.username}`, /can open your expedition/i);

    await leave(guest, guestWorld, guestOrigin);
    const tickBeforeEmpty = Number((await qa(player)).tick);
    await leave(player, ownerWorld, ownerOrigin);
    player.bot.quit();
    ownerOnline = false;
    await setWorld(guest, guestWorld, '/events apocalypse my', otherRealm);
    assert.equal(count(guest, 'amethyst_shard'), 0, 'Friend inventory leaked into the guest’s own expedition');
    await grant(admin, guest, 'quartz', 5);
    await leave(guest, guestWorld, guestOrigin);

    const worldName = realm.split(':')[1];
    await until(async () => (await readFile(new URL('../../../build/plugwright-host/logs/latest.log', import.meta.url), 'utf8'))
      .includes(`Halted I/O scheduler for world '${worldName}'`), 'The unoccupied campaign did not unload', 55000);
    await setWorld(guest, guestWorld, `/events apocalypse join ${player.username}`, realm);
    await until(() => count(guest, 'amethyst_shard') === 4, 'The companion did not resume the exact guest inventory');
    assert.equal(count(guest, 'quartz'), 0, 'Personal gear leaked into the host’s expedition');
    assert.equal(count(guest, 'copper_ingot'), 0, 'The owner’s gear leaked to the visitor');
    await guest.makeOp();
    const tickAfterReopen = Number((await qa(guest)).tick);
    assert.ok(tickAfterReopen >= tickBeforeEmpty && tickAfterReopen - tickBeforeEmpty < 160,
      `The empty campaign clock changed from ${tickBeforeEmpty} to ${tickAfterReopen}`);
    await leave(guest, guestWorld, guestOrigin);

    await setWorld(guest, guestWorld, '/events apocalypse my', otherRealm);
    await until(() => count(guest, 'quartz') === 5, 'The guest’s own equipment did not survive world switching');
    await leave(guest, guestWorld, guestOrigin);
  } finally {
    if (ownerOnline) player.chat('/events leave');
    if (guest.bot.entity) guest.chat('/events leave');
    admin.bot.quit();
  }
});

async function qa(player) {
  const since = player.getMessageBufferIndex();
  player.chat('/events apocalypse qa');
  const line = await until(() => player.messageBuffer.slice(since).find(message => message.startsWith('ARCEVENTS_APOCALYPSE ')),
    'Apocalypse QA state did not arrive', 5000);
  return Object.fromEntries([...line.matchAll(/ (\w+)=(\S+)/g)].map(([, key, value]) => [key, value]));
}

async function placePlayer(admin, player, realm, point) {
  await say(admin, `/execute in ${realm} run tp ${player.username} ${point.x} ${point.y} ${point.z}`, /Teleported/);
  await until(() => player.bot.entity.position.distanceTo(point) < 1.4, `Could not reach fixture position ${point}`);
}

async function useObjective(admin, player, realm, block, material, stand) {
  await placePlayer(admin, player, realm, stand);
  const target = await until(() => {
    const seen = player.bot.blockAt(block);
    return seen?.name === material && seen;
  }, `Expected ${material} for the objective at ${block}`);
  await player.bot.unequip('hand');
  await player.bot.activateBlock(target);
  return target;
}

test('Apocalypse expedition objectives play the radio trail, power the port and commit a horde diversion', async ({player, createPlayer}) => {
  await ready();
  const helper = await createPlayer({username: `PortPeer${Math.floor(Math.random() * 100000)}`});
  await player.makeOp();
  const ownerWorld = watchWorld(player), guestWorld = watchWorld(helper), realm = expedition(player);
  const given = (key, expected) => until(async () => (await qa(player))[key] === expected, `Expected ${key}=${expected}`);
  try {
    await setWorld(player, ownerWorld, '/events apocalypse my', realm);
    await useObjective(player, player, realm, new Vec3(-108, 72, -2), 'cartography_table', new Vec3(-106.5, 72, -2.5));
    await useObjective(player, player, realm, new Vec3(-85, 68, -5), 'waxed_exposed_cut_copper', new Vec3(-84.5, 68, -3.5));
    await useObjective(player, player, realm, new Vec3(-119, 66, 14), 'note_block', new Vec3(-117.5, 66, 14.5));
    await given('objClues', '3');

    await say(player, `/events apocalypse invite ${helper.username}`, /lasts five minutes/i);
    await setWorld(helper, guestWorld, `/events apocalypse join ${player.username}`, realm);
    await useObjective(player, helper, realm, new Vec3(-108, 66, 0), 'waxed_chiseled_copper', new Vec3(-107.5, 66, 2.5));
    await given('objRelay', 'true');
    await given('objDiesel', 'true');
    await useObjective(player, player, realm, new Vec3(-166, 70, 4), 'lodestone', new Vec3(-165.5, 70, 6.5));
    await given('objPower', 'true');
    await until(() => [new Vec3(-147, 70, -2), new Vec3(-155, 70, -2), new Vec3(-162, 70, -2)].every(point =>
      String(player.bot.blockAt(point)?.getProperties().lit) === 'true'), 'The generator did not light the pier');

    await useObjective(player, player, realm, new Vec3(-160, 70, 8), 'grindstone', new Vec3(-159.5, 70, 6.5));
    await until(() => player.bot.entity.position.distanceTo(new Vec3(-7.5, 72, 6.5)) < 1.4, 'The port shortcut did not return to the bridge');
    await useObjective(player, player, realm, new Vec3(-8, 73, 8), 'grindstone', new Vec3(-8.5, 72, 6.5));
    await until(() => player.bot.entity.position.distanceTo(new Vec3(-159.5, 70, 6.5)) < 1.4, 'The bridge shortcut did not return to the pier');

    await useObjective(player, player, realm, new Vec3(-88, 66, 15), 'bell', new Vec3(-86.5, 66, 14.5));
    await given('objSirenDay', '10');
    player.chat('/events apocalypse time 250000');
    await until(async () => Number((await qa(player)).objDiverted) > 0, 'No horde members committed to the siren');
  } finally {
    player.chat('/events leave');
    helper.chat('/events leave');
    await until(() => ownerWorld.current() === 'minecraft:overworld' && guestWorld.current() === 'minecraft:overworld', 'Test players did not leave Apocalypse');
    helper.bot.quit();
  }
});
