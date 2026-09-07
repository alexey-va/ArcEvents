# ArcEvents host E2E

This suite starts the real Paper plugin as a HOST with the bundled `citadel-v1`
arena. The fixture shortens only Gun Game timers and makes the firearm catalog
one-shot for a bounded real combat run; it does not call debug mutations. The
relay suite remains under `src/test/e2e` and runs separately.
