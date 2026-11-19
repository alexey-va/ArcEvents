import assert from 'node:assert/strict';
import { expect, test } from '@drownek/plugwright';
import { button, click, nextScreen, observe, readQa, ready, restoreObserved, seed, until, waitForQa } from './qa.js';

const idle = { phase: 'idle', participants: 0, queue: 0 };
const statsButton = 'action_menu_main_stats_name';
const tttButton = 'action_menu_main_ttt_name';
const practiceButton = 'action_menu_admin_solo_test_name';
const authored = JSON.parse(process.env.ARC_EVENTS_E2E_AUTHORED_ARENAS ?? '{}');

function combatArena(id) {
  return authored[id] ? { arena: id, world: authored[id].world, readyTimeout: 150000 }
    : { arena: 'default', world: 'arcevents_e2e' };
}

async function statistics(player, observed) {
  const since = observed.screens.length;
  player.chat('/arcevents menu');
  const main = await nextScreen(observed, since, statsButton);
  const afterClick = observed.screens.length;
  click(player, main, statsButton);
  const stats = await until(() => observed.screens.slice(afterClick).find((screen) =>
    JSON.stringify(screen.title).includes('Event Statistics')), 'Statistics dialog did not open');
  assert.match(JSON.stringify(stats.body), /Matches:/);
  return stats.body;
}

async function openCard(player, observed, modeButton, cardButton) {
  const since = observed.screens.length;
  player.chat('/arcevents menu');
  const main = await nextScreen(observed, since, modeButton);
  const opened = observed.screens.length;
  click(player, main, modeButton);
  return nextScreen(observed, opened, cardButton);
}

const fighter = (entity) => entity.name === 'pillager' && /Bot_/.test(JSON.stringify(entity.metadata[2]));
const companion = (entity) => entity.type === 'player' && /^rc_[0-9a-f]{8}$/.test(entity.username);

async function movingRivals(player, count, { matches = fighter, lure } = {}) {
  const rivals = () => Object.values(player.bot.entities).filter(matches);
  const actors = await until(() => {
    const nearby = rivals();
    return nearby.length === count && nearby;
  }, `The practice arena did not spawn ${count} visible rivals`);
  const positions = new Map(actors.map((actor) => [actor.id, actor.position.clone()]));
  if (lure) {
    const { bot } = player;
    await bot.lookAt(bot.entity.position.offset(lure[0], bot.entity.eyeHeight, lure[1]), true);
    bot.setControlState('forward', true);
  }
  try {
    await until(() => rivals().some((actor) => {
      const before = positions.get(actor.id);
      return before && Math.hypot(actor.position.x - before.x, actor.position.z - before.z) > 0.75;
    }), 'Practice rivals did not move independently');
  } finally {
    if (lure) player.bot.setControlState('forward', false);
  }
}

async function attackRival(player, succeeded) {
  const { bot } = player;
  let lastAttack = 0;
  let target;
  bot.setQuickBarSlot(0);
  try {
    await until(async () => {
      if (succeeded(target)) return true;
      const rival = bot.nearestEntity(fighter);
      if (!rival) return false;
      const distance = rival.position.distanceTo(bot.entity.position);
      await bot.lookAt(rival.position.offset(0, 1.2, 0), true);
      bot.setControlState('forward', distance > 2.6);
      if (distance <= 2.6 && Date.now() - lastAttack >= 650) {
        target = rival.id;
        bot.attack(rival);
        lastAttack = Date.now();
      }
      return false;
    }, 'Native attack did not connect with a practice rival', 12000);
  } finally {
    bot.setControlState('forward', false);
  }
}

function hasIssuedItem(player, id) {
  return player.bot.inventory.items().some((item) =>
    item.name === id || JSON.stringify(item.components ?? []).toLowerCase().includes(id));
}

async function withPractice({ player, createPlayer }, mode, { world, participants, enter, arena = mode, readyTimeout }, exercise) {
  await ready(arena, readyTimeout);
  const admin = await createPlayer();
  await admin.makeOp();
  const observed = observe(player);
  let origin;
  let restored = false;
  try {
    admin.chat(`/lp user ${player.username} permission set arcevents.admin true`);
    await expect(admin).toHaveReceivedMessage('Set arcevents.admin to true');
    origin = await seed(admin, player, observed, 3);
    const publicStats = await statistics(player, observed);
    await waitForQa(admin, 'status', idle);
    if (enter) await enter(player, observed);
    else player.chat(`/arcevents admin solo ${mode}`);
    const state = participants && await waitForQa(admin, 'status', {
      game: mode, phase: 'active', arena_id: arena, participants, queue: 0, practice: true,
    });
    if (state) {
      await waitForQa(admin, `player ${player.username}`, { match: state.match, status: 'alive' });
      assert.equal((await readQa(admin, `player ${admin.username}`)).status, 'none');
    }
    await until(() => observed.world === `minecraft:${world}`, `Practice ${mode} did not reach its world`);
    await exercise(player, admin);
    await restoreObserved(player, observed, origin);
    restored = true;
    await waitForQa(admin, 'status', idle);
    await waitForQa(admin, `player ${player.username}`, { status: 'none', recovery: false });
    assert.deepEqual(await statistics(player, observed), publicStats, `${mode} practice changed public statistics`);
  } finally {
    if (!restored && player.bot.entity) {
      player.chat('/arcevents leave');
      if (origin && observed.world !== origin.world) {
        await restoreObserved(player, observed, origin).catch((failure) => console.error(failure.message));
      }
    }
    observed.stop();
    admin.bot.quit();
  }
}

test('Ordinary players cannot start admin practice or skip the public TTT minimum', async ({ player, createPlayer }) => {
  await ready('default');
  const admin = await createPlayer();
  await admin.makeOp();
  const observed = observe(player);
  try {
    const origin = await seed(admin, player, observed, 2);
    player.chat('/arcevents admin solo ttt');
    await expect(player).toHaveReceivedMessage(/You do not have access to this action/);
    await waitForQa(admin, 'status', idle);

    const join = 'action_menu_event_join_name';
    const card = await openCard(player, observed, tttButton, join);
    assert.ok(!button(card, practiceButton), 'The ordinary TTT card exposed admin practice');
    click(player, card, join);
    await expect(player).toHaveReceivedMessage(/You joined the queue/);
    player.chat('/arcevents start ttt');
    await expect(player).toHaveReceivedMessage(/Not enough players to start yet/);
    await waitForQa(admin, 'status', { ...idle, queue: 1 });
    player.chat('/arcevents leave');
    await waitForQa(admin, 'status', idle);
    await restoreObserved(player, observed, origin);
  } finally {
    player.chat('/arcevents leave');
    observed.stop();
    admin.bot.quit();
  }
});

test('Admin solo TTT starts from a native button, accepts real combat and finishes without public stats', async (fixture) => {
  await withPractice(fixture, 'ttt', {
    ...combatArena('riot-in-prison'), participants: 4,
    enter: async (player, observed) => {
      click(player, await openCard(player, observed, tttButton, practiceButton), practiceButton);
    },
  }, async (player, admin) => {
    await until(() => hasIssuedItem(player, 'iron_sword'), 'TTT did not issue its preparation sword');
    await movingRivals(player, 3);
    const { bot } = player;
    const hits = new Set();
    const hurt = ({ entityId }) => hits.add(entityId);
    bot._client.on('hurt_animation', hurt);
    try {
      await attackRival(player, (entityId) => hits.has(entityId));
    } finally {
      bot._client.removeListener('hurt_animation', hurt);
    }
    assert.ok(Number((await readQa(admin, `player ${player.username}`)).damage) > 0, 'TTT did not credit damage from the native sword hit');
    await expect(player).toHaveReceivedMessage(/The report is ready/, { timeout: 65000 });
  });
});

test('Admin solo Gun Game advances after a real shot against a moving rival and restores the player', async (fixture) => {
  await withPractice(fixture, 'gungame', { ...combatArena('deadwood'), participants: 2 }, async (player, admin) => {
    await until(() => hasIssuedItem(player, 'flintlock'), 'Gun Game did not issue the Flintlock');
    // The open lane leads west in Deadwood and south in Citadel.
    await movingRivals(player, 1, { lure: authored.deadwood ? [-8, 0] : [0, 8] });
    await until(() => player.bot.health < 20, 'The Gun Game rival did not damage the tester', 15000);
    await attackRival(player, () => hasIssuedItem(player, 'revolver'));
    await waitForQa(admin, `player ${player.username}`, { kills: 1, firearm: 'revolver' });
    player.chat('/arcevents leave');
  });
});

test('Admin solo Disasters runs a real hazard round with a moving rival and skips public stats', async (fixture) => {
  await withPractice(fixture, 'disasters', { world: 'arcevents_disasters_coast', participants: 2 }, async (player) => {
    await expect(player).toHaveReceivedMessage(/Meteors in \d+s/);
    await movingRivals(player, 1, { matches: companion });
    await expect(player).toHaveReceivedMessage(/Winners:/, { timeout: 25000 });
  });
});

test('Admin solo Fishing issues its rod alongside a practice fisherman and restores the player', async (fixture) => {
  await withPractice(fixture, 'fishing', { world: 'arcevents_fishing_archipelago', participants: 1 }, async (player) => {
    await until(() => hasIssuedItem(player, 'fishing_rod'), 'Fishing did not issue its rod');
    const angler = await until(() => player.bot.nearestEntity((entity) => companion(entity) && entity.heldItem?.name === 'fishing_rod'),
      'The practice fisherman was not visible with a rod');
    let swung = false;
    const cast = (entity) => { if (entity.id === angler.id) swung = true; };
    player.bot.on('entitySwingArm', cast);
    try {
      await until(() => swung, 'The practice fisherman did not cast or reel');
    } finally {
      player.bot.removeListener('entitySwingArm', cast);
    }
    player.chat('/arcevents leave');
  });
});

test('Admin solo Zombies starts a real hostile encounter and restores the player', async (fixture) => {
  await withPractice(fixture, 'zombies', { world: 'arcevents_zombies_store', participants: 1 }, async (player) => {
    await until(() => hasIssuedItem(player, 'five_seven'), 'Zombies did not issue its starting firearm');
    await until(() => player.bot.nearestEntity((entity) => /^(zombie|husk)$/.test(entity.name)),
      'The first wave did not spawn a native enemy', 30000);
    player.chat('/arcevents leave');
  });
});

test('Admin solo Apocalypse enters the shared expedition and restores the player on leave', async (fixture) => {
  await withPractice(fixture, 'apocalypse', {
    world: 'arcevents_apocalypse_v2',
    enter: async (player, observed) => {
      const start = 'action_apocalypse_test_name';
      click(player, await openCard(player, observed, 'action_apocalypse_menu_name', start), start);
    },
  }, async (player) => {
    await until(() => hasIssuedItem(player, 'iron_axe'), 'Apocalypse did not issue its starter tools');
    player.chat('/arcevents leave');
  });
});
