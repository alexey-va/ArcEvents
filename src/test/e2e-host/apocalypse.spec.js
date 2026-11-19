import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { gzipSync } from 'node:zlib';
import nbt from 'prismarine-nbt';
import { expect, test } from '@drownek/plugwright';
import { Vec3 } from 'vec3';
import { waitForQa } from './qa.js';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
// Component patches are keyed by type; native save/login may reorder their wire entries.
const itemState = i => i && {name:i.name,count:i.count,nbt:i.nbt,components:[...(i.components??[])].sort((a,b)=>a.type.localeCompare(b.type))};
const items = p => JSON.stringify(p.bot.inventory.slots.map(itemState));
async function until(probe, message, timeout=20000) {
  const end=Date.now()+timeout;
  do { const result=await probe(); if(result)return result; await pause(100); } while(Date.now()<end);
  assert.fail(message);
}
async function ready() {
  await until(async()=> {
    const log=await readFile(new URL('../../../build/plugwright-host/logs/latest.log',import.meta.url),'utf8');
    assert.ok(!/ArcEvents startup preparation failed closed|ArcEvents failed closed during startup/.test(log),'ArcEvents failed closed during startup; inspect native log');
    assert.doesNotMatch(log, /ArcEvents apocalypse world-start .* failed/);
    return /ArcEvents Apocalypse ready world=\S+ generator=apocalypse-original-v1/.test(log);
  }, 'ArcEvents startup did not finish',60000);
}
async function qa(admin) {
  const since=admin.getMessageBufferIndex();let requestedAt=0;
  const line=await until(()=>{
    const response=admin.messageBuffer.slice(since).find(x=>x.startsWith('ARCEVENTS_APOCALYPSE '));
    if(response)return response;
    if(Date.now()-requestedAt>=250){admin.chat('/arcevents apocalypse qa');requestedAt=Date.now();}
    return null;
  },'Admin QA did not return typed Apocalypse state after joining',10000);
  return Object.fromEntries([...line.matchAll(/ ([a-z_]+)=(\S+)/g)].map(([,k,v])=>[k,v]));
}
async function players(admin,n) { await until(async()=> (await qa(admin)).players===String(n),`Expected ${n} apocalypse players`,30000); }
async function teleport(admin,player,x,y,z) {
  admin.chat(`/execute at ${player.username} run tp ${player.username} ${x} ${y} ${z}`);
  await until(()=>player.bot.entity.position.distanceTo(new Vec3(x,y,z))<1,'Native teleport did not arrive');
}
// Disposable native player.dat setup: reproduce Paper loading a player in its fallback world.
async function reconnectFromFallbackWorld(player, admin) {
  const uuid=player.bot.player.uuid;
  assert.match(uuid,/^[a-f0-9-]{36}$/);
  player.bot.quit(); await players(admin,0);
  const since=admin.getMessageBufferIndex(); admin.chat('/save-all flush');
  await expect(admin).toHaveReceivedMessage(/Saved the game/,{since,timeout:15000});
  const file=new URL(`../../../build/plugwright-host/world/playerdata/${uuid}.dat`,import.meta.url);
  const {parsed}=await nbt.parse(await readFile(file));
  assert.ok(parsed.value.BukkitValues?.value['arcevents:apocalypse_session'],'Native session marker was not saved with expedition');
  const uid=await readFile(new URL('../../../build/plugwright-host/world/uid.dat',import.meta.url));
  parsed.value.WorldUUIDMost.value=[uid.readInt32BE(0),uid.readInt32BE(4)];
  parsed.value.WorldUUIDLeast.value=[uid.readInt32BE(8),uid.readInt32BE(12)];
  parsed.value.Dimension.value='minecraft:overworld';
  parsed.value.Pos.value.value=[.5,-60,.5];
  await writeFile(file,gzipSync(nbt.writeUncompressed(parsed)));
  await player.rejoin({timeout:30000}); await players(admin,1);
  const state=await qa(admin); await until(()=>player.bot.entity.position.distanceTo(new Vec3(Number(state.spawn_x)+.5,Number(state.spawn_y),Number(state.spawn_z)+.5))<2,'Fallback login did not resume at the source starter');
}
test('Apocalypse preserves separate inventories through native loot, reconnect and repeated leave',async({player,createPlayer})=>{
  await ready(); const admin=await createPlayer({username:`ApocQA${Math.floor(Math.random()*100000)}`}); await admin.makeOp();
  admin.chat(`/give ${player.username} minecraft:diamond[custom_name='{"text":"Apocalypse-origin"}'] 3`);
  await expect(admin).toHaveReceivedMessage(/Gave/);
  admin.chat(`/item replace entity ${player.username} armor.head with minecraft:golden_helmet`);
  admin.chat(`/item replace entity ${player.username} weapon.offhand with minecraft:shield`);
  await until(()=>player.bot.inventory.slots[5]?.name==='golden_helmet'&&player.bot.inventory.slots[45]?.name==='shield','Native fixture items did not arrive');
  const origin=items(player);
  try {
    player.chat('/arcevents apocalypse'); await players(admin,1);
    assert.ok(!player.bot.inventory.items().some(i=>i.name==='diamond'),'Origin items leaked into expedition');
    assert.ok(player.bot.inventory.items().some(i=>i.name==='iron_axe'),'Starter tools missing');
    const sourceState=await qa(admin);
    assert.equal(sourceState.generation,'apocalypse-original-v1','The native world did not use source structures');
    assert.ok(player.bot.entity.position.distanceTo(new Vec3(Number(sourceState.spawn_x)+.5,Number(sourceState.spawn_y),Number(sourceState.spawn_z)+.5))<2,'Spawn differs from source spawnpoint_entity');
    const mark=admin.getMessageBufferIndex(); admin.chat('/arcevents apocalypse markers LOOT');
    await expect(admin).toHaveReceivedMessage(/^ARCEVENTS_APOCALYPSE_MARKER /,{since:mark,timeout:15000});
    const line=admin.messageBuffer.slice(mark).find(x=>x.startsWith('ARCEVENTS_APOCALYPSE_MARKER '));
    const chest=Object.fromEntries([...line.matchAll(/ ([xyz])=(-?\d+)/g)].map(([,k,v])=>[k,Number(v)]));
    await teleport(admin,player,chest.x+.5,chest.y+1,chest.z+.5);
    const at=new Vec3(chest.x,chest.y,chest.z);
    await until(()=>['chest','barrel','trapped_chest'].includes(player.bot.blockAt(at)?.name),'Original supply container missing');
    // Reproduce the previous version's stocked flag and preserve a pre-existing player item.
    const migrate=admin.getMessageBufferIndex();
    admin.chat(`/execute in minecraft:arcevents_apocalypse_v2 run data merge block ${chest.x} ${chest.y} ${chest.z} {Items:[{Slot:26b,id:"minecraft:diamond",count:1}],PublicBukkitValues:{"arcevents:apocalypse_stocked":1b}}`);
    await expect(admin).toHaveReceivedMessage(/Modified block data/,{since:migrate,timeout:10000});
    const container=await player.bot.openContainer(player.bot.blockAt(at));
    assert.ok(container.containerItems().some(i=>i.name==='diamond'&&i.count===1),'Source migration removed pre-existing items');
    assert.ok(container.containerItems().some(i=>i.name!=='diamond'),'Legacy stocked flag suppressed the one-time source batch');
    const supply=container.containerItems()[0]; assert.ok(supply,'Original container did not receive its finite expedition supply');
    await container.withdraw(supply.type,null,1);
    const remaining=JSON.stringify(container.slots.slice(0,container.inventoryStart).map(itemState)); container.close();
    await pause(200); const expedition=items(player);
    await player.rejoin({timeout:30000}); await players(admin,1);
    await until(()=>items(player)===expedition,'Reconnect changed expedition inventory').catch(error=>{
      const before=JSON.parse(expedition),after=JSON.parse(items(player));
      console.log('[Apocalypse inventory differences]',JSON.stringify(before.flatMap((item,slot)=>JSON.stringify(item)===JSON.stringify(after[slot])?[]:[{slot,before:item,after:after[slot]}]))); throw error;
    });
    await reconnectFromFallbackWorld(player,admin);
    await until(()=>items(player)===expedition,'Fallback world reconnect changed expedition items');
    player.chat('/arcevents leave'); await players(admin,0); await until(()=>items(player)===origin,'Origin inventory was not restored exactly');
    player.chat('/arcevents apocalypse leave'); await pause(300); assert.equal((await qa(admin)).busy,'0','Repeated leave locked player');
    player.chat('/arcevents apocalypse'); await players(admin,1); await until(()=>items(player)===expedition,'Re-entry did not resume saved expedition');
    await teleport(admin,player,chest.x+.5,chest.y+1,chest.z+.5);
    await until(()=>['chest','barrel','trapped_chest'].includes(player.bot.blockAt(at)?.name),'Re-entry source container chunk did not arrive');
    const again=await player.bot.openContainer(player.bot.blockAt(at));
    assert.equal(JSON.stringify(again.slots.slice(0,again.inventoryStart).map(itemState)),remaining,'Reopening changed the source container'); again.close();
    player.chat('/arcevents leave'); await players(admin,0); await until(()=>items(player)===origin,'Second exit changed origin inventory');
    console.log('[Apocalypse native] origin hotbar+armor+offhand restored; finite barrel; reconnect and expedition resume verified');
  } finally { player.chat('/arcevents leave'); }
});

test('Apocalypse refuses queued entry and starts independent cooperative hordes at reference day ten',async({player,createPlayer})=>{
  await ready(); const admin=await createPlayer({username:`HordeQA${Math.floor(Math.random()*100000)}`}); const peer=await createPlayer({username:`HordePeer${Math.floor(Math.random()*100000)}`}); await admin.makeOp();
  const origin=items(player), peerOrigin=items(peer);
  try {
    player.chat('/arcevents join'); await expect(player).toHaveReceivedMessage(/You joined the queue/);
    player.chat('/arcevents apocalypse'); await expect(player).toHaveReceivedMessage(/already|queue|another/i);
    await pause(400); assert.equal((await qa(admin)).players,'0'); assert.equal(items(player),origin,'Queue rejection changed inventory');
    player.chat('/arcevents leave'); await waitForQa(admin,'status',{queue:0});
    player.chat('/arcevents apocalypse'); peer.chat('/arcevents apocalypse'); await players(admin,2);
    await teleport(admin,player,.5,72,-30.5); await teleport(admin,peer,10.5,72,-30.5);
    admin.chat('/arcevents apocalypse time 250000');
    await until(async()=> Number((await qa(admin)).hordes)>=2,'Per-player day-ten hordes did not start',20000);
    await until(async()=> Number((await qa(admin)).enemies)>0,'Committed horde did not produce native actors',30000);
    const mobs=()=>Object.values(player.bot.entities).filter(e=>e.name==='zombie');
    await until(()=>mobs().length>0,'Native zombie was not visible near survivors',20000);
    assert.ok(player.bot.health>0&&peer.bot.health>0,'Cooperative survivors died before first wave');
    console.log('[Apocalypse native] two personal hordes active; live zombie entity packets observed');
  } finally {
    player.chat('/arcevents leave'); peer.chat('/arcevents leave');
    await players(admin,0); await until(()=>items(player)===origin&&items(peer)===peerOrigin,'Co-op exit did not restore both origins');
  }
});

test('Apocalypse original bunker spawner emits native guards at its source position',async({player,createPlayer})=>{
  await ready(); const admin=await createPlayer({username:`BunkerQA${Math.floor(Math.random()*100000)}`}); await admin.makeOp(); const origin=items(player);
  try {
    player.chat('/arcevents apocalypse'); await players(admin,1);
    const since=admin.getMessageBufferIndex(); admin.chat('/arcevents apocalypse markers BUNKER_SPAWNER');
    await expect(admin).toHaveReceivedMessage(/^ARCEVENTS_APOCALYPSE_MARKER /,{since,timeout:15000});
    const line=admin.messageBuffer.slice(since).find(x=>x.startsWith('ARCEVENTS_APOCALYPSE_MARKER '));
    const m=Object.fromEntries([...line.matchAll(/ ([xyz])=(-?\d+)/g)].map(([,k,v])=>[k,Number(v)]));
    admin.chat(`/execute in minecraft:arcevents_apocalypse_v2 run tp ${player.username} ${m.x+.5} ${m.y+1} ${m.z+.5}`);
    await until(()=>player.bot.entity.position.distanceTo(new Vec3(m.x+.5,m.y+1,m.z+.5))<1,'Original bunker teleport failed',30000);
    await until(()=>player.bot.blockAt(new Vec3(m.x,m.y,m.z))?.name==='copper_grate','Source placer did not create its native spawner',30000);
    await until(async()=>Number((await qa(admin)).bunker_enemies)>0,'Original proximity spawner did not produce a native guard',40000);
    await until(()=>Object.values(player.bot.entities).some(e=>e.name==='zombie'&&e.position.distanceTo(player.bot.entity.position)<35),'Native source guard packets were not observed',10000);
    assert.equal((await qa(admin)).tile_failures,'0','Original sign/head/banner hydration failed');
    console.log('[Apocalypse original] source spawner position, native guard and decorative data verified');
  } finally {
    player.chat('/arcevents leave'); await players(admin,0); await until(()=>items(player)===origin,'Original bunker exit did not restore origin');
  }
});

function watchApocalypseDialogs(player) {
  const result={screens:[]};
  const show=({dialog})=>result.screens.push(dialog.data?nbt.simplify(dialog.data):{});
  player.bot._client.on('show_dialog',show);
  result.stop=()=>player.bot._client.removeListener('show_dialog',show);
  return result;
}
function apocButtons(value) {
  if(!value||typeof value!=='object')return[];
  if(value.action?.id&&value.label)return[value];
  return Object.values(value).flatMap(apocButtons);
}
const apocButton=(screen,id)=>apocButtons(screen).find(x=>x.action.id.endsWith(`/${id}`));
function nativePdc(value,key) {
  if(!value||typeof value!=='object')return undefined;
  if(Object.hasOwn(value,key)) {const found=value[key];return found&&typeof found==='object'&&'value' in found?found.value:found;}
  for(const child of Object.values(value)){const found=nativePdc(child,key);if(found!==undefined)return found;}
  return undefined;
}
function apocClick(player,screen,id) {
  const button=apocButton(screen,id);assert.ok(button,`Missing native ${id} button`);
  const params={id:button.action.id,nbt:{type:'end'}};
  const wire=player.bot._client.serializer.createPacketBuffer({name:'custom_click_action',params});
  assert.equal(wire.subarray(-2).toString('hex'),'0100');
  player.bot._client.write('custom_click_action',params);
}
async function sourceMarkers(admin,kind) {
  const since=admin.getMessageBufferIndex();admin.chat(`/arcevents apocalypse markers ${kind}`);
  await expect(admin).toHaveReceivedMessage(/^ARCEVENTS_APOCALYPSE_MARKER /,{since,timeout:20000});
  await pause(150);
  return admin.messageBuffer.slice(since).filter(x=>x.startsWith('ARCEVENTS_APOCALYPSE_MARKER ')).map(line=>{
    const values=Object.fromEntries([...line.matchAll(/ ([a-z_]+)=(\S+)/g)].map(([,k,v])=>[k,v]));
    return {...values,x:Number(values.x),y:Number(values.y),z:Number(values.z)};
  });
}
async function sourceContainer(admin,player,marker) {
  await teleport(admin,player,marker.x+.5,marker.y+1,marker.z+.5);
  const at=new Vec3(marker.x,marker.y,marker.z);
  await until(()=>['chest','barrel','trapped_chest'].includes(player.bot.blockAt(at)?.name),'Source loot container block did not arrive');
  return player.bot.openContainer(player.bot.blockAt(at));
}
async function openAfterSourceCheckpoint(player,block) {
  const {bot}=player;
  let since=player.getMessageBufferIndex();
  // Keep one native window listener and its timeout across explicit server requests to wait.
  const opening=bot.openContainer(block);
  while(true) {
    const window=await Promise.race([opening,pause(250)]);
    if(window)return window;
    if(!bot.currentWindow&&player.messageBuffer.slice(since).some(line=>line.includes('Your transfer is in progress.'))) {
      since=player.getMessageBufferIndex();
      console.log('[Apocalypse native] Retrying source chest after pending checkpoint');
      await bot.activateBlock(block);
    }
  }
}
async function withdrawSourceStack(player,container) {
  await player.bot._syncWindow(container);
  assert.equal(container.selectedItem,null,'Source crate transfer started with a non-empty native cursor');
  const one=container.containerItems().find(item=>nativePdc([item.components,item.nbt],'arcevents:apocalypse_source_item'));
  assert.ok(one,'Source component crate contained no identified source material');
  const identity=JSON.stringify({...itemState(one),count:0}),amount=one.count;
  // The server syncs an open container's player slots before Mineflayer copies them back to window 0 on close.
  const count=()=>container.slots.slice(container.inventoryStart,container.inventoryEnd)
    .filter(item=>item&&JSON.stringify({...itemState(item),count:0})===identity)
    .reduce((total,item)=>total+item.count,0);
  const before=count(),expected=container.slots.slice(0,container.inventoryStart).map(itemState);
  expected[one.slot]=null;
  const remaining=JSON.stringify(expected);
  const destination=container.firstEmptySlotRange(container.hotbarStart,container.inventoryEnd);
  assert.notEqual(destination,null,'Source crate fixture requires one empty hotbar slot');
  // Type-only withdraw merges distinct component-backed ingots in the pinned client. A native empty-slot swap preserves identity.
  await player.bot.clickWindow(one.slot,destination-container.hotbarStart,2);
  await player.bot._syncWindow(container);
  assert.equal(count(),before+amount,'Native source stack transfer changed its identity or quantity');
  assert.equal(container.selectedItem,null,'Native source stack transfer left items on the cursor');
  assert.equal(JSON.stringify(container.slots.slice(0,container.inventoryStart).map(itemState)),remaining,
    'Native source stack transfer changed unrelated crate contents');
  return remaining;
}

test('Apocalypse enters through the native menu and opens actionable guide routes without granting items',async({player,createPlayer})=>{
  await ready();const admin=await createPlayer({username:`GuideQA${Math.floor(Math.random()*100000)}`});await admin.makeOp();
  const origin=items(player),dialogs=watchApocalypseDialogs(player);
  try {
    player.chat('/arcevents');
    const menu=await until(()=>dialogs.screens.find(s=>apocButton(s,'action_apocalypse_menu_name')),'Apocalypse entry missing from native main menu');
    const cardSince=dialogs.screens.length;
    apocClick(player,menu,'action_apocalypse_menu_name');
    const enter='action_apocalypse_enter_name';
    const card=await until(()=>dialogs.screens.slice(cardSince).find(s=>apocButton(s,enter)),'Apocalypse card did not offer ordinary entry');
    assert.ok(!apocButton(card,'action_apocalypse_test_name'),'The ordinary player received the admin-only entry button');
    apocClick(player,card,enter);await players(admin,1);
    const expedition=items(player);let since=dialogs.screens.length;
    player.chat('/arcevents');
    const guide=await until(()=>dialogs.screens.slice(since).find(s=>apocButton(s,'track_supplies')),'Bare /arcevents did not open the field guide');
    assert.match(JSON.stringify(guide.title),/Field guide/);
    assert.ok(apocButton(guide,'track_exit'),'The real surface exit has no guide route');
    assert.ok(apocButton(guide,'track_weapons')||apocButton(guide,'track_city'),'Guide has no actual destination for finding firearms');
    const trackSince=player.getMessageBufferIndex();
    apocClick(player,guide,'track_supplies');
    await expect(player).toHaveReceivedMessage(/^Supply barrels · [A-Za-z -]+ · \d+ blocks\nX -?\d+ · Y -?\d+ · Z -?\d+\nFollow the arrow at the top of the screen\. ↑ means the landmark is straight ahead\.$/,{since:trackSince});
    assert.equal(items(player),expedition,'Tracking a source barrel changed expedition items');
    since=dialogs.screens.length;player.chat('/arcevents apocalypse guide');
    const fresh=await until(()=>dialogs.screens.slice(since).find(s=>apocButton(s,'survival')),'Guide refresh failed');
    apocClick(player,fresh,'survival');
    const help=await until(()=>dialogs.screens.slice(since).find(s=>apocButton(s,'leave')),'Survival controls did not open');
    assert.match(JSON.stringify(help.body),/crowbar/i);
    assert.match(JSON.stringify(help.body),/reload/i);
    apocClick(player,help,'back');
    await until(()=>dialogs.screens.slice(since).filter(s=>apocButton(s,'track_supplies')).length>=2,'Back did not return to the guide');
    player.chat('/arcevents leave');await players(admin,0);await until(()=>items(player)===origin,'Guide flow did not preserve origin inventory');
  }finally{dialogs.stop();player.chat('/arcevents leave');admin.bot.quit();}
});

test('Apocalypse original armories and ammo supply usable native firearms and crowbar crates stay finite',async({player,createPlayer})=>{
  await ready();const admin=await createPlayer({username:`LootQA${Math.floor(Math.random()*100000)}`});await admin.makeOp();
  const origin=items(player);
  try {
    player.chat('/arcevents apocalypse');await players(admin,1);
    // Actor-only setup isolates supply interaction from the already-advanced world's hostile pressure.
    admin.chat(`/gamemode creative ${player.username}`);await pause(300);
    const armed=await sourceMarkers(admin,'WEAPONS');let found=null;
    for(const marker of armed) {
      const chest=await sourceContainer(admin,player,marker);
      const gun=chest.containerItems().find(i=>/world_firearm/.test(JSON.stringify(i.components??i.nbt)));
      if(gun){await chest.withdraw(gun.type,null,1);found=marker;chest.close();break;}
      chest.close();
    }
    assert.ok(found,'No firearm in the deterministic bounded sample of source armories');
    const ammunition=await sourceMarkers(admin,'AMMO');let received=false;
    for(const marker of ammunition) {
      const chest=await sourceContainer(admin,player,marker);
      const ammo=chest.containerItems().find(i=>/world_ammunition/.test(JSON.stringify(i.components??i.nbt))||i.name==='iron_nugget');
      if(ammo){await chest.withdraw(ammo.type,null,Math.min(ammo.count,32));received=true;chest.close();break;}
      chest.close();
    }
    assert.ok(received,'Source ammo containers supplied no usable rounds');
    const gun=player.bot.inventory.items().find(i=>/world_firearm/.test(JSON.stringify(i.components??i.nbt)));
    assert.ok(gun);await player.bot.equip(gun,'hand');
    const loaded=()=>nativePdc(player.bot.heldItem?.components??player.bot.heldItem?.nbt,'arcevents:world_loaded_rounds');
    assert.equal(loaded(),0,'Original unloaded gun was given a free native magazine');
    admin.chat(`/effect give ${player.username} minecraft:resistance 30 255 true`);await pause(200);
    admin.chat(`/gamemode survival ${player.username}`);await pause(200);
    player.bot._client.write('block_dig',{status:6,location:new Vec3(0,0,0),face:0,sequence:0});
    await until(()=>Number(loaded())>0,'Source firearm did not reload with source ammo',12000);
    const beforeShot=Number(loaded());
    admin.chat(`/execute at ${player.username} run summon minecraft:zombie ~2 ~ ~ {NoAI:1b,Tags:["apoc_gun_qa"]}`);
    await pause(300);
    const enemy=player.bot.nearestEntity(e=>e.name==='zombie');assert.ok(enemy,'Native firearm target missing');
    await player.bot.lookAt(enemy.position.offset(0,1.5,0),true);player.bot.attack(enemy);
    await until(()=>Number(loaded())===beforeShot-1,'Accepted native shot did not consume its source-loaded round');
    admin.chat('/execute in minecraft:arcevents_apocalypse_v2 run kill @e[tag=apoc_gun_qa]');
    admin.chat(`/gamemode creative ${player.username}`);await pause(200);
    const crate=(await sourceMarkers(admin,'CRATES'))[0];assert.ok(crate);
    await teleport(admin,player,crate.x+.5,crate.y+1,crate.z+.5);
    const at=new Vec3(crate.x,crate.y,crate.z);
    await until(()=>player.bot.blockAt(at)?.name==='barrel','Source component crate missing');
    admin.chat(`/item replace entity ${player.username} weapon.mainhand with minecraft:iron_sword`);await pause(250);
    let since=player.getMessageBufferIndex();await player.bot.activateBlock(player.bot.blockAt(at));
    await expect(player).toHaveReceivedMessage(/crowbar/i,{since,timeout:6000});
    admin.chat(`/item replace entity ${player.username} weapon.mainhand with minecraft:iron_sword[custom_data={PublicBukkitValues:{"arcevents:apocalypse_tool":"CROWBAR"}}]`);await pause(250);
    const parts=await player.bot.openContainer(player.bot.blockAt(at));assert.ok(parts.containerItems().length>0,'A source crowbar did not open the materials crate');
    const expected=await withdrawSourceStack(player,parts);parts.close();
    const fresh=await player.bot.openContainer(player.bot.blockAt(at));await player.bot._syncWindow(fresh);
    const remaining=JSON.stringify(fresh.slots.slice(0,fresh.inventoryStart).map(itemState));
    assert.equal(remaining,expected,'The first native reopen changed the confirmed source crate withdrawal');fresh.close();
    const again=await player.bot.openContainer(player.bot.blockAt(at));
    await player.bot._syncWindow(again);
    assert.equal(JSON.stringify(again.slots.slice(0,again.inventoryStart).map(itemState)),remaining,'Reopening rerolled or refilled a source component crate');again.close();
    player.chat('/arcevents leave');await players(admin,0);await until(()=>items(player)===origin,'Source armory/crate flow changed origin items');
  }finally{player.chat('/arcevents leave');admin.bot.quit();}
});

test('Apocalypse admits source loot from a player-added west double-chest half and preserves both halves',async({player,createPlayer})=>{
  await ready();const admin=await createPlayer({username:`DoubleQA${Math.floor(Math.random()*100000)}`});await admin.makeOp();const origin=items(player);
  try {
    player.chat('/arcevents apocalypse');await players(admin,1);
    admin.chat(`/gamemode creative ${player.username}`);await pause(200);
    // The firearm case claims index3; use a distinct deterministic rifle container.
    const marker=(await sourceMarkers(admin,'WEAPONS'))[4];assert.ok(marker);
    await teleport(admin,player,marker.x+.5,marker.y+1,marker.z+.5);
    const run=async command=>{const since=admin.getMessageBufferIndex();admin.chat(`/execute in minecraft:arcevents_apocalypse_v2 run ${command}`);await expect(admin).toHaveReceivedMessage(/Changed the block|Modified block data/,{since,timeout:10000});};
    await run(`setblock ${marker.x} ${marker.y} ${marker.z} minecraft:air`);
    await run(`setblock ${marker.x-1} ${marker.y} ${marker.z} minecraft:air`);
    await run(`setblock ${marker.x} ${marker.y} ${marker.z} minecraft:chest[facing=north,type=right]`);
    await run(`setblock ${marker.x-1} ${marker.y} ${marker.z} minecraft:chest[facing=north,type=left]`);
    await run(`data merge block ${marker.x-1} ${marker.y} ${marker.z} {Items:[{Slot:26b,id:"minecraft:diamond",count:2}]}`);
    await pause(250);
    const added=new Vec3(marker.x-1,marker.y,marker.z);
    await until(()=>player.bot.blockAt(added)?.name==='chest','Player-added half did not arrive in native chunk packets',30000);
    const box=await openAfterSourceCheckpoint(player,player.bot.blockAt(added));
    assert.equal(box.inventoryStart,54,'Native fixture did not form a double chest');
    assert.ok(box.containerItems().some(i=>i.name==='diamond'&&i.count===2),'Source admission overwrote the player half');
    const claimed=admin.getMessageBufferIndex();
    admin.chat(`/execute in minecraft:arcevents_apocalypse_v2 run data get block ${marker.x} ${marker.y} ${marker.z} PublicBukkitValues."arcevents:apocalypse_original_loot_v1"`);
    await expect(admin).toHaveReceivedMessage(/1b/,{since:claimed,timeout:10000});
    assert.ok(box.containerItems().some(i=>/world_firearm/.test(JSON.stringify(i.components??i.nbt))),'Opening the unmarked west half bypassed source stocking');
    const remaining=JSON.stringify(box.slots.slice(0,box.inventoryStart).map(itemState));box.close();await pause(100);
    const again=await player.bot.openContainer(player.bot.blockAt(added));assert.equal(JSON.stringify(again.slots.slice(0,again.inventoryStart).map(itemState)),remaining);again.close();
    player.chat('/arcevents leave');await players(admin,0);await until(()=>items(player)===origin,'Double-chest flow changed origin inventory');
  }finally{player.chat('/arcevents leave');admin.bot.quit();}
});
