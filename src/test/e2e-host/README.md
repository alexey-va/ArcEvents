# ArcEvents host E2E

This suite starts the real Paper plugin as a HOST with the bundled `citadel-v1`
arena for the standard combat scenarios. The fixture shortens match timers and
makes the firearm catalog one-shot for a bounded real combat run. Ordinary entry
uses native player actions. A separate administrator account sets up fixtures
and reads permission-gated QA fields. The relay suite under `src/test/e2e`
runs separately.

For the authored combat maps, replace `arena` in the staged isolated HOST
configuration with `arenas.deadwood` and `arenas.riot-in-prison` from the
Parkour profile. The isolated host needs WorldEdit for ArcCore's schematic
service and `enable-command-block=false`. Give `ARC_EVENTS_E2E_AUTHORED_ARENAS`
the `arenas` mapping as JSON and select `TEST_FILES=authored-maps.spec.js`
with `TEST_TIMEOUT=180000` to allow a fresh paste before the first match.
These cases check public queue start, distinct safe spawns, walking out of the
spawn and exact restoration of the roster.

The Fishing case starts a solo expedition from the public menu. It walks to the
quay, casts into the generated water, reels in a native bite, then evacuates and
checks the original inventory slots and location. The Disasters case starts from
the public two-player queue, plays a meteor round, and checks both players after
the result. Run these cases together with `TEST_FILES=fishing-disasters.spec.js`.

The three Zombies cases verify native dialog callbacks for one-click solo and
an open squad queue, isolation of an unrelated queued player, real firearm kills,
exact inventory restoration, and cooperative play after a teammate leaves.
The physical case observes hostile breach, native fence removal, continuous
Shift-and-aim repair, chest lid/item packets, a 650-point roll and one pickup.
After two real kills, its remaining actors are moved out of the dedicated test
world so the ordinary moved-actor cleanup awards no extra kills or points.
After restoration, the fixture waits for the host's typed idle/ready heartbeat
before starting another match; the restoration message alone precedes that
network boundary.

For a local run, all Redis clients must reach the same disposable instance.
The host fixture uses port 16379, while ARC and RedisEconomy use 6379:

```sh
docker run --rm -d --name arcevents-e2e-redis \
  -p127.0.0.1:16379:6379 -p127.0.0.1:6379:6379 \
  redis:7.4-alpine redis-server --requirepass e2e-secret
ARC_EVENTS_E2E_HOST=1 TEST_FILES=zombies.spec.js TEST_NAMES=Zombies TEST_TIMEOUT=180000 \
  ./gradlew plugwrightTest -Pe2eArcJar=/absolute/verified/ARC.jar \
  -Pe2eArcCoreJar=/absolute/verified/ArcCore-0.1.28.jar
docker stop arcevents-e2e-redis
```

These loopback-only credentials belong to the zero-balance test fixture.
The build verifies the exact ArcCore 0.1.28 checksum; retain a compatible verified
ARC provider. CI maps its service at 6379 and rewrites the host fixture port to
6379 before the host run, so it does not need the extra local port mapping.

Paper 1.21.11 length-prefixes the optional custom-click NBT. The installed
protocol schema models this slot differently; the no-input button helper uses
the normal writer's TAG_End representation and asserts the exact native bytes.
It does not patch the protocol library or write raw packets. Lifecycle waits use
typed QA fields and exact state, rather than depending on presentation copy.
