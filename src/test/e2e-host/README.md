# ArcEvents host E2E

This suite starts the real Paper plugin as a HOST with the bundled `citadel-v1`
arena. The fixture shortens only Gun Game timers and makes the firearm catalog
one-shot for a bounded real combat run; it does not call debug mutations. The
relay suite remains under `src/test/e2e` and runs separately.

The Zombies cases use the public queue/creator start for one and two ordinary
players. They verify an actual firearm kill and match points, exact inventory
restoration, and that a teammate leaving does not end the remaining solo run.
After restoration, the fixture waits for the host's typed idle/ready heartbeat
before starting another match; the restoration message alone precedes that
network boundary.

For a local run, all Redis clients must reach the same disposable instance.
The host fixture uses port 16379, while ARC and RedisEconomy use 6379:

```sh
docker run --rm -d --name arcevents-e2e-redis \
  -p127.0.0.1:16379:6379 -p127.0.0.1:6379:6379 \
  redis:7.4-alpine redis-server --requirepass e2e-secret
ARC_EVENTS_E2E_HOST=1 TEST_FILES=zombies.spec.js TEST_NAMES=Zombies TEST_TIMEOUT=90000 \
  ./gradlew plugwrightTest -Pe2eArcJar=/absolute/verified/ARC.jar \
  -Pe2eArcCoreJar=/absolute/verified/ArcCore-0.1.28.jar
docker stop arcevents-e2e-redis
```

These loopback-only credentials belong to the zero-balance test fixture.
The build verifies the exact ArcCore 0.1.28 checksum; retain a compatible verified
ARC provider. CI maps its service at 6379 and rewrites the host fixture port to
6379 before the host run, so it does not need the extra local port mapping.
