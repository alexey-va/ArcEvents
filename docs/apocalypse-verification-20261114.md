# Apocalypse candidate 0.6.0 — verification, 2026-11-14

Candidate: `build/libs/ArcEvents-0.6.0.jar`, 11,126,795 bytes.
SHA-256: `cbce6141e0bb39417deac69a26a154a88e095fd52535a0f542a0b73165c3f019`.
The JAR ownership verifier and complete ZIP integrity check passed. The package
contains no BetterStructures or mod runtime. The source reference and fidelity
limits are in [apocalypse-reference.md](apocalypse-reference.md).

## Executed checks

Focused initial unit gate: 47 cases, zero failures/errors/skips across the
world-layout, survival, bunker, mob-goal, WorldFirearms and asynchronous escrow
specifications. A further23-case survival/AI/escrow gate after the native-session
marker change passed. This includes deterministic geometry/headroom/connectivity,
phase/day boundaries, per-player horde growth and overlap, infection/cures,
JCWS profiles, AI reinstall and native firearm reload.

A full unit sweep executed 497 cases with four existing skips and one failure:
the scope identity test still expected only36 vendor identities. The feature
adds12 native ArcEvents identities. The test now verifies vendor36/native12
separately; its focused five-case rerun passed. The full sweep's other cases
were unchanged and passed; it was not repeated after this test-only correction.
Logs: `/private/tmp/apocalypse-unit-final-20261114.log` and
`/private/tmp/apocalypse-scope-final-20261114.log` on the development host.

The actual Paper 1.21.11-34 / Java25 / ArcCore0.1.28 / ARC1.4.527 fixture ran
three targeted native player journeys, using disposable loopback Redis and
unmodified Mineflayer protocol clients:

1. Origin named diamonds, helmet and offhand shield → ordinary apocalypse entry
   → generated starter bunker → medical barrel with tagged vaccine → withdraw
   → disconnect/rejoin → exact expedition items → disconnect and rewrite the
   disposable native player.dat to the fallback Overworld while retaining the
   session marker/inventory → rejoin at the starter with exact items
   → leave twice → exact origin
   → reenter saved expedition → exact remaining barrel without refill → exit.
   Final candidate rerun passed in22.8s; total selected count1, failures0.
2. A queued player cannot enter or lose items; after leaving the queue, two
   players enter and each receives a day10 personal horde. Some real zombie
   entity packets were observed; this test does not identify an individual
   visible zombie's horde ownership. Both origins restore on exit. Passed4.9s.
3. A generated underground lab at switch(-760,43,387) exposes a real lever and
   forcefield pane. Clicking removes the native pane and commits the switch;
   nearby native guards appear. Origin restores on exit. Passed21.2s.

Native sources: `src/test/e2e-host/apocalypse.spec.js`.
Final inventory log: `/private/tmp/apocalypse-native-session-final-20261114.log`.
Co-op: first combined run in the chat; bunker log:
`/private/tmp/apocalypse-native-inventory-bunker-20261114.log`.
On final shutdown, the world journal persisted seed68200133 and worldTick140,
matching the last native QA state. The fixture also reports its older shared
provider's `Native work queue is closed` cleanup error and ARC's closed ZIP
classloader error during shutdown. These native journeys prove the exercised
play flows, not clean generic provider hotload/shutdown or production health.
The apocalypse generator uses the explicit coordinated-restart barrier.

An initial test compared the order of modern item component arrays. Native
save/login changed their wire order while preserving all values; the corrected
oracle sorts component entries by their keyed type, preserving counts and all
nested contents. A second oracle now distinguishes the tagged vaccine from the
plain honey bottle and compares the complete remaining container. Assertions
were corrected after observing the actual native data, without changing items.

## Spatial evidence and remaining observations

The preview exporter reads actual generated voxel geometry. Offline views were
inspected for starter stairs, city circulation and underground room connectivity.
They are not textured in-client screenshots. Native testing additionally reached
and interacted with the generated starter and underground lab. A real Minecraft
client appearance/play balance review, proxy cross-backend transfer and process
crashes between individual disk writes and restart with connected survivors remain
unobserved. The fallback-world login itself was exercised through native player.dat.

Original geometry, vanilla actor/model substitutes, partial mutant/rage AI and
provisional finite loot are explicit fidelity limits, not exact Terralith,
Lost Cities building library, JCWS131-template or TACZ/Lootr equivalence.

## Delivery boundary

The source is published on origin/main; the verified same JAR is delivered to
classic, classic_survival and parkour through `mc jar --no-restart`. Delivery
receipts and remote hashes belong to the transaction manifest. Disk delivery
does not activate the mode; no production restart or player mutation is part of
this verification record. Before activation, inspect HOST reward integrations
and the [economy assessment](economy/apocalypse-20261114.md).
