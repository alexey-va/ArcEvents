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
- `parkour` runs in `HOST` mode and is the only node allowed to own an active
  match. Production uses the dedicated, ArcEvents-owned `arcevents_ttt` world.
- Redis carries bounded queue, reservation, node-heartbeat, match-summary, and
  statistics records. Active combat remains authoritative on the host.
- A relay never clears an inventory. The host writes one atomic recovery batch
  for the entire match before the first gameplay mutation, verifies every
  restored surface, saves player data, and only then acknowledges the snapshot.
- After confirmed recovery, players are returned through the proxy to the
  backend from which they joined the event.

Player routing is a durable Redis state machine: `QUEUED` → `RESERVED` →
`ARRIVED` → `MATCHED` → `RETURN_PENDING`. `ARRIVED`, `MATCHED`, and
`RETURN_PENDING` never expire. The origin backend is retained after escrow
capture and is deleted only when that backend observes the returning player.
Reservation cancellation moves each selected route to `RETURN_PENDING` with
bounded CAS retries, so host restarts, duplicate join events, and an interrupted
proxy transfer remain recoverable.

The production arena uses the deterministic `citadel-v1` template in a
dedicated void world: a central three-storey keep, four themed
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
- `/events admin` — operator GUI. `status|player|network|recovery` are readable
  diagnostics; `start|stop|reload|recover` operate the queue, round, config, and
  escrow recovery.
- `/events qa status|player|network|recovery` — stable read-only output with the
  `ARCEVENTS_QA` prefix.
- `/events debug help|status|player|network|recovery|bodies` — lab snapshots.
- `/events debug start` reserves the real distributed queue; `bootstrap
  [players...]` starts a local roster from online players for isolated tests;
  `advance`, `end <innocents|traitors>`, `timer <seconds>`, and `cleanup` drive
  round lifecycle cases.
- `/events debug credit|role|health|kill|revive` changes one participant;
  `weapon|ammo|item` supplies match-tagged equipment; `loot
  status|respawn|clear` controls map pickups.
- `/events debug discover|dna|call` drives body-evidence scenarios; `menu
  <player> <main|help|admin|shop|roster|report>` and `close` target either UI
  frontend without manual navigation.

Every debug command requires `arcevents.debug` and `debug.enabled`. Mutations
also require the exact current `server-id` in `debug.allowed-server-ids`; the
bundled and production configs allow only `lab`, while production keeps debug
disabled. Tab completion covers actions, players, roles, teams, equipment,
amounts, and views.

## Interface frontends

Chest inventories remain the complete, stable UI and own the item-dense shop,
roster, body evidence, round report, and combat log. `ui.dialogs-enabled: true`
adds Paper's modern Dialog frontend for the main overview, rules, and operator
screens when the connecting client is Minecraft 1.21.6 or newer. Older clients
and all item-grid views automatically use the chest frontend; both paths call
the same permission and match-state checks. The production mirrors keep the
flag disabled until modern-client acceptance QA is explicitly selected.

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
