# NFL Dynasty Sim — Technical Specification

**Version:** 1.0
**Date:** September 5, 2026
**Owner:** Peter
**Repo:** NFLsimtext  ·  **Working title:** NFL Sim Text

---

## 0. How to read this document

This spec is written to be **executable**: every section maps to a GitHub milestone and a set of issues (see §14 and `ISSUES-SEED.md`). Sections marked **[LOCKED]** are decisions that should not change without a version bump of this document. Sections marked **[OPEN]** are decisions deferred, with the tradeoffs written down so you decide later instead of re-deriving them.

The spec lives in the repo at `docs/SPEC.md`. When you change the design, you change this file **in the same commit** as the code. That is the single most valuable habit in this whole project.

---

## 1. Product definition

### 1.1 What this is

A **text-based NFL dynasty simulator for Android**. No graphics, no play calling, no controller. You are the general manager and head of football operations. The game plays itself; you decide who is on the roster, who coaches them, what scheme they run, and how the money is spent. Results come back as drive summaries, box scores, and league news.

The design target is the way you already play: CPU-vs-CPU slow sim in CFB 26, Quick Manage franchise in The Show. The fun is in **roster construction, scheme fit, development, and the draft** — not in twitch execution. Every feature in this spec earns its place by making one of those four decisions more interesting.

### 1.2 What this is explicitly not **[LOCKED]**

- Not a physics engine. Plays resolve statistically, not spatially.
- Not a play-caller. You set tendencies and personnel; the coordinator AI calls plays.
- Not real-NFL-licensed. Fictional players, fictional names, real city/team structure (32 teams, 8 divisions). Ship with a generated league; support importing a custom roster file.
- Not online. Single-player, local save. No accounts, no server, no ads.

### 1.3 Core loop

```
Preseason  →  Regular season (18 wks)  →  Playoffs  →  Awards  →  Offseason (11 phases)  →  next year
     ↑                                                                                          │
     └──────────────────────────────────────────────────────────────────────────────────────────┘
```

At every point the player can: advance one week, advance to next event, or advance to end of phase. Everything is a state machine advance; there is no real-time anything.

### 1.4 Design pillars **[LOCKED]**

| Pillar | Meaning | Consequence in code |
|---|---|---|
| **Hidden information** | You never see true ratings. You see a scouted estimate with error bars. | Every rating read in the UI goes through a `ScoutingLens`. |
| **Scheme is a first-class citizen** | A 92-overall in the wrong scheme plays like an 84. | `effectiveRating()` never returns the raw rating. |
| **Determinism** | Same save + same seed = same result, forever. | Engine is pure. `Random` is injected, never constructed. |
| **Simulation is legible** | The player can always ask "why did that happen" and get an answer. | Every sim result carries a structured `SimLog`, not just numbers. |
| **Text is the interface** | The output is prose and tables, and it should read well. | Narrative generation is a real subsystem (§10), not an afterthought. |

---

## 2. Platform & tech stack **[LOCKED]**

| Layer | Choice | Notes |
|---|---|---|
| Language | Kotlin | JVM target 17 |
| Engine module | Pure Kotlin/JVM | **Zero Android dependencies.** Non-negotiable. |
| Android UI | Jetpack Compose (Material 3) | Text-heavy, list/table-driven |
| Build | Gradle Kotlin DSL + version catalog | `gradle/libs.versions.toml` |
| Serialization | `kotlinx.serialization` (CBOR for saves, JSON for seed data) | |
| Async | Coroutines; sim runs on `Dispatchers.Default` | |
| DI | Manual constructor injection, or Hilt if it grows | Start manual. Don't add Hilt until you feel the pain. |
| Local DB | Room — **phase 2 only** (see §9) | v1 uses a single serialized save |
| Testing | JUnit 5, `kotlin.test`, Turbine for flows | |
| Min SDK | 26 (Android 8.0) | ~98% device coverage, gives you `java.time` |
| Compile SDK | 37 | |
| Target SDK | 36 minimum (Play requirement as of Aug 31, 2026); 37 preferred | |

### 2.1 Version pinning

Put every version in `gradle/libs.versions.toml`. As of this writing the current stable line is roughly:

```toml
[versions]
kotlin = "2.4.0"
agp = "9.4.0"          # requires Gradle 9.6.0
gradle = "9.6.0"
composeBom = "<latest>" # check developer.android.com; BOM pins all Compose libs together
kotlinxSerialization = "1.9.x"
coroutines = "1.10.x"
junit5 = "5.12.x"
```

**Check these before you type them.** Toolchain versions move every 6–8 weeks and a mismatched AGP/Gradle pair is the single most common "why won't it build" for new Android projects. Android Studio's own new-project wizard will pick a compatible set for you — start from that, then edit.

### 2.2 Why the engine is a separate pure-JVM module **[LOCKED]**

This is the most important architectural decision in the document:

1. **Test speed.** JVM unit tests run in milliseconds. Android instrumented tests take minutes. You will run the sim engine's tests thousands of times.
2. **Calibration harness.** You can write a `main()` that sims 10,000 seasons in a terminal and prints stat distributions. Impossible if the engine imports `android.*`.
3. **Reuse.** The college football sim you mentioned later reuses `:engine` wholesale — a football game is a football game. The draft-class handoff becomes a function call, not an export format.
4. **AI-assisted development.** An agent can iterate on a pure module with fast tests. It cannot meaningfully iterate on Android UI without a device.

**Enforcement rule:** if you ever find yourself typing `import android.` inside `:engine`, stop. The thing you want belongs in `:app`.

---

## 3. Module structure **[LOCKED]**

```
NFLsimtext/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── docs/
│   ├── SPEC.md              ← this file
│   ├── DECISIONS.md         ← architecture decision log (§15)
│   └── CALIBRATION.md       ← target stat bands and current results
├── engine/                  ← Kotlin/JVM library. No Android.
│   └── src/main/kotlin/com/nflsim/engine/
│       ├── model/           ← data classes only, no logic
│       ├── rng/             ← seeded, splittable RNG
│       ├── ratings/         ← effective ratings, scheme fit, fatigue
│       ├── sim/             ← play resolution, drive, game
│       ├── season/          ← schedule, standings, playoffs, awards
│       ├── offseason/       ← the 11-phase machine
│       ├── gm/              ← AI team decision-making
│       ├── gen/             ← league gen, draft class gen, name gen
│       ├── narrative/       ← text generation
│       └── tuning/          ← the coefficient table (sliders)
├── engine-cli/              ← JVM app: batch sim + calibration reports
├── data/                    ← save/load, seed JSON, schema migration
└── app/                     ← Android + Compose
    └── src/main/kotlin/com/nflsim/app/
        ├── ui/<screen>/     ← one package per screen: Screen + ViewModel + State
        ├── format/          ← number/date/name formatting
        └── di/
```

**Dependency direction (never violated):**
`app → data → engine`, and `engine-cli → engine`. Nothing depends on `app`. `engine` depends on nothing but the Kotlin stdlib, coroutines, and serialization.

---

## 4. Data model

All model types are **immutable Kotlin `data class`es** with `val` only. State transitions return new objects. This is what makes save/load, undo, and determinism trivial.

### 4.1 Identity

```kotlin
@JvmInline value class PlayerId(val v: Int)
@JvmInline value class TeamId(val v: Int)
@JvmInline value class CoachId(val v: Int)
@JvmInline value class GameId(val v: Int)
```

Integer IDs, not UUIDs. They serialize small, compare fast, and index into arrays.

### 4.2 Player

```kotlin
data class Player(
    val id: PlayerId,
    val firstName: String,
    val lastName: String,
    val position: Position,
    val archetype: Archetype,
    val birthYear: Int,
    val heightIn: Int,
    val weightLb: Int,
    val college: String,
    val draft: DraftInfo?,          // null = undrafted
    val ratings: Ratings,           // TRUE ratings. Never shown raw to the user.
    val traits: HiddenTraits,       // TRUE traits. Never shown raw to the user.
    val contract: Contract?,        // null = free agent
    val teamId: TeamId?,
    val status: PlayerStatus,       // ACTIVE, IR, PUP, SUSPENDED, RETIRED, FREE_AGENT
    val injury: Injury?,
    val fatigue: Int,               // 0..100, resets weekly
    val morale: Int,                // 0..100
    val yearsInSystem: Int,         // scheme familiarity
    val accruedSeasons: Int,        // for FA eligibility
    val careerStats: CareerStats,
    val awards: List<AwardRecord>,
)
```

### 4.3 Positions & archetypes

**Positions:** QB, RB, FB, WR, TE, LT, LG, C, RG, RT, EDGE, DT, LB, CB, S, K, P, LS

**Archetypes** (drives scheme fit and progression curve — this is the heart of "scheme chemistry"):

| Pos | Archetypes |
|---|---|
| QB | Pocket Passer, Field General, Improviser, Dual-Threat, Gunslinger |
| RB | Power Back, One-Cut Zone, Elusive, Receiving Back, Workhorse |
| WR | X (Contested), Z (Route Tech), Slot, Deep Threat, YAC |
| TE | Inline Blocker, Move TE, Receiving TE, H-Back |
| OL | Zone Blocker, Power Mauler, Pass Protector, Athletic Pulling |
| EDGE | Speed Rusher, Power Rusher, Run-Stopping, Coverage OLB |
| DT | 1-Tech Nose, 3-Tech Penetrator, 5-Tech 2-Gap, Interior Rusher |
| LB | Field General MLB, Coverage LB, Blitzing LB, Thumper |
| CB | Man Press, Zone, Slot, Ballhawk |
| S | Free Safety, Strong Safety, Box Safety, Hybrid/Nickel |

### 4.4 Ratings **[LOCKED — 0..99 scale]**

Stored as a flat `IntArray`-backed class or a data class of `Int`s. Use a data class for clarity; performance is fine at this scale.

**Universal:** `speed, acceleration, agility, strength, jumping, stamina, injuryResist, toughness, awareness, playRecognition, discipline`

**QB:** `throwPower, throwAccShort, throwAccMid, throwAccDeep, throwOnRun, throwUnderPressure, breakSack, playAction, scrambling`

**Ball carrier:** `carrying, ballSecurity, breakTackle, trucking, elusiveness, jukeMove, spinMove, stiffArm, vision`

**Receiving:** `catching, catchInTraffic, spectacularCatch, routeShort, routeMid, routeDeep, release`

**Blocking:** `runBlock, passBlock, impactBlock, leadBlock, runBlockPower, runBlockFinesse, passBlockPower, passBlockFinesse`

**Defense:** `powerMoves, finesseMoves, blockShedding, pursuit, tackle, hitPower, manCoverage, zoneCoverage, press`

**Kicking:** `kickPower, kickAccuracy, puntPower, puntAccuracy`

**Overall** is *derived*, never stored: `overall(player, scheme?) = weightedSum(ratings, positionWeights[archetype])`. Two consequences you want: overall changes when the scheme changes, and there is no "overall" to accidentally use as a shortcut in sim logic.

### 4.5 Hidden traits **[LOCKED]**

Never shown as a number. Revealed gradually through scouting, practice reports, and game evidence.

```kotlin
data class HiddenTraits(
    val developmentCurve: DevCurve,   // SLOW, NORMAL, QUICK, SUPERSTAR, X_FACTOR
    val peakAgeOffset: Int,           // -3..+3 vs position baseline
    val workEthic: Int,               // 0..100 — multiplies offseason gains
    val footballIq: Int,              // gates awareness/PR growth
    val coachability: Int,            // how much coach quality matters for this guy
    val schemeVersatility: Int,       // penalty size when out of scheme
    val injuryProneness: Int,         // separate from injuryResist (recurrence)
    val clutch: Int,                  // 4th quarter / one-score modifier
    val bigGame: Int,                 // playoff / primetime modifier
    val consistency: Int,             // variance of per-game performance
    val ego: Int,                     // locker room + contract demands
    val loyalty: Int,                 // hometown discount likelihood
    val penaltyProne: Int,
    val durabilityUnderLoad: Int,     // injury risk vs snap count
)
```

### 4.6 Scouting model **[LOCKED]**

```kotlin
class ScoutingLens(private val confidence: Float /* 0..1 */, private val rng: Rng) {
    fun view(trueRating: Int): RatingView  // returns a band, e.g. 78–86, plus a point estimate
}
```

- `confidence` starts low for draft prospects and rises with scouting spend, combine/pro-day events, and interviews.
- For a player on your own roster, confidence rises with games played and practice reps; it approaches ~0.95 but **never reaches 1.0**.
- Displayed band half-width ≈ `12 * (1 - confidence)`. A fully-scouted vet shows ±1; an unscouted small-school prospect shows ±12.
- Traits display as letter grades with a `?` until confidence crosses thresholds (0.4 → range, 0.7 → grade, 0.9 → exact).
- **The bias is per-player and stable within a save.** A scout who is wrong about a guy stays wrong until new evidence arrives. Store a per-player `scoutingBias` sampled once at generation.

### 4.7 Team, staff, scheme

```kotlin
data class Team(
    val id: TeamId, val city: String, val nickname: String, val abbrev: String,
    val conference: Conference, val division: Division,
    val roster: List<PlayerId>,
    val depthChart: DepthChart,
    val staff: Staff,
    val finances: TeamFinances,
    val facilities: Facilities,       // training, medical, scouting — investable
    val cultureRating: Int,           // affects FA appeal
    val marketSize: Int,
    val stadium: Stadium,             // dome/outdoor, altitude, crowd noise
)

data class Staff(
    val headCoach: CoachId,
    val offCoordinator: CoachId,
    val defCoordinator: CoachId,
    val stCoordinator: CoachId,
    val positionCoaches: Map<PositionGroup, CoachId>,
    val scoutingDept: Int,            // 0..100
    val trainingStaff: Int,
    val medicalStaff: Int,
)

data class Coach(
    val id: CoachId, val name: String, val age: Int,
    val role: CoachRole,
    val scheme: SchemeId,             // what he runs
    val tendencies: Tendencies,       // §5.4
    val ratings: CoachRatings,        // development, gameplan, adjustments, discipline, motivation, evaluation
    val tree: CoachId?,               // mentor, for coaching-tree flavor
    val hotSeat: Int,
    val contractYearsLeft: Int,
)
```

### 4.8 Schemes **[LOCKED — data-driven, not hardcoded]**

Schemes live in `data/src/main/resources/schemes.json`, not in Kotlin source. Adding a scheme must not require a recompile.

```json
{
  "id": "OFF_WIDE_ZONE",
  "name": "Wide Zone / Play-Action",
  "side": "OFFENSE",
  "archetypeFit": {
    "RB": { "ONE_CUT_ZONE": 1.0, "POWER_BACK": 0.6, "ELUSIVE": 0.8, "RECEIVING": 0.7 },
    "LT": { "ZONE_BLOCKER": 1.0, "ATHLETIC_PULLING": 0.9, "POWER_MAULER": 0.5 },
    "TE": { "MOVE_TE": 1.0, "INLINE_BLOCKER": 0.85, "RECEIVING_TE": 0.7 }
  },
  "ratingEmphasis": { "RB": ["vision", "acceleration"], "OL": ["runBlockFinesse", "agility"] },
  "personnelUsage": { "11": 0.55, "12": 0.30, "21": 0.10, "13": 0.05 },
  "basePassRate": 0.56,
  "playActionRate": 0.31,
  "tempo": 0.45
}
```

**Ship with at minimum:**
Offense — Wide Zone/PA, Gap-Power, Air Raid, West Coast, Spread Option, Vertical/Shot, Run-Heavy Pro.
Defense — 4-3 Over, 4-3 Under, 3-4 Two-Gap, 3-4 One-Gap, 4-2-5 Nickel, Tampa 2, Cover-3 Match, Man-Blitz/Pressure, 3-3-5 Multiple.

### 4.9 Scheme fit math **[LOCKED]**

```kotlin
fun schemeFit(player: Player, scheme: Scheme): Float =
    scheme.archetypeFit[player.position]?.get(player.archetype) ?: 0.5f

fun effectiveRating(player: Player, ratingId: RatingId, ctx: SimContext): Int {
    val base = player.ratings[ratingId]
    val fit  = schemeFit(player, ctx.scheme)
    // versatility softens the penalty for a bad fit; it never boosts a good one
    val adjFit = fit + (1f - fit) * (player.traits.schemeVersatility / 500f)
    val schemeMod = 0.76f + 0.24f * adjFit            // 0.76 .. 1.00
    val familiarity = 0.96f + 0.04f * min(player.yearsInSystem, 3) / 3f
    val emphasis = if (ratingId in ctx.scheme.emphasized(player.position)) 1.03f else 1.0f
    val fatigueMod = 1f - fatiguePenalty(player, ctx)  // 0 .. ~0.15
    val moraleMod = 0.98f + 0.04f * (player.morale / 100f)
    return (base * schemeMod * familiarity * emphasis * fatigueMod * moraleMod)
        .roundToInt().coerceIn(1, 99)
}
```

A perfect-fit veteran in year 3 plays ~+1% over raw before emphasis, a badly miscast one (fit 0.40) about -12%. On an 85 base that is roughly 86 against 75 — an eleven-point swing, so scheme can beat talent. Tuned Sept 2026 against the `schemefit` report; these constants move to `TuningTable` at M4.

### 4.10 League state (the save)

```kotlin
data class League(
    val saveVersion: Int,
    val seed: Long,
    val year: Int,
    val phase: LeaguePhase,
    val week: Int,
    val teams: Map<TeamId, Team>,
    val players: Map<PlayerId, Player>,
    val coaches: Map<CoachId, Coach>,
    val freeAgents: List<PlayerId>,
    val schedule: Schedule,
    val standings: Standings,
    val draftBoard: DraftBoard?,
    val transactions: List<Transaction>,   // append-only ledger
    val history: LeagueHistory,            // per-season aggregates, no play data
    val tuning: TuningTable,               // §12
    val userTeamId: TeamId,
)
```

The `League` object **is** the save file. Nothing else needs to persist.

---

## 5. Simulation engine

### 5.1 Fidelity level **[LOCKED]**

**Play-level, statistically resolved.** Every snap is simulated as a discrete event with a play call, a matchup computation, and a sampled outcome. There is no field geometry, no player coordinates, no ticks.

Why not drive-level (faster) or spatial (more realistic):
- Drive-level can't produce believable individual stat lines, and individual stats are the whole point of a dynasty game.
- Spatial is a 12-month project on its own and adds nothing to a text game.
- Play-level gives you real box scores, real play-by-play text, and sims a full game in ~3–8 ms. A 272-game regular season week sims in under a second.

### 5.2 Call hierarchy

```
simSeason → simWeek → simGame → simDrive → simPlay
```

Each is a pure function `(State, Rng) -> Result`. `simPlay` is the only one that consumes randomness in bulk.

### 5.3 The play resolution pipeline **[LOCKED]**

```kotlin
fun simPlay(s: PlayState, rng: Rng): PlayResult {
    val offCall = offensiveCoordinator.call(s, rng)      // 5.4
    val defCall = defensiveCoordinator.call(s, rng)      // 5.4
    val personnel = resolvePersonnel(s, offCall, defCall) // 5.5
    val matchups = computeMatchups(personnel, offCall, defCall, s) // 5.6
    val outcome = resolveOutcome(offCall, defCall, matchups, s, rng) // 5.7
    val penalty = checkPenalty(personnel, outcome, s, rng)  // 5.8
    val injury = checkInjury(personnel, outcome, s, rng)    // 5.9
    return PlayResult(
        outcome, penalty, injury,
        clockRunoff(outcome, s),
        log = buildLog(s, offCall, defCall, matchups, outcome),
    )
}
```

Every stage is independently testable. This structure is deliberately verbose so that when a stat comes out wrong you can bisect *which stage* is wrong.

### 5.4 Coordinator AI — tendencies

Coordinators are the game's personality. A `Tendencies` object is a set of conditional distributions:

```kotlin
data class Tendencies(
    val basePassRate: Float,
    val passRateByDown: Map<Int, Float>,           // 1st..4th
    val passRateByDistance: DistanceCurve,          // short/med/long to go
    val passRateByScoreDiff: ScoreCurve,            // trailing → more pass
    val redZonePassRate: Float,
    val goalLinePassRate: Float,
    val twoMinuteAggression: Float,
    val fourthDownAggression: Float,                // 0 = punt always, 1 = analytics-brained
    val playActionRate: Float,
    val screenRate: Float, val deepShotRate: Float,
    val runDirectionBias: Map<RunGap, Float>,
    val tempo: Float,                               // affects plays/game and defensive fatigue
    val blitzRate: Float, val blitzRateByDown: Map<Int, Float>,
    val manZoneSplit: Float,                        // 0 = all zone, 1 = all man
    val lightBoxRate: Float, val heavyBoxRate: Float,
    val doubleTeamThreshold: Int,                   // shade coverage at an opposing OVR
)
```

**Adaptation.** Each game, both coordinators track the opponent's realized tendencies and shift within a bounded window (±0.12) based on the HC's `adjustments` rating. A predictable coordinator gets punished. This is where your CFB slider-tuning instincts translate directly.

**User control.** You do not call plays; you set your coordinators' `Tendencies` sliders in the game plan screen, plus a per-opponent weekly game plan (§10.4). That is the strategic layer.

### 5.5 Personnel & depth chart

- Depth chart is per-position with **package overrides**: base, nickel, dime, goal line, 3-WR, heavy, punt/FG/KR/PR.
- Snap distribution follows the depth chart weighted by fatigue and package usage. A back-up RB in a `rbRotation` split of 65/25/10 actually gets those carries.
- Fatigue accumulates per snap, weighted by position (OL/DL fatigue faster), `stamina`, and tempo. Recovery between drives and at halftime.

### 5.6 Matchup computation

For a pass play:

```
protection = Σ effectiveRating(OL, passBlock*) + rbBlockHelp + teChipHelp
             − Σ effectiveRating(pass rushers, powerMoves/finesseMoves)
             − blitzPressureBonus(defCall, blockers vs rushers count)

routeWin[i]  = effectiveRating(WR_i, routeX + release)
             − effectiveRating(defender_i, manCoverage or zoneCoverage)
             + separationBonus(scheme, coverage, alignment)
             + (defCall.isDoubled(WR_i) ? -14 : 0)
```

For a run play:

```
frontAdvantage = Σ effectiveRating(blockers at point of attack, runBlock*)
               − Σ effectiveRating(defenders in box at gap, blockShedding + strength)
               + gapSchemeBonus(offScheme, defFront)   ← the 3-4/4-3/4-2-5 interaction
               − boxCountPenalty(defenders in box vs blockers)
```

`gapSchemeBonus` is a small lookup table (offensive run concept × defensive front). It is the mechanical expression of your scheme-matchup knowledge, and it should be exposed in `TuningTable` so you can tweak it without recompiling.

### 5.7 Outcome sampling

Convert an advantage number into a distribution, then sample:

```kotlin
// Pass example
val pressure = logistic(-protection / K_PRESSURE)              // P(pressure)
val sackGiven = pressure * (1 - qb.breakSack/180f)
val throwQuality = logistic((qb.accForDepth(depth) + routeWin - coverageQuality) / K_THROW)
val completion = throwQuality *
    (if (pressured) qb.throwUnderPressure / 99f * 0.85f else 1f)
val yardsAfterCatch = gamma(shape = f(wr.elusiveness, coverage), scale = g(depth, tacklingQuality))
```

**Rules for this section:**
1. Every constant (`K_PRESSURE`, `K_THROW`, …) lives in `TuningTable`, never as a literal in the function.
2. Yardage uses **skewed distributions** (gamma/log-normal), never uniform. Real football yardage is long-tailed: most runs go 2–5 yards, a few go 60.
3. Clamp nothing silently. If a value goes out of range, that is a bug — assert in debug builds.

### 5.8 Penalties

Sampled per play from a base rate modified by `discipline`, `penaltyProne`, coach `discipline`, home/road, and play type (holding on pass pro, false start on the road with crowd noise, DPI on deep routes contested by a low-`manCoverage` defender). Target ~6.2 accepted penalties per team per game.

### 5.9 Injuries

Two-stage: (1) does an injury event occur on this play, (2) how severe.

- Base rate per snap, modified by position, play type (higher on runs and sacks), `injuryResist`, `injuryProneness`, `durabilityUnderLoad` × recent snap load, and the team's `medicalStaff`.
- Severity draws from a table: 0 (shake it off) → weeks-out → season-ending → career-ending.
- Recurrence: a previously injured body part carries an elevated multiplier that decays over ~2 seasons.
- Target: ~1.8 injuries per team-game causing at least one missed game somewhere in the league per week ≈ 40–60 players on IR league-wide by week 12.

### 5.9a In-season form

Every player carries `form`, -100 to 100, reset each season. After each game
it moves on what he did against what his position is judged against - passer
rating, yards a carry, yards a target, a defender's stops with sacks and
takeaways worth more - weighted by how much of the day he had, then decays
(72% kept a week, 45% of a week's surprise taken in). Positions a box score
does not measure (the line, the specialists) carry none. Form is worth up to
4 rating points and is read in one place: the copy of the player that dresses
for the game, whose ratings are shifted by it. The depth chart and the play
resolution see it; valuations, scouting and draft boards read his real
ratings. Weights live in `TuningTable.form`.

### 5.9b Tackle credit

One man is credited with each tackle, by position and by how far the play
went (`TuningTable.tackling`); a quarter of tackles (`assistShare`) have a
second man in, credited an assist. `tackles` is the man who made the stop,
and combined tackles - what a leaderboard, a box score and a career sheet
show - are the two together.

### 5.10 Special teams, clock, weather

- FG success = f(distance, `kickPower`, `kickAccuracy`, wind, precipitation, altitude, snap/hold quality, pressure/`clutch`).
- Clock model: 40-second play clock, runoff by play type and outcome, out-of-bounds rules, two-minute warning, timeouts. **Get the clock right early** — bad clock logic produces 45-point games and it is miserable to retrofit.
- Weather generated per game from stadium + month + a regional climate table. Dome = neutral. Affects deep passing, kicking, and fumble rate.

### 5.11 Determinism **[LOCKED]**

```kotlin
interface Rng {
    fun nextInt(bound: Int): Int
    fun nextFloat(): Float
    fun split(label: String): Rng   // derives a child stream deterministically
}
```

- One root RNG seeded from `League.seed`.
- Each game splits a child stream from `(seed, year, week, gameId)`. This means **re-simming week 7 game 3 alone reproduces exactly the same game**, regardless of what else happened.
- Never call `Math.random()`, `Random()`, `System.currentTimeMillis()`, or `UUID.randomUUID()` anywhere in `:engine`. Add a lint/CI grep that fails the build on those strings. (See §13.4.)

---

## 6. Season structure

- 32 teams, 2 conferences, 8 divisions of 4.
- 18-week regular season, 17 games, 1 bye per team.
- Schedule generation: 6 division games (home/away), 4 vs a rotating same-conference division, 4 vs a rotating other-conference division, 2 vs same-place finishers, 1 rotating 17th game. Assign to weeks with constraints (bye between weeks 5–14, no 3 straight road, primetime allocation by market/record).
- Playoffs: 7 seeds per conference, #1 bye, standard reseeding.
- Tiebreakers: implement the full NFL cascade (H2H, division record, common games, conference record, strength of victory, strength of schedule, then coin flip from the RNG). Write this as a testable list of predicates — it is a classic source of "why did my 11-6 team miss."
- Awards: MVP, OPOY, DPOY, OROY, DROY, CPOY, Coach of the Year, All-Pro 1st/2nd, Pro Bowl. Voting = a weighted score function over stats + team success + narrative bonuses, with deliberate voter noise so it isn't purely mechanical.

### 6.1 In-season roster

- **Injured reserve.** A man out 4 weeks or more goes on IR at every club:
  still paid, off the 53, and not dressed unless the club has nobody else at
  his position. Healed, he comes back when there is a place for him.
- **Practice squads.** Sixteen per club, no more than six men past two
  accrued seasons, no more than three at a position. A squad player has no
  team on his record: he is a free agent his club trains, so any club may
  sign him to its 53. Squads dissolve each spring and are chosen again after
  the cut to 53, the league snaking through them.
- **The league's clubs** fill a place reserve opens at the position they are
  thinnest - their own squad first, the street second, another club's squad
  only for a man better than both by 8 rating points (`ai.poachClearUpgrade`)
  - top the squad back up, and cut the stopgap when the man returns. A club
  below the 46 it dresses with no cap room tears up the contract that saves
  the most per point of what the man gives.
- **The user's club** makes its own moves on the Free agents screen: sign off
  the street or another club's squad, promote or release squad men, release
  from the 53, bring a healed man back. "Let the front office fill injured
  places" hands them to the league's logic, and is saved with the dynasty.
  The hub says when the 53 has open places and when the squad is short, and
  the news when another club signs a man off his squad.
- **Signing in season** costs the minimum prorated by the weeks left, one
  eighteenth a game (CBA Article 26): one year, all base salary, nothing
  guaranteed.

---

## 7. Offseason — the phase machine **[LOCKED]**

The offseason is an explicit enum. Each phase has an `advance()` that returns a new `League` plus a list of `NewsEvent`s.

| # | Phase | What happens |
|---|---|---|
| 1 | `POST_SEASON_AWARDS` | Awards, All-Pro, retirements announced |
| 2 | `COACHING_CARROUSEL` | Firings, HC/OC/DC hires, coordinator poaching, scheme changes cascade |
| 3 | `RETIREMENTS` | Age + decline + contract + `loyalty` driven; stars can hang on |
| 4 | `CONTRACT_DECISIONS` | Team options, restructures, cuts (pre/post June 1), cap compliance deadline |
| 5 | `FRANCHISE_TAG` | Tag/transition window |
| 6 | `RE_SIGNING` | Exclusive negotiating window with your own pending FAs |
| 7 | `FREE_AGENCY` | Multi-day auction (§8.3) |
| 8 | `PRE_DRAFT` | Combine, pro days, interviews, scouting spend, trade-up/down talks |
| 9 | `DRAFT` | 7 rounds + compensatory picks, live trade offers |
| 10 | `UDFA` | Priority free agent scramble |
| 11 | `OTA_CAMP` | Progression/regression, position battles, scheme installation, preseason injuries, cut to 53 |

Then `PRESEASON → REGULAR_SEASON`.

**Rule:** a phase can only be advanced when its blocking conditions are met (e.g. you cannot leave `CONTRACT_DECISIONS` while over the cap). The UI shows the blocking condition as a to-do list.

**The user's club decides its own offseason.** Every decision below is the
user's first, with the league's own logic available on request - "Let the
front office decide", "bid", "fill and cut", "Let the scouts pick" -
running his club exactly as it runs every other. The offseason stops at
each decision (`ContractsPause`, `FreeAgencyPause`, the draft room's
`DraftPause`, `CutdownPause`); the pauses live in memory and are not saved
half-finished (§10.3).

**Expiring contracts (phases 5-6, the user's club).** Each expiring man is
shown with his market, what he asks his own club for and for how long,
anything he has said he wants, and the franchise tender. Nothing is
decided until the user decides it: re-sign, franchise tag, transition tag
(one tag, as the CBA allows) or let him go. Each shows a recommendation
and its reason, judged on what he is to this club - whether he starts,
whether his ask is within 8% of his market, whether he is under the age a
club pays through (31), whether it fits - taken down the list most
valuable first, so the one tag goes where it suits and the room runs out
where it would. The front office's own call is shown beside it where they
differ. A re-signing can be written any of the ways §8.3 lists. The
blocking condition is that every man is decided, or the undecided are let
go to market.

**Free agency (phase 7, the user's club).** Before the ten days the user
may talk to any free agent's agent (§8.3) and puts standing offers on
whoever he wants - under, at or over the market, for one to five years.
An offer is bid every day the man is unsigned and the club can still pay
it, each counting against the room for the others, and competes under the
auction's own rules. Each man shows advice: his market if he would start
for this club and it fits, one year if he is past the age a club pays
through, pass on anyone who would sit. The draft room opens with who
signed, who went where, and who is still waiting.

**Camp and the cut to 53 (phases 10-11, the user's club).** The offseason
stops after the draft (`CutdownPause`) and hands the user his camp roster
and the street - undrafted men and veterans nobody signed. He releases
whoever he wants, each at the dead money his contract says, and signs off
the street at a year of the minimum. The blocking condition is the roster
size: he cannot leave camp with more than 53 or fewer than 46, the most a
club dresses, and the screen says which he is short of. A position every
club must field (QB, K, P, LS) left empty is filled off the street for him.
Suggested cuts and signings are the league's own fill and cut run on his
roster alone; "Let the front office fill and cut" hands camp to that logic
exactly as it runs every other club.

### 7.1 Progression & regression

Run once per player at `OTA_CAMP`:

```kotlin
fun progress(p: Player, ctx: ProgressionContext, rng: Rng): Player {
    val ageCurve = positionCurve(p.position).valueAt(p.age + p.traits.peakAgeOffset)  // -1.0 .. +1.0
    val devMult  = p.traits.developmentCurve.multiplier                                // 0.7 .. 1.8
    val workMult = 0.75f + 0.5f * (p.traits.workEthic / 100f)
    val coachMult = 0.2975f + 2.00f * (coachDevRating(ctx, p) / 100f) *
                    (p.traits.coachability / 100f)   // 0.9475 at coaching 65, coachability 50
    val snapMult = snapExperienceCurve(p.snapsLastSeason)   // playing time drives growth
    val noise    = rng.gaussian(0f, 1.6f)

    val delta = (ageCurve * devMult * workMult * coachMult * snapMult * ctx.tuning.progressionScale) + noise
    // physical ratings decline earlier and harder than mental ones
    return p.applyDelta(physical = delta * 1.15f, mental = delta * 0.6f + mentalFloorGain(p))
}
```

Key behaviors this produces, all of which you want:
- Mental ratings (`awareness`, `playRecognition`) keep rising into the early 30s while `speed`/`acceleration` fall from ~27. Old QBs and safeties stay useful; old RBs and CBs fall off a cliff.
- A `SUPERSTAR` dev 2nd-round pick who plays 900 snaps as a rookie can jump 8–12 points. A `SLOW` dev 1st-rounder who sits jumps 2.
- Coaching quality matters most for `coachable` young players — which makes the position-coach hiring screen meaningful. The slope was 0.30 until M7 and left no visible difference between staffs; see DECISIONS.md, "The coach slope is 2.00".

**Breakout / bust events.** Small probability of a discrete jump or collapse, gated by `consistency` and `workEthic`, surfaced as news. These are the stories a dynasty game is made of.

---

## 8. Money & AI general managers

### 8.1 Salary cap **[LOCKED]**

Model it properly — the cap *is* the strategy game.

- Cap grows year-over-year by a percentage with noise (~6–8%).
- Contract = `years`, `baseSalary[]`, `signingBonus`, `guaranteedAtSigning`, `rosterBonus[]`, `optionYears`.
- **Signing bonus prorates over min(years, 5).**
- **Dead cap on release** = remaining unamortized proration + guaranteed base. **Post-June-1 designation** splits it across two years.
- Cap carryover from unused space.
- Rookie wage scale by draft slot; 5th-year option for 1st-rounders.
- Franchise tag = max(top-5 average at position, 120% of prior cap hit). Transition tag = top-10.
- Restructure = convert base to bonus, pushing cap into the future. Let the player dig their own grave.

**Implemented (M7 stage 1).** `engine/econ/MarketValue` is the single price
curve — free agency, roster generation, and release decisions all read it, so
players cannot be created at one price and signed at another.
`engine/offseason/CapManagement` grows the cap 6.8% a year, and each offseason
gets every team legal *before* the draft, so team needs reflect the roster a
team can afford. Cuts are chosen by cap hit per point of ability with a nudge
toward players past 28: the expensive veteran goes, not the cheap rookie.
Positional minimums and a 46-man floor are protected, dead money follows the
release, and half of it is carried into the next year's books.

Generated rosters are signed at creation with staggered terms (see
`RosterGenerator.sign`) targeting ~88% of the cap. Before this, generated
players had no contracts at all, so the whole league hit free agency after one
season and every offseason was an accidental redraft.

Still to do: the day-based auction in §8.3, franchise/transition tags, the
5th-year option, and cap carryover.

### 8.2 AI GM decision model

Each AI team has a `GmProfile`:

```kotlin
data class GmProfile(
    val aggression: Float,        // trade frequency, FA spending
    val winNowVsFuture: Float,    // 0 = full rebuild, 1 = all-in
    val draftPhilosophy: DraftPhilosophy, // BPA, NEED, TRAIT_HUNTER, ANALYTICS, TRENCHES_FIRST
    val positionalValue: Map<Position, Float>, // how much they pay for RB, etc.
    val riskTolerance: Float,     // injury/character red flags
    val loyaltyToOwnPlayers: Float,
    val schemeRigidity: Float,    // will they take a bad-fit talent?
)
```

`teamNeed(team, position)` = f(starter quality, depth quality, contracts expiring, age, scheme fit). Needs drive FA targets and the draft board. **The AI evaluates prospects through its own `ScoutingLens`** — AI teams miss on players too, and differently from you. That is what makes the draft feel alive.

### 8.3 Free agency auction

Day-based, not instant:

1. Every FA has a `MarketValue` (from production, age, position value, scheme fit across the league) and a private `reservationPrice`.
2. Each day, teams with cap space and need submit offers. Offers score on money, guaranteed %, years, team quality, playing time projection, `loyalty` to current team, coach/scheme fit, market/culture.
3. Players sign when an offer clears their reservation price, with top FAs holding out a few days to let the market form.
4. Prices deflate as the pool thins; bargains appear on days 5–10. Your patience becomes a real decision.

**Implemented (M8 stage 1).** `engine/offseason/FreeAgency` runs the ten days.
Asking prices open at 1.20x market and decay 4.5% a day; teams look at four
targets a day, pay a premium for positions they need, and each front office has
its own appetite until `GmProfile` (§8.2) exists. Players worth more than a
quarter of the cap hold out three days to let the market form. Whatever is
unsigned after ten days is filled at the minimum.

The auction exists for two things that only happen when teams bid against each
other: the winner's curse (a player signs with the team that values him most,
which is usually the team that is wrong about him) and need premiums (the team
without a quarterback pays more for one). Both are how a hole on the roster
becomes a hole on the cap sheet two years later.

**Implemented since.** Extensions during the season are how a contract
dispute is settled (SPEC 10.1): a man whose market has moved past his deal
asks, and his club pays him the market rate or tells him no. Restructures
run in two places - a club clears what it must on cut-down day, and the
user may move base salary into bonus on any player's card, which frees
cap this year and puts it on every year of the deal and on the dead money
if he is ever released. Proactive cap cuts exist in season: a club below
the 46 it has to dress with no room tears up the contract that saves the
most per point of what the man gives.

Haggling followed: a demand can be met at 90% or 80% of the market, and a
man below his reservation price turns it down and names his floor.
Franchise and transition tags were already built (`FranchiseTag`, with the
right to match), which an earlier version of this note got wrong.

**Every way to write a deal** (`ContractOptions`). A man names a figure for
the length he wants; a year shorter costs the club 5% more a year and a
year longer 3% less, because security is what a contract is for. Each
length is offered in three structures, written out explicitly because
this engine back-loads salary and spreads bonus evenly: *standard* (the
usual deal), *cap-light* (the minimum in salary this year, a bonus spread
over the deal - least cap now, most dead money) and *pay as you go* (flat
salary, no bonus - more cap now, no dead money beyond the guarantee). A
one-year deal is written one way only. The advice writes a kept man's
deal for no longer than his prime, cap-light when the room is tight, pay
as you go near the age line, standard otherwise. Restructures come in a
quarter, half or all of what can move, with the advice to leave it when
there is room.

**The user bids and haggles in free agency.** Standing offers are
described in §7. Before the market opens he may also make up to two
offers to a man's agent: at or above his reservation price he signs on the
spot, before anyone else can bid; under it his agent names his floor, and
after the second no he goes to market. The floor starts at 1.02 of his
market; an ego adds up to 0.10, a star worth 12% of the cap 0.06, loyalty
takes up to 0.14 off for the club he played for, a club that won 60% of
its games 0.05; never below 0.90 or above 1.25.

Still to do: the league's own clubs do not haggle - they pay the market
or refuse - and there are no incentives or guarantee terms to trade
against, only the annual figure. Matching a transition-tagged man's offer
is automatic when the club has the room, and he gets no tender if nobody
bids.

### 8.4 Trades

Value function combining a draft-pick chart (make it a tunable table, not Jimmy Johnson gospel), player surplus value (production − cap hit, adjusted for age and years of control), and team-fit modifiers. AI teams both propose and evaluate. Deadline behavior shifts based on whether they're in contention.

### 8.5 Draft class generation

- Per class: ~260 drafted + ~450 UDFA-grade prospects.
- Position distribution matched to real draft frequencies.
- True ratings drawn from a talent curve: a handful of generational players, a fat middle, a long tail.
- Traits assigned with correlations (elite `workEthic` correlates mildly with `developmentCurve`).
- Each prospect gets a `scoutingBias` and a "small school / big program" exposure factor that sets starting confidence.
- **Hook for the college sim:** `interface DraftClassSource { fun generate(year: Int, rng: Rng): List<Prospect> }`. v1 ships `SyntheticDraftClassSource`. The college sim later provides `CollegeSimDraftClassSource` with real college stats and 3–4 years of scouting history. This one interface is why the college project is a plugin, not a rewrite.

**Board depth is the point (ADR-005).** The class must be far larger than the
draft: ~507 prospects for 224 picks, so teams take the top third rather than
two thirds of the board. Selection, not the rating curve, is where a league's
talent comes from. At 350 prospects the pipeline delivered ~12 rookies a year
able to start against ~28 elite players a year lost to age, and the top of the
league drained once the founding cohort aged out - visible in the count of
players rated 80+, invisible in the league mean.

**Roster age preference (ADR-004).** Cut-to-53 and free agent signing rank on
`rosterValue()`, which discounts 2.2 points per year past 29. Without it a
declining veteran outrates a rookie every year until he is thirty-six, keeps
the roster spot, and never reaches the free agency that ends careers. Rosters
skew young because of decisions, not because players spontaneously retire.

---

## 9. Persistence

### 9.1 v1 — single serialized save **[LOCKED for v1]**

- The `League` object serializes with `kotlinx.serialization` to **CBOR**, gzipped, written to app internal storage.
- Estimated size: ~2–4 MB per save at year 1; grows ~200 KB/season with the history policy below.
- Save slots: 5 user slots + rolling autosave (keep last 3, autosave on every phase advance).
- Every save carries `saveVersion: Int`. Migration is a chain of `(n) -> (n+1)` functions in `data/migration/`. **Write the migration in the same commit as the model change.** A dynasty game that eats saves on update is a dead dynasty game.

### 9.2 History retention policy **[LOCKED]**

| Data | Retention |
|---|---|
| Play-by-play | The user's club's games, current season only |
| Box scores | Last 5 seasons full, then compressed to team totals |
| Player season stat lines | Forever (this is career stats — it's small) |
| Transactions ledger | Forever |
| Draft results, awards, standings | Forever |

*Amended Sept 2026, with the owner's sign-off:* play-by-play was "current
season only" for every game. It is kept for the user's club's games: the
league's would be about forty thousand plays a season re-encoded with every
weekly save, for games nobody opens, where the user's are 2,603 plays and
57KB (ADR "A season of the user's play-by-play"). Every other game keeps
its box score as above. The transactions ledger leaves off the cut to 53
and practice-squad formation, about a thousand camp moves a year.

### 9.4 Roster import / export **[LOCKED]**

The game ships a generated fictional league. It also reads a roster file the
user supplies — which is how someone plays with real players without the
project ever distributing real names or ratings.

**Legal position:** no real player data ships with the app, is bundled in it,
or is downloaded by it. The user brings their own file. This is the same
posture text sims have used for decades and it keeps the legal footprint at
zero.

**Format:** CSV, because the people who maintain roster files live in
spreadsheets. `RosterExporter.template()` is the documented starting point,
and whatever the exporter writes, the importer reads — round-tripping a
generated roster through a spreadsheet and back is a supported workflow and a
test asserts it is lossless.

**Three fidelity levels**, all valid:

| What the file has | What happens |
|---|---|
| `name,position` | A plausible player is generated at that position |
| `name,position,ovr` | Generated to hit that overall |
| `name,position,spd,acc,mcv,…` | The supplied numbers are used exactly as given |

**Filling gaps** — supplied ratings always win. A position-relevant rating that
is missing is filled from the mean of the ratings that *were* supplied, so a
partial sheet stays internally consistent. An irrelevant one gets a low
baseline, so an imported quarterback does not end up with 70 man coverage.

**Archetype inference** — real ratings dumps carry numbers, not labels, but
archetype drives scheme fit, which is the heart of this game. `ArchetypeInference`
reads a sheet the way a scout would: which attributes stand out relative to the
player's own average? It returns a confidence, and a low-confidence guess is
surfaced as a warning rather than hidden.

**Forgiving on input, loud about it.** Position spellings (`DE`, `OLB`, `NT`,
`FS`, `HB`, `WO`) and rating column codes (`TAS`, `MCV`, `RBK`) are aliased.
Ambiguous spellings — a bare `T` or `G` — resolve to the left side *and warn*.
An unrecognised position is an error, not a guess. Unknown columns are listed,
never silently dropped. Every import returns an `ImportReport` and the UI must
show it.

**Determinism holds.** Everything invented during an import comes from the
seed, so the same file always produces the same players.

### 9.3 v2 — Room **[OPEN]**

If save/load exceeds ~1.5 s or the file exceeds ~25 MB, move career/history tables to Room and keep only live state in the serialized blob. Don't do this speculatively. Revisit at milestone M7.

---

## 10. UI (Compose)

### 10.1 Design language

Dense, tabular, readable, dark-mode-first. Think a well-set spreadsheet with good typography, not a mobile game. Monospace or tabular-figure font for all numbers so columns align. Everything reachable in ≤2 taps from the hub.

### 10.2 Screen inventory

| Screen | Contents |
|---|---|
| **New dynasty** | From the start screen or any empty save slot. The league generated first, then every club by division to choose from: the preseason outlook (a tier - contender, playoff hopeful, middle of the pack, rebuilding - never a rating), schemes, head coach, market size, cap room. "Surprise me" takes a random club |
| **Hub** | Week/phase, next action button, top news, standings snippet, cap space, injury alerts |
| **Advance** | The single most-used control. Advance week / to next event / to end of phase |
| **Roster** | Sortable table, scouted ratings with error bars, contract, age, scheme fit badge |
| **Depth chart** | Per-package, drag to reorder, auto-sort by scheme fit, snap-share sliders |
| **Game plan** | Coordinator tendency sliders, weekly opponent plan, focus practice |
| **Player card** | Bio, scouted ratings, revealed traits, career stats, contract, injury history, news |
| **Schedule / Scores** | League-wide, filterable |
| **Game center** | Drive chart, play-by-play feed, box score, snap counts, narrative recap |
| **Standings** | Division/conference/playoff picture with tiebreak explanation |
| **League leaders** | Sortable stat leaderboards, all positions |
| **Finances** | Cap table, dead money, future years, restructure/cut tool with live cap impact |
| **Free agency** | Board, offers, negotiation, day-by-day market |
| **Expiring contracts** | Offseason: each expiring man's market and ask, a recommendation and why, every way to write a re-signing, the two tags, let him go (§7) |
| **Draft room** | Big board, your board vs consensus, needs, live picks, trade offers |
| **Camp** | Offseason: the camp roster with dead money if cut, the street to sign from, suggested cuts and why, the 46–53 bounds (§7) |
| **Trades** | Block, proposal builder with AI valuation feedback |
| **Staff** | Hire/fire, coach cards with scheme + dev ratings, coordinator tree |
| **Free agents (in season)** | The street and other clubs' practice squads to sign from, the user's squad and IR, releases with their dead money, the front office roster toggle (§6.1) |
| **Contract demands** | Demands from the user's own men: every way to pay, 90%/80% offers, refuse, or let the front office answer (§10.4) |
| **Transactions** | The league's wire by season, the user's club or everyone, filtered by kind (§4.7, §9.2) |
| **Saves** | Five slots and three autosaves: play, copy, overwrite, delete, restore, start a new dynasty in an empty slot (§9.1) |
| **History** | Champions, awards, records, franchise timeline, hall of fame |
| **Settings / Tuning** | Sliders (§12), sim speed, autosave, export |

### 10.3 State pattern

One `DynastyStore` owns the current `Dynasty`, the save slots, and the
offseason's in-memory pauses, and applies engine functions to them. Screens
are composables that read the store's Compose state and call its suspend
functions; there are no per-screen ViewModels. **No engine call happens on
the main thread:** the store runs them on `Dispatchers.Default` and writes
saves on `Dispatchers.IO`.

The store is one per process, built on the application context, so a
rotation, a theme change or a font change - which rebuild the Activity -
keeps the dynasty and any offseason pause in hand, and the screen the user
was on is kept with `rememberSaveable`. If Android kills the process in the
background, the pauses go and the offseason runs again from the save; the
pauses are too large for saved state, which ViewModels would not change.

*Amended Sept 2026, with the owner's sign-off:* this section described a
`XxxScreen` + `XxxViewModel` + `XxxUiState` pattern over a
`LeagueRepository`. The single store is what the app was built on, and it
is now the rule.

### 10.4 Narrative generation

This is what makes a text game feel alive rather than like a spreadsheet dump. A template + slot-filling system, seeded from the same RNG:

- **Play-by-play line:** `"{QB} finds {WR} for {yards} on a {concept} against {coverage}."` with variant pools per outcome type.
- **Game recap:** picks the 3–5 highest-leverage plays by win-probability delta and writes around them.
  Built as `engine/narrative/Recaps`, from `narrative/recaps.json`. Win
  probability (`WinProbability`) treats the final margin as normal: centred
  on the score plus what the ball is worth (-1.5 points at the offence's own
  goal line, +0.075 a yard, -0.5 a down used), spread 13.5 points over a
  whole game and shrinking with the square root of the time left. A play's
  swing is the change from its chance to the next play's, or to the result
  after the last. The recap tells the three biggest swings, and up to five
  if the fourth and fifth moved the game 8% or more, in the order they were
  played. It opens by the kind of game: a tie, a comeback (the winner fell
  to 20% or less), a rout (21 or more), a close one (3 or fewer), or
  otherwise. A moment on fourth down says so. The wording is drawn from the
  dynasty seed split by the game's clubs, score and play count, so a game
  reads the same on the game screen and in the box score. It shows at the
  end of the game screen and at the top of the box score, for any game
  whose plays are kept (§9.2: the user's, this season).
- **Weekly news:** injuries, benchings, hot seats, contract disputes, breakout performances, milestone chases.
  A *benching* is filed when a club's best man at a position, by talent,
  takes under 40% of the snaps of the man who played it, and at least ten
  snaps went to someone else - once a man, which form (§5.9a) makes happen.
  A *contract dispute* is a veteran with three accrued seasons, rated 74 or
  better, whose market has passed 1.8 times his cap hit, asking his club to
  fix it between weeks 3 and 15 (2% a week, up for ego and down for loyalty).
  The league's clubs pay the market rate if they have the room and refuse if
  not. The user answers on the Demands screen: pay him (any of the ways §8.3
  lists), offer 90% or 80% against a reservation price (0.90 of market, up to
  0.10 more for ego, 0.14 less for loyalty, never below 0.74; a snub costs
  him 5 morale and names his floor), tell him no (18 morale, and far less
  patience in the spring), or let the front office answer. A demand left
  waiting costs 2 morale a week, down to 50. News also carries a line when
  another club signs a man off the user's practice squad.
- **Press conference / storyline beats:** a holdout, a rookie QB controversy, a coach on the hot seat.

Keep templates in a data file (`narrative/*.json`), not in Kotlin. Aim for 8–15 variants per event type so repetition isn't obvious over a 30-year dynasty.

Weekly news is written from `narrative/news.json`: one list of ten
templates per story (`injury.season`, `injury.weeks`, `big.passing`,
`big.rushing`, `big.receiving`, `big.sacks`, `milestone`, `hot_seat`,
`benching`, `dispute.raised`, `dispute.settled`, `dispute.refused`,
`poached`), each with `{slot}`s such as `{player}`, `{pos}`, `{club}`. The
template is picked from a split of the dynasty seed kept for wording alone
(`headlines|year|week`, and `headlines` under the disputes stream), so a
save replays with the same headlines and the wording never moves what the
sim does. A slot a template uses that its story does not fill is an error,
not braces on screen.

The play-by-play is written the same way from `narrative/plays.json`: 32
kinds of line, 8 or 9 ways each - every run, pass, sack, scramble, turnover,
penalty, kick and punt - with fragments (`run.tackle`, `punt.return`) spliced
into the line they finish. Each game words its plays from its own
`narration` split of the game's stream, so the words never move a snap and
a replayed game reads the same; a snap played on its own words itself from
`split("narration")` of its stream. Every interception line says
"intercept" and every fumble line "fumble", because the game log marks a
turnover by reading the line.

---

## 11. Accessibility & polish (non-optional)

- All tables navigable by screen reader with proper content descriptions.
- Respect system font scaling — a text game that breaks at 200% font size is broken.
- Sim a full week in under 2 s on a mid-range phone; show a determinate progress indicator.
- Export a season as CSV/Markdown (people who play these games want to post about them).

---

## 12. Tuning table (sliders) **[LOCKED]**

Every coefficient in the sim lives in one serializable `TuningTable`, saved with the league and editable in-game behind an "Advanced" toggle. Groups:

`passing` (accuracy scale, pressure sensitivity, YAC scale, INT rate, sack rate)
`rushing` (base yards, breakaway frequency, fumble rate, box-count sensitivity)
`blocking` (protection scale, gap-scheme bonus table)
`coverage` (separation scale, man/zone effectiveness, DPI rate)
`specialTeams` (FG distance curve, return rates)
`penalties` (per-type base rates)
`injuries` (frequency, severity distribution)
`progression` (scale, age curve steepness, dev multipliers)
`ai` (FA aggression, trade frequency, draft BPA-vs-need weighting)
`gameFlow` (tempo, plays per game, clock runoff)

Ship 3 presets: **Realistic** (matches §13.2 bands), **Arcade** (higher scoring, more explosives), **Grinder** (lower scoring, run-heavy, more injuries). You will spend happy hours here.

---

## 13. Testing & calibration

### 13.1 Test pyramid

- **Unit** — pure functions: cap math, tiebreakers, scheme fit, contract proration, clock runoff. Fast, many, exact.
- **Property** — invariants that must hold after *any* sequence of operations:
  - no player on two rosters
  - roster size ∈ [46, 90] depending on phase; exactly 53 in-season
  - Σ team cap hits == league cap spending ledger
  - every game has exactly one winner or a tie, and points ≥ 0
  - a season always terminates (no infinite drives — assert play count per game < 250)
- **Golden-seed** — sim a fixed seed and snapshot key outputs. Any diff must be intentional and explained in the commit message. This catches accidental behavior changes better than anything else.
- **Calibration** — §13.2.

### 13.2 Calibration targets **[LOCKED — the definition of "the sim works"]**

Run 1,000 seasons in `engine-cli` and assert league-wide means fall in these bands:

| Metric | Target band |
|---|---|
| Points per team per game | 21.0 – 24.5 |
| Total yards per team per game | 320 – 360 |
| Pass attempts per team per game | 32 – 36 |
| Completion % | 63 – 68 |
| Yards per attempt | 6.8 – 7.5 |
| Passing TD per team per game | 1.3 – 1.7 |
| INT rate (per attempt) | 2.0% – 2.8% |
| Sacks per team per game | 2.1 – 2.6 |
| Rush attempts per team per game | 25 – 28 |
| Yards per carry | 4.1 – 4.6 |
| Fumbles lost per team per game | 0.5 – 0.8 |
| Accepted penalties per team per game | 5.5 – 7.0 |
| Third-down conversion % | 37 – 42 |
| Red zone TD % | 53 – 60 |
| Plays per team per game | 62 – 67 |
| Games decided by ≤3 points | 18% – 26% |
| Best record in a 32-team league | 13–17 wins, mode ~14 |
| Teams at 4 wins or fewer | 2 – 5 |
| Repeat division winners (yr over yr) | 40% – 55% |

Also check **distribution shape**, not just means: a league where every team goes 8-9 is broken even if the mean is right. Assert the standard deviation of team wins is 2.6–3.4.

### 13.3 The calibration workflow

1. `./gradlew :engine-cli:run --args="calibrate --seasons 1000"`
2. It prints a table of metric / target band / actual / pass-fail.
3. Commit the report to `docs/CALIBRATION.md` whenever it changes materially.
4. CI runs a 50-season version on every push (fast) and a 1,000-season version nightly.

### 13.4 CI guards

Fail the build if `:engine` contains: `import android.`, `Math.random`, `System.currentTimeMillis`, `java.util.Random`, `UUID.randomUUID`. A five-line grep step in the workflow prevents an entire class of nondeterminism bugs.

---

## 14. Build roadmap (→ GitHub milestones)

Each milestone is a GitHub Milestone. Do not start the next one until the current one's tests are green. Time estimates assume evenings-and-weekends solo pace with AI assistance.

| # | Milestone | Definition of done | Est. |
|---|---|---|---|
| **M0** | Project skeleton | Repo, 4 modules, version catalog, CI green, empty app launches | 1 wk |
| **M1** | Domain model | All model classes, serialization round-trips, seed data loads, 32 fictional teams generate | 1–2 wks |
| **M2** | Play engine v0 | `simPlay` works for run/pass, box score adds up, no crashes over 10k plays | 2–3 wks |
| **M3** | Game engine | Drives, clock, downs, special teams, penalties, injuries. One full game sims correctly | 2–3 wks |
| **M4** | Calibration pass 1 | `engine-cli calibrate` hits ≥12 of 18 bands in §13.2 | 2 wks |
| **M5** | Season | Schedule gen, standings, tiebreakers, playoffs, awards. Full season sims | 2 wks |
| **M6** | Minimum playable app | Hub, roster, schedule, box score, advance button. **You can sim a season on your phone.** | 3 wks |
| **M7** | Offseason | All 11 phases, progression, cap, FA, draft, AI GMs | 4–5 wks |
| **M8** | Depth & scheme | Depth chart UI, packages, scheme fit surfaced, game plan sliders, tuning screen | 2–3 wks |
| **M9** | Scouting & traits | ScoutingLens in the UI everywhere, trait reveal, scouting spend, draft room | 3 wks |
| **M10** | Narrative & polish | Recaps, news, history, records, HOF, accessibility, export | 3 wks |
| **M11** | Calibration pass 2 + balance | ≥17 of 18 bands, presets tuned, 30-season stability test | 2 wks |
| **M12** | v1.0 release | Signed AAB, Play listing, save migration tested from M6 saves | 1 wk |

**M6 is the milestone that matters psychologically.** It is the first point where the thing is a game you can hold. Resist the urge to build the offseason before it.

**Post-1.0 candidates:** college sim + draft-class handoff, custom roster import, historical/expansion draft modes, coach career mode, multi-user hot-seat league.

---

## 15. Decision log (`docs/DECISIONS.md`)

Keep one file with short entries. This costs 3 minutes and saves you an argument with yourself in six months.

```markdown
## ADR-001: Engine is a pure Kotlin/JVM module
Date: 2026-09-05
Status: Accepted
Context: Android-coupled sim logic would be untestable at speed and unusable by the college sim.
Decision: :engine has zero Android dependencies. CI enforces via grep.
Consequences: Save/load and file access live in :data. Slightly more boilerplate crossing the boundary.
```

---

## 16. Open questions

- **[OPEN]** Room in v1 or defer to v2? — Deferred. Revisit at M7 with real save-size numbers.
- ~~**[OPEN]** Real player names via an import file, or fully fictional only?~~ **Resolved Sept 2026: both.** Ship fictional; support user-supplied roster import. See §9.4.
- **[OPEN]** Do coaches have their own progression/career arcs? Adds a lot of flavor; adds a lot of scope. Candidate for post-1.0.
- ~~**[OPEN]** Practice squad and gameday inactives — realistic, but is it fun or is it admin?~~ **Resolved Sept 2026: practice squads built** (§6.1); the user's squad is his to manage or hand to the front office. Gameday inactives are still open: every healthy man on the 53 dresses.
- **[OPEN]** Monetization: free, one-time paid, or free with a paid "commissioner tools" tier? Doesn't affect architecture; decide at M11.

---

*End of specification v1.0. Change this file in the same commit as the code it describes.*
