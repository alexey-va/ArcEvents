# Apocalypse workbenches — ArcEvents 0.6.6

The native adaptation now connects bunker supplies to BF1 → TaCZ → SFMS
progression. The pinned Cursed Walking v3.3(patch) recipes are bundled as data;
no Forge classes or additional runtime plugins execute.

## Ordinary player route

The starter has a crafting table at `-1 34 37`, left of the spawn. Right-click
opens its source recipe catalog. The normal 3×3 grid remains available through
the catalog button or Shift + right-click with an empty hand.

| Station | Source cost and location |
| --- | --- |
| BF1 telegraph, T1 | One copper block and one crafting table; assembled at the ordinary table. |
| TaCZ table, T2 | 45 iron sheets, 16 golden sheets and 25 quartz; assembled at T1. |
| SFMS table, T3 | 25 tungsten ingots, 15 sturdy sheets and 35 quartz; assembled at T2. |

The previous station remains usable. Six placeable specialists have distinct
client-only BlockDisplay models: T1, T2, T3, ammunition, mechanical press and
Void refinery. Their real dropper cabinet remains the native collision and
right-click target. Breaking and replacing one preserves its source identity.
Only expedition participants see the packet models; no server Display entities
or additional interaction entities are created.

The catalog has 107 recipes: 66 guns, 21 ammunition recipes and 20 station,
material, fuel and alloy recipes. Cards show current stock, exact costs,
shortage, output and byproducts. Legacy clients use a nonce-bound virtual book.
The TaCZ ammunition station remains distinct; SFMS retains its source whitelist
for six existing TaCZ ammunition recipes.

## Materials and processing

Source IDs distinguish steel, titanium, sheets, sulfur, ore and other components
even when their ordinary carrier material is the same. Renaming ordinary iron
does not make a component. Newly stocked source containers and crafted/mined
materials retain these IDs; previously issued untagged vanilla items cannot be
reidentified safely and are not rewritten.

The press uses the source 1:1 iron/gold/industrial-iron sheet recipes. Its shaft
and casing support recipes retain their source ratios. Native tungsten blasting
requires fuel and a blast furnace: one raw tungsten → one tungsten ingot,
240 ticks, 2 XP. Unsupported metadata/name variants remain unconsumed rather
than falling through to vanilla iron smelting.

Tungsten and columbite use exact post-processor source coordinates in the real
teleporter structures. Ordinary iron/emerald ores are not equivalent. Tungsten
requires an iron pickaxe or better; columbite requires diamond or netherite.
Both use the original Fortune ore formula and no mining XP. Columbite has
independent raw-iron and 50% rhenium pools. The seed study found 51 of each ore
and 40 additional raw tungsten in source chests; this is shared theoretical
unclaimed stock, not a live player inventory or per-player allowance.

The compact Void refinery preserves these ratios:

| Output | Input | Slag | Fuel units |
| --- | --- | --- | --- |
| Titanium-rhenium alloy ×1 | Titanium ×1 + rhenium ×1 | 2 | 320 |
| Tungsten-rhenium alloy ×1 | Tungsten ×2 + rhenium ×1 | 2 | 400 |
| Netherite-rhenium alloy ×1 | Netherite ×1 + rhenium ×1 | 4 | 600 |

A cell carries 1000 units; partial cells retain the remainder and cannot stack.
The bucket-based recipe makes ten cells from ten blaze rods, ten coal blocks,
ten source sulfur dust and one lava bucket, returning its empty bucket. This
keeps the source 100 mB of lava per cell. The control pad represents the original
multiblock, and menu processing is immediate. The source superheated mixer and
80/100/150-second refinery waits are intentionally adapted. HAR50 has one
explicit source-ID correction to the actually registered netherite-rhenium alloy.

Distinct source gun names and costs still share existing native firing profiles;
all ammunition currently feeds the native common reserve. This feature does not
implement TaCZ physics, attachments or Create's rotational simulation.

## First exploration and administration

A source Lost Cities waterfront and bridge sit about 103 blocks from the hatch;
the guide points to its real street at `-100 66 6`. Existing larger cities stay
present. The HUD supplies bearing, relative arrow and distance; guide pages give
XYZ and routes to structures, weapon caches and the workshop. Unopened starter
barrels emit particles for each player until that player actually opens them.

The original floor ivy was incorrectly mapped to unsupported ceiling vines.
The source palette now preserves floor ivy as moss; an exact-coordinate repair
fixes the nine existing generated cases. Old waterfront chunks are compared to
the previous generated baseline before bounded placement. Edited lots are kept
out of source-loot adoption. No world, expedition or player-data reset is used.
The migration accepts naturally accumulated single-layer snow and the grass
block's snowy flag when comparing the original surface. Revision two retries
lots that the first revision skipped for those changes, while foreign blocks
still veto the complete lot. Existing completed lots stay completed.
Outdoor ash, vent smoke and quiet infrastructure sounds add atmosphere without
changing global weather or spawning visual-only threats.

Administrators can use `/arcevents apocalypse fly` or their native self `/fly`.
Non-administrators cannot enable it; expedition resume revokes it after the
permission is removed. Exit still restores the exact original flight state.

## State and validation boundaries

Crafting builds a detached complete storage plan, including all byproducts,
partial fuel cells and output capacity. It commits the post-craft expedition
envelope with a new native-session nonce before inventory mutation. Reconnect
reads the latest durable generation first. A committed but unapplied craft
recovers the origin and retains the crafted expedition for the next entry.
Normal finite-game arrivals are not frozen by an unrelated apocalypse lookup.

Returned records retain the original envelope as a recovery receipt. A lingering
native expedition marker can replay it even after a native playerdata save
failed; an orphan marker without a recoverable root stays quarantined. Native
`Player.saveData()` has no disk acknowledgement and is not claimed to provide
an ACID transaction with the plugin journal.

Ore claims are finite and committed before fulfillment; the shared chunk-ticket
registry prevents normal unload between those steps. Only one ore-index task
uses the shared worker at once. A process crash after claim but before its
world drop can lose that drop, matching the existing at-most-once container
ceiling. Accepted mining attempts wear the tool natively; a later journal
failure does not refund that wear.

Canonical source owners are `ApocalypseWorkbenchRecipes`,
`ApocalypseWorkbenchCrafting`, `ApocalypseWorkbenchStations`,
`ApocalypseWorkbenchMenu`, `ApocalypseWorkbenchDisplays` and
`ApocalypseSourceMining`. Recipe provenance and approved native corrections are
in `src/main/resources/apocalypse/workbench-recipes.json`.

See the [separate balance assessment](economy/apocalypse-workbenches-20261115.md).
Focused recipe, item identity, fuel, ore, connection recovery, guide, model and
source-geometry cases passed after correcting two documented MockBukkit fixture
limitations. The native-station spec has two explicit platform skips; the real
Paper flow separately verifies its placement/break/replacement path.

Real Paper 1.21.11 acceptance passed these focused flows:

- Native 3×3 copper-block crafting → source T1 assembly → unloaded source
  revolver; rejected duplicate callbacks; exact origin/expedition preservation;
  at least twenty packet BlockDisplays appear and the same IDs disappear on
  break; exactly one identified station drops and can be placed again (45.8 s).
- Blast furnace input/fuel transfer → tagged tungsten output with native fuel
  consumption (59.1 s). The fixture uses modern component SNBT rather than a
  JSON string masquerading as the item's display name.
- Actual refinery menu → W400 then N600 from one 1000-unit cell; native partial
  max-stack-size, both alloy outputs and exact slag counts (4.0 s).

Run the native file with `ARC_EVENTS_E2E_HOST=1 TEST_FILES=workbenches.spec.js
TEST_NAMES='Apocalypse native' TEST_TIMEOUT=180000 ./gradlew plugwrightTest`,
supplying the two existing `e2eArcJar`/`e2eArcCoreJar` fixture properties. The
published ops helper has 46 passing bounded menu-gesture tests and two passing
viewer asset/preflight checks. Source workbench/guide images were rendered with
the configured 1.21.11 pack SHA-1
`f8293489eef5e3167f7fc9da4e2fa947b49a3b5e`; those are separate from live client
acceptance. Runtime activation and ordinary public-proxy observations are
recorded in the rollout receipt after delivery.
