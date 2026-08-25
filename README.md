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
  match. Production uses a pool of dedicated, ArcEvents-owned worlds.
- Redis carries bounded queue, reservation, node-heartbeat, match-summary, and
  statistics records. Active combat remains authoritative on the host.
- An authorized player may start the queued roster from any backend. A relay
  first ensures that player is queued, sends a target-bound, replay-bounded
  start request to the configured host, and receives a correlated result; the
  host remains the only node that can reserve players or create a match.
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

Production rotates between the deterministic built-in `citadel-v1` and three
reviewed imported worlds: `cs2-inferno-v1`, `cs2-mirage-v1`, and
`cs2-nuke-v1`. One arena is leased when a reservation is accepted and released
only after player restoration finishes. An administrator may choose the next
ready arena once or return to deterministic automatic rotation. Imported
worlds require exact ownership/source markers, no datapacks or symlinks, and a
startup scan that rejects command-block tile entities. Command blocks are also
disabled by world gamerule. Runtime readiness requires solid footing, two
passable blocks, and WorldGuard/Paper PvP permission at every player-facing
point.

The built-in Citadel remains a central three-storey keep, four themed wings,
covered links, ramparts, an undercroft, and sixteen audited spawns across three
elevations. Its profile is now one entry under the arena pool:

```yaml
arenas:
  citadel:
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
- `/events admin` — operator GUI. `status|player|network|arenas|recovery` are
  readable diagnostics; `arena <id|auto>`, `start [id]`, `stop`, `reload`, and
  `recover` operate the map pool, queue, round, config, and escrow recovery.
- `/events qa status|player|network|arenas|recovery` — stable read-only output with the
  `ARCEVENTS_QA` prefix.
- `/events debug help|status|player|network|arenas|recovery|bodies` — lab snapshots.
- `/events debug start` reserves the real distributed queue; `bootstrap
  [arena|auto] [players...]` starts a local roster from online players for isolated tests;
  `advance`, `end <innocents|traitors>`, `timer <seconds>`, and `cleanup` drive
  round lifecycle cases.
- `/events debug credit|role|health|kill|revive` changes one participant;
  `weapon|ammo|item` supplies match-tagged equipment; `loot
  status|respawn|clear` controls map pickups.
- `/events debug discover|dna|call` drives body-evidence scenarios; `menu
  <player> <main|help|admin|arenas|shop|roster|report>` and `close` target either UI
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

## Round presentation

TTT follows three visible stages. During the 30-second preparation, players
are already free to scout the arena, collect or drop map firearms, and open
the briefing book,
while roles remain hidden and all combat is blocked. The role reveal starts a
short countdown, equips only the relevant role shop, and then unlocks the
active round.

Each participant gets a locale-aware sidebar, per-player boss bar, contextual
action bar, phase titles, and restrained transition particles. The previous
scoreboard is restored when the event ends. Map pickups use a hidden
server-authoritative item for collision plus a temporary rotating `ItemDisplay`
and particles for presentation; `ui.loot-displays` can disable only that visual
layer. Smoke grenades are throwable snowball projectiles and create an
eight-second cloud that repeatedly applies blindness and darkness to every
living participant inside it, including the thrower.

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
