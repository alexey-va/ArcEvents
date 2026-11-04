# Zombies physical interactions: balance assessment

The 0.5.44 change turns the existing 650-point random-weapon purchase into a physical chest opening and adds repairable ingress windows. Starting points, kill and wave awards, weapon pool, damage upgrades and ammunition prices remain unchanged. Repair is free and awards no points, items or XP.

All amounts below are **match-local zombie points**, with no exchange into Vault, tokens or XP. Low/base/high are one-run scenarios, not telemetry. The inherited solo scenarios clear 5/8/10 waves and credit every kill to that player.

| Per player-day | Low | Base | High |
| --- | ---: | ---: | ---: |
| Local points awarded, before = after | 9,400 | 18,775 | 27,175 |
| Paid box openings, before = after | 1 | 8 | 24 |
| Points spent at 650 per opening | 650 | 5,200 | 15,600 |
| Remaining wallet destroyed at restoration | 8,750 | 13,575 | 11,575 |
| Persistent point balance after restoration | 0 | 0 | 0 |

With 1/5/20 participating players these assumptions imply 65,800/657,125/3,804,500 local points issued per network-week and exactly the same destruction. Vault delta is zero; tokens, XP and ordinary iron-nugget ammunition each have a separate zero delta. Existing SELL and the planned SELL-to-contract transition are untouched.

At a 500-point start, two ordinary walker kills make the first 650-point opening affordable. The full existing power progression still costs 6,000 and vitality costs 1,500. An opening competes with those same upgrades. The animation adds elapsed time, not another draw or price. The original exclusive pool excludes the current weapon; no new weighting or permanent entitlement is introduced.

A revealed result expires without refund if abandoned, so waiting for a better result cannot generate free rerolls. A technical cancellation reverses only its exact unclaimed reservation at most once. Double clicks, foreign players, owner departure, full cleanup and late callbacks must not create a second claim. Successful pickup uses the existing owned event-weapon slot and match-bound ammunition; restoration returns the original inventory.

Three-board windows are fixed arena objects. Repair requires continuous nearby participation and has no payment or reward, so repeated breach/repair cannot farm an economic balance. Barricades may change the chance of surviving later waves; this indirect gameplay effect is unmeasured and has no external payout.

The machine-readable assessment is `zombies-box-barricades-20261105.json`; its evaluator output is `zombies-box-barricades-20261105-result.json`. Focused checks cover requester-only queue reservation, one debit/claim, ordinary expiry, technical cancellation, blocked and completed repairs, enemy breach and owned-scene cleanup. Delivery, active runtime and native client observation are separate evidence.
