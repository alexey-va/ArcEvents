# ArcEvents replacement preparation

From an authorized console or RCON use `arcevents:hotload prepare`, then
`arcevents:hotload status`. Preparation stops gameplay using the ordinary shutdown
owner and keeps this maintenance endpoint available. Repeat preparation observes
the same terminal drain; it does not start another close. There is no resume.
Only `ready` confirms successful cleanup of this plugin's owned resources.
`preparing` and `failed` forbid code replacement.

Keep ArcCore and other shared providers enabled until cleanup completes. For any
provider or event/API dependency, prepare and unload consumers leaf-first, then
replace their provider and fresh-load consumers in dependency order. A stopped
consumer can still retain the old API/classloader. A changed shared ABI or an
unvalidated dependency closure requires an ordinary coordinated server restart.

Loaded worlds with this plugin's ChunkGenerator retain its classloader. During
administrative prepare, the `worlds` phase fails if any such world remains
loaded in the pre-prepare snapshot, including a temporary world still being
provisioned; gameplay has already stopped and an ordinary coordinated restart is
required. Ordinary server shutdown does not mistake this retention for a failed
storage drain. This plugin never evacuates players or unloads worlds automatically.
A separately validated evacuation/world unload procedure is required before
attempting administrative prepare without restarting the server.

Use only an external loader already validated for the exact plugin/server and
its dependency closure. This endpoint neither embeds nor certifies PlugManX.
Source tests and same-instance cycles establish preparation only; acceptance
still requires two distinct-JAR/fresh-classloader cycles, durable state and a
late callback check, resource counts, versions and hashes in one server process.
