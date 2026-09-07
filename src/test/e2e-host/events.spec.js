import assert from 'node:assert/strict';
import { expect, test } from '@drownek/plugwright';

function snapshot(player) {
  const item = (entry) => entry ? {
    name: entry.name,
    count: entry.count,
    displayName: entry.displayName,
    nbt: entry.nbt,
    components: entry.components,
  } : null;
  const slots = player.bot.inventory.slots.map(item);
  return {
    inventory: slots,
    armor: slots.slice(5, 9),
    offhand: slots[45],
    experience: { ...player.bot.experience },
    position: { x: player.bot.entity.position.x, y: player.bot.entity.position.y, z: player.bot.entity.position.z },
  };
}

function assertRestored(player, before) {
  const after = snapshot(player);
  assert.deepEqual(after.inventory, before.inventory, `${player.username} inventory was not restored exactly`);
  assert.deepEqual(after.experience, before.experience, `${player.username} experience was not restored exactly`);
  assert.ok(Math.hypot(after.position.x - before.position.x, after.position.y - before.position.y, after.position.z - before.position.z) < 1.5,
    `${player.username} location was not restored`);
}

const pause = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

async function waitForFiringPosition(...players) {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (players.every((entry) => {
      const y = entry.bot.entity?.position.y;
      return y !== undefined && y > 12 && y < 20;
    })) return;
    await pause(50);
  }
  assert.fail(`teleport did not settle at the firing position: ${players.map((entry) => `${entry.username}:${entry.bot.entity?.position.y}`).join(', ')}`);
}

async function waitForWeapon(player, weaponId) {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const weapon = player.bot.inventory.slots[36];
    if (weapon && weapon.name !== 'air' && new RegExp(weaponId, 'i').test(JSON.stringify(weapon))) return weapon;
    await pause(50);
  }
  return player.bot.inventory.slots[36];
}

async function createConnected(createPlayer, prefix) {
  const username = `${prefix}${Math.floor(Math.random() * 1_000_000)}`;
  assert.ok(username.length <= 16, `invalid Minecraft username: ${username}`);
  return createPlayer({ username });
}

async function join(player) {
  player.chat('/arcevents join');
  await expect(player).toHaveReceivedMessage(/Your queue place is confirmed\./);
}

async function cleanup(players) {
  for (const player of players) {
    try {
      if (player.bot.entity) player.chat('/arcevents leave');
    } catch {
      // A disconnect is itself part of the cleanup scenario.
    }
  }
}

async function startGunGame(players) {
  await players[0].makeOp();
  players[0].chat(`/lp user ${players[0].username} permission set arcevents.start true`);
  await expect(players[0]).toHaveReceivedMessage('Set arcevents.start to true');
  for (const player of players) await join(player);
  players[0].chat('/arcevents start gungame');
  await expect(players[0]).toHaveReceivedMessage(/Start confirmed\./);
  for (const player of players) {
    await expect(player).toHaveReceivedMessage(/Gun Game is preparing to start\./, { timeout: 15000 });
  }
  await expect(players[0]).toHaveReceivedMessage(/Gun Game has started\./, { timeout: 15000 });
}

async function shoot(attacker, victim) {
  attacker.bot.chat(`/tp ${attacker.username} -12.5 16 -12.5`);
  await expect(attacker).toHaveReceivedMessage(new RegExp(`Teleported ${attacker.username}`), { timeout: 5000 });
  attacker.bot.chat(`/tp ${victim.username} -10.5 16 -12.5`);
  await expect(attacker).toHaveReceivedMessage(new RegExp(`Teleported ${victim.username}`), { timeout: 5000 });
  await waitForFiringPosition(attacker, victim);
  const eliminatedSince = victim.getMessageBufferIndex();
  attacker.bot.setQuickBarSlot(0);
  await pause(250);
  await attacker.bot.lookAt(victim.bot.entity.position.offset(0, 1.2, 0), true);
  await attacker.bot.activateItem();
  await expect(victim).toHaveReceivedMessage(/You were eliminated\. Respawning in 1s\./, { since: eliminatedSince, timeout: 10000 });
  const respawnSince = victim.getMessageBufferIndex();
  await expect(victim).toHaveReceivedMessage(/You are back in the round\./, { since: respawnSince, timeout: 10000 });
  await pause(250);
}

test('Gun Game advances through every firearm and finishes on the real knife hit', async ({ player, createPlayer }) => {
  const rival = await createConnected(createPlayer, 'Rival');
  const players = [player, rival];
  const firearmIds = ['flintlock', 'revolver', 'hand_cannon', 'double_barrel', 'five_seven', 'g36', 'aek_971', 'rpl_20', 'vepr_12', 'm1_garand', 'vss_vintorez', 'mcmillan'];
  const before = players.map(snapshot);
  try {
    await startGunGame(players);
    for (let stage = 0; stage < 12; stage += 1) {
      const weapon = await waitForWeapon(players[0], firearmIds[stage]);
      assert.ok(weapon, `missing Gun Game weapon at stage ${stage}`);
      assert.notEqual(weapon.name, 'air', `empty Gun Game weapon at stage ${stage}`);
      assert.match(JSON.stringify(weapon), new RegExp(firearmIds[stage], 'i'),
        `wrong firearm at stage ${stage}: ${JSON.stringify(weapon)}`);
      await shoot(players[0], players[1]);
    }

    players[0].bot.chat(`/tp ${players[0].username} -12.5 16 -12.5`);
    await expect(players[0]).toHaveReceivedMessage(new RegExp(`Teleported ${players[0].username}`), { timeout: 5000 });
    players[0].bot.chat(`/tp ${players[1].username} -10.5 16 -12.5`);
    await expect(players[0]).toHaveReceivedMessage(new RegExp(`Teleported ${players[1].username}`), { timeout: 5000 });
    await waitForFiringPosition(players[0], players[1]);
    const marker = players[1].getMessageBufferIndex();
    players[0].bot.setQuickBarSlot(0);
    await players[0].bot.lookAt(players[1].bot.entity.position.offset(0, 1.2, 0), true);
    players[0].bot.attack(players[1].bot.entity);
    await expect(players[1]).toHaveReceivedMessage(/You were eliminated\. Respawning in 1s\./, { since: marker, timeout: 10000 });
    await expect(players[0]).toHaveReceivedMessage(new RegExp(`Winners:.*${players[0].username}`, 'i'), { timeout: 15000 });
    await expect(players[0]).toHaveReceivedMessage(/Your pre-event state was restored\./, { timeout: 15000 });
    await expect(players[1]).toHaveReceivedMessage(/Your pre-event state was restored\./, { timeout: 15000 });
    players.forEach((entry, index) => assertRestored(entry, before[index]));
  } finally {
    await cleanup(players);
  }
});

test('Gun Game disconnect removes one participant while the two-player match stays active, then cleans up', async ({ player, createPlayer }) => {
  const leaver = await createConnected(createPlayer, 'Leaver');
  const keeper = await createConnected(createPlayer, 'Keeper');
  const players = [player, leaver, keeper];
  const before = players.map(snapshot);
  try {
    await startGunGame(players);
    players[1].bot.quit();
    const statusSince = players[0].getMessageBufferIndex();
    players[0].chat('/arcevents status');
    await expect(players[0]).toHaveReceivedMessage(/Gun Game.*round active/i, { since: statusSince, timeout: 5000 });
    players[0].chat('/arcevents leave');
    players[2].chat('/arcevents leave');
    await expect(players[0]).toHaveReceivedMessage(/Your pre-event state was restored\./, { timeout: 15000 });
    await expect(players[2]).toHaveReceivedMessage(/Your pre-event state was restored\./, { timeout: 15000 });
    assertRestored(players[0], before[0]);
    assertRestored(players[2], before[2]);
  } finally {
    await cleanup(players);
  }
});
