# Fishing expedition player corrections (0.5.1)

The first live playthrough of 0.5.0 exposed four problems: a player who fell
into the sea could remain below the raised shore; the fight recovery notice
could repeat; a hooked creature was hard to find and no fish appeared in the
inventory; and the islands felt too small. The owner also requested feeding
fish to an NPC as in How to Fish.

Version 0.5.1 gives each island roughly twice the walkable footprint and a
slab shoreline that can be climbed from the water. A player who stays submerged
for four seconds returns to the current island without ending the run. The
new geometry uses the dedicated `fishing-v3` template and a new configurable
world (`arcevents_fishing_v3` by default); existing v1/v2 chunks remain
untouched. The five island centers are now x=0/64/128/192/256.

Each hook lands the living catch on the dock, outlined and named. Slot five
shows the living catch during combat and a match-tagged bag item after defeat.
The player can hold the bag and right-click the trader NPC under the canopy to
feed one fish per click; the native dialog offers the same action. The authoritative
bag stays in the match state, and item tags, amount, proximity, phase and
snapshot checks prevent stale or duplicated transactions. The NPC and all
creatures are removed on stage change and match closure. Encounter recreation
no longer emits a repeated chat/action-bar notice. Dynamite thrown into the
fishing water still applies its configured damage to the catch it summons.

The solo quit path now begins restoration one tick after the quit event, so
the departing player is no longer selected as an online teleport target while
Paper is disconnecting them. The committed escrow remains available for
recovery on the next join. The 0.5.0 live log showed one rejected recovery
teleport at 23:13:47 MSK; source and unit tests do not prove that particular
player's later restoration.

Economy delta is zero for a full bag: feeding pays each existing catch value
once, with unchanged temporary credit values, prices, limits and catch chances. It emits
no Vault money, premium tokens, exported items, XP or EMC. The
[0.5.0 balance assessment](fishing-v2-balance.json) therefore still applies.

Focused tests cover the five-island run, NPC feeding, visible inventory states,
shore geometry, water rescue, departure escrow, configuration profiles and
RU/EN locales. Native client rendering, physical shore climbing and actual
player-return behavior require live acceptance after activation.
