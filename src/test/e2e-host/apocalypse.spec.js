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
    return /ArcEvents enabled .*arenaReady=true redisConnected=true/.test(log);
  }, 'ArcEvents startup did not finish',60000);
}
async function qa(admin) {
  const since=admin.getMessageBufferIndex(); admin.chat('/arcevents apocalypse qa');
  await expect(admin).toHaveReceivedMessage(/^ARCEVENTS_APOCALYPSE /,{since,timeout:10000});
  const line=admin.messageBuffer.slice(since).find(x=>x.startsWith('ARCEVENTS_APOCALYPSE '));
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
    const container=await player.bot.openContainer(player.bot.blockAt(at));
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
