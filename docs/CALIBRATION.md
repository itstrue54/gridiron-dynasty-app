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

## Pass 2 — September 2026 (milestone M3/M4)

**17 of 18 bands passing**, measured over 500 games.

| Group | Metric | Result | Target |
|---|---|---|---|
| Rushing | yards per carry | 4.39 | 4.10 – 4.60 |
| Rushing | carries losing yardage | 0.13 | 0.08 – 0.18 |
| Rushing | carries of 20+ | 0.011 | 0.008 – 0.030 |
| Rushing | fumbles per carry | 0.010 | 0.004 – 0.014 |
| Passing | completion percentage | 0.65 | 0.63 – 0.68 |
| Passing | yards per attempt | 7.43 | 6.80 – 7.50 |
| Passing | interception rate | 0.026 | 0.020 – 0.028 |
| Passing | sack rate | 0.070 | 0.055 – 0.085 |
| Discipline | penalties per snap | 0.099 | 0.06 – 0.12 |
| Scoring | points per team per game | 21.4 | 21.0 – 24.5 |
| Scoring | yards per team per game | 357 | 320 – 360 |
| Scoring | plays per team per game | 64.6 | 62 – 67 |
| Efficiency | third down conversion | 0.40 | 0.37 – 0.42 |
| Efficiency | red zone touchdown rate | 0.58 | 0.53 – 0.60 |
| Efficiency | turnovers per team per game | 1.15 | 1.0 – 1.8 |
| Efficiency | penalties per team per game | 6.4 | 5.5 – 7.0 |
| Outcomes | home win rate | 0.57 | 0.52 – 0.60 |
| Outcomes | games decided by 3 or less | 0.18 | 0.18 – 0.26 |

The last one sits on its band floor. With 500 games the standard error on that
statistic is around 0.018, so it is not distinguishable from the middle of the
band and is not worth tuning toward.

### The two real lessons

Both of the hardest problems in this pass were **measurement errors wearing
tuning problems as a disguise**. In each case the tell was identical: two
numbers that could not simultaneously be true.

**1. Two harnesses disagreeing.** A synthetic harness fed situations straight
into `simPlay`, sampling field position uniformly from the 5 to the 94. That put
17% of its snaps inside the twenty against roughly 8% in a real game. The moment
red zone difficulty was tuned, that harness reported the league had collapsed
(6 of 9 bands) while whole-game statistics said it was perfect (15 of 15).

The synthetic harness made sense when games did not exist. Once they did it
became a second source of truth that disagreed with the first, and tuning
against it meant fixing problems that were not there. It was deleted.
`GameCalibration` is now the only calibration authority. If a fast play-level
harness is ever wanted again, it must sample situations at the frequency games
actually produce them.

**2. Counting snaps the NFL does not count.** Plays wiped out by penalty were
being counted as offensive plays. At around 6 penalties per team per game, the
reported figure ran six higher than the offence actually snapped. Plays per game
read 63 and inside the band while the real number was 58 and below it, so the
clock had been slowed to hit a target that was already inflated.

The visible symptoms were points (18.6 against a 21.0 floor), total yards, and
drives per team (9.3 against a real 11.5), and none of them responded to the
dials that appeared responsible. Adding drive-outcome diagnostics is what
exposed it: kicking, red zone rate and fourth down behaviour were all correct,
and only the number of possessions was wrong. Fixing the counter and speeding
the clock moved four bands at once.

### Method notes

- Move one thing at a time. A single change to pass depth moved six bands and
  made attribution impossible; the correction then overshot in the other
  direction (13.0 yards per catch, then 10.1, target 10.9).
- Half steps beat full steps. Both large swings in this pass overshot.
- When two metrics fail in opposite directions, suspect one cause. Completion
  percentage low *and* yards per attempt high was not two problems - it was
  offences throwing digs on first and ten, because concept depth keyed off
  yards to go without considering down.
- Diagnostics beat guesses. The drive-outcome block cost ten minutes and found
  in one run what three tuning rounds had failed to.

### Coefficients as of this pass

```
rushing.baseYards              3.60      passing.pressureScale        30
rushing.advantageYards         2.00      passing.sackGivenPressure    0.18
rushing.variance               4.00      passing.baseCompletion       0.80
rushing.breakawayBase          0.042     passing.depthPenaltyPerYard  0.0120
rushing.breakawayYards         11.0      passing.interceptionBase     0.025
rushing.redZoneCompression     0.40      passing.yacScale             1.06
coverage.redZoneCompression    1.45      penalties.perPlayBase        0.135
blocking.crowdNoiseProtectionCost  7.0   gameFlow.runPlayClockRunoff  31
blocking.crowdNoiseRunCost         4.0   gameFlow.completionClockRunoff 29
RunResolution.ADVANTAGE_DIVISOR   26
```

### Home field

Home advantage is modelled where it physically happens rather than as a bonus
applied to the scoreboard: crowd noise costs a road offence protection and run
blocking, and raises its false start rate. The diagnostics confirm it arrives
through that mechanism - road teams commit more penalties (6.78 against 6.03),
gain fewer yards (350 against 364) and score less (20.4 against 22.3). The 0.57
home win rate is a consequence of those, not an input.

---

## Still unmeasured

These need a full season before they mean anything, and are what M5 unlocks:

best record in the league · teams at 4 wins or fewer · standard deviation of
team wins · repeat division winners year over year
