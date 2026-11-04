# ArcEvents host E2E

This suite starts the real Paper plugin as a HOST with the bundled `citadel-v1`
arena. The fixture shortens match timers and makes the firearm catalog
one-shot for a bounded real combat run. Ordinary entry uses native player
actions; permission-gated QA reads and scoped administrator setup establish
test preconditions without granting ordinary players that authority. The
relay suite remains under `src/test/e2e` and runs separately.

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
