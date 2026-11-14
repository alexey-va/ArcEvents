# Original source loot and field guide — ArcEvents 0.6.2

The existing original V2 structures are retained. This increment reads 93 loot
tables from the six URL/SHA-256-pinned archives in `ApocalypseStructureAssets`:
JCWS, Keerdm, Lost Cities, the Cursed Walking v3.3 patch datapack, Deep Void and
Deeper Void. They are external data caches (~300 MiB); no upstream assets are
bundled and no Forge classes, original scripts or commands are executed.
Only the pack's nested NewLCLoot datapack is read from its large ZIP.

## Gameplay

- The two real starter barrels contain source food/material rolls. The starter
  kit remains the source-style axe, boots/mask, bread and vaccines; it grants no
  firearm. Ordinary iron nuggets remain plain and usable for native ammunition.
- Weapons, ammunition, medical supplies, tools and construction materials come
  from their original container tables. Original weights, air outcomes,
  inclusive counts, nested tables, append-loot processors and authored chest/
  barrel Items are interpreted. A gun is not guaranteed in each armory.
- Source firearms use the existing ArcEvents gun catalog; they are unloaded
  unless source NBT specifies ammunition. Existing LMB/RMB/F combat/reload is
  reused. Source ammunition counts are retained, split at stack limits.
- Original component crates require the source crowbar equivalent in the main
  hand. A plain iron sword does not bypass it. Ordinary weapon containers open
  by hand. Source procedural crate material rolls are retained.
- Loot is finite per physical container and shared. The durable claim precedes
  materialization; double-chest halves reserve together. V2 containers that
  received the older provisional supplies receive one source-loot-v1 batch,
  preserving existing items and old claims. Full containers consume no claim:
  remove items and reopen. Reopening a completed container never refills it.
  A crash between the journal and native block save may lose the finite batch;
  it is not blindly reissued.
- `/arcevents` inside the expedition opens an optional field guide: next step,
  actual barrel/ladder/city/weapon coordinates, direction/height, day, infection
  and next horde. Tracking uses the existing bossbar. Older clients use a
  virtual book without consuming an inventory slot. Entry does not force a
  combat-blocking dialog. The original FTB guide is optional and has no reward
  gates; this port introduces no artificial quest payout.
- Separate origin/expedition inventories, armor, offhand and XP journal remain
  in place. Leaving returns origin gear; reentry resumes expedition gear.

## Native compatibility limits

This is a native gameplay adaptation, not the TACZ/Point Blank/Create runtime.
Source gun identities map explicitly to existing firearm profiles. Attachments
become iron-nugget salvage; Create/material/armor/medicine equivalents are
explicitly mapped. Vanilla material items keep plain metadata, so crafting and
native ammunition similarity work. Source exploration-map directives use a
lodestone compass to an actual nearby teleporter site when available. Depths
world travel, Create production and the original gun upgrade/crafting trees
are not implemented by these loot substitutions.

Pinned source spelling mistakes have narrow aliases; missing JCWS
`chests/voidmaterials` uses Deeper Void `chests/void_dimension` as a documented
native compatibility choice, not evidence of the original author's intent.
No unknown item/table silently falls back to generic loot. Original vanilla
book enchantments, wear and potion metadata use native APIs. The authored
Combat Roll multi-roll III book becomes Unbreaking III; this is an explicit
utility-book substitute, not a claim that dodge charges are implemented.

## Verification and delivery

The first focused gate passed 45 tests (source loot, original assets/jigsaws,
field-guide routing, native item conversion, content lifecycle and existing
world firearms). Kotest 6.0.7 wildcard filters have the upstream descriptor bug;
exact fully qualified spec names were used instead. The subsequent parser
regression and native acceptance results are recorded with the final candidate.

Before/after finite item flows, one-time migration, zero direct Vault/token
payout and external-hook uncertainty are recorded in
[economy review](economy/apocalypse-source-loot-20261115.md).
Source tests, native test acceptance, disk delivery, activation and a real
player's textured client experience are distinct observations.

Final candidate: `ArcEvents-0.6.2.jar`, 11,422,260 bytes, SHA-256
`8d2dc698018ebfcd450b26d42144d30ca993047cfcf8439e7d3ec732cee953c4`.
ZIP integrity, descriptor identity and `scripts/verify-shadow-jar.py` passed;
no Forge archives or provider entrypoints are bundled. The final focused
item/guide gate passed three cases across two specs; the added item assertions cover
quoted source wear, loaded ammunition and stored enchantments, including the
explicit Combat Roll book replacement. No dependency versions changed.

Six distinct native Paper1.21.11 scenarios passed across focused runs:

- source stock plus exact inventories through reconnect, fallback-world resume,
  repeated exit/reentry; the final migration variant passed43.7s and preserved
  a diamond in a barrel carrying the old `apocalypse_stocked` flag;
- queued-entry refusal and two independent day-ten cooperative hordes (2.7s);
- a native proximity guard at its original source marker (20.7s);
- real main-menu entry, field-guide opening, post-click route response,
  survival-help controls and Back callback without inventory grants (1.2s);
- actual source armory gun, source-ammo reload, shot consumption, crowbar-only
  crate and no refill, followed by origin restoration (22.6s);
- a player-added west half of a source double chest, two retained diamonds,
  source firearms admitted through the unmarked half and no repeat refill
  (39.0s, final candidate).

The first guide expectation used the wrong plural label; migration fixture
expectations initially used a nonmatching vanilla command response, and the
first double-chest probe accessed a chunk before its client packet arrived.
Those test issues were corrected and only the failed/changed flows rerun.
The final native fixture uses ArcCore0.1.28/ARC1.4.527; production has a newer
provider. Generic provider shutdown/closed-ZIP diagnostics remain outside
these gameplay assertions. Book fallback and textured production appearance
were inspected by code/layout review, not a real rendered game client.
Native logs are task-local `/private/tmp/apocalypse-progression-native*.log`.
The full-container branch is source-reviewed, not a native crash/replay test.

## Production activation and owner-requested reset

The immutable candidate above was delivered to Spawn and Parkour only through
`mc jar --no-restart`; backup manifest `jar-20261114T223105Z-5906` retained the
previous JAR. Both roots had one ArcEvents descriptor with that exact hash.
Parkour restarted immediately under the owner's policy; Spawn's fresh typed
empty player list permitted an immediate restart. Parkour reported HOST ready
at22:34:43 UTC and Spawn RELAY ready at22:34:46 UTC. Source containers and guide
callbacks were exercised in the native fixture, not with a production client.

The owner subsequently explicitly requested resetting the Apocalypse world and
expeditions. Parkour was fully stopped through the supported helper; no Java
process remained. The V2 world and Apocalypse data were moved to
`.mc-ops-backups/apocalypse-reset-20261114T2240Z`, outside the active journal.
A new empty V2 directory retained only the verified ownership marker before
My_Worlds autoload. The seed remains68200133: this loot/guide increment did not
change geometry, and resetting with the same seed keeps source building layout.
The old and new world UID hashes differ, confirming a new world instance.

The one offline ACTIVE record could not safely be deleted: it contained the
only pending origin-inventory snapshot. Its expedition was cleared and phase
changed to RETURNING, preserving the origin envelope, route and native session
exactly. The normal next-login recovery restores origin and returns to Spawn;
then a new expedition receives the starter kit. This pending origin recovery is
not retained expedition progress. No native player.dat or other mode was edited.
All world loot/actor/horde/infection checkpoints were cleared. Parkour returned
to HOST ready at22:42:21 UTC, with the new world, no saved expedition and the
unchanged0.6.2 candidate. Actual owner login recovery remains a client observation.

CI for source commit7b7565a passed all unit/package and storage integration
checks. Its combined hosted run exposed test coupling: the preceding firearm
case had already claimed armory index3, so replacing that container for the
new double-chest fixture correctly did not issue a second batch. The isolated
native case had passed on an unclaimed container. The fixture moved to the distinct deterministic rifle container at index4.
The next combined run still observed no source loot there, although both the
production-class oracle and an independent JavaRandom replay expect three
guns, gunpowder6 and copper9. Earlier tests had not claimed that container.
That second failure is not explained by an empty source roll or the first
fixture coupling.

A fresh post-delivery economy read at22:45:32 UTC, bounded from22:31:39 UTC,
reported zero records/operations/players in that window. No per-currency flow
was observed. This idle/restart-window observation does not exercise an
ordinary survival session and does not resolve other plugins' world hooks.


## 0.6.3: first interaction before chunk decoration

The native block can reach a client before the runtime's bounded two-chunks-per-
tick decoration queue registers its loot marker. `markerAt` previously searched
only that runtime map, letting the interaction reach a vanilla empty container.
Generation already caches the pure marker list in the domain layout. The lookup
now verifies the world and populates the runtime marker entry from that cache
on demand, while decorative/entity hydration stays in its existing queue.
This closes the source-visible race; attributing the CI failure specifically to
that queue is an inference, since CI did not inspect the runtime map directly.
The regression also reads the original-loot PDC after opening instead of relying
only on the presence of a firearm. Source loot quantities, claims, world seed,
geometry, currencies and origin recovery are unchanged; the clean world is not
reset again.

Candidate0.6.3 SHA-256:
`9a8aface9ce06d51e2b708400690f89db9b52313ede55f70997a213f350d42c8`.
The focused source/item/guide gate passed13 cases; artifact ownership validation
passed. The complete six-case native run and updated CI are evaluated before
claiming the correction accepted.
