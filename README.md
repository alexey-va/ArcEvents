# ArcEvents

ArcEvents is the network event engine for RusCrafting with four modes:

- **TTT**: hidden traitors, detectives, evidence, role shops and living/spectator chat.
- **GunGame** (`gungame`): each elimination advances through the twelve existing
  firearms; a final knife hit wins. Players respawn after three seconds with
  two seconds of protection. The six-minute limit ranks progress, then kills.
- **Disasters** (`disasters`): six survival waves alternate meteors, lightning
  and low fog. Survivors earn one point per wave; everyone returns for the next
  wave. The dedicated generated arena provides roofs and climbable terraces.
- **Islands of the Deep** (`fishing`): a solo fishing expedition across a camp,
  reef ruins and an underwater shrine. Reel in dangerous creatures, defeat them,
  unlock the next island and defeat the final boss. The native menu offers
  **Start solo**; `/arcevents fishing` starts the same personal journey.

Player-state handling is selected per host with `player-state.mode`. `PRESERVE`
is the default: all modes use durable player-state escrow and origin-server
return routes. `DISPOSABLE` is for hosts whose inventory is independent of the
origin backend: ArcEvents skips snapshots and restores, clears event loadout and
temporary gameplay state on exit, and keeps a local participant's pre-event
location when it can return on the same backend. Configure this only on a
`HOST`; changing the mode requires a restart.

Public modes use a common FIFO queue; its creator selects the mode at start.
Fishing reserves only its authenticated requester's row, even when someone else
is first in the public queue. It never recruits other players. The host still
owns one active match at a time; the solo menu reports a busy host before entry.
GunGame and Disasters require three
players by default (configurable under `arcade`), while TTT keeps its own rules.
Arcade wins contribute to shared match/win statistics without changing TTT karma
or role-specific wins. Old-round projectiles cannot affect a later match.

Fishing owns a generated `fishing-v1` world, configured through
`arcade.fishing.arena.world`. It is excluded from public combat-map rotation.
Its temporary rod, weapons, creatures and hooks leave no permanent item or XP
reward; original player state is restored on success, defeat, evacuation and
recovery in `PRESERVE` mode. `DISPOSABLE` clears the event state instead of
restoring the original inventory. Gameplay tuning is under `arcade.fishing`:
reload rules while idle; changing the generated world's identity requires a
restart. No backend name is
embedded in the fishing implementation.

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
- A relay never clears an inventory. In `PRESERVE` mode the host writes one
  atomic recovery batch for the entire match before the first gameplay mutation,
  verifies every restored surface, saves player data, and only then acknowledges
  the snapshot.
- A host configured as `player-state.mode: DISPOSABLE` skips snapshot capture
  and restoration entirely. Existing recovery files are retained but ignored
  while this mode is active; review those records before switching back to
  `PRESERVE`.
- After confirmed recovery, players are returned through the proxy to the
  backend from which they joined the event.

Player routing is a durable Redis state machine: `QUEUED` → `RESERVED` →
`ARRIVED` → `MATCHED` → `RETURN_PENDING`. `ARRIVED`, `MATCHED`, and
`RETURN_PENDING` never expire. The origin backend is retained after escrow
capture and is deleted only when that backend observes the returning player.
Reservation cancellation moves each selected route to `RETURN_PENDING` with
bounded CAS retries, so host restarts, duplicate join events, and an interrupted
proxy transfer remain recoverable.

From durable arrival capture through confirmed restoration, Paper chat viewers,
Bukkit broadcasts and PacketEvents filter outside chat and action bars for event
participants. ArcEvents sends its own messages through the explicit silent packet
transport. `chat.packet-isolation.enabled` defaults to `true`; PacketEvents 2.12.1
is required alongside ARC. Titles, boss bars and disconnect reasons are unaffected.

HOST nodes renew a proxy chat lease every five seconds on `arc:events_chat`.
ProxyARC accepts only the current backend connection and its own player UUID;
`plugins/proxyarc/modules/event-chat.yml` controls `enabled` and `allowed-servers`.
The shipped list contains `parkour`; add any other event backends there. The
allowlist is hot-reloadable with `/proxyarc reload`. A lease expires after 15
seconds without renewal and is cleared on leave, server transition, disconnect,
shutdown, or removal from the allowlist. ProxyARC filters its own delivery paths;
independent Velocity plugins need their own integration.

Reservation routes cannot release players whose durable recovery is still pending.
Cancelled arrivals remain protected and retry restoration before returning home.
Configured enabled arena bounds remain protected between rounds.
See [the 2026-09-22 review](docs/review-20260922.md) for coverage and remaining limits.

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
transitions. The arcade modes use `ArcadeMatchRuntime` for their rules and `ArcadeSession`
for Paper presentation, sharing escrow, routing, firearms and the arena lease.

Mode-specific world artifacts are lifecycle owners as well. In particular,
`TttBodyRegistry` owns corpse entities, evidence records, despawn tasks,
diagnostics and cleanup. Firearms, smoke, loot presentation and HUD already
have equivalent focused owners. Session tasks are separate from the permanent
one-second service tick, so ending one mode cannot accidentally cancel the
plugin heartbeat or leave a previous round's work alive.

Cross-server movement is fail-closed. A `ROUTE_PLAYER` message is acted on only
when Redis still contains the exact `RESERVED` tuple for player, match and
destination and the reservation has not expired. A return message likewise
requires the exact `RETURN_PENDING` tuple and recorded origin. Host heartbeats advertise supported modes. Start requests and reservations
carry the selected mode, while legacy messages default to TTT. A relay refuses
to send a new mode to a host that has not advertised it. Deploy the host and
relays together before offering the arcade modes to a mixed-version network.

## Commands

- `/arcevents` — player hub.
- `/arcevents join`, `/arcevents leave`, `/arcevents status`, `/arcevents shop`.
- `/arcevents start [ttt|gungame|disasters]` — start the common queue as its creator.
- `/arcevents team <message>` — private traitor/detective team chat in a match.
- `/arcevents admin` — operator GUI. `status|player|network|arenas|recovery` are
  readable diagnostics; `arena <id|auto>`, `start [ttt|gungame|disasters] [id]`, `stop`, `reload`, and
  `recover` operate the map pool, queue, round, config, and escrow recovery.
- `/arcevents reload` — direct `arcevents.admin` shortcut. Locale text, menu/HUD
  presentation, nameplates, loot displays, smoke, active item visuals and safe
  world-distance limits update without stopping a live round. Match rules and
  timing are snapshotted, so a running round remains internally consistent and
  the new values apply to the next round. Weapon mechanics require an idle
  event; imported-world sanitation, network, arena/node identity and packet
  interception require restart. Rejected reloads keep the last known-good
  runtime configuration.
- `/arcevents admin weapons add|remove|show` — while standing inside an idle map,
  persist an exact mandatory weapon point, remove the nearest point within
  three blocks, or preview all mandatory points with client-only particles.
- `/arcevents qa status|player|network|arenas|recovery` — stable read-only output with the
  `ARCEVENTS_QA` prefix.
- `/arcevents debug help|status|player|network|arenas|recovery|bodies` — lab snapshots.
- `/arcevents debug start` reserves the real distributed queue; `bootstrap
  [ttt|gungame|disasters] [arena|auto] [players...]` starts a local roster from online players for isolated tests;
  `advance`, `end <innocents|traitors>`, `timer <seconds>`, and `cleanup` drive
  round lifecycle cases.
- `/arcevents debug credit|role|health|kill|revive` changes one participant;
  `weapon|ammo|item` supplies match-tagged equipment; `loot
  status|respawn|clear` controls map pickups.
- `/arcevents debug discover|dna|call` drives body-evidence scenarios; `menu
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
colors remain locale-owned under `nameplate.lines`, so one `/arcevents reload`
updates geometry and wording together without restarting Paper.

## Firearms and loot

TTT's firearm catalog includes the flintlock pistol, revolver, hand
cannon, double-barrel shotgun, FN Five-seveN, G36, AEK-971, RPL-20, Vepr-12,
M1 Garand, VSS Vintorez, and McMillan. The server remains authoritative for
ammo, cooldowns, ray hits, headshots, damage, and match ownership. The parkour
runtime maps those contracts to stable custom-model-data IDs from the
`voxelspawns_megaflintlocks` and `gold_guns` ItemsAdder namespaces; bundled
defaults remain dependency-free vanilla items.

All firearms fire with LMB, RMB toggles the raised aiming pose, and F reloads.
A private magazine hologram displays one vertical cartridge mark per round,
the exact count, reserve and these controls. It follows the player through
Core's native passenger attachment with a camera-facing local transform,
like the staff HUD; movement is not reproduced with world teleports. Empty
and reloading states are explicit, and Creative shows infinity instead of a
consumable magazine. It is removed on weapon/state exits and runtime cleanup.
Pistols, the double barrel, G36 and M1 Garand use iron sights (1.15×);
AEK-971, RPL-20 and Vepr-12 use physical reflex sights (1.35×); VSS Vintorez
(3×) and McMillan (5×) use hollow 3D optics. The world stays visible through
the optic. First-person sights require the current server resource pack and
Minecraft 1.21.4+ item models; the supported server/client target is 1.21.11.
Zoom follows the client's FOV Effects setting.

`TttScopes` owns transient client-only abilities and held-item model packets.
It does not write real equipment or alter damage/spread. Models use
`arc:event_sights/<firearm>_{hip,aim}`. A temporary `_equip_hip` or `_equip_aim`
alias uses the native client's render-interpolated incoming equip raise
instead of four server-tick model steps. The outgoing pose remains fixed.
After six ticks the same geometry settles on `_hip` or `_aim`, with both
replacement animation and equip displacement disabled so ammunition/lore
updates or attacks cannot replay the downward dip. Each toggle sends two item packets. The physical
optic remains attached in both poses.
Nearby observers receive a cosmetic charged-crossbow holding pose with the
original weapon model; the real inventory remains unchanged. GUI views retain
the vendor model. The shooter's own F5 arm pose remains unchanged because
replacing their local item type would also change client input behavior.
Zoom packets preserve the current authoritative flight flags
and speeds. Match aiming uses the same living-participant, weapon ownership
and selected fishing-loadout checks as firing.

Publish the pack's new `_equip_*` definitions before activating 0.5.34. Existing
item definitions remain unchanged, so the new pack remains compatible with
0.5.33 during the transition. Native motion and observer alignment still need
an actual Minecraft client observation.

The optional VSS laser is controlled by `weapons.scope-laser.enabled` in
`plugins/ArcEvents/config.yml` (default `false`). Set it to `true` and use the
normal `/arcevents reload` path to enable it. Both audiences use native
client-only passengers attached to the player, so movement follows the client
carrier without independently teleported world anchors. The shooter's own
first-person beam uses native camera billboarding: its direction follows the
current local camera yaw and pitch every rendered frame. Observers use a
separate fixed-orientation beam driven by the reported head direction; their
camera never steers somebody else's laser.

Ray length/contact distance remains server-resolved every tick and clipped
before unloaded chunks. This does not make new wall contact instantaneous.
The camera-space eye/passenger offset is compensated from the latest server
pose; rapid pose/angle changes can briefly shift that small offset. Front-facing
third-person camera mode is not a first-person sight and has different camera
orientation. Native player attachment and billboarding are source-verified;
client smoothness and these view/pose transitions still need native acceptance.

Shots use shared swept-segment bullet flight, gravity, walls/entity collision
and a loaded-chunk boundary. Automatic trigger groups release rounds on
successive ticks; shotgun pellets share one volley. Short moving brass tracers,
compact muzzle flash/smoke, material contact particles and weapon-specific
sounds accompany actual shots. Relative camera kick and damped recovery use
Minecraft 1.21.9+ rotation packets without restoring an old mouse direction.
Older clients omit camera recoil. Native armour and hurt immunity remain.

Ordinary-world guns use LMB to fire, RMB to aim and F to reload. A new
magazine starts empty; one ordinary iron nugget loads one round. Event ammo
and other custom nuggets do not qualify. Real gun items use Minecraft's native
maximum stack size of one. Previously issued stacks are split into available
storage slots, preserving each item's metadata and magazine. If inventory is
full, remaining legacy guns stay in their original stack until space is freed;
the migration never drops overflow or deletes an item. Loaded rounds persist
on the same vendor item. A cancelled reload
consumes nothing. Creative fires without ammunition or reloading, including an
empty gun; it neither consumes reserve items nor adds free persisted rounds.
Returning to Survival or Adventure restores the unchanged magazine rules.
In Creative, F may play a cosmetic reload; LMB interrupts it immediately and
fires without spending ammunition. It does not impose a reload requirement.

Fire mode is fixed per weapon. The current catalog fires three-round groups
for G36 and AEK-971, four-round groups for RPL-20, and one round per trigger
for the other weapons (shotgun pellets share that one round). These are finite
groups per LMB press. Continuous automatic fire while holding LMB is not
implemented: the vanilla 1.21.11 client sends no air-attack release/held state,
so a server timer cannot safely infer that the trigger remains pressed.

Minecraft 1.21.11 inventory presentation disables the native first-person
attack swing for recognized guns with `minecraft:swing_animation` type `none`.
Ordinary projection preserves the authoritative item, vendor identity and
magazine data. Creative inventory copies may retain the cosmetic `none`
component, without adding ammunition or changing the vendor model. The client's
special crossbow renderer ignores this type: the four vendor guns backed by a
crossbow retain their native swing. No crossbow material conversion or client
mod is included. Actual first-person playback still needs native acceptance.

The attack-driven equip-height dip is a separate native transform. Since
0.5.38, stable sight/reload definitions and twelve private `_raw` aliases use
`swap_animation_scale: 0`; deliberate `_equip_*` aliases retain scale one.
Raw aliases preserve the vendor model tree, charged/pull variants and GUI
properties. They are projected only for recognized packed guns with the
default item model; portable vanilla fallback and explicit custom item models
are retained. Publish these pack assets before activating 0.5.38. This change
does not suppress the separate crossbow whack branch or camera recoil.

Each firearm has its own first-person reload sequence. Articulated native item
models move the appropriate magazine, cylinder, barrel hinge, bolt or feed lid;
40 pose samples are resampled to the existing authoritative reload duration.
Mechanical cues follow those beats. Inventory, ammunition and reload acceptance
remain with the event or ordinary-world owner. Cancelling or replacing the held
gun invalidates its presentation, and completion restores the current item.
Publish the additive `arc:event_sights/*_reload_*` pack assets before activating
this version; the generator and validation live in the ops sight asset recipe.

Muzzle positions use the actual catalog barrel faces and native hip/aim item
transforms, with separate left-hand and scoped poses. Flash, tracer birth and
the simulated bullet share one safe muzzle origin. The projectile converges
on the camera ray, then retains configured spread and gravity. Tracer display
roots stay unrotated so their world-direction transform is applied once.
Calibration assumes the native settled standing pose and default 70-degree
world FOV, correcting the known server zoom. Client FOV settings, bobbing and
view/zoom transitions remain native-client acceptance limits.

Outside events, native `damage(amount, shooter)` keeps the real player as the
source so WorldGuard, Lands, JustTeams and other protection listeners may cancel
the hit. World PvP-off and event boundaries are checked separately. This path
never clears a cancelled damage event or breaks blocks. See the
[ammunition assessment](docs/economy/world-firearms-20261101.md) and the
[Creative exception](docs/economy/creative-firearms-20261102.md).

For aiming outside an event, use an event `HOST` or
`RELAY` node outside protected event areas, obtain one of the 12 mapped vendor guns through
the ordinary `/arc items` catalog, hold it in the main hand, switch to first person and click
RMB. A second click lowers it. This requires `arcevents.scope-preview` (default
`true`, with explicit permission denials still respected) and an exact supported ItemsAdder identity from `gold_guns` or
`voxelspawns_megaflintlocks`; renaming an unrelated item does not qualify.
When ItemsAdder is not enabled, the same 12 vendor items are recognized from
their saved `minecraft:custom_data` compound `itemsadder.{namespace,id}` through
PacketEvents. This does not require installing ItemsAdder on that node; obtain
the vendor item on a node that provides its catalog.
The preview supports Survival, Adventure and Creative on both node roles and
supports ordinary-world shooting as described above. A RELAY
retains its routing role and cannot create a local match.

Changing slot/weapon, reloading, observed inventory opens/actions/closes,
teleporting, elimination, quitting/leaving, recovery, round end and plugin
shutdown clear the transient state. The service tick rechecks authorization.
Match aiming is limited to Survival/Adventure; spectator mode is not a preview
mode. Opening the ordinary personal inventory with E is client-local in vanilla
and cannot be detected until an inventory action/close reaches the server.
No sight state enters player escrow or persists into another match. `qa player`
reports the active `scope` level (0, 1.15, 1.35, 3 or 5). Native held-item updates
are copied, preserving the latest ammo, PDC, count and other components; invalidating
cleanup restores the current real item. Source-rendered pose checks, on-disk
resource-pack delivery and native-client appearance/control feel are separate
acceptance evidence.

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
./gradlew shadowJar
```

For a focused change, run only the relevant unit test, for example
`./gradlew test --tests '*TttCitadelLootTest' shadowJar`. Full verification is opt-in:
`RUSCRAFTING_OPS_ROOT=/absolute/path/to/ruscrafting-ops ./gradlew clean check shadowJar`.
That lane includes the disposable Redis integration suite and is normally
owned by GitHub Actions.

The Plugwright suite in `src/test/e2e` runs ArcEvents on Paper 1.21.11 with a
disposable Redis instance. Its relay fixture has no host arena, so the
acceptance journey verifies the real player command route rejects queue
admission and keeps status and leave stable. The hosted suite in
`src/test/e2e-host` runs a real Paper host and disposable Redis through the
GunGame firearm-to-knife win, seeded exact restoration and disconnect recovery,
plus a real TTT role allocation, traitor-blade eliminations, winner report and
exact restoration. The TTT case grants a tagged traitor blade through the
permission-gated debug setup to make the combat path deterministic; it checks
the allocated role through that same lab inspection command, so it does not
itself establish that role secrecy is hidden from an administrator. Run the
relay suite locally with the shared Paper runner from the workspace:
`python3 /tmp/arc-plugwright-run.py ./gradlew plugwrightTest`; set
`ARC_EVENTS_E2E_HOST=1` for the hosted profile.

The three production locale mirrors are governed by the durable `arcevents`
translation profile:

```bash
../ruscrafting-ops/scripts/mc translate arcevents validate
../ruscrafting-ops/scripts/mc translate arcevents render-check
```

Native menus use ArcCore 2.7.69 shared history and the server's Escape preference.
Direct `/arcevents` entry starts a new flow. Informational and loading screens
use one muted footer; the TTT loading result replaces its current visit and
late replies cannot reopen a dismissed menu. Unsupported clients and tactical
inventory screens retain the inventory frontend.


### Optional ARC telemetry and world loading

ArcEvents must load before My_Worlds so persisted arena worlds can resolve their
generators. ARC loads after My_Worlds, so declaring ARC in ArcEvents softdepend
would create a Paper load cycle. Event completion uses ARC's enabled plugin
classloader at call time instead; absence or telemetry errors remain harmless.
Other dependencies and early generator registration are unchanged.
