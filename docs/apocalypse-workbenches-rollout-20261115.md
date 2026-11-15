# Apocalypse workbenches rollout — 2026-11-15

## Published source and immutable artifacts

- Feature source: `f24cd4fd03778d8d1dba53ad053f699b10da924c`.
- Entity-order-independent mining assertion: `95acf1da47e48d15980ad5d676d417a8f6b0cd0f`.
- Snow-aware bridge migration and native-client fixture corrections:
  `bdb4396770dff7b36da8937f626a2cc76fa26a4b` (ArcEvents 0.6.6).
- ArcEvents 0.6.5 SHA-256:
  `2af02b2ccbedbd6a3d8a02948b01d1402d4a8d38addc86eb4dee1255e6b22741`,
  11,645,379 bytes. Activated on Parkour at 23:16:05 MSK.
- ArcEvents 0.6.6 SHA-256:
  `395146b25ca51d9081bb4eed95da6af11fce7df3f9c5be20facabe9d4fd14a02`,
  11,646,444 bytes. Parkour delivery manifest
  `jar-20261115T204708Z-88776`, handoff `deploy-20261115T204707Z-88753`.
  The supported immediate Parkour restart finished Paper startup at
  23:49:10 MSK. ArcEvents reported HOST readiness, arena ready and Redis
  connected at 23:49:40 MSK. Remote file hashing matched the immutable candidate.

The runtime target is Parkour on Gercena. Spawn remains the compatible 0.6.4
relay; it was not restarted for this rollout. Survival was not changed. The
generic delivery summary printed a second `classic` activation row even though
only Parkour was requested; file/runtime readback, rather than that extra row,
defines the delivery evidence. The world and expedition records were preserved.
Spawn disk readback at 23:49:44 still found only `ArcEvents-0.6.4-d25148ca.jar`,
SHA-256 `d25148ca66e67a0615aac93b6269285f60dca147cbc67fdd602458d9f939d6aa`.

## Public-entry player acceptance

The ordinary account `CodexQA_729` entered through the Spawn menu using protocol
1.21.11 and the announced server pack. The downloaded archive was 20,476,006
bytes, SHA-1 `f8293489eef5e3167f7fc9da4e2fa947b49a3b5e`, SHA-256
`e385fd5fb5785d5e682787d27c2dfa6377c1892d4730d7df0f076ad855bf52b4`.
Headed viewer captures rendered actual received world/entity/particle packets
with that archive; these are separate from the offline source-model gallery.

Confirmed on the active 0.6.5 host:

- Ordinary starter table at `-1 34 37` opened the T1 recipe. Exactly one copper
  block and one crafting table were consumed. Those two test ingredients were
  explicitly granted inside the isolated expedition; this does not establish
  ordinary acquisition of either ingredient.
- The crafted T1 was installed at `-1 34 36`. The client received 21 BlockDisplay
  parts, rendered the telegraph, and opened its real weapon/T2 catalog. The
  useful common station remains in the bunker.
- Both unopened starter barrels emitted personal amber particles. Actual
  opening of A stopped its particles while B kept emitting. Opening B advanced
  the HUD to the exit; climbing the actual source ladder advanced it to the
  street at `-100 66 6`, 103 blocks west.
- Four source floor-ivy positions held supported moss carpet. Outdoor ash,
  smoke, the bridge and the waterfront were present in received live packets.
- Normal leave restored the original Spawn world, position, mode, HP 20,
  food 17 and all occupied slots/counts: paper x1, iron ingot x6, paper x1.
  The account quit normally at 23:36:38 MSK; fresh complete proxy-wide typed
  readback at 23:37:26 contained only `GrocerMC`.

The long first capture allowed native enemies to accumulate. The actor died
to zombies and a drowned, so that attempt did not prove bridge-top traversal
or city-container contents. Read-only saved-region inspection then established
the actual approach defect: chunk `(-1,0)` had status 3 (skipped), while the next
span had status 2 (complete). Its 162 planned cells differed only through
single-layer snow and snowy grass. The skipped entrance left air where the
first deck should have been. Version 0.6.6 retries precisely that weather case;
foreign blocks and stacked snow still reject the complete lot.

After 0.6.6 activation, a read-only native console check at 23:52:35 MSK passed
for the repaired tread at `-7 71 6`, `-12 71 6` and `-15 70 6`. The ordinary
retest encountered the persisted enemy crowd on entry. A single QA-only
Resistance effect was applied for at most 120 seconds at 23:52:43 so route
geometry could be observed without changing game mode, flight or permissions.
This protected observation cannot establish ordinary combat difficulty.
The actor climbed the real ladder, crossed the repaired bridge and reached
the native gray-concrete street at `-98.5 66 6.5` at 23:54:27. Its client scan
confirmed all six tread cells from X -12 through -7 at Y 71, Z 6. Resistance
expired before the explicit clear at 23:54:52 (`Target doesn't have the
requested effect`). Normal leave at 23:55:22 again restored the exact origin
state; the complete typed proxy roster at 23:57:11, independently refreshed
at 23:57:42, contained only `GrocerMC`.

## Verification

The focused real-Paper workbench, tungsten blast furnace and refinery flows
passed; exact costs, source identities, fuel remainder, slag, display lifecycle
and native station break/replacement are described in the
[feature evidence](apocalypse-workbenches-20261115.md).

All three focused migration tests passed after fixing the test platform's
missing snow-layer registration with a narrow `Snow` double. Production JAR
ownership and ZIP integrity passed. The earlier full run
[`43757572817`](https://github.com/alexey-va/ArcEvents/actions/runs/43757572817)
passed unit/package, storage and all hosted GunGame/TTT/Zombies assertions. Its
two Apocalypse failures were stale test oracles: the old route-message shape
and component-blind client stack merging. The corrected crate test checks
identity, count and the full slot payload through two fresh native reopens.

The full 0.6.6 run is
[`43759948668`](https://github.com/alexey-va/ArcEvents/actions/runs/43759948668).
Unit/package and storage passed; 14 of 15 hosted cases passed. The remaining
crate test read Mineflayer's stale window 0 while a container was open. Its
quantity oracle was changed to the synced current window's player slots;
the identity and two complete native reopen comparisons remain required.
The fixture pins ARC 1.4.527 and ArcCore 0.1.28; production runs ARC 1.4.539 and
ArcCore 0.1.29. Production-native observations and fixture results remain
separate evidence. Administrator flight was checked in focused source tests,
not by granting the ordinary live account administrator permissions.

Distinct source guns continue to share the existing native firing profiles.
The compact refinery preserves inputs, fuel and byproducts but uses immediate
menu processing instead of the original timed multiblock. No claim of complete
TaCZ/Create or whole-modpack equivalence is made.

## Structure and horde follow-up — 2026-11-16

Published source `46326b85523c37b6970ac410c17fa15b295c5b3e` is ArcEvents 0.6.7.
Its immutable JAR is 11,687,615 bytes, SHA-256
`151799d7dfa3a85a177ccb380c64bee3a143206a4c95a907d8ed9354071d2558`.
Parkour delivery manifest: `jar-20261115T213423Z-22584`; handoff:
`deploy-20261115T213422Z-22562`. Remote hashing matched. Paper startup finished
at 00:36:39 MSK; the 0.6.7 HOST reported arena and Redis readiness at 00:37:09.

The owner's flying administrator exposed a global actor-pass impulse: every
second, any grounded zombie next to another zombie or an obstacle received
Y velocity 0.38. That entire generic impulse was removed. Native navigation
retains ordinary pursuit/climbing, and the source leaper keeps its individual
ability. The regression exercises a real actor pass with two touching zombies,
a flying target and a nearer grounded participant; targets change while their
original velocity remains intact.

The map import had copied Lost Cities' bare fence/pane states and ignored its
adaptive `torch:true` metadata. The original generator expects an attachment
and torch-placement pass. The native adaptation now performs it for fences,
bars, panes, stone walls and torches through a buffered `LimitedRegion`
populator. A bounded background/main-thread queue applies source-guarded
repairs to existing chunks after scenery migration. It never loads neighbors
for a repair; missing seams resume when their neighbors load. Prepared plans
are capped at two, with one source read in flight. Wall cap geometry uses
Paper's collision boxes, top-down ordering and the complete bottom-face union.
Moving-piston and shulker-box caps keep their original wall state because
their shape consults a live block entity.

The pre-update production region contained 164 relevant cells in building3:
91 white panes, 70 bars and three wall torches. The old oak-fence pair at
`-339 78 103` and `-339 78 104` also existed with all four connections false.
Native fixture output changed 140 of the building's cells: 82 panes, 55 bars
and all three torches. Seven exact target states matched, including the
torch at `-92 67 24` facing west toward its actual east support, and the other
two facing south. The saved native chunk was still `initialize_light` with
no runtime repair receipt, providing generation evidence separately from
old-world migration.

All 14 focused shape/source/movement/goal/weather assertions passed. Two
MockBukkit gaps were kept in test-only doubles: snow layers and mutable wall
heights. An initial wildcard Kotest descriptor-filter error occurred before
assertions; exact class filters ran correctly. The focused real-Paper armory,
ammunition and finite crate case passed in 55.7 seconds on this exact JAR.
Its native log contained no SourceShapes/Populator/LimitedRegion/async-world
exceptions. Existing shutdown queue/classloader diagnostics remain separate
from that successful gameplay result. Owned local Paper, Redis and forwarding
processes were stopped; ports 25565, 6379 and 16379 were verified free.

Full code CI:
[`43764419610`](https://github.com/alexey-va/ArcEvents/actions/runs/43764419610).
All three jobs passed: unit/package, storage integration and real-Paper E2E.

The ordinary public-proxy account then confirmed the existing production
building's repaired states on the wire: the pane at `-85 66 17` connects west
and east, its neighbor at `-84 66 17` connects west, the torch at `-92 67 24`
faces west toward its real east support, and bars at `-92 65 23` connect on all
four sides. A native viewer frame shows the attached torch, joined side window
and connected pit railing together. This is existing-world repair evidence,
separate from the earlier generated-chunk comparison.

A second short fixture visit confirmed the old oak-fence pair at 00:48:12:
`-339 78 103` has only SOUTH; `-339 78 104` has only NORTH. The standing point
retained its actual cracked-stone-brick floor and clear camera volume.
The visual fixture used guarded technical-account teleports; it does not
claim ordinary walking from the bunker to that distant fence. One bounded
Resistance effect expired before the native clear at 00:45:19, and the final
visit used only one second of saturation to restore the technical expedition's
empty food bar. No administrator permissions, flight or game-mode changes were
granted. Normal leave at 00:48:22 restored the exact original Spawn state;
the account quit at 00:48:30. These observations do not separately establish
combat difficulty or a live flying-target motion distribution; the shared
impulse removal is covered by the actor regression and source inspection.
An independent fresh complete typed proxy roster at 00:49:39 confirmed
`CodexQA_729` was offline.

The bounded production startup/gameplay log review found zero ArcEvents
failure lines. The retained My_Worlds autoload rejection for the old Zombies
template remains separate from the ready canonical worlds, as in earlier
rollouts.
