import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import nbt from 'prismarine-nbt';
import { expect, test } from '@drownek/plugwright';
import { Vec3 } from 'vec3';

const WORLD = 'minecraft:arcevents_apocalypse_v2';
const STARTER_TABLE = new Vec3(-1, 34, 37);
const BENCH = new Vec3(-5, 35, 41);
const FURNACE = new Vec3(-5, 35, 42);
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const itemState = item => item && { name: item.name, count: item.count, nbt: item.nbt,
  components: [...(item.components ?? [])].sort((a, b) => a.type.localeCompare(b.type)) };
const inventory = player => JSON.stringify(player.bot.inventory.slots.map(itemState));
const count = (player, predicate) => player.bot.inventory.items().filter(predicate).reduce((n, item) => n + item.count, 0);
const materialCount = (player, name) => count(player, item => item.name === name);

async function until(probe, message, timeout = 20000) {
  const deadline = Date.now() + timeout;
  do {
    const result = await probe();
    if (result) return result;
    await pause(100);
  } while (Date.now() < deadline);
  assert.fail(message);
}

async function ready() {
  await until(async () => {
    const log = await readFile(new URL('../../../build/plugwright-host/logs/latest.log', import.meta.url), 'utf8');
    assert.doesNotMatch(log, /ArcEvents startup preparation failed closed|ArcEvents failed closed during startup/);
    assert.doesNotMatch(log, /ArcEvents apocalypse world-start .* failed/);
    return /ArcEvents Apocalypse ready world=\S+ generator=apocalypse-original-v1/.test(log);
  }, 'ArcEvents host startup did not finish', 60000);
}

async function qa(admin) {
  const since = admin.getMessageBufferIndex();
  let requestedAt = 0;
  const line = await until(() => {
    const response = admin.messageBuffer.slice(since).find(message => message.startsWith('ARCEVENTS_APOCALYPSE '));
    if (response) return response;
    // Join restoration can briefly reject read-only commands; wait for typed readiness by asking again.
    if (Date.now() - requestedAt >= 250) {
      admin.chat('/arcevents apocalypse qa');
      requestedAt = Date.now();
    }
    return null;
  }, 'Admin QA did not return typed Apocalypse state after joining', 10000);
  return Object.fromEntries([...line.matchAll(/ ([a-z_]+)=(\S+)/g)].map(([, key, value]) => [key, value]));
}

async function activePlayers(admin, expected) {
  await until(async () => {
    const state = await qa(admin);
    return state.players === String(expected) && state.busy === '0';
  }, `Expected ${expected} active apocalypse players with no pending transaction`, 30000);
}

async function waitOrigin(player, admin, original) {
  await activePlayers(admin, 0);
  await until(() => inventory(player) === original, 'Native origin hotbar, armor and offhand were not restored exactly');
}

async function command(admin, value, response) {
  const since = admin.getMessageBufferIndex();
  admin.chat(value);
  await expect(admin).toHaveReceivedMessage(response, { since, timeout: 15000 });
}

async function teleport(admin, player, position) {
  admin.chat(`/execute in ${WORLD} run tp ${player.username} ${position.x} ${position.y} ${position.z}`);
  await until(() => player.bot.entity.position.distanceTo(position) < .8, 'Native fixture teleport did not arrive');
}

async function grant(admin, player, item, amount, predicate = value => value.name === item.replace('minecraft:', ''), server = null) {
  const before = count(player, predicate);
  const instruction = `/give ${player.username} ${item} ${amount}`;
  if (instruction.slice(1).length > 256) {
    assert.ok(server?.execute, 'Long native fixture grants require the Plugwright console API');
    server.execute(instruction.slice(1));
  } else {
    await command(admin, instruction, /Gave/);
  }
  await until(() => count(player, predicate) === before + amount, `Native fixture input did not arrive: ${item}`);
}

function nativePdc(value, key) {
  if (!value || typeof value !== 'object') return undefined;
  if (Object.hasOwn(value, key)) {
    const found = value[key];
    return found && typeof found === 'object' && 'value' in found ? found.value : found;
  }
  for (const child of Object.values(value)) {
    const found = nativePdc(child, key);
    if (found !== undefined) return found;
  }
  return undefined;
}

const pdc = (item, key) => nativePdc([item?.components, item?.nbt], `arcevents:${key}`);
const isTelegraph = item => item?.name === 'dropper' && pdc(item, 'apocalypse_source_item') === 'tacz:workbench_a' &&
  pdc(item, 'apocalypse_source_block') === 'bf1:telegraph' && pdc(item, 'apocalypse_workbench_source') === 'bf1:telegraph';
const isRevolver = item => pdc(item, 'apocalypse_source_item') === 'tacz:modern_kinetic_gun' &&
  pdc(item, 'apocalypse_source_gun') === 'bf1:sw_model3';
const isSource = source => item => pdc(item, 'apocalypse_source_item') === source;
const displaysAt = (player, position) => Object.values(player.bot.entities).filter(entity => entity.name === 'block_display' &&
  entity.position.distanceTo(position.offset(.5, 0, .5)) < 1);
const stationDisplays = player => displaysAt(player, BENCH).map(entity => entity.id);

async function workbenchOutline(player, position) {
  const fields = player.bot.registry.entitiesByName.block_display.metadataKeys;
  return until(() => displaysAt(player, position).find(entity =>
    (entity.metadata[fields.indexOf('shared_flags')] & 0x40) !== 0 &&
    entity.metadata[fields.indexOf('glow_color_override')] === 0xffc055),
  `The workbench at ${position} did not send a colored native glowing outline`);
}

async function nativeStationDisplays(player) {
  return until(() => {
    const ids = stationDisplays(player);
    return ids.length >= 20 && ids;
  }, 'Placed workstation did not send at least twenty native BlockDisplay entities');
}

function watchDialogs(player) {
  const screens = [];
  const receive = ({ dialog }) => screens.push(dialog.data ? nbt.simplify(dialog.data) : {});
  player.bot._client.on('show_dialog', receive);
  return { screens, stop: () => player.bot._client.removeListener('show_dialog', receive) };
}

function buttons(value) {
  if (!value || typeof value !== 'object') return [];
  if (value.action?.id && value.label) return [value];
  return Object.values(value).flatMap(buttons);
}

const button = (screen, id) => buttons(screen).find(value => value.action.id.endsWith(`/${id}`));
function text(value) {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map(text).join('');
  if (!value || typeof value !== 'object') return '';
  return `${value.text ?? ''}${(value.extra ?? []).map(text).join('')}`;
}

function click(player, action) {
  assert.ok(action?.action?.id, 'Native dialog did not provide the requested action token');
  const params = { id: action.action.id, nbt: { type: 'end' } };
  // Paper 1.21.11 requires a length-prefixed optional TAG_End for no-input actions.
  const wire = player.bot._client.serializer.createPacketBuffer({ name: 'custom_click_action', params });
  assert.equal(wire.subarray(-2).toString('hex'), '0100');
  player.bot._client.write('custom_click_action', params);
}

async function nextScreen(dialogs, since, predicate, message) {
  return until(() => dialogs.screens.slice(since).find(predicate), message);
}

async function openBench(player, dialogs, position, title) {
  const since = dialogs.screens.length;
  await until(() => player.bot.blockAt(position), 'Physical workbench chunk did not arrive');
  await player.bot.unequip('hand');
  await player.bot.activateBlock(player.bot.blockAt(position));
  const screen = await nextScreen(dialogs, since, value => button(value, 'recipe_0'), 'Native right-click did not open the workbench catalog');
  assert.match(text(screen.title), title);
  assert.equal(player.bot.currentWindow, null, 'A custom workbench exposed its carrier inventory');
  return screen;
}

async function recipe(player, dialogs, screen, name) {
  for (let page = 0; page < 64; page++) {
    const choice = buttons(screen).find(value => /\/recipe_\d+$/.test(value.action.id) && text(value.label).includes(name));
    const since = dialogs.screens.length;
    if (choice) {
      click(player, choice);
      const detail = await nextScreen(dialogs, since, value => button(value, 'back'), `Native recipe card did not open: ${name}`);
      assert.ok(text(detail.title).includes(name), `Wrong native recipe card: ${text(detail.title)}`);
      return detail;
    }
    const next = button(screen, 'next');
    assert.ok(next, `Source recipe missing from native catalog: ${name}`);
    click(player, next);
    screen = await nextScreen(dialogs, since, value => button(value, 'recipe_0'), 'Native catalog pagination did not arrive');
  }
  assert.fail(`Source recipe exceeded the bounded native catalog: ${name}`);
}

async function closeBench(player, dialogs) {
  let screen = dialogs.screens.at(-1);
  if (button(screen, 'back')) {
    const since = dialogs.screens.length;
    click(player, button(screen, 'back'));
    screen = await nextScreen(dialogs, since, value => button(value, 'close'), 'Native Back did not return to the workbench catalog');
  }
  click(player, button(screen, 'close'));
}

async function craftCopperBlock(player, catalog) {
  assert.ok(button(catalog, 'native_crafting'), 'Source assembly bench has no ordinary crafting-grid action');
  click(player, button(catalog, 'native_crafting'));
  const window = await until(() => player.bot.currentWindow?.type.startsWith('minecraft:crafting') && player.bot.currentWindow,
    'Native crafting-grid action did not open the real 3×3 inventory');
  assert.equal(window.inventoryStart, 10, 'Native assembly grid is not 3×3');
  const copper = window.items().find(item => item.name === 'copper_ingot');
  assert.equal(copper?.count, 9, 'Native copper-block fixture must contain exactly nine ingots');
  await player.bot.clickWindow(copper.slot, 0, 0);
  for (let slot = 1; slot <= 9; slot++) await player.bot.clickWindow(slot, 1, 0);
  await player.bot._syncWindow(window);
  await until(() => window.slots[0]?.name === 'copper_block' && window.slots[0].count === 1,
    'Nine native grid ingots did not produce the copper-block recipe');
  await player.bot.putAway(0);
  await player.bot._syncWindow(window);
  await player.bot.closeWindow(window);
  await until(() => materialCount(player, 'copper_block') === 1 && materialCount(player, 'copper_ingot') === 0,
    'Native grid crafting did not consume nine copper ingots and return one block');
}

async function craftOnce(player, admin, dialogs, detail, completed, message) {
  const action = button(detail, 'craft');
  assert.ok(action, `Exact source ingredients did not enable Craft: ${text(detail.title)}`);
  const since = dialogs.screens.length;
  click(player, action);
  click(player, action);
  await until(completed, message);
  await activePlayers(admin, 1);
  await nextScreen(dialogs, since, screen => JSON.stringify(screen.body).includes('Crafted'), 'Committed craft did not return native feedback');
  const accepted = inventory(player);
  click(player, action);
  await pause(500);
  assert.equal(inventory(player), accepted, 'Replaying a consumed native callback changed inventory');
}

async function preparePad(admin, player) {
  // A disposable local fixture beside the source table; crafting still uses the ordinary native menu.
  await teleport(admin, player, STARTER_TABLE.offset(.5, 1, 2.5));
  const bounds = [new Vec3(-7, 34, 39), new Vec3(-3, 34, 44), new Vec3(-7, 37, 39), new Vec3(-3, 37, 44)];
  await until(() => bounds.every(position => player.bot.blockAt(position) !== null), 'Disposable native pad chunk did not load before fill', 30000);
  await command(admin, `/execute in ${WORLD} run fill -7 34 39 -3 34 44 minecraft:stone`, /Successfully filled|No blocks were filled/);
  await command(admin, `/execute in ${WORLD} run fill -7 35 39 -3 37 44 minecraft:air`, /Successfully filled|No blocks were filled/);
}

async function placeStation(player, predicate = isTelegraph) {
  const carrier = player.bot.inventory.items().find(predicate);
  assert.ok(carrier, 'No identified workstation carrier is available for native placement');
  const support = player.bot.blockAt(BENCH.offset(0, -1, 0));
  assert.equal(support?.name, 'stone', 'Disposable workstation support did not arrive');
  assert.equal(player.bot.blockAt(BENCH)?.name, 'air', 'Workstation destination was not empty before placement');
  await player.bot.equip(carrier, 'hand');
  await player.bot.placeBlock(support, new Vec3(0, 1, 0));
  await until(() => player.bot.blockAt(BENCH)?.name === 'dropper' && count(player, predicate) === 0,
    'Native workstation placement did not consume exactly one identified carrier');
}

test('Apocalypse native workbenches craft exact source items, persist the gun and drop one replaceable T1 carrier', async ({ player, createPlayer }) => {
  await ready();
  const admin = await createPlayer({ username: `BenchQA${Math.floor(Math.random() * 100000)}` });
  await admin.makeOp();
  await grant(admin, player, 'minecraft:diamond[custom_name=\'{"text":"Workbench-origin"}\']', 3, item => item.name === 'diamond');
  await command(admin, `/item replace entity ${player.username} armor.head with minecraft:golden_helmet`, /Replaced/);
  await command(admin, `/item replace entity ${player.username} weapon.offhand with minecraft:shield`, /Replaced/);
  await until(() => player.bot.inventory.slots[5]?.name === 'golden_helmet' && player.bot.inventory.slots[45]?.name === 'shield', 'Origin equipment did not arrive');
  const original = inventory(player), dialogs = watchDialogs(player);
  try {
    player.chat('/arcevents apocalypse');
    await activePlayers(admin, 1);
    assert.equal(player.bot.game.gameMode, 'survival', 'Workbench actor must remain an ordinary survival player');
    assert.equal(materialCount(player, 'diamond'), 0, 'Origin items leaked into the expedition');
    assert.equal((await qa(admin)).generation, 'apocalypse-original-v1');
    await teleport(admin, player, STARTER_TABLE.offset(.5, 1, 2.5));
    await until(() => player.bot.blockAt(STARTER_TABLE)?.name === 'crafting_table', 'Source starter crafting table is missing at -1,34,37');
    const starterOutline = await workbenchOutline(player, STARTER_TABLE);
    await preparePad(admin, player);

    await grant(admin, player, 'minecraft:copper_ingot', 9);
    await grant(admin, player, 'minecraft:crafting_table', 1);
    await teleport(admin, player, BENCH.offset(.5, 0, 2.5));
    await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'crafting_table'), 'hand');
    await player.bot.placeBlock(player.bot.blockAt(BENCH.offset(0, -1, 0)), new Vec3(0, 1, 0));
    await until(() => player.bot.blockAt(BENCH)?.name === 'crafting_table', 'Player-placed crafting table did not arrive');
    const placedOutline = await workbenchOutline(player, BENCH);
    await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'iron_axe'), 'hand');
    await player.bot.dig(player.bot.blockAt(BENCH));
    await until(() => !player.bot.entities[placedOutline.id], 'Breaking the crafting table left its outline behind');
    await teleport(admin, player, BENCH.offset(.5, 0, .5));
    await until(() => materialCount(player, 'crafting_table') === 1, 'Broken crafting table was not recovered');
    await teleport(admin, player, STARTER_TABLE.offset(.5, 1, 2.5));
    let catalog = await openBench(player, dialogs, STARTER_TABLE, /Assembly bench/);
    await craftCopperBlock(player, catalog);
    catalog = await openBench(player, dialogs, STARTER_TABLE, /Assembly bench/);
    const bootstrap = await recipe(player, dialogs, catalog, 'Telegraph');
    await craftOnce(player, admin, dialogs, bootstrap, () => count(player, isTelegraph) === 1 &&
      materialCount(player, 'copper_block') === 0 && materialCount(player, 'crafting_table') === 0,
    'T1 bootstrap must spend exactly one copper block and one crafting table');
    await closeBench(player, dialogs);

    await teleport(admin, player, BENCH.offset(.5, 0, 2.5));
    await until(() => stationDisplays(player).length === 0, 'Previous disposable workstation displays did not clear');
    await placeStation(player);
    await nativeStationDisplays(player);
    await workbenchOutline(player, BENCH);
    // Two gun batches leave enough input to detect a duplicated accepted callback.
    await grant(admin, player, 'minecraft:oak_log', 8);
    await grant(admin, player, 'minecraft:iron_ingot', 32);
    catalog = await openBench(player, dialogs, BENCH, /Telegraph.*T1/);
    const revolver = await recipe(player, dialogs, catalog, 'No. 3 Revolver');
    await craftOnce(player, admin, dialogs, revolver, () => count(player, isRevolver) === 1 &&
      materialCount(player, 'oak_log') === 4 && materialCount(player, 'iron_ingot') === 16,
    'Source bf1:sw_model3 must spend exactly four logs and sixteen iron ingots');
    const gun = player.bot.inventory.items().find(isRevolver);
    assert.equal(pdc(gun, 'world_firearm'), 'REVOLVER', 'Crafted source gun lacks its usable native firearm identity');
    assert.equal(Number(pdc(gun, 'world_loaded_rounds')), 0, 'Crafting granted a free native magazine');
    await closeBench(player, dialogs);
    const expedition = inventory(player);
    player.chat('/arcevents leave');
    await waitOrigin(player, admin, original);
    await until(() => !player.bot.entities[starterOutline.id], 'The native starter workbench outline remained after leaving Apocalypse');
    player.chat('/arcevents apocalypse');
    await activePlayers(admin, 1);
    await until(() => inventory(player) === expedition, 'Leave/re-entry changed the crafted expedition inventory');
    assert.equal(count(player, isRevolver), 1, 'Saved expedition lost or duplicated its source gun');

    await teleport(admin, player, BENCH.offset(.5, 0, 2.5));
    const minedDisplays = await nativeStationDisplays(player);
    await grant(admin, player, 'minecraft:iron_pickaxe', 1);
    await player.bot.equip(player.bot.inventory.items().find(item => item.name === 'iron_pickaxe'), 'hand');
    const drops = new Map();
    const dropped = entity => {
      const item = entity.getDroppedItem();
      if (item?.name === 'dropper' && entity.position.distanceTo(BENCH) < 4) drops.set(entity.id, itemState(item));
    };
    player.bot.on('itemDrop', dropped);
    try {
      await player.bot.dig(player.bot.blockAt(BENCH));
      await until(() => player.bot.blockAt(BENCH)?.name === 'air' && drops.size > 0, 'Native block breaking did not emit its carrier drop');
      await until(() => minedDisplays.every(id => !player.bot.entities[id]), 'Mining the station did not destroy all observed native BlockDisplay IDs');
      assert.equal([...drops.values()].reduce((n, item) => n + item.count, 0), 1, 'BlockDropItemEvent emitted duplicate workstation carriers');
      assert.ok([...drops.values()].every(isTelegraph), 'Native breaking lost the workstation item/block identity');
      await teleport(admin, player, BENCH.offset(.5, 0, .5));
      const nearbyCarriers = () => Object.values(player.bot.entities).filter(entity => entity.position.distanceTo(BENCH) < 4)
        .map(entity => entity.getDroppedItem()).filter(item => item?.name === 'dropper');
      await until(() => count(player, isTelegraph) === 1 && nearbyCarriers().length === 0, 'The sole identified workstation drop was not picked up');
      await pause(500);
      assert.equal(materialCount(player, 'dropper'), 1, 'Native breaking also delivered an untagged or duplicate dropper');
      assert.equal([...drops.values()].reduce((n, item) => n + item.count, 0), 1);
    } finally {
      player.bot.removeListener('itemDrop', dropped);
    }
    await teleport(admin, player, BENCH.offset(.5, 0, 2.5));
    await placeStation(player);
    await nativeStationDisplays(player);
    await openBench(player, dialogs, BENCH, /Telegraph.*T1/);
    await closeBench(player, dialogs);
    assert.equal(count(player, isRevolver), 1, 'Mining and replacing the station changed the crafted firearm');
    player.chat('/arcevents leave');
    await waitOrigin(player, admin, original);
    console.log('[Apocalypse workbenches] native 3×3 copper block and source bootstrap; T1 cost/unloaded gun; consumed callbacks; exact origin/expedition restore; twenty-plus native BlockDisplay spawn/destroy IDs; one identified native drop and re-placement');
  } finally {
    dialogs.stop();
    player.chat('/arcevents leave');
    await activePlayers(admin, 0);
    admin.bot.quit();
  }
});

test('Apocalypse native blast furnace consumes fuel and preserves tungsten source identity', async ({ player, createPlayer }) => {
  await ready();
  const admin = await createPlayer({ username: `BlastQA${Math.floor(Math.random() * 100000)}` });
  await admin.makeOp();
  const original = inventory(player);
  let furnace;
  try {
    player.chat('/arcevents apocalypse');
    await activePlayers(admin, 1);
    assert.equal(player.bot.game.gameMode, 'survival');
    await preparePad(admin, player);
    await command(admin, `/execute in ${WORLD} run setblock ${FURNACE.x} ${FURNACE.y} ${FURNACE.z} minecraft:blast_furnace`, /Changed the block/);
    await teleport(admin, player, FURNACE.offset(.5, 0, 2.5));
    const isRaw = item => pdc(item, 'apocalypse_source_item') === 'the_deeper_void:raw_tungsten';
    const isIngot = item => pdc(item, 'apocalypse_source_item') === 'the_deeper_void:tungsten_ingot';
    // Match the canonical sourceItem(raw) ExactChoice: typed PDC and its plain non-italic English name.
    await grant(admin, player, 'minecraft:raw_iron[custom_data={PublicBukkitValues:{"arcevents:apocalypse_source_item":"the_deeper_void:raw_tungsten"}},custom_name={text:"Raw tungsten",italic:false}]', 1, isRaw);
    await grant(admin, player, 'minecraft:coal', 1);
    await until(() => player.bot.blockAt(FURNACE)?.name === 'blast_furnace', 'Native blast furnace chunk did not arrive');
    furnace = await player.bot.openFurnace(player.bot.blockAt(FURNACE));
    const raw = furnace.items().find(isRaw), coal = furnace.items().find(item => item.name === 'coal');
    assert.ok(raw && coal, 'Native blast furnace did not expose the granted inputs');
    await furnace.putInput(raw.type, null, 1);
    assert.ok(isRaw(furnace.inputItem()), 'Native input transfer stripped the raw tungsten identity');
    await furnace.putFuel(coal.type, null, 1);
    try {
      await until(() => Number(furnace.fuel) > 0, 'Native coal fuel did not ignite');
      await until(() => isIngot(furnace.outputItem()) && furnace.outputItem().count === 1,
        'Native blasting did not produce the tagged tungsten ingot', 35000);
    } catch (error) {
      const input = furnace.inputItem(), fuel = furnace.fuelItem(), output = furnace.outputItem();
      console.log('[Apocalypse blasting fixture]', JSON.stringify({
        input: { item: input?.name, count: input?.count, source: pdc(input, 'apocalypse_source_item'),
          customName: input?.components?.find(component => component.type === 'custom_name')?.data },
        fuel: { item: fuel?.name, count: fuel?.count, remaining: furnace.fuel },
        output: { item: output?.name, count: output?.count, source: pdc(output, 'apocalypse_source_item') },
      }));
      throw error;
    }
    assert.equal(furnace.outputItem().name, 'iron_ingot');
    assert.equal(furnace.inputItem(), null, 'Native blasting did not consume its one tagged raw input');
    await furnace.takeOutput();
    furnace.close(); furnace = null;
    await until(() => count(player, isIngot) === 1, 'Native tungsten output pickup lost source identity');
    assert.equal(count(player, isRaw), 0);
    assert.equal(materialCount(player, 'coal'), 0, 'Native fuel was not consumed from the player inventory');
    player.chat('/arcevents leave');
    await waitOrigin(player, admin, original);
    console.log('[Apocalypse workbenches] native fuel/input transfer and tagged tungsten blasting output verified');
  } finally {
    if (furnace) furnace.close();
    player.chat('/arcevents leave');
    await activePlayers(admin, 0);
    admin.bot.quit();
  }
});

test('Apocalypse native refinery spends one fuel cell as 400 then 600 units and returns exact source byproducts', async ({ player, createPlayer, server }) => {
  await ready();
  const admin = await createPlayer({ username: `RefineQA${Math.floor(Math.random() * 100000)}` });
  await admin.makeOp();
  const original = inventory(player), dialogs = watchDialogs(player);
  const refinery = 'the_deeper_void:void_refinery_control_pad';
  const fuel = isSource('the_deeper_void:refinery_fuel_cell');
  const tungsten = isSource('the_deeper_void:tungsten_ingot');
  const rhenium = isSource('the_deeper_void:rhenium');
  const tungstenAlloy = isSource('the_deeper_void:tungsten_rhenium_alloy');
  const netheriteAlloy = isSource('the_deeper_void:netherite_rhenium_alloy');
  const slag = isSource('the_deeper_void:void_slag');
  const taggedIngot = source => `minecraft:iron_ingot[custom_data={PublicBukkitValues:{"arcevents:apocalypse_source_item":"${source}"}}]`;
  try {
    player.chat('/arcevents apocalypse');
    await activePlayers(admin, 1);
    assert.equal(player.bot.game.gameMode, 'survival');
    await preparePad(admin, player);
    await teleport(admin, player, BENCH.offset(.5, 0, 2.5));
    await grant(admin, player, `minecraft:dropper[custom_data={PublicBukkitValues:{"arcevents:apocalypse_source_item":"${refinery}"}},block_entity_data={id:"minecraft:dropper",PublicBukkitValues:{"arcevents:apocalypse_workbench_source":"${refinery}"}}]`, 1, isSource(refinery), server);
    await placeStation(player, isSource(refinery));
    await grant(admin, player, taggedIngot('the_deeper_void:tungsten_ingot'), 2, tungsten);
    await grant(admin, player, taggedIngot('the_deeper_void:rhenium'), 2, rhenium);
    await grant(admin, player, 'minecraft:netherite_ingot', 1);
    await grant(admin, player, 'minecraft:blaze_powder[custom_data={PublicBukkitValues:{"arcevents:apocalypse_source_item":"the_deeper_void:refinery_fuel_cell","arcevents:apocalypse_refinery_fuel_units":1000}}]', 1, fuel);
    let catalog = await openBench(player, dialogs, BENCH, /Void refinery/);
    const first = await recipe(player, dialogs, catalog, 'Tungsten-rhenium alloy');
    await craftOnce(player, admin, dialogs, first, () => count(player, tungstenAlloy) === 1 && count(player, slag) === 2 &&
      count(player, tungsten) === 0 && count(player, rhenium) === 1 && count(player, fuel) === 1 &&
      Number(pdc(player.bot.inventory.items().find(fuel), 'apocalypse_refinery_fuel_units')) === 600,
    'Native tungsten refining must spend 400/1000 fuel units and return one alloy plus two slag');
    const partial = player.bot.inventory.items().find(fuel);
    assert.equal(partial.count, 1, 'Remaining refinery charge must occupy one partial cell');
    assert.ok(partial.components?.some(component => component.type === 'max_stack_size' && component.data === 1),
      'Native partial fuel cell must carry the non-stackable component');
    await closeBench(player, dialogs);
    catalog = await openBench(player, dialogs, BENCH, /Void refinery/);
    const second = await recipe(player, dialogs, catalog, 'Netherite-rhenium alloy');
    await craftOnce(player, admin, dialogs, second, () => count(player, netheriteAlloy) === 1 && count(player, tungstenAlloy) === 1 &&
      count(player, slag) === 6 && count(player, rhenium) === 0 && materialCount(player, 'netherite_ingot') === 0 && count(player, fuel) === 0,
    'Native netherite refining must spend the same cell\'s remaining 600 units and return four more slag');
    await closeBench(player, dialogs);
    player.chat('/arcevents leave');
    await waitOrigin(player, admin, original);
    console.log('[Apocalypse workbenches] native refinery used one 1000-unit cell as 400+600; typed alloys and exact 2+4 slag observed');
  } finally {
    dialogs.stop();
    player.chat('/arcevents leave');
    await activePlayers(admin, 0);
    admin.bot.quit();
  }
});
