# Zombies world interactions, 2026-11-05

The authored v2 arena retains the reactor, cargo cover and industrial copper/deepslate palette. One two-block weapon chest occupies X=-5..-4, Y=65, Z=-22, facing south. Six framed windows are the only mob ingress points: (-25,-15), (25,-15), (-25,5), (25,5), (-7,25), (7,25). Their inward normals are (1,0), (-1,0), (1,0), (-1,0), (0,-1), (0,-1).

## Interaction and ownership

The chest costs the existing 650 match points. A reservation subtracts that amount once and leaves the player's current weapon unchanged. Native `Lidded.open/close` owns the chest lid; one client-only item display cycles prepared candidates at 500,650,800,950,1150,1400,1700,2050,2500,3100,3900ms, settles at 4800 ms and remains available for 15000 ms. A right click on either chest half or the visible weapon enters the same claim. The visible-result aim bound is its measured model AABB expanded0.15 blocks, with a five-block range, loaded-chunk and native solid-occlusion checks. Empty space over an idle chest preserves normal firearm input.

Claim replaces only the existing owned weapon/ammunition slots 0/9/10/11/12; it never drops or overflows an item. Native slot failure restores those slots before refund. Normal expiry, leaving or transfer out of the arena consumes the accepted roll; technical cancellation and shutdown consume the reservation and refund the original debit once. A downed player keeps the result until its ordinary expiry and can collect after revival. No permanent currency, XP, rewards, native mob death or loot source is added.

Each window starts with three broad packet-rendered spruce boards and three connected native spruce fences at Y=65. The fences supply 1.5-block collision; upper shooting lines stay open. One shared 2-second strike clock per window removes one board; the final board removes only the authored fence cells. A queued zombie retains its own window and does not enter normal targeting/unstuck handling until it passes that window. Players repair one board per 1500 ms of continuous sneaking, aim and proximity. Leaving range, looking away, damage or releasing Shift resets the held action. Repair has no point award. A rebuilt collision gate cannot overlap an actor or trap a teammate in the alcove. Match close restores only its owned cells when their current material still matches AIR/SPRUCE_FENCE.

`ZombiesAdventure` owns one 2-tick `LifecycleTaskScope`; it closes the scope before its box, boards and actors. Display count is bounded by 18 boards, four station labels and one rolling weapon. All world work remains on the Paper thread. The generator/template identity is `zombies-zero-v2`; v1 arenas are not rebuilt by a running interaction owner.

## Model and transform evidence

The exact eight mapped models were resolved from the canonical ItemsAdder declarations and the downloaded 1.21.11 pack. Explicit `resource.model_id` allocations were checked against every modern item dispatcher in all five downloaded public pack variants. The tracked allocation cache lacks those fixed IDs, so the analyzer received a **synthetic offline input cache** built only from the verified explicit declarations; the live/cache files were not edited. Its provenance is `/private/tmp/zombies-grounding-ia/provenance.json`.

Runtime entity position is (-4,67,-21.2), context FIXED, entity scale (.6,.6,.6), zero translation, identity left/right rotations, yaw 0. The analyzer separately applies the native ItemDisplay Y180 base after the item context. These are deliberately suspended weapons, not grounded props. The lowest current mesh point is Y=66.545654 and the highest is Y=67.397653. The reports' Y=66.25 reference plane is a clearance check, not a claimed physical contact; the preview uses the **current runtime pose**, never the analyzer's suggested grounding adjustment. Actual client pack delivery and native rendering were not observed by these reports.

| Item | Material/CMD | Resolved model SHA256 | Current Y bounds |
| --- | --- | --- | --- |
| `gold_guns:gold_g36` | PAPER/2100003 | `b6f0de6c96eeeef4a5b631df87bbcdead4953e9aa69a4a77586d222567d381c5` | 66.551330..67.296952 |
| `gold_guns:gold_aek_971` | PAPER/2100001 | `a870d6a41cfdb2f0be6c977ff6e4ed73dff18295b6b7e686da4db24ad9df0a03` | 66.545654..67.285945 |
| `gold_guns:gold_rpl20` | PAPER/2100006 | `1a60f80dba759e3ba27325ab92334a2241702a5fa4052d3c1c20b87a814e99df` | 66.606806..67.242554 |
| `gold_guns:gold_vepr_12` | PAPER/2100007 | `374bf351592659bcdfdf28931a9b012d7dddcc77892dc13ad0da945cafdff7f9` | 66.604693..67.193024 |
| `gold_guns:gold_m1_garand` | PAPER/2100004 | `cb0084aedeab2d05189b0b05dc1b55e97e477bd95ddadc3494879028f7937e26` | 66.681665..67.243977 |
| `gold_guns:gold_vss_vintorez` | PAPER/2100008 | `b0abae6c6c790db06900599fbab96f9d273be95712e6a8322d0f4a4340d4d7d4` | 66.614134..67.367225 |
| `gold_guns:gold_mcmillan_new` | PAPER/2100005 | `46b004acf02a9f9c19164870eef99e28fd58bd00dc499d416ec4ec9197168339` | 66.573413..67.338250 |
| `voxelspawns_megaflintlocks:vs_rifle_double` | CROSSBOW/2100103 | `1dbf61f7b18e84eb618c83d60c7b1d40492bb9ee759691d7873a5a0160017a31` | 67.214706..67.397653 |

## Reproduce and inspect

The focused `ZombiesArenaGeneratorTest` exports actual generator block states, board geometry and interaction timings when `ARC_EVENTS_ZOMBIES_ARENA_PREVIEW` is set. The variable is not a Gradle input, so an explicitly requested fresh export must execute that selected test, rather than reuse an unrelated cached result.

```sh
ARC_EVENTS_ZOMBIES_ARENA_PREVIEW=/private/tmp/zombies-cod-preview/export ./gradlew :test --rerun --tests ru.ruscrafting.events.paper.ZombiesArenaGeneratorTest
python3 -B scripts/render-zombies-interactions.py --export /private/tmp/zombies-cod-preview/export --grounding /private/tmp/zombies-grounding-ia --client-jar /Users/alexey23/RusCrafting/ArcBuilder/build/reports/placement-assist/minecraft-1.21.11-client.jar --ops-root /Users/alexey23/RusCrafting/ruscrafting-ops --output /private/tmp/zombies-cod-preview/render
```

Each grounding report is produced with the installed `itemsadder-item-display-grounding/scripts/analyze_itemsadder_display.py`, the explicit item ID, `--context fixed --scale .6,.6,.6 --surface-y 66.25 --entity-position=-4,67,-21.2`, and the exact pack. The 1.21.11 pack SHA256 is `64bb71ced5645eff1a7902439824a1ef39cd693afad154b96bcb6caa9bbdf874`. Vanilla client SHA1 is `ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd`.

The preview builder reuses the existing atelier renderer and baked vanilla assets. Its bridge converts the analyzer's GL-bottom UVs once to atelier image-top UVs. There are 21 exported states and 31 baked models, with zero detected near-coplanar face overlaps. A representative case and window were inspected before rendering all weapon/state frames. Source-derived PNGs, contact sheets, lossless animated WebP files, the CPU raster source and provenance live under `/private/tmp/zombies-cod-preview/render/`:

- `box-4800.png`, `box-contact-sheet.png`, `box-cycle-lossless-v3.webp` — all eight guns ; 4800 ms roll, 15000 ms ready hold and 1000 ms idle sample.
- `window-0.png`, `window-contact-sheet.png`, `window-cycle-lossless-v3.webp` — 3→2→1→0 boards, then 1→2→3; 2000 ms strike and 1500 ms repair samples.
- `scene.json`, `palette.json`, `provenance.json`, `z-fighting-diagnostics.json` — editable/export evidence.

The offline chest model is explicitly a static vanilla furniture approximation of the native double chest. Native lid animation, sound, client lighting, packet culling, perceived playback and real player input/pathfinding remain separate observations. PNG/animation export and source tests do not establish native-client acceptance. No production world was changed by this authoring/preview work.
