# Cursed Walking reference and native port

Source audit: 2026-11-14. This records the specific reference studied and the
local ArcEvents implementation. Source correspondence, compilation, deployment,
server behavior and client appearance are separate evidence; this document does
not establish live activation or client acceptance.

## Pinned reference

The reference is OnMod's **Cursed Walking – A Modern Zombie Apocalypse**, project
682001, **v3.3 (patch)**, file **8603430**, published 2026-08-08. It is the Forge
1.20.1 release; this audit is pinned to that file.

- [Exact CurseForge file](https://www.curseforge.com/minecraft/modpacks/cursed-walking-a-modern-zombie-apocalypse/files/8603430).
- [Original archive](https://mediafilez.forgecdn.net/files/8603/430/Cursed%20Walking-v3.3%20%28patch%29.zip).
- [Associated server pack, file 8712048](https://www.curseforge.com/minecraft/modpacks/cursed-walking-a-modern-zombie-apocalypse/files/8712048), published 2026-08-23; this audit used the client manifest/overrides archive above.

The archive manifest declares Minecraft **1.20.1**, **Forge 47.4.0**,
`recommendedRam=10240` MiB and **245 required file references**. The latter is
the manifest count, not a claim that 245 independent gameplay mods run on both
sides. The manifest calls the version `v3.3 (patch)`; there is no additional
patch number in that field.

Downloaded study artifact: `cursed-walking-v3.3-patch.zip`, **294201586 bytes**.
SHA-256:

```text
9e27be83c25a78d13508134be65d2fe70efb389ce015d2a9f54f8dd0f9861901
```

At audit time it was stored under
`/private/tmp/cursed-walking-study-20261114/`, with selected text extracted into
`text/`. Those temporary paths are research aids, not required runtime paths.
The upstream project lists All Rights Reserved. The native Kotlin runtime reads
original structure data from separately cached, pinned archives; the plugin and
repository do not redistribute the upstream archive, structure NBT or city
assets. [Project metadata](https://www.curseforge.com/minecraft/modpacks/cursed-walking-a-modern-zombie-apocalypse).

Relevant exact manifest entries:

| Component | CurseForge project / file | Evidence used |
| --- | --- | --- |
| The Hordes | 485779 / 6718502 | [1.5.4c file](https://www.curseforge.com/minecraft/mc-mods/the-hordes/files/6718502), pack config and [author source at 5f0265dd1872cb4d631a8605ab4a8b5d6fc27eec](https://github.com/SmileycorpMC/The-Hordes/tree/5f0265dd1872cb4d631a8605ab4a8b5d6fc27eec) |
| Zombies+ | 1072974 / 5889911 | [2.1.2 file](https://www.curseforge.com/minecraft/mc-mods/zombies-plus-frenzied-horde/files/5889911), pinned Forge class bytecode |
| JCWS | 1140880 / 8502762 | [Exact file](https://www.curseforge.com/minecraft/mc-mods/jeffs-cursed-walking-structures/files/8502762), structure sets and procedure bytecode |
| Scaling Mob Difficulty | 506521 / 7398187 | [2.4.3 file](https://www.curseforge.com/minecraft/mc-mods/scaling-mobs/files/7398187), pack config and pinned bytecode |
| Keerdm's Zombie Apocalypse Essentials | 1061079 / 7740113 | [1.42 file](https://www.curseforge.com/minecraft/mc-mods/keerdm-zombie-apocalypse-essentials/files/7740113), original city parts and overrides |
| The Lost Cities | 269024 / 7432706 | [7.4.11 file](https://www.curseforge.com/minecraft/mc-mods/the-lost-cities/files/7432706), base city assets |

Additional SHA-256 fingerprints of the inspected JARs:

```text
Zombies+5889911  1aa6b95b2036f754f2910f5026eb16df8a6fd16a1b24d489c1592bb1277dcf0c
JCWS8502762      80da781a4c097f93d0be3f2139228508124f2af678abd036479de9f1f8b82e23
Scaling7398187   d4ae69cabcaefd914f4a1601b7e33d831310350dedbd490e285b8d36c89ef5fb
Keerdm7740113    1b9db7636db3a1bcfb015d19c6d44b663e454f05e12e472d128c60f2b5214d3e
LostCities7432706 7a3f3c55de1b869380fdbdef4fa8865bc1daf3ae6c66fab1056ea44d6534b4fb
```

The three structure-content JARs total **5228600 bytes** (about 5.2 MB).
`plugins/ArcEvents/content/apocalypse-original/` is a local runtime cache. The
loader verifies pinned hashes and decodes archive entries as data away from
gameplay work. It does not load Forge classes or execute code from those JARs.

## Horde clock and infection

Archive inputs are `overrides/config/hordes-common.toml` and
`overrides/config/hordes/data/hordes/horde_data/`. The selected script points to
`tables/test.json`, despite its name; `tables/default.json` is not the active
population table for these rules.

The Hordes counts days from zero and keeps events **per player**, using world
time rather than accumulated player playtime. With the pack defaults:

- No day 0 event; every 10 days, no random variation.
- Start at time 10000 with a 1200-tick admission buffer.
- Event duration 6000 ticks; 6 mobs per pulse, every 1000 ticks: six pulses in the
  initial event, 36 requested mobs before caps or multiplayer reduction.
- Configured active cap 125; spawn distance 75, 25 placement checks, path interval 10
  and horde movement multiplier 1.0.
- Each nearby active horde player reduces a pulse by ×0.8, flooring at each step.
- Empty **server** pauses the upstream clock/event; beds are disabled during an
  event. Pausing merely because one world is empty is a different policy.

The 1.5.4c source builds the next spawn amount from the previously recorded horde
day and uses linear growth. The initial requested pulse sizes are 6, 6, 7 rather
than an exponential `6×1.1^n` progression. ArcEvents keeps a strict configured
cap 125; the upstream comparison is `>125`, which can admit a 126th entity.
Delayed native-port callbacks drop missed pulse slots rather than bursting all
missed spawns into one tick.

The active weighted table's maximum day is **inclusive**:

| Source type / profile | Weight | Days |
| --- | ---: | --- |
| Slow / weak | 6 / 8 | 0–20 |
| Vanilla zombie | 10 | 0+ |
| Crawler / axe / sword | 3 / 2 / 2 | 20–40 |
| Leaper / runner | 2 / 2 | 40–60 |
| Bow / crossbow | 2 / 2 | 60–80 |
| Brute | 3 | 80–99 |
| Drone / feral | 5 / 1 | 100+ |
| Raged vanilla zombie | 1 | 80+ |
| Mutated vanilla zombie | 1, then 3 | 150–199, then 200+ |
| Mutant Monsters zombie, max-health NBT 250 | 1 | 100+ |

Infection has base player/villager chance **25%**, modified by configured
wearables. It has **four 6000-tick stages**; expiry of the fourth is lethal in
the pinned source. After each cure, later stage duration is multiplied by
`0.95^cureCount`; death resets the count. Slowness progresses through movement
factors 1.0, 0.9, 0.8, 0.7 and exhaustion through 0.03, 0.06, 0.09, 0.12 per tick.

`tags/items/infection_cures.json` contains golden apple, enchanted golden apple,
Immersive Weathering golden moss clump and enchanted golden moss clump. The
vaccine is a separate immunity item: `cure:vaccine`, duration 350 **seconds**,
converted to 7000 ticks. It clears infection without incrementing the cure
count. A golden-apple cure does not grant that vaccine immunity window.
`infectionSpawnsZombiePlayers=false`; zombie-player graves must not be inferred
from the infection feature alone.

The pure implementations are
[ApocalypseSurvival.kt](../src/main/kotlin/ru/ruscrafting/events/domain/apocalypse/ApocalypseSurvival.kt)
and [its parity checks](../src/test/kotlin/ru/ruscrafting/events/domain/apocalypse/ApocalypseSurvivalTest.kt).

## Natural population and attributes

Archive inputs are `overrides/config/incontrol/phases.json`, `spawn.json` and
`spawner.json`. Phase upper day bounds are **exclusive**, unlike the Hordes
table. The actual phases overlap; the four UI phase labels are presentation
buckets and do not define population unlocks.

| InControl phase | Active elapsed days |
| --- | --- |
| t1 / t2 / l2 | [0, 1) / [1, 6) / [0, 6) |
| z1 / c1 | [6, 21) / [11, 51) |
| z2, t4 / z3, t5 / b1 | [21, 31) / [31, 41) / [31, 51) |
| z4, t6 / z5, t7 / b3 | [41, 51) / [51, 71) / [51, 71) |
| c2 / d1 | [51, ∞) / [61, 91) |
| z6, t8 / b4 / z7, t9 / m1, d2 | [71, 81) / [71, ∞) / [81, ∞) / [91, ∞) |

The 33 custom spawner rows are retained in
[ApocalypseSurvivalReference.kt](../src/main/kotlin/ru/ruscrafting/events/domain/apocalypse/ApocalypseSurvivalReference.kt),
including per-second chance, amount range, per-type cap, cave/sky and time
conditions. Natural unlocks are weak/slow at 0, vanilla walker at 6, crawler at 11,
axe/sword at 21, leaper/runner at 31, bow/crossbow at 61, brute at 71 and mutant at 91.
Shrieker is explicitly denied in the overworld and absent from the active pools.

Placement defaults in these rows are distance 60–110, vertical distance 40,
light 0–4 and 300 attempts. Day/night surface windows split at 13500. `spawn.json`
also controls native spawn admission; the custom spawner table alone is not the
whole Forge spawning engine. A source anomaly is preserved: both cave-crawler
rows use `c1` (chances 0.1 and 0.15); no custom `c2` spawner row exists.

Pinned attribute/goal distinctions:

| Type | Base max HP / damage | Attribute speed | Separate goal parameter |
| --- | --- | --- | --- |
| Weak | 20 / 1 | 0.20999999344348907 | Native melee |
| Slow | 20 / 1 | 0.17499999701976776 | Native melee |
| Crawler | 15 / 2.5×0.8 | 0.23000000417232513 | Native melee |
| Runner | 20 / 3 | 0.23000000417232513×0.9 | Pursuit 1.8 |
| Leaper | 20 / 3 | 0.23000000417232513 | Pursuit 1.7; jump Y 0.35 |
| Brute | 40 / 5 | 0.17000000178813934×1.2 | Native melee |
| Axe / sword | 20 / 6 or 5 | 0.23000000417232513 | Weapon equipment remains separate |
| Bow / crossbow | 20 / 3 | 0.23000000417232513 | Pursuit 1.2 / range 15 / interval 20; pursuit 1.5 / range 8 |
| Feral | 30×3 / 5 | 0.43 | Config-derived |
| Drone | 30 / 4 | 0.25 | Armor 3; source melee panic 800 ticks |

The ×0.9, ×1.2, ×0.8 and ×3 values come from InControl finalize rules, not guessed
variant bonuses. Attribute speed is not the same value as an AI movement
multiplier. The leaper's grounded check uses squared target distance 4–16 and
the source impulse `(normalizedHorizontal×0.4 + currentVelocity×0.2)×2`, with
vertical velocity 0.35.

ScalingMobs applies `MULTIPLY_TOTAL` modifiers: health/damage factor
`1 + min(scale×0.0025,1.5)`, speed factor `1 + min(scale×0.0005,1.1)`.
Its health update preserves the current/max-health ratio. The adapter currently
uses elapsed integer days for scale. Mutant Monsters is blacklisted from these
modifiers. Full player-scale favors, milestones, armor piercing and drop/XP
scaling are outside this helper; the existing mutant carrier still has a
documented base-damage/speed approximation.

## JCWS procedures and special zombies

The five spawner procedures summon **`minecraft:zombie` with NBT**, not five
registered custom zombie entity types. The names
`jeffs_cursed_walking_structures:{bunker,hazmat,soldier,mutated,warehouse}_zombie`
are explicit ArcEvents aliases for those procedure identities.

| Procedure profile | Commanded HP / max HP | Damage / speed / KB resistance | Source armor |
| --- | --- | --- | --- |
| Bunker | 28 / 28 | 2 / vanilla zombie speed / 0.3 | Netherite boots; iron legs, chest and helmet |
| Hazmat | 35 / 45 | 6 / 0.3 / 0.1 | Netherite boots; Marbled's Arsenal hazmat legs, chest and helmet |
| Soldier | 28 / 25, initial health clamps to 25 | 5 / 0.23 / 0 | Netherite boots; leather legs / chest color 6192150; turtle helmet |
| Mutated | 150 / 150 | 9 / 0.3 / 0.1 | Netherite boots; leather legs / chest color 11546150; NetherrackDigger player head; visual fire |
| Warehouse | 23 / 23 | 5 / 0.3 / 0 | Netherite boots; leather legs / chest color 4673362; iron helmet |

Source armor drop NBT is zero except mutated boots 0.05 and warehouse boots 0.01.
These are recorded source facts, not an approved replacement for expedition
loot balancing. `structureZombies`, `structureZombie(spawn)` and
`profile.spawn(token)` expose the data without Bukkit. `stats.health` is maximum
health; `stats.initialHealth` preserves the separate initial value. Existing
`minecraft:zombie`/`mutated` horde records still resolve to that profile.

The bunker block schedules its update every **25 server ticks**. For each player
in an AABB inflated 35, the procedure increments a shared block timer. At 15 it
spawns two zombies; its Timer/Kill counters lengthen subsequent intervals.
The six successive required player-passes are 15, 17, 19, 21, 23, 25;
after six pulses the block becomes air. Multiple nearby players accelerate the
shared timer. This is not a random-tick scheduler or a kill-gated quest.

The forcefield switch scans a sphere of radius 31 and removes forcefield air,
generators and itself; forcefield collision damage is 2. The gun-storage
interaction opens its container and plays a sound. No keycard, alarm quest,
POWER→SECURITY→CONTAINMENT chain or invented reactor boss was found in these
procedures. Rage≥1 has no base-stat buff: with a target, its source procedure
has a 3% per-tick chance to fire the custom blockbreak projectile.

## City and structure distribution

`lostcities/common.toml` selects `minecraft:overworld=custom2`. Its profile has
city chance 0.0005, minimum/maximum radius 125/225, city threshold 0.2, ground 71,
building chance 0.3, maximum floors 8 and cellars 0–3. The active world style is
`keerdm_zombie_essentials:testworld`, selecting its `standard` city style. The
native V2 world uses original part layouts and palettes rather than the V1
prototype's authored parcel interiors.

The catalog loads base Lost Cities assets first, then overlays Keerdm's assets,
including its overrides in the `lostcities` namespace. It contains **255 merged
parts and 51 building definitions**. The active city-style closure reaches
**25 distinct buildings and 126 building parts**: five regular building types
plus seven multi-building definitions. Shopping, town hall, center, library and
open shopping layouts use 2×2 footprints; `huge1` and `huge2` use 3×3 footprints.
The source closure check found no missing definitions, undefined palette
characters or undersized random-palette lists in those active building parts.

Original floor, cellar and roof parts retain their dimensions and character
layouts. Palette blocks use the source's cumulative **128-slot** selection;
the value 1000 fills the remaining slots, rather than representing a normalized
1000-based weight. Multi-building cells share their top-left height/cellar rolls,
limits and building palette. Source roads and parks use their original parts.
The shared loader and city renderer are
[ApocalypseStructureAssets.kt](../src/main/kotlin/ru/ruscrafting/events/domain/apocalypse/source/ApocalypseStructureAssets.kt)
and [ApocalypseLostCityAssets.kt](../src/main/kotlin/ru/ruscrafting/events/domain/apocalypse/source/ApocalypseLostCityAssets.kt).

One literal source anomaly is handled narrowly: Keerdm's `top_crane` declares
16×16×23 cells, but two layers contain 255 characters each. Their original
flattened character positions are preserved. Only the absent final cell is
omitted; rows are not padded and no solid replacement is fabricated. Other
malformed parts are rejected.

The retained base palette also contains two literal references to
`minecraft:red_sandstone@2`. Legacy metadata 2 is canonicalized to
`minecraft:cut_red_sandstone` before state validation and placement, using the
official Minecraft 1.20.1 flattening entry 2866 (`179<<4 | 2`). It is distinct
from the later `smooth_red_sandstone` block. The pinned Lost Cities parser
delegates unbracketed legacy names to Mojang's `BlockStateData`; unsupported
additional `@` forms are rejected rather than discarded or mapped generically.

Pinned JCWS JAR data contains **131 structure NBT files**. Its relevant structure
sets are:

| Set | Placement parameters |
| --- | --- |
| Bunkers | Random spread spacing 75 / separation 6 chunks, triangular, salt 59786254; frequency 0.9 with legacy_type_3; exclusion 8 chunks from surface set |
| Surface | 46 / 7, salt 647884569 |
| Desert surface / snow surface | 50 / 8, salt 637884569 / 45 / 8, salt 627884569 |
| Water | 82 / 6, salt 480456315; oil rig weight 2, underwater lab weight 1 |
| Starter bunker | 4000 / 67, salt 80085; initial-login placement is separate |
| Teleporter | Concentric rings: count 32, distance 32, spread 2 |

All **131 original JCWS NBT templates**, their template-pool memberships and
named/oriented jigsaw connector data are imported. The assembler uses those
original pieces and connections for source structures, including the starter
bunker, instead of the prototype's equivalent lab/reactor/ruin rooms. Template
blocks retain source local coordinates and block states before native mapping.
Source custom entities currently contribute spawn and spawner placement markers;
their Forge implementations and the other custom entities are not materialized.

The native layout reuses relevant 75/6, 46/7 and 82/6 spread parameters and salts,
with bounded terrain checks and its own placement orchestration. Importing the
original layouts does not reproduce the full Forge generator: city placement,
RNG consumption, infrastructure selection, terrain fitting, biome rules and
surface exclusion are not Forge-identical. Terralith and CW–Depths dimensions,
decoration and progression are not reproduced by the native terrain generator.

Mod-only block IDs and properties have explicit vanilla replacements. Original
coordinates and template layouts are preserved, but the materials, textures and
behavior of those replacement blocks remain native adaptations. Original sign
text and supported skull/banner decoration are restored through a block-entity
whitelist in
[ApocalypseSourceTiles.kt](../src/main/kotlin/ru/ruscrafting/events/paper/apocalypse/ApocalypseSourceTiles.kt).
Decoded source `Items` and `LootTable` data stay inert: hydration does not issue
their contents, evaluate loot tables or execute commands. Existing finite
expedition loot remains the container-content authority.

V2 defaults to `arcevents_apocalypse_v2` and retains the V1 prototype world.
When recovering a saved expedition from an older world, its location is
normalized to the canonical V2 starter spawn while its inventory is preserved.
This migration does not issue another starter kit. Origin-inventory recovery
continues through its existing durable journal; this policy does not itself
establish that a live migration has run.

## Start and loot evidence

`overrides/config/starterkit/kits/starterkit.txt` is the active kit. The Archer,
Default, Lumberjack and Witch alternatives are under `kits/inactive/`. The active
kit contains:

| Slot / content | Exact source item and amount |
| --- | --- |
| Head / feet | `marbledsarsenal:cm6m_gas_mask`×1 / `minecraft:iron_boots`×1 |
| Tool | `marbledsmelees:fire_axe`×1, Damage 42 |
| Food | `minecraft:bread`×9 |
| Quest book | `ftbquests:book`×1 |
| Medicine | `cure:vaccine`×6 |
| Equipment | `binocularsmod:binoculars`×1 |

There is no firearm or ammunition in that active kit. `SpawnInBunkerProcedure`
does not issue items: with its gamerule enabled, world time≤200 and the player's
firstjoin value 0 or 1, it places the `starterbunker` structure. Treat starter-kit
issuance and initial structure placement as separate source mechanisms.

`overrides/config/openloader/data/NewLCLootV3.1.zip` contains **62 loot tables**:
60 under `newlc` plus two TACZ chest tables under `keerdm_zombie_essentials`.
They distinguish houses, offices, bedrooms, kitchens, garages, melee, armor,
advanced variants and guns. Example source pools:

- `tacz_ammochest`: two material rolls and five weighted ammunition rolls.
- `tacz_gunchest`: two material rolls and one–two gun-pool rolls, with 14 entries
  including an empty result; gun identities span TACZ and BF1 content.
- `a_gun1` and `a_gun5`: two component rolls plus one–two gun-pool rolls.
- `basic_house1`: four material rolls; `kitchen1` has separate food, knife and tea
  pools rather than the port's generic resource bundle.

The city condition file weights ordinary house/office/kitchen/bedroom/garage
tables 13, ordinary melee/armor 3, TACZ chest entries 9 and advanced guns 2, with
additional rare tables. One literal source inconsistency remains unresolved:
the condition references `keerdm_zombie__essentials` with two underscores, while
the bundled tables use one. No successful Forge runtime resolution is claimed.

`incontrol/loot.json` is empty and `kubejs/server_scripts/lootTweaks.js` is wholly
commented out. Lootr is enabled, but `refresh_all=false` and refresh lists are
empty; its presence is not evidence of periodic automatic loot renewal.

The current native port uses **its own finite, provisional expedition loot**.
It has not imported these 62 tables, TACZ/BF1 gun/attachment catalog, modded food
catalog or Lootr's complete per-player container semantics. ArcEvents firearms
are the existing WorldFirearms implementation, not TACZ weapon simulation.

## Native acceptance limits

- The backend target is Paper/Purpur 1.21.11 with an unmodified Minecraft client,
  not Forge 1.20.1. No BetterStructures or new mod runtime is required.
- Original external NBT/city layouts replace the prototype geometry. They do not
  imply identical Forge terrain/RNG placement, native mod-block behavior or
  materialization of source custom entities beyond spawn/spawner markers.
- Actors are vanilla zombie carriers with
  [entity-owned Paper goals](../src/main/kotlin/ru/ruscrafting/events/paper/apocalypse/ApocalypseMobGoal.kt).
  Pursuit, 20-tick melee and leap math are implemented; full Zombies+/Feral/
  Mutant Monsters models, special AI, animation and effects are not implied.
- Arrows use the native projectile/damage pipeline; bow spread/difficulty rolls
  and native client pose are not fully reproduced. Crossbow uses fixed 25-tick
  charge plus 15-tick recovery. Rage uses a loaded-chunk-only, callback-gated,
  no-drop ray up to 4 blocks instead of the original custom projectile.
- Reinstalling AI removes the stable owned goal key and competing MOVE goals,
  retains target-selection goals and creates no global AI timer. Its focused
  [boundary tests](../src/test/kotlin/ru/ruscrafting/events/paper/apocalypse/ApocalypseMobGoalTest.kt)
  do not establish native pathfinding, projectile physics or client rendering.
- Source-derived timers/profiles and deterministic state recovery are narrower
  claims than a complete reproduction of the modpack. Original-template assembly,
  native block replacements, equipment, starting experience, weapon handling and
  provisional loot still require native-server and in-client acceptance
  separately. Build and source-catalog checks do not prove rendered appearance.

The native admission token is saved only with an applied expedition loadout and
removed after verified origin restoration, before PARKED acknowledgement. A
matching ACTIVE token can recover current native gear from Paper's fallback
world without treating that gear as a new origin. RETURNING always replays its
durable origin. The native test also exercises an offline player.dat fallback.

Further JCWS source detail: ForcefieldGeneratorOnTickUpdate grants its custom
Restricted Area effect for300 ticks to players in an inflated32-block AABB. The
pinned MineProcedure reads that effect but never assigns Forge BreakSpeed or
cancels the event; it merely initializes a local number. The native port does
not invent a mining prohibition from that unused value. The custom status icon
and generator particle animation are not reproduced. Source armor-drop chances
are retained as metadata; native owned-actor removal currently emits no armor
drops, including the source's mutated5%/warehouse1% boots.
