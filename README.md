# ArcEvents

ArcEvents is the network event engine for RusCrafting. The first mode is a
Minecraft-native interpretation of Trouble in Terrorist Town: hidden traitors,
public detectives, evidence, role shops, a timed round, spectators, persistent
statistics, match-scoped living/spectator chat, and crash-safe player-state
restoration. Projectiles are tagged with their match and removed during cleanup,
so an arrow from an old round cannot affect a later one.

## Menu configuration

All chest-menu rows, fixed controls and dynamic regions live under
`ui.layouts` in `config.yml`. Runtime code refers only to semantic ids such as
`center`, `arenas`, `offers`, `participants`, `back` and `next`. Reload parses
and validates the complete candidate before activation; missing ids, overlaps,
out-of-range slots and undersized regions reject the whole generation.

`ui.menu-items` configures every system icon and optional `custom-model-data`.
State variants have separate ids, for example ready/unavailable start buttons,
selected/ordinary arenas, available/lost DNA and friendly/lethal/ordinary
combat records. Player-head roles remain heads so their owner skin can be
rendered.

Names and lore are MiniMessage templates in `lang/ru.yml` and `lang/en.yml`.
The locale contract requires identical keys, row counts and placeholder counts,
while runtime values are injected as non-parsing Adventure components. The
main available tag groups are:

| Surface | Value tags |
|---|---|
| event overview and queue | `<queue>`, `<minimum>`, `<arena_state>`, `<queue_state>`, `<selected_arena>` |
| statistics and admin | `<matches>`, `<wins>`, `<kills>`, `<deaths>`, `<karma>`, `<phase>`, `<match>`, `<recovery>`, `<server>`, `<host>`, `<network_state>` |
| arenas and shop | `<arena>`, `<state>`, `<template>`, `<world>`, `<selection>`, `<credits>` |
| roster and bodies | `<player>`, `<role>`, `<status>`, `<seconds>`, `<weapon>`, `<damage>`, `<hit>` |
| report and combat | `<winner>`, `<reason>`, `<time>`, `<events>`, `<kills>`, `<friendly>`, `<sequence>`, `<attacker>`, `<victim>`, `<flags>` |

These value tags can be combined freely with MiniMessage colors, gradients and
decorations; changing the template does not require recompilation.

## Network shape

- `spawn` and `survival` run in `RELAY` mode: menus, queueing, announcements,
  and proxy transfer only.
- `parkour` runs in `HOST` mode and is the only node allowed to own an active
  match. Production uses a pool of dedicated, ArcEvents-owned worlds.
- Redis carries bounded queue, reservation, node-heartbeat, match-summary, and
  statistics records. Active combat remains authoritative on the host.
- The first player to create the queue becomes its durable event creator. Only
  that player (or a global `arcevents.admin`) may choose the next arena and
  start the roster. A relay sends a target-bound, replay-bounded request with
  the creator identity to the configured host; the host validates ownership
  again and remains the only node that can reserve players or create a match.
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

During match-owned phases, Paper chat viewers and Bukkit broadcast recipients
exclude participants; living and spectator channels remain match-scoped.
`chat.packet-isolation.enabled: true` may additionally suppress external player,
disguised, system-chat, and action-bar packets through an installed ProtocolLib
build compatible with the exact server version. This packet boundary is a
soft-dependency and is disabled by default.

Production rotates between three lightweight packaged arenas:
[Japanese Lobby](https://www.planetminecraft.com/project/japanese-lobby-6691829/)
by CedricD0812,
[Edged Mansion](https://www.planetminecraft.com/project/edged-mansion/)
by kostahansen, and
[Practice Map Build](https://www.planetminecraft.com/project/pratice-map-build/)
by Zyumie.
Each is used under CC BY 4.0 with its author and source URL embedded in an
immutable manifest. The exact reviewed artifact is bundled with a SHA-256
checksum; schematic builds are cropped before an entity-free WorldEdit paste.
For the Anvil build, ArcEvents creates a clean Paper `level.dat` and transplants
only the reviewed region file; downloaded world metadata is never packaged or loaded.
All three passed license, archive, command-content, datapack and performance
gates. One arena is leased
when a reservation is accepted and released only after player restoration
finishes. The queue creator or a global administrator may choose the next ready
arena once or return to deterministic automatic rotation. Imported
worlds require exact ownership/source markers and source checksums, no
datapacks, function files or symlinks, and a startup scan that rejects
command-block tile entities, removes imported
non-player entities, and clears standing and wall banners before a round can
use the world. Command blocks are also
disabled globally in `server.properties` and again by world gamerule. Every participant uses the same configured map
spawn. Dedicated arena worlds cap view distance at six chunks and simulation
distance at four. Runtime readiness requires solid footing, two passable
blocks, and WorldGuard/Paper PvP permission at every player-facing point.

The recovery, exact-destination teleport authorization, generic GUI background,
and post-match return contracts intentionally follow the proven ArcDuels
patterns while keeping ArcEvents' event protocol and state machine independent.

## Game runtime architecture

Paper integration is split from authoritative game state. `ArcEventsService`
is the host adapter for players, inventories, HUD, arenas and network messages;
it does not own TTT match transitions directly. `EventGameRuntime` is the small
mode boundary, and `TttMatchRuntime` owns the current match, exact phase clocks,
recent-attacker attribution, bounded combat history, cancellation and recovery
transitions. A future mode implements its own runtime rather than adding a
second state machine to the Paper service.

Mode-specific world artifacts are lifecycle owners as well. In particular,
`TttBodyRegistry` owns corpse entities, evidence records, despawn tasks,
diagnostics and cleanup. Firearms, smoke, loot presentation and HUD already
have equivalent focused owners. Session tasks are separate from the permanent
one-second service tick, so ending one mode cannot accidentally cancel the
plugin heartbeat or leave a previous round's work alive.

Cross-server movement is fail-closed. A `ROUTE_PLAYER` message is acted on only
when Redis still contains the exact `RESERVED` tuple for player, match and
destination and the reservation has not expired. A return message likewise
requires the exact `RETURN_PENDING` tuple and recorded origin. Adding another
mode still requires a mode-aware queue partition and protocol migration; the
runtime boundary keeps that network migration independent from the new game's
rules and Bukkit presentation.

## Commands

- `/events` — player hub.
- `/events join`, `/events leave`, `/events status`, `/events shop`.
- `/events team <message>` — private traitor/detective team chat in a match.
- `/events admin` — operator GUI. `status|player|network|arenas|recovery` are
  readable diagnostics; `arena <id|auto>`, `start [id]`, `stop`, `reload`, and
  `recover` operate the map pool, queue, round, config, and escrow recovery.
- `/events reload` — direct `arcevents.admin` shortcut. Locale text, menu/HUD
  presentation, nameplates, loot displays, smoke, active item visuals and safe
  world-distance limits update without stopping a live round. Match rules and
  timing are snapshotted, so a running round remains internally consistent and
  the new values apply to the next round. Weapon mechanics require an idle
  event; imported-world sanitation, network, arena/node identity and packet
  interception require restart. Rejected reloads keep the last known-good
  runtime configuration.
- `/events admin weapons add|remove|show` — while standing inside an idle map,
  persist an exact mandatory weapon point, remove the nearest point within
  three blocks, or preview all mandatory points with client-only particles.
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

The participant TextDisplay is configured under `ui.nameplates`: enablement,
refresh period, distance, width, view range, scale, vertical offset, shadow,
ARGB background, visibility protections and both row priorities. Row text and
colors remain locale-owned under `nameplate.lines`, so one `/events reload`
updates geometry and wording together without restarting Paper.

## Firearms and loot

TTT's firearm catalog includes the flintlock pistol, revolver, hand
cannon, double-barrel shotgun, FN Five-seveN, G36, AEK-971, RPL-20, Vepr-12,
M1 Garand, VSS Vintorez, and McMillan. The server remains authoritative for
ammo, cooldowns, ray hits, headshots, damage, and match ownership. The parkour
runtime maps those contracts to stable custom-model-data IDs from the
`voxelspawns_megaflintlocks` and `gold_guns` ItemsAdder namespaces; bundled
defaults remain dependency-free vanilla items.

Large arena layouts guarantee one of every firearm before filling remaining
weapon points from the weighted rarity pool. Ammo remains universal and
match-scoped. `lemon_vfxdrop` model IDs 2–6 render the animated rarity beam
under the rotating weapon display, while a hidden signed item owns pickup
collision. Every display and pickup is removed together on pickup, cleanup,
round end, or plugin shutdown. Cosmetic spawn failures degrade to a visible
pickup and never cancel the gameplay round.

Each imported arena declares `weapon-count`. Mandatory points placed by an
administrator are filled first and count toward that target; deterministic
random points supply only `weapon-count - mandatory points`. The complete
pickup budget stays equal to the reviewed `loot-spawns` catalog, so adding an
exact point does not increase the number of rendered loot entities. Mandatory
points live in the atomic runtime file `data/arena-weapon-points.json`, survive
restarts and configuration deploys, and make an arena unavailable if their
floor later becomes unsafe.

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
scoreboard is restored when the event ends. A hidden server-authoritative item
owns pickup collision while `ui.loot-displays` presents the temporary rotating
weapon model; unsafe loot points move to nearby non-barrier flooring. The
optional rarity beam remains separately configurable. Smoke grenades are
throwable snowball projectiles and create an
eight-second cloud that repeatedly applies blindness and darkness to every
living participant inside it, including the thrower.

## Build

```bash
./gradlew clean check shadowJar
```

The three production locale mirrors are governed by the durable `arcevents`
translation profile:

```bash
../scripts/mc translate arcevents validate
../scripts/mc translate arcevents render-check
```
