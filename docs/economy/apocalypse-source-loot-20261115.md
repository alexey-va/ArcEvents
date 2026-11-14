# Apocalypse source loot, 2026-11-15

ArcEvents 0.6.2 replaces the 0.6.1 generic grant with the original source loot rules. The proposal carries 93 original tables, retains source pool rolls, weights, inclusive count ranges and empty/air outcomes, and maps mod items to explicit native equivalents. Each physical container grants one shared finite batch; fresh containers receive source loot only. Direct currency mint and burn remain separately **0 Vault and 0 tokens** before and after. There is no currency price, paid progression, boost or purchasable full loadout, so currency saving time and a full-set price do not apply.

This is a source-level balance assessment, not production activation or a measured loot/travel rate. The [formal assessment](apocalypse-source-loot-20261115.json) follows the existing economy format; [evaluate.py output](apocalypse-source-loot-20261115-result.json) checks its stated arithmetic and units. The exact table/scenario fractions, archive hashes and input SHA-256 are embedded in the assessment. All EVs use the supplied analytical rational calculation, with **zero Monte Carlo samples** and no sampling standard error. Decimal displays below round to six places; exact fractions in JSON are authoritative.

## Before/after per physical container

Every actual table-based Lootr container in this comparison, including the two armory table variants, was a `LOOT` marker in 0.6.1. Its generic grant had EV **22 loose rounds, 3.5 cooked beef, 2 iron ingots, 0.25 native gun and 0.2 vaccine**. The unused `CORE` guaranteed-gun branch is not the armory baseline.

A source check corrects one omission in the supplied raw analysis: legacy `ApocalypseRuntime.legacySupplies` calls `TttFirearms.worldFirearmItem` without a loaded count, which defaults to a full configured magazine. The uniformly selected profiles have default capacities **20 / 30 / 8 / 8**, mean 16.5 per gun. Old loaded-round EV is therefore **0.25 × 16.5 = 4.125 per LOOT container**, separate from the 22 loose rounds. These are current source defaults; deployed configuration overrides have not been observed. The new mapper defaults absent `GunCurrentAmmoCount` to zero, and all firearm entries in the modeled source tables omit it: **new loaded-round EV is 0**. The formal comparison includes this correction rather than treating the raw input's old zero as actual behavior.

| Source table / procedure | Guns | Loose reload units | Iron ingots | Edible items | Vaccines | Iron blocks |
|---|---|---|---|---|---|---|
| supplychest | 0 | 3.5 | 1.458333 | 4.483871 | 0.21875 | 0 |
| ammo_tacz | 0 | 74.369565 | 0 | 0 | 0 | 0 |
| ammo_tacz_2 | 0 | 53.565217 | 0 | 0 | 0 | 0 |
| armory_chest | 0.472603 | 0 | 0 | 0 | 0 | 0 |
| armory_chest_tacz | 0.5 | 0 | 0 | 0 | 0 | 0 |
| resource_chest | 0 | 0 | 7.519032 | 0 | 0 | 0.401786 |
| gun_part_chest | 0 | 0 | 17.15 | 0 | 0 | 0 |
| create_part_chest | 0 | 0 | 6.75 | 0 | 0 | 0 |

“Loose reload units” is a potential-use category covering tagged native ammunition and plain `IRON_NUGGET`, both accepted by `WorldFirearms.usableAmmo` at one unit per round. It does not create a second grant or convert iron ingots/blocks. In `supplychest` there is no firearm or direct ammunition entry: its **3.5** loose reload units are ordinary iron nuggets, calculated as **3.5 expected first-pool rolls × 4/24 weight × 6 mean nuggets**. The 74.369565 figure belongs to `ammo_tacz`, not supply barrels. Supply rolls may produce no nuggets; EV does not assert the current deterministic seed outcome.

Food units measure the number of edible items, including rotten/negative foods, not equivalent nutrition, healing or saturation. A vaccine is only the medicine-tagged source `cure:vaccine`, not every honey bottle. Armor, other weapons/tools, potions, maps, enchantments and other resources remain source grants; this seven-metric comparison neither assigns them a money value nor claims they are absent. Source component-crate results use nominal Bernoulli probabilities and source inclusive counts from pinned bytecode facts; Forge/Minecraft execution and finite-grid `Math.random` behavior are not reproduced. `gun_part_chest` and `create_part_chest` require the native source crowbar before admission.

## Explicit activity sensitivity

Low/base/high assume **1 / 5 / 20 participating accounts** and, only to expose an activity-rate assumption, **1 / 2 / 4 active hours per participant-day**. Unique supply openings are **2 / 8 / 32**, ammo openings **1 / 3 / 8**, and armory openings **0.25 / 1 / 4**. Together these imply **3.25 / 6 / 11** eligible openings per active hour; none is measured throughput. All participants in each scenario use that activity, while their fraction of overall server activity is unknown.

The ammo tables are assumed **50/50 `ammo_tacz` / `ammo_tacz_2`**; armories are assumed **50/50 `armory_chest` / `armory_chest_tacz`**. This is an explicit analytical mixture, not an observed generated distribution. Resource/component crates are reported per opening above and are excluded from this requested daily mix. Before and after use the same eligible physical-opening counts to isolate the changed grant rules.

Per participant-day:

| Unit | Low before → after | Base before → after | High before → after |
|---|---|---|---|
| Native guns | 0.8125 → 0.121575 | 3 → 0.486301 | 11 → 1.945205 |
| Loose reload units | 71.5 → 70.967391 | 264 → 219.902174 | 968 → 623.73913 |
| Actual iron ingots | 6.5 → 2.916667 | 24 → 11.666667 | 88 → 46.666667 |
| Edible item units | 11.375 → 8.967742 | 42 → 35.870968 | 154 → 143.483871 |
| Vaccines | 0.65 → 0.4375 | 2.4 → 1.75 | 8.8 → 7 |
| Iron blocks | 0 → 0 | 0 → 0 | 0 → 0 |
| Loaded rounds in guns | 13.40625 → 0 | 49.5 → 0 | 181.5 → 0 |

Network seven-day sensitivity, before → after:

| Unit | Low: 1 player × 7 days | Base: 5 players × 7 days | High: 20 players × 7 days |
|---|---|---|---|
| Native guns | 5.6875 → 0.851027 | 105 → 17.020548 | 1540 → 272.328767 |
| Loose reload units | 500.5 → 496.771739 | 9240 → 7,696.576087 | 135520 → 87,323.478261 |
| Actual iron ingots | 45.5 → 20.416667 | 840 → 408.333333 | 12320 → 6,533.333333 |
| Edible item units | 79.625 → 62.774194 | 1470 → 1,255.483871 | 21560 → 20,087.741935 |
| Vaccines | 4.55 → 3.0625 | 84 → 61.25 | 1232 → 980 |
| Iron blocks | 0 → 0 | 0 → 0 | 0 → 0 |
| Loaded rounds in guns | 93.84375 → 0 | 1,732.5 → 0 | 25410 → 0 |

These weekly figures require seven days of distinct eligible containers. Reopening, replacing a container, reconnecting or adding a second player does not replenish that coordinate. Further travel can generate more locations, so no finite global weekly item budget or maximum travel/farming rate is claimed. Shared supply is not multiplied per viewer. Additional accounts can receive their own existing one-time personal starter kit but cannot reroll shared containers; no premium multiplier or separate jobs/contract income is stacked with the same assumed expedition hours.

## Starter stock and one-time migration

The **two physical shared starter barrels** use `supplychest` once each. For the pair, expected native guns and loaded rounds are **0**; expected plain nuggets are **7**, actual iron ingots **2.916667**, edible item units **8.967742**, and vaccines **0.4375**. This is the source-table expectation, not observed seeded contents or two new barrels per account/day. The unchanged personal starter kit (source-equivalent axe wear 42, bread 9, vaccines 6, iron boots and native mask/binocular substitutes) is issued once per expedition profile; reentry, death and migration do not award it again.

Existing generated **sourceV2** containers keep their items and old claim keys. A container without the new `source-loot-v1` claim may receive **one additional source batch once**, even if the old generic batch was already issued. The additional expectation is the corresponding after-table row above. The old contents may be intact, partly consumed or empty; no baseline stock is presumed to remain. If an entire old batch happens to be intact, the combined accounting is old vector plus source vector, including old loaded rounds; those separate upper-accounting vectors are recorded in JSON for table-based old LOOT containers only. Procedure-crate historical grants are not guessed.

Migration does not form a daily or weekly entitlement and is excluded from the recurring sensitivity tables. Its total is `sum(eligible existing physical containers × their source-table EV)`; the existing cohort by table is unknown, so a numerical network total would be invented. Freshly generated containers never receive an old generic batch first. A full container fails admission before consuming the new claim; freeing capacity retries the same deterministic plan. Concurrent/double-chest halves are locked together. The persisted claim prevents a duplicate batch after reload/replay; a crash between durable claim and world materialization may instead lose that batch. This conservative failure mode is separate from duplication and still needs native runtime observation.

## Currency boundary and context

The expedition journal isolates inventory, armor, offhand and native XP; exit restores the origin state while expedition gear remains saved separately. Ender chest and external shop/contract commands are blocked. ArcEvents calls no Vault, token, shop or contract payout API. Its owned-actor lethal-damage branch cancels/removes the actor before native death reward callbacks. None of these source facts proves every other plugin's mining, advancement, damage or proxy hook is excluded: indirect external reward/export behavior remains an integration uncertainty. XP collected inside the expedition remains XP in its isolated profile, not a Vault/token payout.

The canonical `ruscrafting-ops/docs/knowledge/economy-inventory.md` was consulted for currency units, current income sources, concentration and the owner’s **planned EconomyShopGUI SELL → contracts** transition. Current SELL and dependent channels are not assumed disabled or changed by this task; there is no hypothetical SELL valuation of expedition loot or double-counted job + contract + loot chain.

The parent’s fresh read-only 168-hour ledger observation covers **2026-11-07 21:43:13.086 UTC → 2026-11-14 21:43:13.091 UTC** (`since=1794087793086`, `generatedAt=1794692593091`), **4,989 records / 45,926 operations / 18 players**. Reported amounts remain in their own currency:

| Currency | Reported mint | Reported burn | Reported net |
|---|---:|---:|---:|
| Vault | 439,469.7090980104 | 3,806,196.669999985 | −3,366,726.9609019747 |
| tokens | 61 | 3 | 58 |

These are reported ledger aggregates, not ArcEvents attribution; the tiny final-digit residual between the reported Vault net and recomputing the two reported floating aggregates is not a new flow. Jobs Vault mint is **275,114.1652759985**, approximately **65%** concentrated in one player, so it does not establish ordinary player income. There are **37 unknown operations**; the raw mixed-unit absolute amount `222893` is not a valid currency total. The available currency-specific detail is 36 casino operations with Vault net **46,090**, plus token burn **3** from CrateKeyShop. Five-minute activity buckets are a proxy, not playtime. The observation was reused rather than queried again; it supplies neither per-mode loot telemetry nor evidence about the proposed version.

## Assessment and outstanding observation

The source model supports the change within its stated isolation boundary: guns move from generic barrels to actual armory tables, ammunition concentrates in real ammo tables, default full loaded magazines disappear, and ordinary gun/iron/food/vaccine expectations decline under the same modeled opening mix. Exact source fidelity does not promise equal nutrition, equal combat power or an identical progression speed; native substitutes and the actual route/table mix affect those outcomes. Migration deliberately adds one finite batch to an unknown old cohort, rather than refilling containers continuously. No direct Vault/token emission or sink is introduced.

The focused economy check is `python3 -B <ruscrafting-ops>/.agents/skills/balance-ruscrafting-economy/scripts/evaluate.py docs/economy/apocalypse-source-loot-20261115.json`; it completed successfully. A separate exact-fraction comparison checks the three scenario vectors against the supplied oracle, with only the documented legacy loaded-magazine correction. No code/build/runtime behavior is established by this documentation check.

After delivery, the parent should keep these observations separate: active HOST version/config; a fresh source container; one old generic container migrating once without replacing retained items; a full container retry; double-chest/reload/crash replay; origin inventory/armor/offhand/XP restoration; and per-currency ledger checks for other-plugin hooks/export. Source checks, publication, delivery, activation and native/client observations are independent evidence. No recurring monitor or scheduled follow-up is created by this assessment.
