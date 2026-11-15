# Apocalypse weapon workbenches, 2026-11-15

The addition opens source-cost crafting inside the isolated Apocalypse expedition. The catalog has **107 recipes: 66 guns (12 T1 / 37 T2 / 17 T3), 21 ammunition recipes, six stations and 14 material recipes**, plus separate native tungsten blasting. The material recipes include the ammo boxes, presses, shaft/casings, refinery fuel batch and three rhenium alloys. Before, these source recipes were unavailable; that is not a zero-price offer. After, successful crafting consumes the listed physical ingredients. Direct currency mint and burn remain separately **0 Vault and 0 premium tokens** before and after. Workbench crafting/refining has no XP reward; tungsten blasting introduces native furnace XP within the expedition profile.

The refinery now supplies the original titanium-, tungsten- and netherite-rhenium alloys required by the other 13 T3 recipes. Those recipes no longer have a missing-refinery disabled gate. This is source-level acquisition support for all 17 T3 recipes, not proof that an ordinary player has the remaining finite inputs, fuel or a completed gun. Every alloy still consumes typed rhenium, its metal input and fuel energy; none is fabricated for free.

This assesses source rules and explicit sensitivities, not an active HOST, a native client or measured ordinary-player progression. The [input](apocalypse-workbenches-20261115.json) and [canonical evaluator result](apocalypse-workbenches-20261115-result.json) keep item quantities, XP and each currency separate. The runtime [recipe catalog](../../src/main/resources/apocalypse/workbench-recipes.json) contains exact source IDs, tags, quantities and provenance for every imported recipe. Attachments, colorizers and unsupported rows are outside this craftable scope.

## Station costs and the progression gate

| Result | Craft at | Consumed items for one result | Guns opened |
|---|---|---|---:|
| T1, `bf1:telegraph` | Ordinary crafting table | 1 copper block + 1 crafting table | 12 |
| T2, `tacz:gun_smith_table` | T1 | 45 `create:iron_sheet` + 16 `create:golden_sheet` + 25 quartz | 37 |
| T3, `sfms:ms_workbench` | T2 | 25 `the_deeper_void:tungsten_ingot` + 15 `create:sturdy_sheet` + 35 quartz | 17 |
| AMMO, `tacz:ammo_workbench` | Ordinary crafting table | 2 copper ingots + 1 iron ingot + 1 `tacz:ammo_box` | 18 TaCZ ammo recipes |
| PRESS, `create:mechanical_press` | Ordinary crafting table | 1 `create:shaft` + 1 `create:andesite_casing` + 1 iron block | Three material presses |
| REFINERY, `the_deeper_void:void_refinery_control_pad` | Ordinary crafting table | 2 steel ingots + 1 redstone block + 2 tungsten ingots + 1 refinery casing + 2 repeaters + 1 gold ingot | Three rhenium alloys |

The existing table is a required station, **not a consumed ingredient**. T1, T2 and T3 remain available after crafting the next table. The AMMO recipe is a vanilla-crafting branch, not a quest lock or a required T1 upgrade. T3 also exposes its three SFMS ammunition recipes and the same six original TaCZ ammunition recipes (`12g`, `556x45`, `308`, `57x28`, `40mm`, `9mm`) without changing their costs.

One new iron ammo box costs **1 iron ingot + 7 planks**, so building AMMO with that box consumes **2 copper ingots + 2 iron ingots + 7 planks** in total. The alternative gold and diamond ammo-box recipes each consume their named material ×1 plus 7 planks. These are item costs, not a new currency or free storage grant.

Source processing in this scope is deliberately limited:

| Processing | Input → output | Additional requirement / XP |
|---|---|---|
| Iron pressing | 1 iron ingot → 1 `create:iron_sheet` | Existing `create:mechanical_press`; 0 new XP |
| Gold pressing | 1 gold ingot → 1 `create:golden_sheet` | Existing press; 0 new XP |
| Industrial iron pressing | 1 `createdeco:industrial_iron_ingot` → 1 `createdeco:industrial_iron_sheet` | Existing press; 0 new XP |
| Tungsten blasting | 1 `the_deeper_void:raw_tungsten` → 1 `the_deeper_void:tungsten_ingot` | Native blast furnace, normal native fuel, 240 ticks, 2 native furnace XP per processed item when awarded |

The press can be found in finite source loot or crafted. Its support chain is **2 andesite alloy → 8 shafts**, and **1 stripped log/wood + 1 andesite alloy → 1 andesite casing**. Starting without those intermediates, one new press therefore costs **3 andesite alloy + 1 stripped log/wood + 1 iron block**, with **7 shafts left over**. Casing `item_application` is adapted to the ordinary crafting table with the original input/output quantities; the native menu does not reproduce Create's kinetic machinery. No free crafting input or operator grant is assigned.

These quantities were checked against pinned Create 6.0.8 file 7178761, CreateDeco file 6373226 and The Deeper Void file 8145376. The source paths are in the JSON. Pressing a generic iron ingot does not make industrial iron, sturdy sheet, titanium, steel or an alloy. Beyond the three refinery alloys below, further source processing for rose quartz, steel and titanium is not imported; their available source-loot forms remain distinct acquisition gates.

## A complete example material bill

One example takes a **DB-2 Durin → AKM → AF-46**, while retaining all earlier guns and stations. It is an illustrative route, not a requirement to buy every gun or proof of equal combat value.

| Step | Additional gun cost beyond its station |
|---|---|
| T1 DB-2, `tacz:db_short` | 8 iron ingots + 6 eligible logs |
| T2 AKM, `tacz:ak47` | 38 iron sheets + 15 quartz + 10 eligible logs |
| T3 AF-46, `sfms:af_46` | 16 tungsten ingots + 4 titanium ingots + 8 rose quartz |

All four stations, a newly made iron ammo box and those three guns consume, at the immediate-ingredient boundary:

**1 copper block + 1 crafting table + 2 copper ingots + 10 iron ingots + 83 iron sheets + 16 golden sheets + 75 quartz + 16 logs + 7 planks + 41 tungsten ingots + 15 sturdy sheets + 4 titanium ingots + 8 rose quartz.**

If all iron/gold sheets are made with the included presses and all tungsten is blasted from raw, replace the sheet/tungsten terms with **93 total iron ingots, 16 gold ingots and 41 raw tungsten**; the other terms stay as listed. This substitution replaces intermediate inputs rather than adding their raw materials a second time. It does not convert different materials into one monetary value. The output is four stations and three unloaded guns; no ammunition batch is included.

Processing those 41 raw tungsten takes **9,840 cooking ticks = 492 seconds at 20 TPS**, plus the required native fuel, and can award **82 native furnace XP**. Of that, the T3 table alone needs 25 tungsten: 6,000 ticks and 50 XP if made entirely from raw. Looted finished ingots skip that processing and its XP. These are furnace-service times, not measured time to find the materials or complete progression. The primary bill uses an existing press; if none is found/owned, add the separately itemized new-press setup above. A native blast furnace, fuel and mining tool remain additional non-free prerequisites.

Some other T3 guns consume **a specific source gun**, including recipes needing `tacz:spr15hb`, `tacz:m249` or two `sfms:inf`. That predecessor is an actual additional sink; a different gun sharing its native Paper profile does not satisfy the source `GunId`. The example above does not hide or generalize those alternative costs. Their exact input vectors remain in the catalog.

One explicit owner-approved repair preserves the HAR-50 quantity while replacing its nonexistent source ingredient `the_deeper_void:netherite_rhenium_ingot` ×2 with the registered `the_deeper_void:netherite_rhenium_alloy` ×2. The original vector and correction remain recorded; the new refinery supplies that registered alloy through its paid item/fuel recipe.

## Refinery costs, fuel and native adaptations

The source casing recipe consumes **6 iron ingots + 3 iron bars → 2 refinery casings**. Building one new control pad with that fresh batch therefore consumes **6 iron ingots + 3 bars + 2 source steel ingots + 1 redstone block + 2 tungsten ingots + 2 repeaters + 1 gold ingot**, leaving **one unused casing**. Already owned parts replace only their actual corresponding inputs; no stock is granted by this calculation.

The casing's native carrier is **one iron ingot, not an iron block**. Two iron-block carriers could be uncrafted into 18 ordinary iron from a recipe costing only 6 ingots plus 3 bars. Two ingot carriers yield at most 2 ordinary iron-equivalent, so that mint loop is removed while the original output of two source-identified casings stays intact. No general vanilla-crafting gate is added or claimed: native carrier use may sacrifice source identity, but does not reconstruct a source component for free.

| One refinery operation | Consumed material inputs | Cell energy | Material output and byproduct | Original wait without upgrade |
|---|---|---:|---|---:|
| Titanium-rhenium alloy | 1 `mcore:titanium_ingot` + 1 rhenium | 320 | 1 alloy + 2 void slag | 80 s |
| Tungsten-rhenium alloy | 2 tungsten ingots + 1 rhenium | 400 | 1 alloy + 2 void slag | 100 s |
| Netherite-rhenium alloy | 1 netherite ingot + 1 rhenium | 600 | 1 alloy + 4 void slag | 150 s |

These material, energy and byproduct ratios are exact source rules. Refining awards **0 XP**. Void slag is a real additional item output, with no included recycling recipe, money redemption or assigned SELL value.

The owner-approved native implementation makes two material progression changes explicit. First, **the crafted control pad represents the complete original multiblock**. The source would additionally place 9 bottom casings, 8 pressure tanks and 5 casings. If all source parts were freshly crafted in whole recipe batches, those omitted parts add **54 iron ingots + 51 iron bars + 36 zinc ingots + 36 copper ingots + 4 tungsten ingots + 4 redstone dust** beyond the native pad bill; both calculations leave one casing. This is a lower reusable capital requirement, not full source assembly fidelity. Second, **native refining is an instant atomic menu craft**: the original 80/100/150-second waits are recorded but not enforced. One of each would occupy 330 seconds in the source; no native maximum craft rate is invented. The changed time gate can increase throughput when resources are available.

The native fuel batch consumes **10 blaze rods + 10 coal blocks + 10 typed `powderedup:sulfur_dust` + 1 lava bucket → 10 refinery fuel cells + 1 empty bucket**. Each cell stores **1000 energy units**, so the batch stores **10,000**. One bucket supplies the same ten × 100 mB of lava as the original per-cell recipe; the returned empty bucket is the existing container shell, not a new bucket grant. The source superheated Create mixer and its engine/heat requirement are not enforced. Ordinary gunpowder does not substitute for the source sulfur identity; acquisition of sufficient sulfur remains a material prerequisite.

Full cells stack at 1000 units per item. A partially spent cell is a portable, validated PDC item with **1–999 units and maximum stack size 1**. Spending uses partials first and removes a cell at zero. Three titanium operations consume 960 units and leave 40; one of each alloy consumes 1320 units and, starting with two full cells, leaves one partial with 680. Energy is not rounded to a whole cell per craft. The capacity check includes the alloy, slag and partial-cell remainder; if any will not fit, the operation must leave all inputs and energy unchanged. These item-energy units are not Vault or premium tokens.

For an additional rhenium example, one **AF-57** beyond the existing T3 table and refinery needs **2 titanium ingots + 2 rhenium + 24 tungsten ingots + 8 rose quartz + 640 refinery energy**, producing the unloaded gun and **4 void slag** through its two intermediate alloy operations. Counting a new T3 table (25 tungsten) and new refinery pad (2) makes the tungsten requirement **51** for that branch, plus the other listed station/material/fuel costs. There is no extra refinery XP. This is a second explicit bill, not a promise of enough remaining stock for every player.

## Ammunition costs and the native utility limit

All quantities below are consumed for one successful batch. Copper, iron, gunpowder, lapis and blaze rods mean their exact allowed source ingredient/tag; brass remains the source `create:brass_ingot`, not an ordinary gold substitute.

| Source ammo ID | Output rounds | Batch inputs |
|---|---:|---|
| `tacz:12g` | 18 | 10 copper + 5 gunpowder + 18 iron nuggets |
| `tacz:308` | 60 | 20 copper + 9 gunpowder + 1 lapis |
| `tacz:30_06` | 24 | 14 copper + 5 gunpowder |
| `tacz:338` | 18 | 16 copper + 7 gunpowder + 3 lapis |
| `tacz:357mag` | 48 | 16 copper + 5 gunpowder |
| `tacz:40mm` | 6 | 2 iron + 6 copper + 8 gunpowder |
| `tacz:45acp` | 30 | 7 copper + 2 gunpowder |
| `tacz:45_70` | 36 | 10 copper + 3 gunpowder + 3 lapis |
| `tacz:46x30` | 48 | 8 copper + 2 gunpowder |
| `tacz:50ae` | 36 | 20 copper + 6 gunpowder + 3 lapis |
| `tacz:50bmg` | 24 | 70 copper + 18 gunpowder + 8 lapis + 1 blaze rod |
| `tacz:556x45` | 45 | 10 copper + 3 gunpowder |
| `tacz:57x28` | 48 | 10 copper + 3 lapis + 2 gunpowder |
| `tacz:58x42` | 40 | 10 copper + 3 gunpowder |
| `tacz:762x39` | 35 | 10 copper + 3 gunpowder |
| `tacz:762x54` | 60 | 8 copper + 3 gunpowder |
| `tacz:9mm` | 50 | 6 copper + 2 gunpowder |
| `tacz:rpg_rocket` | 3 | 2 iron + 20 copper + 10 gunpowder |
| `sfms:25gl` | 24 | 10 brass + 20 gunpowder + 4 lapis |
| `sfms:300ms` | 60 | 2 amethyst blocks + 6 brass + 5 gunpowder |
| `sfms:50arms` | 30 | 20 brass + 5 gunpowder + 3 lapis |

Source ammo identity is retained, but **the current native firearm reload system accepts common ammunition across these calibers**. Therefore the cheap 9mm batch gives 50 usable native rounds, while the much more expensive 50 BMG batch gives 24. Retaining source recipes does not make those outputs equal in native utility. Plain eligible iron nuggets remain an existing alternative at one nugget per round; they are not counted again as a new recipe output. No recipe rebalance or fictional money exchange is proposed here.

The 66 source gun IDs retain their names and use the existing **ten native gun profiles**; this does not reproduce 66 distinct mod firing/damage mechanics. None of the 66 recipe outputs supplies `GunCurrentAmmoCount`, so each is created **loaded = 0**. No free full magazine is added to the item bill. Existing ammunition and already loaded state stay governed by the earlier reload rules.

## Conditional low / base / high crafting

Assume **1 / 5 / 20 participating accounts** and **1 / 2 / 4 active hours per participant-day**. Within those same hours, each has the necessary existing stations, stocked inputs and stored cell energy and performs the counts below. They are assumptions for arithmetic sensitivity, not measured craft rates, resource availability or daily entitlements. Other activities are not added as extra income during the same hours. The ten-cell fuel batch is an upfront material bill, not a free daily refill.

| Per participant-day | Low | Base | High |
|---|---:|---:|---:|
| 9mm batches | 1 | 4 | 16 |
| Common rounds produced | 50 | 200 | 800 |
| Copper / gunpowder consumed by those batches | 6 / 2 | 24 / 8 | 96 / 32 |
| DB-2 guns produced | 0 | 1 | 2 |
| Iron / logs consumed by those guns | 0 / 0 | 8 / 6 | 16 / 12 |
| Iron press input → sheets | 4 → 4 | 16 → 16 | 64 → 64 |
| Gold press input → sheets | 1 → 1 | 4 → 4 | 16 → 16 |
| Industrial iron input → sheets | 0 → 0 | 4 → 4 | 16 → 16 |
| Raw tungsten consumed → ingots | 0 → 0 | 5 → 5 | 20 → 20 |
| Native furnace XP within expedition | 0 | 10 | 40 |
| Each of the three refinery alloys produced | 0 | 1 | 4 |
| Titanium / tungsten / netherite consumed by refining | 0 / 0 / 0 | 1 / 2 / 1 | 4 / 8 / 4 |
| Rhenium consumed by refining | 0 | 3 | 12 |
| Refinery cell energy consumed | 0 | 1,320 | 5,280 |
| Void slag produced | 0 | 8 | 32 |

Before, these crafting/processing flows are unavailable and their quantities are zero. After, the table gives separate outputs and sinks. Total iron ingot consumption across the modeled gun and iron-press operations is **4 / 24 / 80**, not just the gun row. Vault and tokens mint/burn remain **0 / 0 / 0** independently; outside-expedition XP and newly loaded rounds also remain zero.

The calculator's conditional seven-day totals are **350 / 7,000 / 112,000 common rounds**, **0 / 35 / 280 DB-2 guns**, **0 / 175 / 2,800 raw tungsten consumed**, and **0 / 350 / 5,600 expedition furnace XP**. Refining additionally gives **0 / 35 / 560 of each alloy**, consumes **0 / 105 / 1,680 rhenium** and **0 / 46,200 / 739,200 energy units**, and outputs **0 / 280 / 4,480 slag**. Each is its own unit. They require enough distinct existing stock or further exploration for every successful debit; multiplication by accounts does not duplicate a shared chest, ore block or fuel cell. These modeled tungsten/rhenium demands exceed the finite studied cohort and are **not sustainable forecasts**. There is no newly imposed daily cap, premium multiplier or assumed universal network input budget. The source model does not establish a maximum collection or click rate.

## Finite acquisition, source identity and indirect flows

The **93 source loot tables retain their rolls, weights, quantities and empty outcomes**. Existing source claims remain once per physical shared container. Reopening, another viewer or another account cannot replenish its batch. The earlier [source-loot assessment](apocalypse-source-loot-20261115.md) remains the per-container EV baseline; this task does not reweight it. Additional finite containers nearer the city can change accessibility and the encountered table mix. Permitted administrator flight can also shorten travel. Their effect on openings per hour has not been measured, so no loot-EV or income multiplier is assigned.

A source-plan study for **seed 68200133** finds **13 placed teleporters / 17 natural main rooms**, containing **51 actual source tungsten-ore blocks** and **40 raw tungsten in the studied chest plans**. Before Fortune and assuming untouched obtainable stock, that is **91 raw-equivalent tungsten**, shared across the studied sites. The example T3 table + AF-46 needs 41, so the theoretical base raw budget covers that part of one route. This does not prove that the relevant containers are unclaimed, tools/other inputs are available, the route is reachable by an ordinary player, or every player can make the same purchase. It is not a live balance, a per-player grant, a full-world upper bound or proof of unlimited T3 supply.

Source tungsten ore needs a correct iron/diamond/netherite pick, gives **0 mining XP**, has no separate Silk Touch branch, and uses the original Fortune multiplier `1 + max(0, nextInt(f + 2) - 1)`. At ordinary Fortune III the ore multiplier has expectation **11/5** and maximum **4**. For this same finite cohort, including the 40 chest raw, that means **152.2 expected / 244 maximum raw**, conditional on all those untouched blocks being mined with that tool. It is not a loot-table reweight or a rate per hour. A journaled shared claim per source XYZ prevents replacing/rebreaking the same location from becoming a renewable raw/XP source. An unported city crystal-crushing route is not counted as renewable baseline income.

The same source study contains **51 columbite blocks**, requiring a correct **diamond/netherite pick** and giving **0 mining XP**. Each drops a uniform 1–2 plain raw iron, plus an independently selected **50% chance of one typed rhenium**. Each awarded branch has its own Fortune roll; there is no separate Silk Touch branch. Before Fortune, the cohort yields **51–102 raw iron, EV 76.5**, and **0–51 rhenium, EV 25.5**. At Fortune III the EVs are **168.3 raw iron / 56.1 rhenium**, with maxima **408 / 204**. The unchanged studied chests contain another 14 rhenium in their source plans: combined rhenium EV is **39.5 before Fortune / 70.1 at Fortune III**, conditional on untouched stock. These are finite source expectations, not observed inventory balances or per-player rewards.

This adapter replaces earlier native carrier drops at exact source coordinates: tungsten ore previously mapped to deepslate iron ore, while columbite mapped to polished deepslate. New typed-output availability does **not** mean the old world yielded no ordinary materials. Columbite's native ore appearance is migrated only at guarded source positions; ordinary lookalike emerald ore is not a rhenium source. The new raw iron can be processed by unchanged native recipes; its realized downstream output and furnace XP are unmeasured and are not asserted zero by the modeled tungsten-XP row.

New mod loot and processing outputs carry **source PDC identity**. A renamed or plain iron/gold/native carrier cannot satisfy `create:sturdy_sheet`, tungsten, titanium, industrial iron or a specific predecessor gun. This prevents an ordinary-material shortcut around the listed mod costs. Old untagged materials are not retrospectively converted: their original mod identity cannot be recovered reliably. Genuine vanilla ingredient rows still accept their ordinary native items. No compensation stock, operator grants or free materials are included in the assessment.

Crafting is manufacturing, not a claim for free output: the accepted recipe must debit every required input and have capacity for the complete output. A full inventory, missing ingredient, stale station, cancellation or repeated click must not leave an unpaid result; no overflow drop or partial free batch is assumed. Source checks and focused tests can support this contract, while reload/replay, station break/place, native fuel and normal-player acceptance remain separate runtime observations.

The expedition continues to isolate inventory, armor, offhand and XP and to restore the origin state on exit. Existing command/export restrictions remain. ArcEvents adds no Vault/token deposit or shop/contract payout. Third-party mining, advancement or reward hooks remain an integration uncertainty until observed on the actual HOST; the source assessment does not prove zero indirect external emission. The canonical inventory records the existing SELL policy and the owner's SELL-to-contract transition separately. This task changes neither, assigns no SELL value to expedition loot and does not treat future contracts as replacement income. Currency saving time is **not applicable**; actual item-acquisition time is **unknown**.

The bounded balance conclusion is **new physical manufacturing/fuel costs, real alloy/slag outputs and isolated furnace XP, with no direct currency emission or currency sink**. Original alloy ratios and finite shared collection rules give an explicit resource budget; the native compact assembly and instant processing deliberately reduce the source capital/time gates. The former missing-refinery gate for 13 T3 recipes is supplied without free alloys. Equal native combat value, sustainable high-scenario output, sufficient live stock and ordinary-player completion are not established. The common-ammo utility mismatch and unobserved HOST hooks remain explicit limits for the parent’s integration and acceptance decision. Cosmetic station displays add no inventory items, fuel, XP or earning boost.

## Evidence and completion boundary

The canonical economy inventory and assessment format were read. The original 225-row normalized recipe source has SHA-256 `c704392b8454ad93406e4fab556af8a76e105bbade529e8d3bf0da9a466382bb`; its archive identity, recipe paths and the runtime catalog hash used for this assessment are recorded in JSON. Current tool metadata exposes **no economy / `arc_ops_server` tool**, so no fresh 168-hour currency ledger was obtained and no unsupported shell/API bypass was used. A prior dated observation cannot establish this version's integration behavior.

The canonical evaluator checks the stated arithmetic and units; it does not validate combat equivalence, loot reachability or the completeness of the economy model. Focused documentation checks compare station/gun/ammo costs to the catalog and verify the full-route arithmetic. They do not run or activate the plugin.

After delivery, distinguish the active HOST version/config, ordinary successful and refused crafts, insufficient/full-inventory behavior, rapid replay/reload, source-identity rejection, station break/place persistence, finite container sharing, native ore/fuel/XP behavior and origin-state restoration. Refinery acceptance must include alloy + slag + partial-cell capacity, exact energy retention/depletion and the fuel batch's bucket return without duplication. If a supported ledger tool becomes available, check per-currency post-change hooks separately. No deployment, live grant, recurring monitor or schedule is created by this assessment.
