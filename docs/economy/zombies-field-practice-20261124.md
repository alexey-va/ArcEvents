# Zombies administrator practice balance

Classic keeps its existing economy. The new profiles use the same isolated
match points and add three resource counters. A credited kill awards one
copper and one powder. A claimed crate grants three copper, four powder
and two iron. Its separate rolls yield 250 points with 70% probability and a
replacement match weapon with 20% probability.

| Solo scenario | Clears | Kills | Copper | Powder | Iron | Expected bonus points |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Easy, approximately 10 minutes | 2 | 14 | 20 | 22 | 4 | 350 |
| Hard, approximately 25 minutes | 5 | 44 | 59 | 64 | 10 | 875 |
| Hardcore, approximately 50 minutes | 10 | 110 | 140 | 150 | 20 | 1,750 |

The table assumes every crate is claimed and records a single active day.
The expected weapon replacements are 0.4, 1 and 2. These are design scenarios,
with no live completion or claim-rate measurements. A squad shares kill
credit for points through the existing assist rule. The killer receives the
materials, and each standing player can claim the crate once.

A player starts with 500 points. Wire costs 500 and the stone barrier costs
800. The bench takes 1,000 points for its first upgrade and 2,000 for its
second. A turret takes four iron, which requires two claimed crates, and a
shell takes seven copper and nine powder. Frag costs two of each, and smoke
costs one copper and two powder. A participant can have one barrier and one
turret at a time. Claims, inventory decisions and teardown are server-owned.

Net Vault, premium tokens and XP per player-day and network-week are zero in
all scenarios. There is no banking or external item redemption. Match points
are spent or expire, ammo and weapons are escrowed event items, and the
resource counters are cleared with the match. Replay, multiple accounts and
admin grants yield no permanent payout. The existing SELL transition and
Classic's mystery-box economy are outside this change.

The [calculation inputs](zombies-field-practice-20261124.json) include
individual units and exact costs. Native practice checks should exercise one
claim, repeat clicks, insufficient funds, cleanup and exact inventory return.
