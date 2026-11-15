# Apocalypse arrival recovery — ArcEvents 0.6.4

On 2026-11-15 at 20:14:35–36 Moscow time, the host accepted a player carrying a
RETURNING expedition record, restored its origin inventory and returned it to
Spawn. The resulting local record was PARKED, origin=null, expedition=null and
returnPending=false. This was successful inventory recovery, not an unavailable
world. Subsequent menu attempts at 20:14:42 and 20:14:46 failed on Spawn with
`IllegalArgumentException: arrival already pending` in ApocalypseNetwork line40.
The generic unavailable-world message hid that transfer-state boundary.

The host's known-record reconciliation returned before consuming the accepted
arrival ticket. Its 30-second validity then blocked immediate reentry. The
normal arrival path already consumed the ticket, so a fresh-profile local-host
smoke did not exercise the broken early recovery branch.

## Correction

- Consume the receiver's arrival before selecting native resume, origin replay
  or fresh entry. Preserve local origin recovery even when Redis cleanup fails;
  do not admit a fresh expedition on that Redis failure.
- A retry can atomically replace a pending ticket for the same player, origin
  and host, then resend the normal backend transfer. Different routes remain
  protected. No actor inventory, journal or world reset is used to unblock entry.
- Failed/offline/throwing transfer cleanup compares the exact newly issued nonce.
  It cannot delete a concurrent replacement. A valid foreign-host ticket is not
  consumed by a different receiver; expired/malformed records retain cleanup.

The existing network API, player-record format, source-loot rules and world
identity are unchanged. This is recovery for interrupted arrival/return flows,
not a bypass for an actually unavailable host or invalid persistent data.

## Verification

Eight focused tests passed: same-route refresh, competing routes, failed/offline
transfer, throwing transfer, admission CAS race, cleanup CAS race, receiver
ownership/one-time consumption, and malformed/expired cleanup. No dependencies
were added. A real Paper1.21.11 host case passed (46.2s), covering origin and
expedition inventory, finite loot, reconnect, fallback-world resume and repeated
leave/reentry. The immutable candidate passed ZIP and shadow ownership guards.

The selected live actor uses the existing CodexQA allowlist; no production
player data was rewritten for acceptance.

## Delivery and activation

- Published runtime source: `0adf09764a9931d2e247419fc365ddae534fc993`.
  [Full CI](https://github.com/alexey-va/ArcEvents/actions/runs/43742201578)
  passed unit/package, storage integration and real-Paper E2E jobs.
- Immutable ArcEvents0.6.4 candidate: 11,423,287 bytes, SHA-256
  `d25148ca66e67a0615aac93b6269285f60dca147cbc67fdd602458d9f939d6aa`.
- Supported delivery manifest `jar-20261115T173902Z-72709`, handoff
  `deploy-20261115T173901Z-72686`. Spawn and Parkour file hashes were read back
  against this candidate again at 20:55 Moscow time.
- Parkour was restarted immediately under the owner's standing instruction;
  HOST readiness was recorded at 20:44:18. Spawn used the normal notified
  five-minute countdown; RELAY readiness was recorded at 20:49:46 and server
  startup completed at 20:49:50. Both loaded ArcEvents0.6.4.
- Survival was not restarted. This correction did not reset the world,
  expeditions or inventories.

## Ordinary live acceptance

The tester used `CodexQA_729` through the public proxy and normal native menu
callbacks. The published ops helper revision was
`46e1bae329369513598e96f9a28cbeeb96ea0e38`; its bounded callback tests passed
44/44. No admin teleport, authentication reset or ticket deletion was used.

| Moscow time | Observed ordinary-player result |
| --- | --- |
| 20:51:55 | Spawn baseline: `rc_origin_spawn`, (58.59,70,-135.50), HP20, food17; occupied slots36 paper×1,37 iron_ingot×6,38 paper×1. |
| 20:52:10 | Spawn Events menu → Apocalypse admitted the player. Native world became `arcevents_apocalypse_v2`; starter bunker settled position was (3.5,34,39.5). |
| 20:52:49 | Field guide → Survival tips → Leave expedition returned to Spawn. Baseline inventory, world, position, health and food matched. |
| 20:52:56 | The same menu admitted a second expedition 6.972 seconds after the first return. The expedition's axe, bread×9, vaccine bottles×6 and spyglass persisted. |
| 20:53:14–18 | Second guide/leave restored the same origin state; the bot quit normally after returning. |

The host log independently confirms both backend arrivals and transitions into
the Apocalypse world. No `arrival already pending` or apocalypse-transfer
failure was recorded during the QA window. A fresh complete typed proxy roster
at 20:54:48 independently confirmed the actor offline.

This verifies native menu actions, actual world entry and inventory separation.
Rendered building appearance and resource-pack visuals were not evaluated in
this protocol-based live check. The known RETURNING-record failure branch is
supported by incident evidence and focused recovery tests; acceptance did not
manufacture a broken production player record to replay it.
