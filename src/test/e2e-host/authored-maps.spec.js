import assert from 'node:assert/strict';
import { expect, test } from '@drownek/plugwright';
import { Vec3 } from 'vec3';
import { observe, ready, restoreObserved, seed, until, waitForQa, walkTo } from './qa.js';

const idle = { host: 'ready', phase: 'idle', participants: 0, queue: 0 };
const authored = process.env.ARC_EVENTS_E2E_AUTHORED_ARENAS;
const maps = [
  { mode: 'ttt', id: 'riot-in-prison', world: 'arcevents_ttt_riot_in_prison', participants: 4 },
  { mode: 'gungame', id: 'deadwood', world: 'arcevents_gungame_deadwood', participants: 2 },
];

const point = (value) => new Vec3(...value.split(',').slice(0, 3).map(Number));

function standsOnGround(bot, position) {
  return bot.blockAt(position)?.boundingBox === 'empty' &&
    bot.blockAt(position.offset(0, 1, 0))?.boundingBox === 'empty' &&
    bot.blockAt(position.offset(0, -1, 0))?.boundingBox === 'block';
}

if (authored) {
  const arenas = JSON.parse(authored);
  for (const { mode, id, world, participants } of maps) {
    test(`Authored ${id} starts from the public ${mode} queue and restores the roster`, async ({ player, createPlayer }) => {
      const arena = arenas[id];
      assert.equal(arena?.world, world, `Stage the ${id} entry from the Parkour profile`);
      const spawns = arena.spawns.map(point);
      await ready(id, 150000);
      const admin = await createPlayer();
      await admin.makeOp();
      const roster = [player];
      while (roster.length < participants) roster.push(await createPlayer());
      const observed = roster.map(observe);
      const before = [];
      let restored = false;
      try {
        await waitForQa(admin, 'status', idle);
        for (const [index, entry] of roster.entries()) {
          before.push(await seed(admin, entry, observed[index], index + 1));
          const since = entry.getMessageBufferIndex();
          entry.chat('/arcevents join');
          await expect(entry).toHaveReceivedMessage(/You joined the queue/, { since });
        }
        await waitForQa(admin, 'status', { ...idle, queue: participants });
        player.chat(`/arcevents start ${mode}`);
        const state = await waitForQa(admin, 'status', { game: mode, phase: 'active', arena_id: id, participants, practice: false }, 30000);
        for (const [index, entry] of roster.entries()) {
          const { bot } = entry;
          await until(() => observed[index].world === `minecraft:${world}` && bot.game.gameMode === 'adventure' &&
            bot.entity.onGround && spawns.some((spawn) => bot.entity.position.distanceTo(spawn) < .45) &&
            standsOnGround(bot, bot.entity.position), `${entry.username} did not land on a playable ${id} spawn`);
        }
        const landings = new Set(roster.map(({ bot }) => bot.entity.position.floored().toString()));
        assert.equal(landings.size, participants, `The ${id} roster did not land at distinct spawn points`);
        if (mode === 'ttt') {
          assert.equal(Number(state.loot), arena['loot-spawns'].length, 'The imported loot catalog did not produce all its pickups');
        } else {
          await until(() => roster.every(({ bot }) => /flintlock/i.test(JSON.stringify(bot.inventory.slots[36]))),
            'The native roster did not get its starting firearm');
        }

        const { bot } = player;
        const origin = bot.entity.position.clone();
        const exit = [[1, 0], [-1, 0], [0, 1], [0, -1]].find(([x, z]) =>
          standsOnGround(bot, origin.offset(x, 0, z)) && standsOnGround(bot, origin.offset(2 * x, 0, 2 * z)));
        assert.ok(exit, `The ${id} spawn has no level exit`);
        await walkTo(player, origin.offset(2 * exit[0], 0, 2 * exit[1]));

        for (const entry of roster) entry.chat('/arcevents leave');
        await Promise.all(roster.map((entry, index) => restoreObserved(entry, observed[index], before[index])));
        await waitForQa(admin, 'status', idle);
        restored = true;
      } finally {
        for (const entry of roster) if (!restored && entry.bot.entity) entry.chat('/arcevents leave');
        observed.forEach((entry) => entry.stop());
        admin.bot.quit();
      }
    });
  }
}
