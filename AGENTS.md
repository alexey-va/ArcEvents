# AGENTS.md — ArcEvents

Standalone Kotlin/Paper plugin for network-wide RusCrafting custom events.

- Target Purpur/Paper 1.21.11, Java 25, and Kotlin 2.3.0.
- Use the pinned public `arc-core` release by default; opt into a local
  composite only with `-ParcCoreDir=/absolute/path/to/arc-core`. Consult
  `../arc-core/docs/shared-primitives.md` before adding
  infrastructure; ArcEvents owns only its event domain, Redis namespace, and
  protocol.
- Paper tests use `ru.ruscrafting.arc:arc-core-paper-testing:2.0.0` and
  `MockBukkitTestRuntime`; never pin or manage MockBukkit directly here.
- Keep queues, role allocation, match state, win rules, statistics DTOs, and
  network messages independent of Bukkit.
- A `RELAY` node may advertise, queue, and route only. It must never mutate a
  player's inventory or create a local match. The configured `HOST` is the
  sole active-match authority.
- Capture the complete player-state batch with `PaperPlayerStateService` and
  commit it through `DurableRecordJournal` before teleporting, clearing, or
  issuing any event item. Restore and acknowledgement must be idempotent on
  quit, shutdown, crash, and next join; retain legacy readers only for durable
  records that can still exist in production.
- Keep the joining backend in a durable route through `MATCHED`; delete that
  route only after the origin backend acknowledges the returning player.
- The first mode is `TTT`: innocents and detectives oppose hidden traitors.
  Role secrecy is a gameplay boundary; only permission-gated QA output may
  expose it outside the owning player/team.
- Every gameplay event route must prove the same match id and participant
  state. Non-participants and later recycled matches remain isolated.
- All player text belongs in `lang/ru.yml` and `lang/en.yml`; keys stay equal,
  dynamic values use non-parsing Adventure placeholders, and GUI text is
  explicitly non-italic.
- Runtime recovery data belongs under `plugins/ArcEvents/data/` and is never
  tracked or deployed as configuration.
- Build and test with `./gradlew clean check shadowJar`. Set
  `RUSCRAFTING_OPS_ROOT=/absolute/path/to/ruscrafting-ops` to include tests
  that verify tracked runtime profiles.
