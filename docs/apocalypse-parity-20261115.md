# Cursed Walking parity — ArcEvents0.6.4

Reference: the inspected Cursed Walking v3.3(patch) archive. This is a native
Paper adaptation inside ArcEvents. Archive/config imports, executable native
mechanics and rendered client acceptance are separate evidence.

## Present

- A permanent cooperative world with menu entry, leave/resume and durable
  separation of origin and expedition inventories, equipment and experience.
- 131 original JCWS NBT templates, 45 pools and 31 structure definitions.
  These are building pieces and definitions, not 131 distinct bunkers.
  LostCities/Keerdm city parts and source floor/cellar/street/park composition
  data are also imported. Modded blocks and decoration have native replacements.
- 93 original loot tables, authored container items and source crate rolls,
  with weights, empty results and counts preserved. Native replacements cover
  source gun/ammunition/material/medicine positions. Container stock is finite
  and shared. Crowbar access applies to the relevant parts crates.
- Existing native firearms shoot and reload. Personal hordes recur every ten
  Minecraft days, with source timing, composition and progression; native
  spawning and bunker proximity profiles are present.
- Infection has four stages, source infection/cure/vaccine timing and repeat
  infection progression. Optional field-guide screens explain supplies,
  exploration, equipment, infection, the next wave and returning home.
- ArcEvents0.6.4 repairs stale arrival tickets and same-route retry; ordinary
  live menu entry, exit, inventory restoration and immediate reentry passed.
  See [recovery evidence](apocalypse-entry-recovery-20261115.md).

## Material gaps

| Surface | Current native behavior | Missing parity |
| --- | --- | --- |
| Guns and progression | 88 source gun IDs map to ten used existing firearm profiles; for example M4A1/P90/MP5 map to G36. Ammunition is simplified and attachment items become salvage. | TACZ/BF1/PointBlank weapon catalog, distinct calibers, attachments, magazine/ballistic behavior and T1→T2→T3 weapon/ammunition workbenches. |
| Base production | Imported Create items have ordinary native replacements. | Working waterwheel/press, pumps/steam engine, deployer/precision mechanism and the related manufacturing chains. |
| Base security | Native doors/blocks retain their ordinary behavior. | SecurityCraft reinforced blocks, code doors, cameras and turrets. |
| Depths and DeepVoid | Their source loot can be interpreted by the loot owner. | Dimensions, travel, pressure, terrain/ores and map→mine/drilling rig→rare material→refinery progression. Imported loot tables do not implement those mechanics. |
| World generation | Original source buildings/parts are materialized using native blocks. | Exact Terralith terrain/biomes and Forge LostCities placement/RNG/infrastructure behavior; full modded blocks and decoration. |
| Enemies | Native zombie carriers provide pursuit, melee, leap, ranged and bounded rage attacks; crawler size and late-wave stats are approximated. | Full mutant/feral/drone AI, models, animations and all source scaling rules. |
| Medicine and equipment | Infection, cures and vaccines work; several consumables use native potion equivalents. | Wearable immunity modifiers, Foxweed→vaccine crafting and full source medicine recipes/effects. |
| Loot and death | One finite physical container inventory is shared. Expedition state is journaled. | Lootr per-player container inventories, source mob drops/XP and Corpse recovery. Death inventory policy still depends on the host's normal rules. |
| Pack support | Optional native field guide and existing Minecraft UI. | Full FTB quest chapters, backpacks/upgrades, trains/air transport, seasons and client minimap/recipe interfaces. |

The original Lootr configuration has `refresh_all=false` and empty refresh lists;
it does not establish periodic automatic loot renewal. Likewise, the original
FTB guide is mostly optional guidance rather than a mandatory keycard/reactor
campaign. Those should not be invented as requirements for parity.

## Next useful implementation

First implement a functional weapon bench with tiered guns, useful parts,
ammunition and upgrades. Then connect base production/defense and a rare-material
expedition to that progression. Adding more building templates alone will not
provide the missing reason to keep exploring after the first usable weapon.

This is a proposed order, not a claim that these mechanics have been delivered.
No meaningful whole-pack parity percentage is asserted: the manifest includes
client utilities and performance mods as well as actual gameplay.

## Evidence

- [Reference import and generation limits](apocalypse-reference.md).
- [Source tables and explicit native item substitutions](apocalypse-source-loot-20261115.md).
- Native owners: `ApocalypseLootItems.kt`, `ApocalypseRuntime.kt` and
  `ApocalypseMobGoal.kt` in the Paper apocalypse package.
- Original `ftbquests/quests/chapters`: `ballistics_division.snbt`,
  `create_t1.snbt`, `create_t2.snbt`, `create_t3.snbt`, `base_security.snbt`,
  `depths.snbt` and `guides_and_tips.snbt`; Hordes `immune_wearables.json`.

Building geometry and resource-pack appearance were not visually accepted by
the0.6.4 live protocol test. Its acceptance covers actual entry, normal guide
callbacks, origin restoration and expedition persistence.
