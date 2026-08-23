# ArcEvents

ArcEvents is the network event engine for RusCrafting. The first mode is a
Minecraft-native interpretation of Trouble in Terrorist Town: hidden traitors,
public detectives, evidence, role shops, a timed round, spectators, persistent
statistics, and crash-safe player-state restoration.

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

The initial production arena remains disabled until its bounds, lobby,
spectator point, and at least sixteen spawn points are surveyed and committed.
An unavailable arena is visible in the menu and QA status; it cannot accept a
reservation accidentally. Runtime readiness also requires the world and
WorldGuard to allow PvP at every configured player-facing point.

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
