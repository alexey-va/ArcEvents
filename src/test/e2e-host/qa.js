import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { isDeepStrictEqual } from 'node:util';
import { expect } from '@drownek/plugwright';
import nbt from 'prismarine-nbt';
import { Vec3 } from 'vec3';

export async function until(probe, message, timeout = 10000) {
  const deadline = Date.now() + timeout;
  do {
    const result = await probe();
    if (result) return result;
    await new Promise((resolve) => setTimeout(resolve, 50));
  } while (Date.now() < deadline);
  assert.fail(message);
}

export async function walkTo(player, point, stopped = () => false) {
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

export async function ready(arena, timeout = 60000) {
  const logPath = new URL('../../../build/plugwright-host/logs/latest.log', import.meta.url);
  await until(async () => {
    const log = await readFile(logPath, 'utf8');
    assert.doesNotMatch(log, /ArcEvents (?:startup preparation failed closed|failed closed during startup)/);
    return arena === 'apocalypse'
      ? /ArcEvents Apocalypse ready world=\S+ generator=apocalypse-original-v1/.test(log)
      : log.includes(`ArcEvents arena ready id=${arena} world=`);
  }, `ArcEvents did not publish its ready ${arena} world`, timeout);
}

export function observe(player) {
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

export function observeSounds(player) {
  const { bot } = player;
  const sounds = [];
  const capture = (packet) => {
    const { sound, volume, pitch, x, y, z } = packet;
    const name = sound?.data?.soundName ?? bot.registry.sounds?.[sound?.soundId]?.name;
    if (name || Number.isInteger(sound?.soundId)) sounds.push({ name: name ?? '', id: sound?.soundId, volume, pitch, x, y, z });
  };
  bot._client.on('sound_effect', capture);
  bot._client.on('entity_sound_effect', capture);
  return {
    sounds,
    stop() {
      bot._client.removeListener('sound_effect', capture);
      bot._client.removeListener('entity_sound_effect', capture);
    },
  };
}

export const button = (screen, id) => screen.actions?.find((action) => action.action?.id?.endsWith(`/${id}`));
export const nextScreen = (observed, since, id) => until(
  () => observed.screens.slice(since).findLast((screen) => button(screen, id)), `Native dialog did not expose ${id}`,
);

export function click(player, screen, id) {
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

function inventory(player) {
  return player.bot.inventory.slots.map((item) => item && ({
    name: item.name,
    count: item.count,
    nbt: item.nbt,
    components: [...(item.components ?? [])].sort((a, b) => a.type.localeCompare(b.type)),
  }));
}

export async function seed(admin, player, observed, count) {
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

export async function restoreObserved(player, observed, before) {
  const { bot } = player;
  await until(() => observed.world === before.world && bot.entity.position.distanceTo(before.position) < 0.001 &&
    bot.game.gameMode === before.gameMode && bot.quickBarSlot === before.selected && isDeepStrictEqual(inventory(player), before.slots),
    `${player.username} did not return to the original world, position and inventory`, 15000);
}

export async function readQa(admin, subject = 'status') {
  const marker = admin.getMessageBufferIndex();
  admin.chat(`/arcevents qa ${subject}`);
  await expect(admin).toHaveReceivedMessage(/^ARCEVENTS_QA /, { since: marker, timeout: 5000 });
  const line = admin.messageBuffer.slice(marker).find((message) => message.startsWith('ARCEVENTS_QA '));
  return Object.fromEntries([...line.matchAll(/ ([a-z0-9_.-]+)=("(?:\\.|[^"\\])*"|\S+)/g)]
    .map(([, key, value]) => [key, value.startsWith('"') ? JSON.parse(value) : value]));
}

export async function waitForQa(admin, subject, expected, timeout = 20000) {
  const deadline = Date.now() + timeout;
  let state;
  do {
    state = await readQa(admin, subject);
    if (Object.entries(expected).every(([key, value]) => state[key] === String(value))) return state;
    await new Promise((resolve) => setTimeout(resolve, 200));
  } while (Date.now() < deadline);
  assert.fail(`QA ${subject} did not reach ${JSON.stringify(expected)}; last state: ${JSON.stringify(state)}`);
}
