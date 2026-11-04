import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { expect, test } from '@drownek/plugwright';

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const itemState = (player) => JSON.stringify(player.bot.inventory.slots.map((item) => item && {
  name: item.name, count: item.count, nbt: item.nbt, components: item.components,
}));

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

async function start(players) {
  for (const player of players) {
    player.chat('/arcevents join');
    await expect(player).toHaveReceivedMessage(/Your queue place is confirmed/);
  }
  // The ordinary first queue member starts the session through creator controls.
  players[0].chat('/arcevents start zombies');
  await expect(players[0]).toHaveReceivedMessage(/Start confirmed/);
  await expect(players[0]).toHaveReceivedMessage(/has started/, { timeout: 30000 });
  for (const player of players) {
    // Chat and the inventory update travel in separate native packets.
    for (let i = 0; i < 100 && !/FIVE_SEVEN/i.test(JSON.stringify(player.bot.inventory.slots[36])); i++) await pause(50);
    const item = player.bot.inventory.slots[36];
    assert.ok(item && /FIVE_SEVEN/i.test(JSON.stringify(item)), 'Starting event firearm was not issued');
  }
}

async function leave(player, original) {
  const marker = player.getMessageBufferIndex();
  player.chat('/arcevents leave');
  await expect(player).toHaveReceivedMessage(/Your pre-event state was restored/, { since: marker, timeout: 15000 });
  for (let i = 0; i < 50; i++) {
    if (itemState(player) === original) return;
    await pause(100);
  }
  assert.equal(itemState(player), original, 'Exact inventory restoration failed');
}

async function idle(admin) {
  for (let i = 0; i < 30; i++) {
    const marker = admin.getMessageBufferIndex();
    admin.chat('/arcevents qa status');
    try {
      await expect(admin).toHaveReceivedMessage(/host=ready.*phase=idle/, { since: marker, timeout: 750 });
      return;
    } catch {
      await pause(250);
    }
  }
  assert.fail('The host did not release the recovered match and publish idle readiness');
}

test('Zombies solo starts for an ordinary creator, shoots an owned enemy and restores the inventory', async ({ player, createPlayer }) => {
  await ready();
  const admin = await connected(createPlayer, 'ZombieQA');
  await admin.makeOp();
  admin.chat(`/give ${player.username} minecraft:diamond[custom_name='{"text":"Zombie-state-marker"}'] 3`);
  await expect(admin).toHaveReceivedMessage(/Gave/);
  await pause(150);
  const original = itemState(player);
  try {
    await start([player]);
    let enemy;
    for (let i = 0; i < 120; i++) {
      enemy = Object.values(player.bot.entities).find((entity) => /^(zombie|husk)$/.test(entity.name));
      if (enemy) break;
      await pause(250);
    }
    assert.ok(enemy, 'No native owned zombie appeared during the first wave');
    assert.ok(player.bot.health > 0, 'The solo player died before the first encounter');
    const marker = admin.getMessageBufferIndex();
    admin.chat(`/execute at ${player.username} run tp ${player.username} ${enemy.position.x - 2.5} ${enemy.position.y} ${enemy.position.z}`);
    await expect(admin).toHaveReceivedMessage(/Teleported/, { since: marker, timeout: 5000 });
    await pause(250);
    player.bot.setQuickBarSlot(0);
    for (let i = 0; i < 10; i++) {
      const current = player.bot.entities[enemy.id];
      if (!current) break;
      await player.bot.lookAt(current.position.offset(0, 1.25, 0), true);
      player.bot.attack(current);
      await pause(350);
    }
    assert.ok(!player.bot.entities[enemy.id], 'Accepted firearm hits did not remove the owned zombie');
    const pointsMarker = admin.getMessageBufferIndex();
    admin.chat(`/arcevents debug player ${player.username}`);
    await expect(admin).toHaveReceivedMessage(/zombies_points=600/, { since: pointsMarker, timeout: 5000 });
    await leave(player, original);
    await idle(admin);
  } finally {
    if (player.bot.entity) player.chat('/arcevents leave');
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
    await start([player, teammate]);
    await leave(teammate, teammateOriginal);
    const marker = player.getMessageBufferIndex();
    player.chat('/arcevents status');
    await expect(player).toHaveReceivedMessage(/(?:Resupply \d+s|Wave \d+\/\d+)/i, { since: marker, timeout: 5000 });
    assert.ok(/FIVE_SEVEN/i.test(JSON.stringify(player.bot.inventory.slots[36])), 'Remaining survivor lost the event loadout');
    await leave(player, original);
    await idle(admin);
  } finally {
    if (player.bot.entity) player.chat('/arcevents leave');
    if (teammate.bot.entity) teammate.chat('/arcevents leave');
    teammate.bot.quit();
    admin.bot.quit();
  }
});
