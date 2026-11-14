# Original Cursed Walking structures — ArcEvents 0.6.1

This replaces the 0.6.0 authored lookalike buildings with data read from the exact
Cursed Walking v3.3 sources. All three archives are pinned by URL/SHA-256 in
`ApocalypseStructureAssets.pinnedSources`; they are cached under
`plugins/ArcEvents/content/apocalypse-original`. The plugin reads only data:
no Forge classes, original scripts or mod runtime are executed. No upstream
archive or structure assets are redistributed in this repository or plugin JAR.

## Content and correspondence

- JCWS file8502762: all131 NBT templates,45 pools,31 structure definitions.
- Lost Cities file7432706 overlaid with Keerdm file7740113:255 original parts,
  51 building definitions and17 multibuildings retained. The active citystyle's
  reachable closure has25 building definitions and126 parts, with no missing
  definitions, undefined palette symbols or incomplete128-slot weighted lists.
- The original starter assembles `starterbunkertop → starterbunkertunnel →
  starterbunkerbigpart → starterbunkercorridorlore → itbeggins`. Its own
  `spawnpoint_entity` yields block(3,35,39) on seed68200133; support and three
  air cells were checked. No authored replacement room or guessed spawn offset
  is inserted. The imported templates contain no forcefield switch/generator;
  the synthetic0.6.0 field/quest-like scene is not inserted into original rooms.
- Jigsaw pieces keep their original block coordinates, dimensions, connectors,
  named targets, rotations and pool composition. Rendering clips each piece to
  the requested chunk. City floors/cellars/roofs/streets/parks use source parts;
  a multibuilding shares its top-left floor/cellar roll and palette.
- Original sign text, decorative skull profiles and banner patterns are applied
  through whitelisted native APIs once per generated chunk. Source commands,
  inventories and loot tables remain inert. Existing finite expedition supplies
  are assigned to real original container positions; both chest halves reserve
  before opening. Original spawner placers bind to the existing native guards.

The preview helper runs the compiled production classes, then a separate
`prismarine-nbt` decoder reads the original archive and compares coordinates.
For the final selected starter/laboratory/reactor cutaway exports, **64,627
vanilla block positions**, including19,683 non-air positions, matched by block
identity after rotation: starter3,924; laboratory28,116; reactor32,587; zero
mismatches. Mod substitutions, jigsaws, spawner placers and source processor
replacements are outside this particular comparison. Directional state rotation
has separate unit coverage; the position comparison does not claim to compare
every block property.

The 25-chunk city preview uses actual world chunks(-30..-26,-2..2), including
source shopping_open00..11 sections, shopping01_in_2/shopping10_in_3, building1/4/5,
park_fountain1, park_plants_pillars and native street source parts.
Reproduction helpers are in `scripts/apocalypse-preview/`.

## Explicit limits and source compatibility

This preserves source building forms and supported vanilla states, not a
bit-identical Forge world seed. The terrain, biome distribution, city placement
and some selection random streams remain the native port. Modded block models,
Create machines and custom decorative entities need native substitutions; they
are not rendered as the original mod models. The full original free-standing
entity population is retained as data but is not instantiated. Marker-derived
guards and the source spawnpoint are handled separately.

Two original `top_crane` layers have255 characters for a declared16×16 slice.
Their existing flat indices are preserved and only the absent final cell is
skipped. Other malformed dimensions fail. The only legacy city block literal,
`red_sandstone@2`, is canonically migrated to `cut_red_sandstone`, following
Minecraft1.20.1's flattening table. Immersive Weathering's charred planks/slab/
stairs use corresponding dark oak forms. An exhaustive palette pass covers
2,649 distinct input states under four rotations; static native registry
validation includes copycat material data.

## World and player safety

New generation owns `arcevents_apocalypse_v2`, marker
`apocalypse-original-v1:68200133`. PrototypeV1 chunks and generation identity are
retained; they are not overwritten. A saved expedition from the previous world
keeps its inventory and moves to the original starter's canonical position.
There is no second starter-kit grant. Origin snapshots, native session markers,
recovery and independent inventory ownership remain in use.

Before enable, saved original worlds resolve a deferred, content-owned generator.
Archive I/O/decoding, source planning and generation run off the gameplay thread.
Native decorative writes use a bounded eight-entry tick queue. The real candidate
is validated with focused tests and the Paper host fixture; source, fixture
acceptance, disk delivery and activation are distinct states.

Supply quantities/chances and direct Vault/token rates are unchanged from0.6.0.
New structure availability is not a measured loot/hour rate. See
[economy calculation](economy/apocalypse-original-structures-20261114.json), its
result alongside, and [the existing boundary assessment](economy/apocalypse-20261114.md).

## Candidate and observed native run

Candidate ArcEvents0.6.1: SHA-256
`9e6eb07a3a8cf8479e7195863a5b043777ea23a7f60e591840a9231099dd2580`.
Native Paper1.21.11 / Java25 / ArcCore0.1.28 fixture, actual3 cases executed,
3 passed, zero failed: original starter/container, exact origin+expedition
items through reconnect/fallback-world/reentry, queued/cooperative hordes, and
source-position proximity guards. Durations29.3s/2.9s/20.0s, total52.1s. Native
QA reported original spawn(3,35,39), zero registered tile errors and two real bunker
actors. The test does not compare rendered sign lines/banner patterns/skull
profiles, or correlate one visible entity packet with a specific guard UUID. Final run log: `/private/tmp/apocalypse-original-native-candidate-20261114.log`.
The older fixture provider still reports its existing generic shutdown/closed
ZIP cleanup diagnostics; these cases do not establish general hotload acceptance.

Focused source/generation/recovery tests passed before native execution. The
first37-case gate had no failures/skips; subsequent changed decoder/palette
specs were rerun successfully. The exhaustive corrected palette scan accepted
all2,181 unique native output states, including163 copycat variants. Three
cutaway binary volumes matched the previous independently checked outputs.
Original material compatibility failures were fixed before this candidate;
no unknown-state fallback silently bypassed them.

Publication and the supported no-restart delivery promote this same immutable
JAR to classic, classic_survival and parkour. The loaded mode is not upgraded by
disk replacement. The subsequent production activation is recorded below;
textured real-client acceptance and bit-identical Forge terrain/placement
remain outside the observed evidence.

## Production activation and menu observation

On 2026-11-14 UTC, the owner requested activation on Spawn and Parkour only.
Both runtimes reached a fresh `Done` and loaded ArcEvents0.6.1. Each active
plugin root contained exactly one ArcEvents descriptor, named
`ArcEvents-0.6.1.jar`, with the candidate SHA-256 above. Survival was not restarted.
The supported shared restart operation was
`b26179cc0531298a18a3f1f8bdeb5bdb633031f0a617b8c1d767aa01f29da2f0`;
it completed with both targets ready. During its countdown, the owner specified
immediate Parkour restarts, and the published CLI helper stopped Parkour
immediately; the shared observer accepted that same new process generation.
Spawn retained its original countdown.

Parkour reported `plugin-ready` at20:52:20 UTC, `mode=HOST`, `redis=true`, and
loaded `arcevents_apocalypse_v2`. Its marker was
`apocalypse-original-v1:68200133`. Spawn reported ready at20:53:58 UTC with
`mode=RELAY`, `player_state=PRESERVE`, `redis=true`. Both live configurations
contained `apocalypse: {slot: 11}` after defaults migration.

The ordinary `/arcevents` command was exercised with the authorized
CodexQA_728 actor on Spawn at20:56:56 UTC and Parkour at20:57:55 UTC. Both native
dialogs titled `Ивенты` contained `Апокалипсис ›`, the source tooltip, and a
bound `action_apocalypse_menu_name` callback. This establishes menu presence
and its registered action, not a live click or transfer into the bunker.
The actor returned to Spawn and disconnected; offline state was confirmed.
The observer's resource-pack request returned HTTP403, so these protocol
observations do not establish textured client appearance. Startup logs also
included a My_Worlds rejection of the older `zombies-zero-v1` generator marker;
the configured V2 Zombies arena and the Apocalypse HOST subsequently became
ready. General startup cleanliness is not claimed.
