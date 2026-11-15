# Apocalypse arrival recovery — ArcEvents 0.6.4

On 2026-11-15 at20:14:35–36 Moscow time, the host accepted a player carrying a
RETURNING expedition record, restored its origin inventory and returned it to
Spawn. The resulting local record was PARKED, origin=null, expedition=null and
returnPending=false. This was successful inventory recovery, not an unavailable
world. Subsequent menu attempts at20:14:42 and20:14:46 failed on Spawn with
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

Publication, active runtime hashes and ordinary proxy/menu entry observations
are recorded after rollout below. The selected live actor uses the existing
CodexQA allowlist; no production player data is rewritten for acceptance.
