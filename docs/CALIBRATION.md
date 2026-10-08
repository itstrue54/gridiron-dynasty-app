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

**18 of 18 bands passing**, measured over 500 games.

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
| Scoring | points per team per game | 21.1 | 21.0 – 24.5 |
| Scoring | yards per team per game | 356 | 320 – 360 |
| Scoring | plays per team per game | 64.6 | 62 – 67 |
| Efficiency | third down conversion | 0.41 | 0.37 – 0.42 |
| Efficiency | red zone touchdown rate | 0.58 | 0.53 – 0.60 |
| Efficiency | turnovers per team per game | 1.15 | 1.0 – 1.8 |
| Efficiency | penalties per team per game | 6.4 | 5.5 – 7.0 |
| Outcomes | home win rate | 0.57 | 0.52 – 0.60 |
| Outcomes | games decided by 3 or less | 0.19 | 0.18 – 0.26 |

That last figure read 0.18 and failed at 240 games, then passed at 0.19 with
500. Nothing changed but the sample size. The standard error on a proportion
that size is around 0.026 at 240 games and 0.018 at 500, so the earlier reading
was never distinguishable from the middle of the band. Worth remembering before
tuning toward a metric that is sitting on its own noise floor.

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

## Pass 3 — September 2026 (milestone M5)

**18 of 18 bands passing**, measured over 500 games. Re-tuned after the season
simulator exposed several bugs that the play and game harnesses could not see,
because they only appear across a whole schedule.

### What the season print-out found

**A rotation that was not mutual.** Each division chose its intra-conference
opponent with an offset formula, so division 0 could choose 1 while 1 chose 2 -
leaving division 1 playing eight extra games. Records came out 11-4-1 and
10-9-1, which read as football until you count them. Fixed by using the three
fixed pairings of four divisions, cycling every three years.

**A silent fallback that hid it.** The week assigner had a "never leave a season
unplayable" branch that quietly stuffed leftover games into any week with room.
That is what turned a broken rotation into plausible standings instead of a
crash. It is gone, and there is now an assertion at construction that every team
comes out at exactly seventeen games. A generator that quietly produces a
twenty game season is worse than one that stops.

**Greedy week filling does not work.** Two teams who still owe each other a game
can each have every remaining week booked. Each week needs a perfect matching on
the available teams over unplayed games; the assigner backtracks and branches on
the most constrained team, which is what makes the search finish instantly.

**Everything concentrated on one player per position.** The starting back took
every carry (649 in a season), the best pass rusher took every sack (62), and
the top three receivers took every target. Carries now rotate 60/28/12, sacks
are awarded weighted by rush skill, and targets spread across receivers, tight
ends and backs.

**Positions had no ratings for skills they use but are not rated on.** Relevant
ratings were taken from the overall formula, and a running back's overall does
not weigh route running - so backs generated with 34 route running and could not
catch. Invisible until targets started going to them, at which point completion
percentage fell to 0.60 and the interception rate hit 0.035. Secondary skills
now generate twelve points below a player's level.

**Playoff statistics were inflating regular season leaderboards.** A deep run
adds four games and was winning rushing titles on volume. Kept separate now.

### The trap worth remembering

Fixing the route-running bug made the numbers *worse*: five bands went out at
once. `yacScale` had been tuned in an earlier pass while receivers had 34
elusiveness, so the coefficient had silently absorbed the defect. Correcting the
ratings released about two extra yards a catch.

That is the cost of tuning against symptoms rather than causes - the dial ends up
encoding the bug, and the bug fix then looks like a regression. The tell was that
five bands moved together in the same direction, which is the signature of a
global change rather than a local one.

### Method notes added this pass

- When several bands fail at once, find the one thing upstream of all of them.
  Every time dials were turned on a multi-band failure in this project it was
  wrong; every time the shared cause was found it was right.
- Two metrics failing in opposite directions is one cause, not two.
- A guard that fails on its own sampling noise is worse than no guard, because
  it teaches you to ignore it. The regression test now runs 260 games rather
  than 90 for exactly this reason.

---

## Pass 4 — October 2026 (pre-launch review)

**What was wrong:**
- A first-time-player review saw a game with 79 runs to 41 passes.
- `GameCalibration` turned out not to measure five of SPEC 13.2's per-game bands.
- Four of those five were out of band. "All 18 bands pass" had never covered the run/pass balance.

**The league ran too much and threw too little.** Carries were never the only way to fumble in the NFL; here they were, so carries fumbled at twice the NFL's rate to make up the total.

**Measured** on league 2026, 2,000 games, seed 2026:

| Band | Before | After | Target |
|---|---|---|---|
| Carries per team | **30.0** | 27.3 | 25–28 |
| Pass attempts per team | 32.2 | 35.3 | 32–36 |
| Passing TDs per team | **1.16** | 1.33 | 1.3–1.7 |
| Sacks per team | **2.65** | 2.46 | 2.1–2.6 |
| Fumbles lost per team | **0.34** | 0.56 | 0.5–0.8 |
| Yards per carry | 4.43 | 4.36 | 4.1–4.6 |
| Yards per attempt | 7.37 | 7.08 | 6.8–7.5 |
| Completion % | 0.641 | 0.639 | 0.63–0.68 |
| Points per team | 21.7 | 21.3 | 21.0–24.5 |
| Yards per team | 352 | 353 | 320–360 |
| Plays per team | 65.5 | 65.7 | 62–67 |
| Third-down conversion | 0.415 | 0.399 | 0.37–0.42 |
| Turnovers per team | 1.07 | 1.38 | 1.0–1.8 |
| Games decided by 3 or less | 0.241 | 0.221 | 0.18–0.26 |

All 23 bands pass on league 2026 and on league 7. League 99, the most pass-heavy mix of schemes, misses one: attempts at 36.3 against 36, 7% of the band.

**Changes:**
- **Calling:**
  - pass rate on first down −0.06 → +0.015;
  - pass rate on second down +0.02 → +0.06;
  - goal-line cut +0.22 → −0.12 (clubs throw more at the goal line, so short scores are not all runs);
  - sneak rate 0.40 → 0.20.
- **Passing:**
  - sack given pressure 0.21 → 0.18;
  - yards after catch mean 2.6 → 1.7;
  - base completion 0.83 → 0.85;
  - depth penalty 0.0138 → 0.0155 (shorter completions, so yards per game stay in band while throwing more).
- **Rushing:** base yards 3.63 → 3.48.
- **Game flow:** run-play clock runoff 35 → 36 s.
- **New:** strip-sacks (`stripSackLost` 0.05) and fumbles after a catch (`catchFumbleBase` 0.0051). Any turnover stops the clock.

**Passing leaders:** the new balance raises the season passing leader by about 100 yards on average. Six seasons ran 4,810–5,587, against 4,791–5,466 before. That stays inside `SeasonTest`'s plausibility band.

**Still off:** field-goal attempts run about 2.4 a team against the NFL's 1.8, and points sit near the bottom of their band. Drives stall in field-goal range too often; that is fourth-down and red-zone behaviour, for a later pass.

## Pass 5 — October 2026 (field goals and long plays)

**What was wrong:** field goals ran about 2.4 attempts a team. Recent NFL seasons run about 1.9–2.0 (pass 4's 1.8 was low). Three measurements explained why:
- **Few long plays.** Plays of 40+ yards: 0.03 a team per game, against the NFL's ~0.6. Touchdowns from 20+ yards out: 5% of touchdowns, against ~25%. Yards after the catch averaged under a yard (`yacScale` 0.35), so every score was a long march, and long marches stall.
- **Fourth downs in range nearly always kicked.** Of fourth downs inside the opponent's 40, 94% were kicked; NFL coaches go for it far more on fourth and short there. With 3 or more to go inside the 38, the code kicked every time.
- **Cutting air yards doesn't work.** Shortening every throw to pay for long catch-and-runs cost passing touchdowns and points faster than it bought long plays (candidates A–C below).

**Measured** on league 2026, 2,000 games, seed 2026:

| | Before | After | Target / NFL |
|---|---|---|---|
| Plays of 40+ per team | 0.03 | 0.23 | ~0.6 (diagnostic) |
| Plays of 20+ per team | 2.2 | 2.0 | ~3.5 (diagnostic) |
| Touchdowns from 20+ yards | 0.05 | 0.18 | ~0.25 (diagnostic) |
| Field-goal attempts per team | 2.42 | 2.09 | ~1.9–2.0 (diagnostic) |
| Touchdown drives per team | 2.19 | 2.41 | ~2.4 (diagnostic) |
| Fourth-down attempts per team | ~1.0 | 1.42 | ~1.3 (diagnostic) |
| Turnovers on downs per team | ~0.38 | 0.52 | ~0.6 (diagnostic) |
| Passing TDs per team | 1.33 | 1.52 | 1.3–1.7 |
| Yards per attempt | 7.08 | 7.26 | 6.8–7.5 |
| Yards per carry | 4.36 | 4.32 | 4.1–4.6 |
| Points per team | 21.3 | 22.1 | 21.0–24.5 |
| Yards per team | 353 | 357 | 320–360 |
| Red zone TD rate | — | 0.582 | 0.53–0.60 |
| Third-down conversion | 0.399 | 0.385 | 0.37–0.42 |

**Bands:**
- All 23 pass on leagues 2026 and 7.
- League 99, the most pass-heavy mix of schemes, misses two:
  - attempts at 36.2 against 36. It missed this before too, at 36.3.
  - yards at 364 against 360, 10% of the band.

  Both are inside the test's tolerance.

**Candidates tried** (league 2026, before the fourth-down change):

| | Air scale | Catch breakaway (base / mean) | Run breakaway (base / mean) | Pass TD | Points | 40+ | FGA |
|---|---|---|---|---|---|---|---|
| A | 0.90 | 0.06 / 16 | 0.030 / 16 | **1.20** | **20.7** | 0.14 | 2.49 |
| B | 0.85 | 0.08 / 18 | — | **1.17** | **20.2** | 0.18 | 2.48 |
| C | 0.88 | 0.07 / 20 | 0.028 / 18 | **1.28** | **20.9** | 0.19 | 2.45 |
| F | 0.95 | 0.05 / 25 | 0.026 / 20 | 1.42 | 22.1 | 0.16 | 2.42 |

Bold is out of band. Long plays alone barely moved field goals (F). The fourth-down change did: with it, F went to 2.08 attempts.

**Changes:**
- **Passing (new):**
  - catch breakaways: base 0.07, mean 28 yards, ±0.0015 per point of receiver elusiveness and speed, and of the secondary's tackling, capped at 0.25;
  - `airYardsScale` 0.92;
  - a catch and run of 20+ yards gets its own play-by-play lines.
- **Rushing:**
  - breakaway base 0.042 → 0.026;
  - breakaway mean 11 → 20 yards;
  - base yards 3.48 → 3.40.
- **Fourth down (new):**
  - in easy range (the opponent's 38 in), he always kicks only with 5+ to go (was 3+);
  - with less, `goKickRange` 0.25 is added to his chance of going for it, except late in the fourth within a kick.

**Field goals** are now within about 0.1–0.2 a game of the NFL. The fourth-down change did most of that.

**Still off: long plays.** Plays of 40+ are about a third of the NFL's, and plays of 20+ about 60%. The gains are too uniform:
- a route's depth varies by only ±2 yards (`airYardsSpread` 2.2);
- yards after the catch are small apart from breakaways;
- mid-range throws complete about 72% of the time (NFL ~57%), and deep ones about 58% (NFL ~38%).

**Tried and not shipped** (league 2026, 2,000 games):

| Change | Plays of 20+ | Problem |
|---|---|---|
| More YAC, shorter routes (YAC mean up to 3.5, air scale down to 0.62) | 1.8–2.0 | No gain: a 2–3 yard exponential seldom reaches 20, and shorter routes lose the deep gains |
| More and shorter catch breakaways (base 0.15–0.20, mean 12–14 yards) | ~2.2 | Barely moves |
| Wider route spread (5–6 yards) | 2.9–3.6 | Third-down conversion falls to 0.36: half the throws land short of the sticks |
| One-sided depth tail (exponential, 0.20–0.35 of route depth, outside the red zone) | 3.0–3.3, third downs in band | 370–380 yards a team; a steeper depth penalty to pay for it drops completion to 0.61; field goals rise, because more yards between the 20s mean more trips into range |

**The next pass** has to rebalance three things at once:
- the concept mix, with more short throws;
- completion by depth;
- a one-sided depth tail.

It also has to keep total yards in band. No single value does it.

## Pass 6 — October 2026 (the shape of a pass)

**What was wrong:** pass 5 left plays of 20+ at 2.0 a team per game (NFL ~3.5) and of 40+ at 0.23 (NFL ~0.55). Measured by concept over 600 games, the mix and the outcomes were both off:
- **Too many mid-range throws.** About 48% of attempts were 11–15-yard routes. First downs aimed 7–13 yards deep.
- **No screens.** A screen's depth of −2 never fell in the depth pool, so they were 0.4% of attempts against the NFL's ~8–10%.
- **Completion barely fell with depth.** Mid routes were caught 72% of the time (NFL ~57%), deep ones 58% (NFL ~38%).
- **No variation in depth.** A route's depth varied by ±2 yards, so a dig was 14 yards and never 30.

**Approach:** pass 5's single changes had each failed, so the new levers were tuned together by search.
- Two new levers: screens, and a one-sided depth tail.
- About 180 candidates on league 2026, 2,000 games each.
- Each was scored by distance outside any band (with a 10% margin), plus distance from the NFL on plays of 20+ and 40+, field goals, third downs, completion and long touchdowns.
- The best was rounded, then trimmed for yards on league 99.

**Measured** on league 2026, 2,000 games, seed 2026:

| | Before | After | Target / NFL |
|---|---|---|---|
| Plays of 20+ per team | 2.04 | 2.81 | ~3.5 (diagnostic) |
| Plays of 40+ per team | 0.23 | 0.34 | ~0.55 (diagnostic) |
| Touchdowns from 20+ yards | 0.18 | 0.25 | ~0.25 (diagnostic) |
| Carries of 20+ | 0.012 | 0.017 | 0.008–0.030 |
| Field-goal attempts per team | 2.09 | 2.16 | ~1.9–2.0 (diagnostic) |
| Completion % | 0.640 | 0.638 | 0.63–0.68 |
| Yards per attempt | 7.26 | 7.18 | 6.8–7.5 |
| Yards per carry | 4.32 | 4.38 | 4.1–4.6 |
| Passing TDs per team | 1.52 | 1.53 | 1.3–1.7 |
| Points per team | 22.1 | 22.5 | 21.0–24.5 |
| Yards per team | 357 | 357 | 320–360 |
| Third-down conversion | 0.385 | 0.378 | 0.37–0.42 |
| Red zone TD rate | 0.582 | 0.554 | 0.53–0.60 |

**Bands:**
- All 23 pass on leagues 2026 and 7.
- League 99 misses the same two as after pass 5, inside the test's tolerance:
  - attempts at 36.2;
  - yards at 362, down from 364.

**Changes:**
- **Calling:**
  - first-down depth 7–13 → 4–9 (`firstDownDepthMin`, `firstDownDepthRange`, new; the 7–13 was a literal in `PlayCaller`);
  - screens `screenRate` 0.065, on early downs and third and 10+ (new).
- **Passing:**
  - base completion 0.85 → 0.88;
  - depth penalty 0.0155 → 0.0216;
  - air yards scale 0.92 → 0.87;
  - depth tail `airYardsTail` 0.19, outside the red zone (new);
  - YAC mean 1.7 → 1.73;
  - catch breakaway base 0.07 → 0.11, mean 28 → 24.
- **Rushing:**
  - base yards 3.40 → 3.20;
  - breakaway base 0.026 → 0.037;
  - breakaway mean 20 → 23.

**The trade:**
- **Field goals rise slightly,** 2.09 → 2.16, and third downs fall slightly, 0.385 → 0.378. The search never found long plays that came without a few more yards between the 20s, and those yards end in field-goal range.
- **The red-zone touchdown rate falls** to 0.554, close to the NFL's ~0.56.

**The mix now:** 49% short routes, 39% mid and 11% deep (screens 6%), close to the NFL's split. Completion on thrown balls is 84%, 71% and 55% by those groups; the NFL's is ~75%, ~57% and ~38%, so the fall with depth is steeper than before but still too flat.

**Still off:** plays of 20+ are about 80% of the NFL's, and 40+ about 60%. The search had levelled off: its best 15 candidates all sat at 2.75–2.90 and 0.32–0.39. More would need the total-yards band, or ordinary plays that gain less still.

## Pass 6a — October 2026 (the clock at the end of a quarter)

**What was wrong:**
- **The clock carried over between quarters.** A play that ran past a quarter's end took the extra seconds from the next quarter. The NFL stops the clock at zero, so the sim's games were short of their full time.
- **Kicks went unrecorded in possession.** Box-score possession left out the punt, field goal and try runoffs. A regulation game added up to about 57:40, not 60:00.

**Measured** (league 2026, 2,000 games):
- Ending the carry-over gave back about half a play per team per game. Plays went 65.6 → 66.1, and yards 357 → 360, the top of the band.
- The run-play runoff goes 36 → 37 s to pay for it. With that, plays are 65.4 and yards 356, and every band is within 0.25 of where it was.
- League 99 improves slightly: attempts 36.2, yards 361.

## Pass 7 — October 2026 (season shape, over ten years)

SPEC 13.2's season-level bands had only been checked loosely, one season at a time, by `SeasonTest` (best record 12–17, spread of wins 2.0–4.0). Teams at four wins or fewer, and repeat division winners, were never measured: repeat winners waited on the offseason (M7).

**Measured:** four generated leagues, each played through ten seasons with every offseason (40 seasons):

| Year | Spread of wins | Clubs at ≤4 wins | Best record |
|---|---|---|---|
| 2026 (first) | **3.56** | 4.2 | 15.8 |
| 2027 | 2.86 | 2.8 | 14.8 |
| 2028 | 2.90 | 2.5 | 15.0 |
| 2029 | 2.72 | 2.0 | 15.0 |
| 2030–2035 | 2.64–2.92 | 1.8–3.2 | 13.8–14.5 |

Over all 40 seasons:
- best record 14.6, mode 14 (13–17);
- 2.6 clubs at four wins or fewer (2–5);
- spread of wins 2.87 (2.6–3.4);
- repeat division winners 42% (40–55%).

**Findings:**
- **The league holds its shape.** It settles after the first offseason and stays there for a decade, without drifting toward everyone going 8–9.
- **The first season was out.** Its spread of wins ran 3.4–3.6, wider than the league settles to and past the band, because the generator's team-strength draw was wider than the offseason sustains. That first season is the one every new dynasty plays.

**Change:**
- The generator's team-strength draw narrows from 2.9 to 2.4 overall points (`LeagueGenerator.TEAM_STRENGTH_SPREAD`).
- Twelve fresh leagues' first seasons go from 3.40 to 3.23 spread, 4.2 to 3.9 clubs at ≤4 wins, and 15.4 to 14.6 best record. Their next two seasons stay in band.

**Per-game bands** (2,000 games): close games 0.217 → 0.232 (league 2026) and 0.204 → 0.225 (league 99). Everything else moves by less than 0.01, or 1 yard. All 23 bands pass on leagues 2026 and 7; league 99 misses attempts (36.4) and yards (362) as before.

**`SeasonShapeTest`** holds the first season of eight new leagues to the mean bands: best record, clubs at four wins or fewer, and the spread of wins. At the old spread it fails at 3.45.

## Pass 8 — October 2026 (stars over a decade)

**What was wrong:** over a ten-year dynasty, players rated 90+ multiplied fourfold, from about 14 to about 60, then levelled off. The average starter barely moved (80.1 → 79.8), so the league didn't get better; the top just got crowded. Measured on four leagues:

| Year | 2026 | 2028 | 2030 | 2032 | 2034 | 2035 |
|---|---|---|---|---|---|---|
| Players 90+ | 14 | 26 | 43 | 56 | 63 | 59 |
| Players 85+ | 106 | 125 | 145 | 156 | 151 | 141 |

**Where the stars came from:** tracking every player's year-to-year change (two leagues, eight seasons):
- of 212 players who reached 90, 146 rose from 88–89;
- a player at 88+ aged 25–27 changes +0.26 a year on average, so these were mostly a lucky year of noise, not development.

Progression's growth didn't depend on how good a player already was, so nothing stopped a crowd of 88s drifting over 90 a point at a time.

**Target** (decided October 2026): meet in the middle, about 30–40 players at 90+ throughout.

**Changes:**
- **Progression:** above `progression.growthTaperFrom` (80) overall, a year's rise (the age curve and the noise together, never a breakout) is scaled down in a straight line to `growthAtCeiling` (0) at 99. A player at 88 keeps 58% of a year's rise, and one at 92 keeps 37%. Decline is untouched.
  - Tapering only the age curve was tried first (at 0.5 and 0.3) and changed little: at peak age the curve is almost nothing, and the noise did the climbing.
- **Generator:** a player's target overall spreads 4.5 either side of his slot's target (`RosterGenerator.PLAYER_SPREAD`, was 3.0). A new league has about 29–31 players at 90+ (was 12–14). The league mean is unchanged (71.6).

**Measured after:** spread 4.5 with the taper, four leagues, ten years:

| Year | 2026 | 2027 | 2028 | 2029 | 2030 | 2031 | 2032 | 2033 | 2034 | 2035 |
|---|---|---|---|---|---|---|---|---|---|---|
| Players 90+ | 31 | 29 | 28 | 32 | 38 | 41 | 42 | 38 | 39 | 36 |
| Players 85+ | 144 | 140 | 152 | 150 | 156 | 160 | 155 | 152 | 146 | 134 |
| Average starter | 80.6 | 80.6 | 80.6 | 80.5 | 80.5 | 80.4 | 80.3 | 80.1 | 79.8 | 79.5 |

**Cost:** the average starter eases about a point over the decade, against 0.3 before: the taper takes a little off the top that nothing puts back. `StabilityTest`'s thirty-year bands (league mean within −2 to +3 of where it started, the top not drained) still pass.

**Per-game bands** (2,000 games, the generator's spread):
- Moved: close games 0.232 → 0.217 (league 2026), passing touchdowns 1.54 → 1.51; the rest move by less than 0.03.
- All 23 pass on leagues 2026 and 7.
- League 99 misses attempts (36.5) and yards (365), as before, inside the tolerance.

## Pass 9 — October 2026 (rebuilds and dynasties)

Measured on six leagues, ten seasons each, with pass 8's changes (192 club-decades). No change was needed.

| | Sim | NFL (approx.) |
|---|---|---|
| Year-to-year correlation of a club's wins | 0.35 (1,728 season pairs) | 0.3–0.4 |
| Clubs at ≤4 wins that reach 10 within the decade | 85% | most |
| Median years from ≤4 wins to 10 | 3 | 3–4 |
| From ≤4 wins to 10 the next year | 15 of 116 (13%) | about one club a year |

**Longest run of 11-win seasons, per club:**

| Run | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|---|
| Clubs | 21 | 92 | 43 | 25 | 4 | 4 | 1 | 2 |

A run of seven happens, about once in a hundred club-decades: a dynasty can sustain itself, and one usually doesn't.

## Pass 10 — October 2026 (every league, not just two)

**What was wrong:** league 99 had missed pass attempts and yards in every pass since pass 4, inside the test's tolerance. The cause was its draw, not the sim:
- each club's scheme was rolled on its own, and league 99 drew six Air Raids and seven West Coasts;
- its average base pass rate was 0.583, above every one of 40 other leagues (0.520–0.578).

**Change:** schemes are dealt, as evenly as 32 clubs allow, and shuffled onto the clubs. Every league has the same mix, with an average base pass rate of 0.56. Rosters are unchanged: the team streams take the same draws as before.

**Measured** (2,000 games each, with pass 8's generator):

| League | Carries | Attempts | Points | Yards | Close games | Home wins | Bands out |
|---|---|---|---|---|---|---|---|
| 2026 | 27.3 | 34.8 | 22.1 | 354 | 0.227 | 0.567 | none |
| 7 | 27.3 | 34.4 | 22.3 | 358 | 0.210 | 0.576 | none |
| 99 | 27.0 | 34.8 | 22.6 | 358 | 0.232 | 0.561 | none (was attempts 36.5, yards 365) |
| 11 | 27.3 | 34.6 | 22.1 | 353 | 0.222 | 0.552 | none |
| 12 | 27.2 | 34.9 | 22.1 | 354 | 0.221 | 0.564 | none |
| 13 | 27.1 | 35.1 | 22.1 | 354 | 0.219 | 0.576 | none |

**All 23 bands pass on all six leagues.** That's the first time a league other than 2026 and 7 has been in band.

**Tests:**
- `SchemeDealTest` (new): every league holds each scheme to within one of every other; leagues differ in who runs what, not the mix; rosters don't depend on the deal.
- `TradeOffersTest`'s block test assumed every one of the user's ten best would be an upgrade somewhere. In the new league one wasn't: no club's best edge rusher was worse than him. It now asks the question for the men some club would take, and requires at least eight of the ten to be such men.
- Two more tests moved onto older weaknesses:
  - curls gaining about 16 yards a catch;
  - adaptation barely measurable.
  Passes 11 and 12 fix them.

## Pass 11 — October 2026 (stop routes)

**What was wrong:**
- **Curls ran far.** A curl gained about 16 yards a catch (NFL ~11), and a curl-only diet averaged 11.4 yards an attempt in `PlaySimulatorTest`'s matchup.
- **The cause was pass 6.** Its throw past the route and its catch breakaways applied to every route. A curl, stick, out, flat or screen is caught standing, facing the quarterback, with the defender closing: there is no "behind his man", and less room to run.

**Change:**
- Routes carry a `stop` flag: the curl, stick, out, flat and screen.
- A stop route never draws the throw past the route. Its breakaway chance is `stopRouteBreakaway` of the in-stride chance.
- Passing was re-searched around pass 6's values (50 candidates, 2,000 games each), with curl yards a catch added to the score.

**New values:**
- `stopRouteBreakaway` 0.42 (new);
- `airYardsTail` 0.19 → 0.26;
- `yacBreakawayBase` 0.11 → 0.12, `yacBreakawayYards` 24 → 28;
- `baseCompletion` 0.88 → 0.888, `depthPenaltyPerYard` 0.0216 → 0.0203;
- `yacMean` 1.73 → 1.96.

**Measured:**
- **The curl:** 12.5 yards a catch and 9.6 an attempt in the test's matchup.
- **League 2026 against pass 10:**
  - completion 0.645 → 0.661;
  - yards per attempt 7.18 → 7.15;
  - plays of 20+ 2.71 → 2.68, and of 40+ 0.343 → 0.332;
  - touchdowns from 20+ yards 0.258 → 0.274;
  - field goals 2.15 → 2.10.
- **Bands:** all 23 pass on all six leagues.

## Pass 12 — October 2026 (a staff that notices)

**What was wrong:** SPEC 5.4 says a predictable coordinator gets punished, but against an offence that ran nine times in ten, a staff rated 100 for adjustments took only about 0.05 yards a carry off it compared with one rated 0. That's inside the noise even over 600 games, and it went the wrong way in one of three matchups.

The box shift shared the pass and blitz window (±0.12). For the box that window is a chance of a man more on a snap, so the sharpest staff in the league loaded the box on one snap in eight against a team that only ran.

**Change:** the box gets its own window, `adaptation.boxWindow`, 0.12 → 0.6. The pass and blitz windows are unchanged.

**Measured** (yards a carry a staff rated 100 takes off a run-90% offence, against one rated 0, 300 games each, three matchups):

| Box window | 0.12 | 0.4 | 0.6 |
|---|---|---|---|
| Matchup 1 | −0.02 | 0.08 | 0.19 |
| Matchup 2 | 0.06 | 0.22 | 0.34 |
| Matchup 3 | −0.03 | 0.15 | 0.26 |

**Effect on the league:** most offences sit near the neutral pass rate, so the league barely moves. On league 2026: carries 27.1 → 27.3, attempts 34.6 → 34.3, yards 351 → 350, YPC 4.42 → 4.42, third down 0.380 → 0.383. All 23 bands pass on all six leagues: attempts 34.0–34.7, yards 349–353, points 21.7–22.2.

## Pass 13 — October 2026 (the shape of a catch; tried, not shipped)

**Measured:** 40,000 snaps called by the play caller from first and ten at a club's own 30, across 40 matchups.

| | Sim | NFL (approx.) |
|---|---|---|
| Completion: median, 90th percentile | 8, 20 | ~8, ~20+ |
| Completions of 20+ | 10.2% | ~15% |
| Yards after the catch: median, mean, 90th percentile | **0**, 3.4, 4 | ~3, ~5, ~11 |
| Runs: median, 20+ | 4, 2.2% | ~3, ~2.7% |

**The run game is close; the catch isn't.** `yacScale` (0.35) shrinks an ordinary catch's run to under a yard. Every long catch-and-run comes from a breakaway roll, with nothing between them.

**Tried:** two searches of 50 candidates each, scored on the bands plus the catch's shape:
- ordinary YAC scale 0.8–1.0 and mean 3.5–6;
- shorter routes, fewer breakaways;
- the run game.

**Results** (league 2026, 2,000 games):

| | Now | Best in band | Best for long plays |
|---|---|---|---|
| YAC median / mean | 0 / 3.4 | 2.7 / 5.2 | 3.7 / 5.7 |
| Plays of 20+ | 2.64 | 2.90 | 3.96 |
| Plays of 40+ | 0.33 | 0.27 | 0.31 |
| Yards per team | 350 | 359 | **395** |
| Bands out | none | none | five (yards, YPA, points, passing TDs, red zone) |

**Why it stops there:**
- A realistic catch only fits the yards band with shorter throws, and shorter throws take the deep catches under 20 yards.
- Keeping throws deep reaches the NFL's long plays, but at 370–395 yards a team, with red-zone touchdowns and points out of band.
- The NFL gets about 3.5 plays of 20+ on about 330 yards. The sim's ordinary plays gain too much for the long ones to fit on top.

**Not shipped:** the best in-band candidate traded a fifth of the 40-yard plays for its realistic catch.

**Next time:** make ordinary plays less productive, not long plays more common:
- more short throws defended;
- more tackles at the catch on underneath routes;
- more runs stopped near the line.

Then add the realistic catch on top. This is a structural change to how plays resolve, not a retune.

## Pass 14 — October 2026 (a head coach's discipline)

**What changed:** the head coach's `discipline` now scales his side's flags (SPEC 5.8): false starts and holding on offence, offside and pass interference on defence. The scale is 1 + 0.5 × (65 − discipline) / 100, centred on the generated coaching mean, so a league's coaching as a whole flags at the old rate. A coach rated 30 draws about 17% more flags, and one rated 100 about 17% fewer.

**Measured:** six leagues, 2,000 games each, with the effect off (`coachDisciplineScale` 0, as before) and on:

| League | Bands, off / on | Penalties per team per game, off → on |
|---|---|---|
| 2026 | 23 / 23 | 6.35 → 6.41 |
| 7 | 23 / 23 | 6.19 → 6.18 |
| 99 | 23 / 23 | 6.29 → 6.19 |
| 11 | 23 / 23 | 6.43 → 6.48 |
| 12 | 23 / 23 | 6.30 → 6.29 |
| 13 | 23 / 23 | 6.38 → 6.46 |

**Result:** the league moves with its coaches' average discipline, between −0.09 and +0.08 a team, and averages 6.32 → 6.34. No band moves.

## Pass 15 — October 2026 (every coach rating has a job)

**What changed:** each coordinator's game plan is an edge in rating points on his side's snaps (a pass's separation, a run's blocking): 6 per 100 points over the coaching mean of 65, with the other side's coordinator's taken off it. The special teams coordinators' game plans move returns by 6 yards per 100 points between them. Motivation and evaluation work between games (form and scouting), so a single game doesn't see them.

**Measured:** six leagues, 2,000 games each, game plans and returns off and on:

| League | Bands off / on | Points per team | Yards per team |
|---|---|---|---|
| 2026 | 23 / 23 | 21.89 → 21.68 | 348.8 → 347.5 |
| 7 | 23 / 23 | 21.85 → 21.85 | 347.8 → 349.9 |
| 99 | 23 / 23 | 21.88 → 22.05 | 352.5 → 352.0 |
| 11 | 23 / 23 | 22.03 → 21.96 | 350.7 → 350.6 |
| 12 | 23 / 23 | 21.75 → 21.96 | 349.0 → 350.2 |
| 13 | 23 / 23 | 21.85 → 21.63 | 349.9 → 349.9 |

**One coordinator's worth:** 60,000 snaps from one matchup against an average opposite number. An offensive coordinator at 30 gets 5.54 yards a snap, one at 65 gets 5.67, and one at 100 gets 5.80: about ±0.13 a snap, or 8 yards a game.

**Result:** no band moves, and every league stays within about 0.2 points and 2 yards of its old figures.

## Pass 16 — October 2026 (the carousel hires for the job)

**What changed:** AI clubs read a coaching candidate the way the user's pool does (`Staffing.worth`): a head coach by his ratings other than game plan, a coordinator by his game plan. A new head coach's own coordinator is now the best of three from his tree, like the other side's, rather than the first one drawn.

**Why both:** reading coordinators by game plan alone made the side that chooses pull away from the side that takes whoever comes. After ten seasons, defensive coordinators averaged a 72.9 game plan and offensive coordinators 62.2, which is an edge for every defence on every snap. `main` already leaned that way (66.4 against 61.3) once game plans counted (pass 15).

**Measured:** six leagues, ten seasons each, AI clubs' staffs at the end:

| | main | Reading by game plan only | Shipped |
|---|---|---|---|
| Offensive coordinators' game plan | 61.3 | 62.2 | 75.5 |
| Defensive coordinators' game plan | 66.4 | 72.9 | 73.3 |
| Head coaches (worth) | 66.3 | 66.6 | 66.9 |
| Year-to-year correlation of wins | 0.35 | 0.37 | 0.34 |
| Coaching changes per offseason | 5.43 | 5.47 | 5.58 |

**Result:** both sides' game plans rise together and cancel, within about 2 points (an eighth of a rating point on a snap). Head coaches, and the development they bring, hold where they were. The per-game bands are measured on new leagues before any carousel, so they don't move. Clubs that hire well now get coordinators worth hiring, and the user's club has to as well.

## Pass 17 — October 2026 (coordinators promoted to head coach)

**What changed:** a club hiring a head coach now looks at other clubs' offensive and defensive coordinators too, since under the NFL's anti-tampering policy no club can block a promotion. The club that loses one hires the best of three replacements. `poachLook` sets how many coordinators a club looks at.

**Target:** in recent NFL hiring cycles, roughly 60-70% of new head coaches were coordinators (about 5 of 8 in 2024, 5 of 7 in 2025).

**Measured:** six leagues, ten seasons each:

| | main | poachLook 3 | 6 | **9 (shipped)** |
|---|---|---|---|---|
| Promoted coordinators, share of new head coaches | 0% | 35% | 53% | **59%** |
| Promotions per offseason | 0 | 1.97 | 2.85 | 3.17 |
| Coaching changes per offseason | 5.58 | 5.57 | 5.42 | 5.38 |
| Year-to-year correlation of wins | 0.34 | 0.35 | 0.33 | 0.30 |
| AI coordinators' game plans (offence / defence) | 75.5 / 73.3 | 74.0 / 75.0 | 75.7 / 75.0 | 73.9 / 74.1 |
| Head coaches' development | 66.8 | 67.8 | 70.2 | 71.1 |

**Talent, main against shipped** (six leagues, after ten seasons; this probe's own count, so compare it with itself): 15.8 against 16.5 players at 90+ a league, and 77.47 against 77.56 for the mean of each club's top 22.

**Re-measured after the shortlists became per-man draws** (the fix that keeps an agreement the user made from reshuffling every club's spring): 55% of new head coaches are promoted coordinators (3.17 a spring), coordinators' game plans 74.7 / 74.3, head coaches' development 69.0, year-to-year correlation of wins 0.32, coaching changes 5.75 a spring. Talent: 13.3 players at 90+ a league (15.8 on main, a count that moves by a few between runs), and a top-22 mean of 77.33 (77.47). The user's club lost a coordinator 11 times in 60 offseasons.

**Result:** the head coaching market looks like the NFL's, the two sides of the ball stay level, and the league's talent doesn't move. Head coaches develop players better, because a coordinator is hired on ratings he already has rather than drawn below the mean; each player's coaching blends that with his position coach's, which holds where it was. The user's club lost a coordinator to a promotion 8 times in 60 offseasons. The per-game bands are measured before any carousel and don't move.

## Pass 18 — October 2026 (coaches age and retire)

**What changed:** every coach ages each spring, and a man in a job retires at an age of his own from 66 to 72. Clubs replace a retired head coach as if they had fired him, and a coordinator with the best of three. Anyone else is replaced from the generator's spread, so the league's coaching holds.

**Measured:** twelve leagues (seeds 11-22), ten seasons each:

| | main | Retirement |
|---|---|---|
| Coach retirements per spring | 0 | 11.9 |
| Staffs' mean age, oldest | 51.1, 75 | 53.6, 71 |
| Coaching changes per spring | 5.55 | 6.09 |
| Position coaches' development | 64.9 | 65.2 |
| Head coaches' development | 68.4 | 69.0 |
| Mean of each club's top 22 | 77.39 | 77.31 |
| Year-to-year correlation of wins | 0.315 | 0.29 |

The first six leagues alone gave 0.32 against 0.27 for the correlation, and the next six gave 0.31 against 0.31. The measure moves about that much between runs: a club's seasons are pairs that aren't independent.

**Result:** staffs turn over, at about one man in forty a spring. Position coaches' development stays where a new league starts it, so talent holds. About half a head coach a spring more changes hands, from retirements. No band moves.

## Pass 19 — October 2026 (coach careers)

**What changed:** a coach's ratings move with his age. He rises 1.2 a year to 45, holds to 58, and slips 0.4 a year after, with a point of noise of his own. A new coach is drawn where his age puts him on the curve, and `careerPeakLift` sets how far a prime sits above the old flat mean. Clubs may promote their own position coaches to coordinator, less an 8-point discount.

**Measured:** six leagues, twenty seasons; staffs and talent at year 20:

| | main | Lift 3 | **Lift 1.5 (shipped)** |
|---|---|---|---|
| Position coaches' development | 65.35 | 66.86 | 65.84 |
| Head coaches' development | 68.91 | 71.71 | 70.18 |
| Head coaches' discipline | 69.16 | 70.14 | 72.90 |
| Coordinators' game plans (off / def) | 75.3 / 75.0 | 78.2 / 79.6 | 75.6 / 74.9 |
| Mean of each club's top 22 | 76.60 | 76.83 | 76.48 |
| Players at 90+ a league | 14.0 | 14.2 | 10.8 |

**Lift 3 drifted:** coaching climbed about a point and a half a decade and was still rising at year 20.

**Lift 1.5 holds:**
- **Coaches:** position coaches and coordinators end within half a point of main, and head coaches' development within 1.3.
- **Talent:** the top-22 mean holds. The count at 90+ is lower, but it moved 13-17 between runs of main alone, while the steadier top-22 mean doesn't move.
- **Discipline:** head coaches' discipline ends 3.7 higher, which is about 2% fewer flags league-wide by year 20.

The per-game bands are measured on new leagues, whose coaches are generated as before, so they don't move.

## Pass 20 — October 2026 (a new league's street)

**What changed:** a new league is generated with the street an offseason leaves: 260 unsigned men, made as squad camp bodies are and spread over the positions as a 53-man roster is. Before, the first season had nobody on the street. Rosters and squads are generated exactly as before (the same hash over twelve leagues).

**Measured:** twelve leagues (seeds 11-22), the first season, every club's moves made by the league's logic:

| | main, season 1 | **Street, season 1** | main, season 2 |
|---|---|---|---|
| Points per team per game | 21.89 | 22.02 | 22.14 |
| Mean of each club's top 22 at season end | 80.590 | 80.589 | - |
| Signed off the street | 308 | 1,967 | 418 |
| Promoted from the club's own squad | 1,827 | 552 | 1,313 |
| Signed off another club's squad | 414 | 87 | 774 |
| Mean overall of the men signed or promoted | 59.9 | 59.1 | - |

**Why season one signs off the street:** a new league's squads are camp bodies at the street's own level (median 55, best 60 for both), so the best of 260 street men at a position usually beats the best of a club's 16. From the second season, squads are chosen from camp cuts (median 59) and win instead. The street itself matches the one an offseason leaves (median 55 for both; 90th percentile 60 against 58).

**Result:** who fills a reserve place in season one changes, not how good he is: the men signed are 0.8 weaker on average, and season-end strength and scoring hold. No band moves.

## Pass 21 — October 2026 (a new league's squads from camp cuts)

**What changed:** a new league's practice squads are chosen from generated camp cuts, as every later season's are from the cut to 53, instead of being generated camp bodies. The cuts the squads leave are the street. Rosters are unchanged (the same hash over twelve leagues).

**Measured:** twelve leagues (seeds 11-22), the first season, every club's moves made by the league's logic:

| | main (pass 20) | **Camp cuts** | main, season 2 |
|---|---|---|---|
| Squads' median overall | 56 | 59 | 59 |
| Squad men past two accrued seasons | 0% | 19% | 21% |
| The street's median overall | 56 | 56 | 55 |
| Promoted from the club's own squad | 552 | 1,379 | 1,313 |
| Signed off the street | 1,967 | 983 | 418 |
| Signed off another club's squad | 87 | 164 | 774 |
| Points per team per game | 22.01 | 22.07 | 22.14 |
| Mean of each club's top 22 at season end | 80.589 | 80.586 | - |
| Mean overall of the men signed or promoted | 59.1 | 59.8 | - |

**Result:** squads now come out as a later season's do, and promotions from them match season two. The street and other clubs' squads still split differently from season two: an offseason's street has almost no linemen, so clubs reach into other squads for them, and its squads carry a tail of released veterans (up to 85) worth taking. Generated cuts cover every position and have no such tail. Scoring and strength hold; no band moves.

## Pass 22 — October 2026 (game-day inactives)

**What changed:** each club dresses 47 of its 53 on game day, or 48 with eight offensive linemen; the deepest healthy men sit. The per-game calibration dresses the same way.

**Measured:** `calibrate`, 1,000 games on each of two leagues:

| | main, seed 2026 | **Inactives, 2026** | main, seed 77 | **Inactives, 77** |
|---|---|---|---|---|
| Bands passing | 23 of 23 | 23 of 23 | 23 of 23 | 23 of 23 |
| Points per team per game | 21.7 | 21.6 | 22.0 | 21.8 |
| Yards per team per game | 348 | 348 | 351 | 349 |
| Yards per carry | 4.41 | 4.42 | 4.28 | 4.31 |
| Yards per attempt | 7.12 | 7.11 | 7.25 | 7.23 |
| Sacks per team per game | 2.32 | 2.33 | 2.31 | 2.32 |
| Home win rate | 0.54 | 0.54 | 0.57 | 0.58 |
| Games decided by 3 or less | 0.22 | 0.23 | 0.24 | 0.23 |

**Result:** the men who sit are depth that seldom played: rated 42-56 in a new league, across positions (most often linebackers, receivers and edge rushers, and some third quarterbacks). Every band holds, and no figure moves more than run-to-run noise. No band moves.

## Still unmeasured

Everything on this list has been measured: season shape (pass 7), talent at the top (pass 8), and rebuilds and dynasties (pass 9).
