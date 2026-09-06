# Calibration

Target statistical bands and the latest measured result. The bands themselves
live in `docs/SPEC.md` section 13.2; this file records what the engine actually
produces and what was changed to get there.

Reproduce with:

```
./gradlew :engine-cli:run --args="playdemo"
```

The same harness (`CalibrationHarness`) backs both that command and the
`CalibrationTest` regression guard, so the thing you tune against and the thing
that protects the tuning cannot drift apart.

---

## Method

A random offence is drawn against a random defence on **every snap**, across all
32 teams. The first attempt at this sampled a single matchup for 40,000
consecutive snaps, which meant tuning the league's coefficients to fix one
team's stylistic quirks. Yards per carry read 5.47 in that sample and 3.42
across the league with identical coefficients — the sampling method mattered
more than any single dial.

Situations are drawn uniformly over down, distance, field position and score,
which is not how a real game distributes them. That is acceptable at this stage
because no drive or game structure exists yet; once `simDrive` and `simGame`
land at M3 the harness should sample real game flow instead, and these numbers
should be re-measured.

---

## Pass 1 — September 2026 (milestone M2)

**9 of 9 bands passing.**

| Group | Metric | Result | Target |
|---|---|---|---|
| Rushing | yards per carry | 4.27 | 4.10 – 4.60 |
| Rushing | carries losing yardage | 0.10 | 0.08 – 0.18 |
| Rushing | carries of 20+ | 0.009 | 0.008 – 0.030 |
| Rushing | fumbles per carry | 0.009 | 0.004 – 0.014 |
| Passing | completion percentage | 0.63 | 0.63 – 0.68 |
| Passing | yards per attempt | 7.03 | 6.80 – 7.50 |
| Passing | interception rate | 0.022 | 0.020 – 0.028 |
| Passing | sack rate | 0.067 | 0.055 – 0.085 |
| Discipline | penalties per snap | 0.068 | 0.06 – 0.12 |

Measured over 60,000 snaps, seed 2026, league seed 2026.

### What was wrong, and what fixed it

**Tight end blocking help was absolute, not relative.** The original formula
added `averageTightEndRunBlock * 0.12` to the blocking advantage — roughly nine
points just for having a tight end on the field. League-wide yards per carry sat
at 6.1 and nothing in a box score would have looked obviously broken; the league
would simply have run the ball unreasonably well forever. Now help is measured
against a replacement-level blocker (68) so a bad blocker is a liability.

**Sack losses were unbounded.** An exponential draw with no cap produced a
27-yard sack. Real sacks average about 7 and essentially never exceed 15.
Capped at 15 and floored at the offence's own goal line.

**Pressure rate and sack conversion are different quantities.** The first
adjustment raised `pressureScale` while lowering `sackGivenPressure`, and the
two cancelled — sack rate moved 0.113 to 0.108. Pressure on ~39% of dropbacks is
realistic; converting 30% of it into sacks is not. Conversion alone came down to
0.18 and the rate landed at 0.067.

**Distribution shape converged before level did.** After the second pass, yards
per carry read 3.42 while both the share of carries losing yardage and the share
going 20+ were already inside their bands. Shape being right and level being
wrong is a one-dial fix (`baseYards`), and it is worth checking for before
touching anything else.

### Coefficients as of this pass

```
rushing.baseYards              3.60
rushing.advantageYards         2.00
rushing.variance               3.50
rushing.breakawayBase          0.030
rushing.breakawayYards         11.0
RunResolution.ADVANTAGE_DIVISOR  26
passing.pressureScale          30
passing.sackGivenPressure      0.18
passing.baseCompletion         0.775
passing.depthPenaltyPerYard    0.0120
passing.interceptionBase       0.021
passing.yacScale               1.06
penalties.perPlayBase          0.095
```

---

## Still unmeasured

These bands from SPEC 13.2 need drive and game structure before they mean
anything, and are the reason M4 exists as a separate milestone after M3:

points per game · total yards per game · plays per game · third down
conversion rate · red zone touchdown rate · games decided by 3 or fewer ·
best record in the league · teams at 4 wins or fewer · standard deviation of
team wins
