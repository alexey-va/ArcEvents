# ArcEvents

ArcEvents is the network event engine for RusCrafting. The first mode is a
Minecraft-native interpretation of Trouble in Terrorist Town: hidden traitors,
public detectives, evidence, role shops, a timed round, spectators, persistent
statistics, match-scoped living/spectator chat, and crash-safe player-state
restoration. Projectiles are tagged with their match and removed during cleanup,
so an arrow from an old round cannot affect a later one.

## Network shape

- `spawn` and `survival` run in `RELAY` mode: menus, queueing, announcements,
  and proxy transfer only.
- `parkour` runs in `HOST` mode and owns the active match in world `pvp`.
- Redis carries bounded queue, reservation, node-heartbeat, match-summary, and
  statistics records. Active combat remains authoritative on the host.
- A relay never clears an inventory. The host writes one atomic recovery batch
  for the entire match before the first gameplay mutation, verifies every
  restored surface, saves player data, and only then acknowledges the snapshot.
- After confirmed recovery, players are returned through the proxy to the
  backend from which they joined the event.

The initial production arena remains disabled until its world-creation capsule
is explicitly authorized. ArcEvents includes the deterministic `citadel-v1`
template for a dedicated void world: a central three-storey keep, four themed
wings, covered links, ramparts, an undercroft, and sixteen audited spawns across
three elevations. It never adopts or overwrites an unmarked world. An
unavailable arena is visible in the menu and QA status; it cannot accept a
reservation accidentally. Runtime readiness also requires solid footing, two
passable blocks, and WorldGuard/Paper PvP permission at every player-facing
point.

The reviewed production coordinates for the built-in template are:

```yaml
arena:
  enabled: true
  world: arcevents_ttt
  template: citadel-v1
  lobby: '0.5,42,0.5,180,0'
  spectator: '0.5,38,0.5,0,0'
  minimum: '-66.5,4,-66.5'
  maximum: '66.5,48,66.5'
  spawns:
    - '-12.5,16,-12.5,45,0'
    - '12.5,16,-12.5,-45,0'
    - '-12.5,16,12.5,135,0'
    - '12.5,16,12.5,-135,0'
    - '-8.5,16,-39.5,0,0'
    - '8.5,16,-47.5,180,0'
    - '38.5,16,-8.5,90,0'
    - '47.5,16,8.5,-90,0'
    - '-8.5,16,38.5,0,0'
    - '8.5,16,47.5,180,0'
    - '-38.5,16,-8.5,90,0'
    - '-47.5,16,8.5,-90,0'
    - '-12.5,26,-7.5,90,0'
    - '12.5,26,7.5,-90,0'
    - '-42.5,7,0.5,90,0'
    - '42.5,7,0.5,-90,0'
```

The recovery, exact-destination teleport authorization, generic GUI background,
and post-match return contracts intentionally follow the proven ArcDuels
patterns while keeping ArcEvents' event protocol and state machine independent.

## Commands

- `/events` — player hub.
- `/events join`, `/events leave`, `/events status`, `/events shop`.
- `/events team <message>` — private traitor/detective team chat in a match.
- `/events admin` — operator GUI; `/events admin start|stop|reload|recover` for
  console and power-user operation.
- `/events qa status|player|network|recovery` — stable read-only output with the
  `ARCEVENTS_QA` prefix.
- `/events debug start|advance|end|credit` — lab-only mutation surface guarded
  by `arcevents.debug` and `debug.enabled`.

## Build

```bash
../arc-core/gradlew -p . clean check shadowJar
```

The three production locale mirrors are governed by the durable `arcevents`
translation profile:

```bash
../scripts/mc translate arcevents validate
../scripts/mc translate arcevents render-check
```
