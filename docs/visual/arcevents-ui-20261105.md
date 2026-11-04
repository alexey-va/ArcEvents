# ArcEvents menus and text, 2026-11-05

All five modes retain their entry points. Native dialogs share a short objective, current participation facts and a primary action. Rules are a separate destination; TTT and Zombies have two short pages. Root activity labels use the same weight. Navy panels carry content, green denotes a possible start or purchase, cyan denotes rules, and white denotes navigation.

Zombies exposes **Play solo** and **Form a squad**. Solo uses the service's requester-only reservation. Squad joins the existing global queue and refreshes the current screen. Start authority and counts come from the same queue-control snapshot. Loading removes actions; per-player generation tokens prevent repeat submission and stale callbacks from closing another screen. Only a successful actual start closes the dialog. Failed starts remain visible on the current page. GunGame, Disasters and TTT retain their inventory fallback.

The initial arcade briefing contains one or two authored lines. Detailed guides remain available through Rules. Ordinary successful starts no longer need another menu acknowledgement. TTT avoids duplicating role and countdown state across the actionbar, bossbar and scoreboard. Fishing dialogs use short contextual actions and inspectable unlock requirements. Zombies supplies expose three stations; the physical mystery chest has no instant-purchase dialog route. Native results show eight players per page with roles and combat totals; existing report visibility checks remain in the controller.

## Render and test evidence

`ArcEventsNativePreviewTest` exports actual production `PaperDialogScreen` components for both locales to `build/reports/native-ui/content.json`. It checks parameter resolution, Rules routes for all five modes and absent purchase actions in unavailable supply states. `LocaleContractTest` checks all YAML roots, duplicate mappings, RU/EN leaf and parameter parity, compact briefings and action lore, controls and result states.

The source-driven native renderer is a thin wrapper over the shared RusCrafting surface renderer. It uses component font-provider data, preserves exact body and button widths, and rejects an export older than the UI sources/locales.

```sh
# Export with the repository's normal test runner:
./gradlew test --tests ru.ruscrafting.events.paper.ArcEventsNativePreviewTest

python3 -B scripts/render-native-preview \
  --states ru-root,ru-zombies,ru-zombies-queued,ru-zombies-loading,ru-zombies-error,ru-ttt,ru-gungame,ru-disasters,ru-fishing,ru-trader,ru-armory,en-armory,ru-supply-ammo,ru-supply-power,ru-supply-vitality,ru-supply-poor,ru-supply-maxed,ru-results,ru-results-last,ru-rules-zombies,ru-rules-zombies-controls,en-rules-ttt-controls
```

Optional `--client-jar` and `--resource-pack` arguments reuse verified local artifacts. Vanilla 1.21.11 client SHA1 is `ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd`; asset-index SHA1 is `d216443b5a6b0b33e8f9db446f4f1a0adbe210d1`. The rendered pack SHA256 is `863afef8fbd8ff48f4402dfd1c8e5e7d02882c183b11f6992d0788cdd0b263fb`. The final native gallery has 22 selected states and 204 assigned text leaves, with zero unresolved parameters. Author inspection at logical 640×360, GUI scale 3 (1920×1080), found no clipped labels, overflowing body text or overflowing tooltips. Eight 16-character player names fit in the results table. An earlier representative was inspected before applying the design to supplies, traders and results.

- Native screenshots, including hover states: `build/reports/native-ui/preview/`.
- Source/client/pack/image hashes and reviewed images: `build/reports/native-ui/acceptance-manifest.json`.
- Legacy inventory, title, HUD and RU/EN chat samples: `build/reports/ui-samples/rendered/`. The sample renderer reports 51 assigned leaves and zero automatic chat wraps.
- The source manifest for those selected samples is [arcevents-ui-samples-20261105.yml](arcevents-ui-samples-20261105.yml); run it with the shared `render_plugin_surface_dump.py`, `--project-root .` and `--gui-scale 3`.

These are offline previews, not live Minecraft client captures. Native dialogs use the official panorama as a backdrop; HUD backgrounds are schematic. Legacy head-dependent inventory previews are excluded because the renderer cannot resolve the real head/skin textures. The historical preview's removed fake player-head model was not substituted for runtime player heads. Native input, timing, pack delivery and client appearance are separate observations from export, rendering and server/protocol tests.
