# NFL Dynasty Sim — Technical Specification

**Version:** 1.0
**Date:** September 5, 2026
**Owner:** Peter
**Repo:** [itstrue54/gridiron-dynasty-app](https://github.com/itstrue54/gridiron-dynasty-app)  ·  **Game:** Gridiron Dynasty (working title was NFL Sim Text)

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
gridiron-dynasty-app/
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

**What each rating does (save version 45).** Every coach rating has a
job, and every one but development is measured from the league's coaching
mean (`coachMean`, 65, the generated average), so a league's staffs as a
whole play as calibrated and only the gap between two clubs shows:

| Rating | Whose | What it does |
|---|---|---|
| development | head coach and position coaches | Players' growth in the offseason (§7.1) |
| adjustments | head coach | How far his staff adapts during a game (§5.4) |
| discipline | head coach | His side's flags (§5.8) |
| gameplan | offensive and defensive coordinators | Rating points of edge on every snap on his side of the ball - a pass's separation, a run's blocking - `gameplanPoints` (6) per 100 over the mean; the other side's coordinator takes his off it |
| gameplan | special teams coordinator | Yards on every kickoff and punt return, his club's and the other's: `returnYards` (6) per 100 points between the two coordinators |
| motivation | head coach | How fast a slump fades (§10.1's form): `motivationSlump` (0.5) of its weekly decay faster per 100 over the mean, as much slower under it. A hot streak fades the same either way |
| evaluation | head coach | His eye for talent, added to the scouting department everywhere the club reads how well it knows a player (`Scouting.department`): `evaluationScouting` (0.5) points a point over the mean |

A head coach's game plan is his coordinators' job, and a position coach's
only rating that matters is development. An empty chair is a league-average
coach.

**Hiring and firing (save version 43).** The user's club changes its own
staff, every job on it - head coach, the three coordinators, the eleven
position coaches - and its general manager, in a spring window: after the
last playoff game, before the user starts the offseason
(`offseason.Staffing`). Nobody else fires the user's coaches. The
carousel (§7 phase 2) still warms and cools his head coach's seat, which
the Staff screen shows as advice against the bar a club like his fires
at, and a contract that runs out is extended; letting a coach go is the
user's call.

Each job is filled from a pool: the coaches out of work in that role (the
league's fired head coaches, the coordinators who left with them, anyone
the user lets go) - including the ones the league lets go this spring -
and six fresh candidates a spring, drawn from the
league's seed, the year and the job, so the same spring always offers the
same men. Fresh candidates are drawn as the carousel draws its outside
ones, below the league's coaching mean, because the user chooses the best
of several. The pool lists them best first by what the job uses: a head
coach's ratings other than game plan, a coordinator's game plan, a
position coach's development (see the table above). A head coach or coordinator candidate may
run any scheme on his side of the ball (a head coach, either side); a
special teams or position coach works in the club's.

**This spring's market (save version 46).** The window sees the carousel
before it runs: `OffseasonEngine.springCarousel` is exactly the carousel
the offseason will run from the saved dynasty, deterministic, so the pool
reads who is out of work from its result (`Staffing.market`). A man still
working for a club that is letting him go is marked with the club; hiring
him is a `PendingHire` on the dynasty, and he joins when the offseason
starts, straight after the carousel. Until then the carousel keeps him off
every other club's shortlist, so nobody takes him first, and the user can
change his mind. Who the league lets go never depends on the user; whom it
hires can, since a coach the user lets go is one more man out of work.
A finished offseason clears every agreement, kept or not (save version 50
clears any an older offseason left behind).

**Careers (save version 51).** A coach's ratings move with his age
(`offseason.CoachCareer`): each spring he improves `careerGrowth` (1.2) a
rating a year until 45, holds through 58, and slips `careerDecline` (0.4) a
year after - slowly, as the user asked: experience doesn't leave a coach
the way legs leave a player - with a point of his own either way. A new
coach is drawn where his age puts him on that curve, so a young assistant
is still learning and grows into his prime rather than climbing past the
man he replaced; `careerPeakLift` sets a prime's level so the league's
coaching, young and old together, holds where it was calibrated. A club
that needs a coordinator looks at its own position coaches on that side of
the ball as well, less `promoteFromWithinDiscount` for never having run a
side, and the user can promote his own: at once if the job is open, or as
it opens when the offseason starts, his old job then filled. The Staff
screen says where each coach is: still improving, in his prime, or
slowing down.

**Age and retirement (save version 48).** Every coach is a year older
each spring, in a job or out of one, and a man who stays in his job is a
year on in his contract - extended when it runs out, as a head coach who
stays is. A man out of work leaves the pool at
68, as before; a man in a job retires at an age of his own, drawn once for
him from 66 to 72 (`retireFrom`, `retireSpread`). A retiring head coach
goes whatever his record, and his club hires as if it had fired him; a
retiring coordinator is replaced by the best of three, as when one is
promoted away; anyone else by a man drawn from the spread a new league's
staffs are, so the league's coaching - and the development that comes
with it - holds where it started. The user's club fills its own: the
window says who is retiring, and the user can hire his replacement then.

**Promotions, under the NFL's rules (save version 47).** The NFL's
anti-tampering policy lets no club stop an assistant taking a promotion -
to head coach anywhere, and since 2020 to a coordinator's job - while a
move sideways, or a head coach under contract leaving, is the club's to
refuse. The interview calendar's limits (after a club's season, playoff
clubs' assistants later) are all met by a window that opens after the
Super Bowl. So:
- **A club hiring a head coach** looks at a few other clubs' offensive and
  defensive coordinators (`poachLook`, 9) beside its outside candidates and
  the head coaches out of work, read the same way. A coordinator it
  promotes takes a head coach's levers; his club hires the best of three
  replacements, as likely as any to keep its scheme. A coordinator hired
  this spring is nobody's to promote until next spring. The offseason
  report marks a promoted head coach with the club he left.
- **The user's club** can promote any club's coordinator to head coach,
  his own included. Another club's man joins when the offseason starts
  (his club cannot refuse, and hires his replacement); the user's own moves
  up at once and leaves his job open. Other clubs' coordinators are not
  offered for coordinator jobs: that move is sideways.
- **The user's coordinators can be promoted away** like anyone's. The
  window shows who is leaving and for which club, and the user can hire his
  replacement then: whoever he picks joins when the offseason starts.
- **A position coach to coordinator,** under the same policy since 2020: a
  club filling a coordinator's job looks at a few other clubs' position
  coaches on that side too (`assistantLook`, 3), with its own and the
  outside candidates, less `promoteFromWithinDiscount` for never having run
  a side; their clubs fill the jobs they leave. The user's coordinator
  pools hold other clubs' position coaches, and his own can be taken - the
  window says where to - and the report lists them under "Promoted to
  coordinator".
- Not modelled: the draft-pick compensation the NFL gives a club that
  loses a minority coach to a head coaching job.

Firing a coordinator leaves the club's scheme alone; hiring one brings his,
and if it is new the players on his side start learning it again, as after
the carousel. A job still open once the carousel has run and the user's
agreed hires have joined, the front office fills from the same pool with
the best man, and for a coordinator
the best who runs the club's current scheme if any does, so no scheme
changes that the user did not choose. A new hire's contract is
`newContractYears`.

The general manager is hired the same way, from the general managers out
of work (`League.gmPool`, newest first, the most recent 24) and six fresh
candidates a spring. The user makes the calls; his general manager runs
what the user hands the front office - injured places and the practice
squad, answering demands, and the user's club's side of any offseason
decision left to "the front office" - with his own `GmProfile`. An empty
chair runs on neutral values until it is filled; the owner fills it when
the offseason starts.

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

**Adaptation.** Each game, both coordinators track the opponent's realized tendencies and shift within a bounded window based on the HC's `adjustments` rating. The pass and blitz rates move at most ±0.12 (`adaptation.window`). The box, a chance of a man more or fewer on a snap, moves at most ±0.6 (`boxWindow`): against an offence that only runs, a staff rated 100 loads the box on most snaps. A predictable coordinator gets punished: a staff rated 100 takes 0.2–0.35 yards a carry off an offence that runs nine times in ten, against one rated 0. This is where your CFB slider-tuning instincts translate directly.
Built (`sim.Adaptation`, `TuningTable.adaptation`):
- Each side watches what the other has shown this game: the offense's runs and passes, and the defense's boxes.
- A defense facing an offense that leans to the pass (against a neutral 0.57) sends more. Facing one that leans to the run, it loads the box.
- An offense facing loaded boxes throws more; facing light boxes, it runs.
- Nothing moves until a side has seen 8 snaps. The pass and blitz shifts reach ±0.12 at most, and the box ±0.6, times the head coach's adjustments rating over 100.
- The user's own calls count as his club's tendencies like any other, and the coordinators' suggestions to him already carry the adaptation.

**The end of a game.** Coordinators play the last minutes the way NFL coaches do:
- **Victory formation.** Ahead in the fourth quarter, with no more clock left than the kneels before fourth down can run off, the offence kneels (`PlayCaller.canKneelItOut`). The kneel runs off what a running play does (`TuningTable.gameFlow.runPlayClockRunoff`).
- **The kick that ties or wins.** When another snap would end the half or the game and a field goal is in range, the kicker goes out on any down (`FourthDown.lastKick`). At the half he takes the points; at the end he kicks if it ties the game or wins it.
- **Fourth down, late and behind.** In the last five minutes of the fourth (`fourthDown.lateSeconds`), a club that trails by more than a field goal, or can't reach one, goes for it far more often. In the last 2:30, four or more behind, it goes for it unless it is deep in its own end facing long yardage (`desperateSeconds`, `desperateDeficit`, `desperatePuntYards`, `desperatePuntDistance`). Within three and in range, it kicks.
- **Fourth and short in easy range.** Inside the opponent's 38 (`fourthDown.easyKickYards`), with fewer than `fourthDown.kickAlwaysDistance` (5) yards to go, a coach adds `fourthDown.goKickRange` (0.25) to his chance of going for it: a touchdown is worth more than the three, as NFL coaches now play it. With 5 or more to go he kicks. Late in the fourth and within a kick, the bonus is off, because the three tie or win it.
- **Prevent.** A defence `gameFlow.preventLead` (9) points ahead in the fourth quarter sits back: deep zones only, nobody sent, and a dime look on anything but short yardage. It gives up underneath yards and late points rather than the big play. That trade is why leads shrink at the end of games, and it's what keeps the close-game band (§13.2) honest.
- A user calling his own offence is asked about the kick before fourth down, as he is on fourth down: kick now, or run a play.

**User control.** You set your coordinators' `Tendencies` sliders in the game plan screen, plus a per-opponent weekly game plan (§10.4). Each lever reads in plain words (pass rate "Run-first" to "Air it out", fourth down "Conservative" to "Very aggressive", blitz "Rarely" to "All the time", and so on) with a line on what it does; "Show the numbers" gives the exact values and which are the staff's. That is the strategic layer. And you may call your own game: "Call the plays" on the hub plays the week with the user's regular-season game stopping at each of his snaps (`SnapCaller`). On offence he takes the coordinator's call or makes his own - a formation, then a play from it - and on fourth down chooses go, punt or kick first; on defence a front, then a coverage or pressure. Either side can go back to its coordinator at any snap, and "Let the coordinators finish the game" hands over the rest. The coordinators always call first, from the game's own stream, so a game whose every suggestion is taken plays exactly as a simmed one; a call of the user's own changes his game and nobody else's. In the postseason the same button calls each of the club's playoff games in turn:
- Each game opens on a kickoff card showing the game just finished and the one about to start. The user can kick it off, let the coordinators play that one, or let them play the rest of the postseason.
- A tied game goes to overtime (§5.10), and the user keeps calling it.
- When the postseason is over, it opens the box score of the club's last game.

**Playbooks.** Every scheme has a book in `playbooks/<scheme id>.json` (§8 data rule): an offence's by formation - a personnel group - and a defence's by front, 58 to 103 plays each. Every play is an exact engine call: a run or pass concept with play-action, protection and target, or a coverage with extra rushers, box and bracket. The books have their scheme's shape (Air Raid in shotgun and empty sets, Gap/Power and Run-Heavy Pro in I and jumbo sets, Spread Option with the read keeper, each defence with its own pressures), and every call a coordinator can make is in his book, so a suggestion always has a name ("Singleback Bunch - Mesh").

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
4. A turnover is settled before the goal lines. A lost fumble ends the drive as a fumble and the defence's ball at the spot, never a touchdown or a safety for the club that lost it: one carried to either goal line is recovered at the one.

**Fumbles off a pass.** A carry is not the only way to lose the ball:
- A sack strips the quarterback and the defence keeps it with chance `passing.stripSackLost` (0.05).
- A catch short of the end zone is lost with chance `passing.catchFumbleBase` (0.0051), scaled by ball security and the weather as a carry's is.

Either is a turnover where the play ended, it ends the drive as a fumble, and it is credited to the quarterback or the receiver. Box scores keep `rushFumblesLost` apart from the rest. Any change of possession stops the clock (§5.10). About half the league's lost fumbles come off sacks and catches, as in the NFL.

**Long plays.** Most of a game's yards come in short gains, but a game turns on its few long ones. The NFL's shape is many short throws and a long right tail, and the sim aims for that:
- **What a coordinator throws.** On first down he aims `calling.firstDownDepthMin` (4) to 4 + `firstDownDepthRange` (6) − 1 yards deep, 4–9; second and third downs aim by the distance.
- **Screens.** A pass on first or second down, or on third and `calling.screenThirdDistance` (10) or more, is a screen with chance `calling.screenRate` (0.065), unless it is a shot play or inside the five. A screen is caught behind the line with blockers in front: `passing.screenYac` (4.2) is added to its run after the catch.
- **Completion falls with depth.** Base completion is `passing.baseCompletion` (0.888), less `depthPenaltyPerYard` (0.0203) a yard of route depth. Measured on thrown balls (throwaways and sacks aside), short routes are caught 84% of the time, mid routes 71% and deep 55%. That is still flatter than the NFL's ~75%, ~57% and ~38%.
- **A throw past the route.** Between the twenties, a receiver who gets behind his man is thrown to where he is: an exponential extra with a mean of `passing.airYardsTail` (0.26) of the route's depth. It only ever adds depth, so a third-down throw still reaches the sticks. Inside the 20 there is no behind, and it draws nothing. Every route's depth is first scaled by `airYardsScale` (0.87).
- **Stop routes.** A route that stops or works back to the ball (the curl, the stick, the out, the flat and the screen: `PassConcept.stop`) is caught standing, facing the quarterback. It never draws the throw past the route, and its catch breaks open at `passing.stopRouteBreakaway` (0.42) of the in-stride chance.
- **A catch broken open.** A catch breaks open with chance `passing.yacBreakawayBase` (0.12). Each point of the receiver's elusiveness and speed over 70 adds `yacBreakawayElusiveness` (0.0015); each point of the secondary's tackling over 70 takes off `yacBreakawayTackling` (0.0015). The chance is capped at `yacBreakawayMax` (0.25). A broken catch adds an exponential run of mean `yacBreakawayYards` (28). One that goes `catchAndRunYards` (20) or more is called a catch and run.
- **A run broken open** is `rushing.breakawayBase` (0.037), for an exponential run of mean `rushing.breakawayYards` (23). The ordinary carry is `rushing.baseYards` (3.20) before the roll, so the yards come in fewer, longer runs.

`GameCalibration` reports plays of 20+ and 40+ and the share of touchdowns from 20+ yards out as diagnostics (CALIBRATION.md passes 5 and 6).

### 5.8 Penalties

Sampled per play from a base rate modified by `discipline`, `penaltyProne`, coach `discipline`, home/road, and play type (holding on pass pro, false start on the road with crowd noise, DPI on deep routes contested by a low-`manCoverage` defender). Target ~6.2 accepted penalties per team per game.

**Coach discipline (implemented).** The head coach's `discipline` scales his side's flags: false starts and holding on offence, offside and pass interference on defence. The scale is 1 + `coachDisciplineScale` × (`coachDisciplineMean` − his discipline) / 100, which at 0.5 is half a percent a point, so the range runs from about 17% more flags for a 30 to 17% fewer for a 100. It is centred on the generated coaching mean (65), so the league's rate stays where it was calibrated. A club with nobody in the chair flags at the league rate. Hiring weighs it with the head coach's other ratings (§4.7).

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
- **Special teams units** (`sim.SpecialTeamsUnits`). Each kick is played by
  units taken from the 48 dressed and not hurt that game, each man picked
  for what his job needs (`specialTeams.roleWeights`):

  | Role | Unit | Ratings read |
  |---|---|---|
  | Coverage | kickoff (10), punt (7) | speed, acceleration, pursuit, tackle, block shedding |
  | Gunner | punt (2, split wide) | speed, acceleration, release, tackle |
  | Return blocker | kick return (10), punt return (7) | impact block, run block, strength, speed, awareness |
  | Jammer | punt return (2) | press, speed, strength |
  | Protector | field goal and punt (9: linemen, tight ends) | pass block, strength, awareness |
  | Rusher | field goal and punt block (9: the front) | jumping, strength, power moves, acceleration |

  The returners (`SpecialTeams.returnerFor`) field the ball, so they are
  kept off their own return blocking and the punt-return jammers. A club
  picks its returner - unless it pins one - from its receivers, backs and
  corners by speed (and elusiveness, on punts), keeping its starters off
  returns: a starter returns only when better than the backups by
  `returnerStarterPenalty` (8) for each place he starts above the last
  starter at his position, so a club's first receiver or corner almost
  never returns and its third sometimes does. A returner gains on the
  league's average return by how far his speed is past
  `kickoffReturnSpeedAnchor` (63) on kickoffs, and his speed and
  elusiveness past `puntReturnSkillAnchor` (140) on punts.
  Starters are picked for coverage and returns only when better by
  `starterPenalty` (15 points), so the units are mostly backups, as clubs
  keep starters off them; protection and the rush take whoever is best.
  Quarterbacks, kickers, punters and snappers play only their own parts.
  The user may pin core special teamers (`DepthPins.specialTeams`), who play
  on every coverage and return unit.

  A unit's strength is its men's average in their role. Each kick reads two
  units against each other, each as points off a new league's average unit
  (`*Anchor`), so an average matchup plays as before:
  - **Kickoffs:** the kicker's leg (`KICK_POWER`) moves the touchback chance
    (`kickoffPowerTouchback` a point); a return moves `kickoffUnitYards` a
    point of the return blockers over the coverage.
  - **Punts:** gunners over jammers force fair catches (`puntReturnGunners`
    a point off the return chance); a return moves `puntUnitYards` a point of
    blockers over coverage.
  - **Blocks:** field goals are blocked `fgBlockBase` (1.2%) and punts
    `puntBlockBase` (0.5%) of the time, more as the rush beats the
    protection and as the snap and hold slip. A blocked field goal is a miss;
    a blocked punt is recovered `puntBlockedLoss` yards behind the line.
  - **Snap and hold:** the snapper's and holder's (the punter's) overall at
    their positions, averaged, moves field-goal and try accuracy
    (`snapScale` a point). There is no snapping rating; a snapper's overall
    reads the ratings his position uses.
  - **Box score:** a returned kickoff or punt counts a return and its yards
    for the returner (a kickoff is fielded at the goal line, so its yards are
    the spot it reaches), and one coverage tackle for a man on the kicking
    club's coverage unit, the better cover men more often
    (`StatLine.specialTeamsTackles`). Fair catches and touchbacks count
    nothing. The tackler is drawn from a stream of the return's own, so
    counting changed no game. Coverage tackles are kept apart from defensive
    tackles, which awards, the Hall of Fame and contract pricing read. The
    game's leaders list its top returner; a player card adds a special-teams
    table when he has any.
- Clock model: 40-second play clock, runoff by play type and outcome, out-of-bounds rules, two-minute warning, timeouts. **Get the clock right early** — bad clock logic produces 45-point games and it is miserable to retrofit.
  Built (`sim.ClockManagement`):
  - Runoff by play type and outcome.
  - The two-minute warning in the second and fourth quarters.
  - The hurry-up: a trailing offence in the last `gameFlow.hurryUpSeconds` of the game, or any offence in the two-minute drill before the half, takes `hurryUpRunoff` off a running clock.
  - Timeouts: three a side each half and two in each overtime period. The club chasing the game (behind, or level with the ball under two minutes) spends them to stop a running clock in the last `timeoutSeconds`, if it is within `timeoutMaxDeficit`. It doesn't spend one on a snap the warning stops anyway. The play-by-play says so.
  - A leader kneels only when the kneels before fourth down can outlast the defence's timeouts.
  - Out of bounds: a run, scramble or catch can end out of bounds. It's likelier on an outside run (12%) or a sideline route (35%) than up the middle, and late it's likelier for a club chasing the game and less likely for one protecting a lead. The roll has its own stream, so it moves the clock and nothing else. In the last two minutes of the half, the last five of the game and overtime, it stops the clock until the snap and the play-by-play says so. Elsewhere the clock restarts on the spot, saving `outOfBoundsRestartSave` seconds.
  - The base pace is slower than before (runs 37 s, completions 32 s, up from 31 and 28) so that plays per game stay in band with the time the clock now gives back.
  - **A quarter ends when its clock does.** A play that runs past zero takes nothing from the next quarter, which starts with its full 15 minutes.
  - **Possession is every second the clock runs while a club has the ball:** its snaps, and the punt (12 s), field goal (6 s) or try (8 s) that ends the drive. The two clubs' possession adds up to 60:00, plus any overtime played.
  - A user calling his own game can call his own timeouts (`SnapCaller.timeout`). He arms one ahead with "Timeout after this play", and it's taken after the next snap that leaves the clock running and would save time. Left alone, the coordinators spend his timeouts as they would, so a game whose every suggestion is taken still plays exactly as a simmed one.
- Overtime follows the NFL's rules from 2025 on (`Overtime`, passed to each game):
  - Every overtime period opens with a toss, a kickoff and two timeouts a side.
  - Both clubs get the ball once. After that, the next score wins. A touchdown that ends the game has no try after it, including the second club's first possession when the first came away with nothing. A touchdown that only draws level, or leaves the club short, still has its try.
  - A period that runs out with one club ahead ends the game, even if the other club's answering drive is cut short.
  - The two-minute drill runs at the end of an overtime period as it does at the end of the fourth quarter.
  - `GameState.periods` counts the four quarters and each overtime period. Overtime is played only after regulation and draws from the game's stream only then, so a game decided in four quarters plays exactly as it did before overtime existed.
- The regular season plays one ten-minute period. A game still level when it runs out is a tie.
- The playoffs play fifteen-minute periods until somebody wins:
  - Unlike the NFL, each period opens with its own toss and kickoff rather than carrying on from the spot.
  - Past ten periods, a safety valve, the bracket's old rule settles the game: the coordinators replay it, up to three times, and then the home club wins by three.
- Weather generated per game from stadium + month + a regional climate table. Dome = neutral. Affects deep passing, kicking, and fumble rate.
  Built (`sim.Weather`, `climate.json`):
  - Each stadium has a climate region (`teams.json`). The table gives each region and month a temperature and its spread, a chance of rain or snow (snow at 33° or colder), and wind.
  - Each game draws its weather once, from its own stream, for the home stadium and the week: September through January, and February for the title game. A domed stadium plays indoors, where nothing applies.
  - Effects (`TuningTable.weather`):
    - Wind past 10 mph costs completion on throws of 15+ air yards.
    - Rain and snow cost completion on every throw and raise the fumble chance on a carry.
    - Wind, rain and snow cost a field goal accuracy.
    - Wind and cold shorten a kicker's range, for the kick itself and for the coach's fourth-down call.
  - The conditions open an outdoor game's play-by-play. The game result records them.
  - The bands (§13.2) are the NFL's, weather and all, so the fair-weather baseline was raised to meet them: base completion 0.83 (from 0.82) and base fumble rate 0.0115 (from 0.0125). Base completion is now 0.888, raised with a steeper depth penalty so short throws are caught more and deep ones less (October 2026, `docs/CALIBRATION.md` passes 4 and 6).

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
- **The street.** Unsigned men a club can sign in season. Each offseason
  keeps up to `ai.freeAgentPool` of them (260, about eight a club, what a
  real wire holds).
- **A new league** has had no cut to 53, so it is given camp cuts
  (`gen.CampCutGenerator`): a squad place for every club and
  `ai.freeAgentPool` over, made as the offseason's camp bodies are
  (`ai.campBody`, undrafted-rookie ages) and spread over the positions as a
  53-man roster is. Its squads are chosen from them as every later season's
  are, and the men they leave are its first street. Rosters are generated
  first and do not depend on them.
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
- **Game day** (CBA Article 25, `season.GameDay`). A club dresses 47 of its
  53, or 48 if at least eight of them are offensive linemen; the rest are
  inactive. Men hurt but not on reserve sit first. The healthy scratches are
  the deepest men at their positions for how deep a roster runs there
  (the generator's template), the lower-rated of two as deep. A club never
  scratches below two quarterbacks, a kicker, a punter and a long snapper,
  nor its eighth lineman, whose place in the 48 would go with him. The
  user's club may name its scratches on the depth chart
  (`DepthPins.inactive`); the front office picks any it leaves unnamed. Per-
  game calibration dresses the same way.
- **Call-ups** (practice-squad elevations). A club may call up two of its
  squad for a game, each man at most three times a regular season
  (`Player.elevations`, cleared each spring), and they dress within the
  same 47 or 48; after the game they are back on the squad. A club calls one
  up only when it is short: a position below what a game needs, then an
  eighth lineman, then the position furthest short of a roster's depth
  while it has fewer fit men than it may dress. The user may name his own
  (`DepthPins.callUp`), who dress and push his deepest men out. A club
  also calls up a squad man whose coverage beats the weakest man it would
  put on kick coverage - with starters counted down, as the unit builder
  picks them - by `callUpCoverageMargin` (1 point), and of two men as deep it
  scratches the one worth less at his position and on special teams
  together (§5.10). The emergency third quarterback is not modelled.
- **Signing in season** costs the minimum prorated by the weeks left, one
  eighteenth a game (CBA Article 26): one year, all base salary, nothing
  guaranteed.

---

## 7. Offseason — the phase machine **[LOCKED]**

The offseason is an explicit enum. Each phase has an `advance()` that returns a new `League` plus a list of `NewsEvent`s.

| # | Phase | What happens |
|---|---|---|
| 1 | `POST_SEASON_AWARDS` | Awards, All-Pro, retirements announced |
| 2 | `COACHING_CARROUSEL` | Firings; HC hires, other clubs' coordinators promoted among them, and their clubs' replacements; OC/DC hires; scheme changes cascade (§4.7). Then the user's agreed hires join and the jobs he left open are filled; owners replace general managers (§8.2). Never fires the user's coaches |
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

A man the user's club lets go - by the user's choice, by not choosing, or
by its front office deciding for it - may come back to it that spring
only if he is still unsigned, is the best the club can find, and is
willing: his loyalty at least matches his ego (`ai.returnLoyaltyOverEgo`,
0). That holds in the auction, in the late roster fill and in the talks
before the market opens, where an unwilling man's agent will not take the
call and his advice reads "He will not come back". A willing one comes
back on the usual terms, a loyal man's hometown discount included. The
league's own clubs are not held to this.

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

**Growth slows near the top.** Above `progression.growthTaperFrom` (80) overall, a year's rise (the age curve and the noise, never a breakout) is scaled down in a straight line to `growthAtCeiling` (0) at 99. A player at 88 keeps 58% of it, and one at 92 keeps 37%. Decline is untouched. Without it the count of players rated 90+ climbed from about 14 to about 60 over a dynasty's first decade, mostly 88s drifting over on a lucky year. With it, and a generator whose players spread 4.5 either side of their slot's target (`RosterGenerator.PLAYER_SPREAD`), a league keeps about 30–40 at 90+ from its first season to its tenth (`docs/CALIBRATION.md` pass 8).

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
- **A cap cut has to save money.** A club over the cap cuts only men whose release frees room this year; one whose dead money outruns his cap hit costs more to cut than to keep, and is never cut for the cap. Still over with nobody left worth cutting, it restructures, past its GM's habit, before the league year starts.
- **Over the cap in season** (CBA Article 13): a release's dead money can put a club over. It may not sign anyone until it is under (`Transactions.sign` checks the room), and it has seven days - a week here - to get there. A club still over when its week is played restructures, whatever moves the most room first, until it is under (`Transactions.comply`) - at the front office's usual share first, and all of it only if that isn't enough, since every dollar moved now lands on every year after. The hub warns the user's club, with the two ways out and what happens otherwise.

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

Built since: the ten-day auction (§8.3, `FreeAgency`), franchise and
transition tags (`FranchiseTag`), and the 5th-year option
(`FifthYearOptions`), and cap carryover: as the offseason opens, the room
each club left unused as the season closed (never less than zero) times
`ai.capCarryoverShare` (1.0, the NFL's rule) is written to
`TeamFinances.carryover` and added to that club's cap for the new year.
Every cap check counts it - the auction, re-signings, tags, trades, the
cut to legal, the fill, in-season signings and contract demands - and the
user's cap sheet shows it as "carried over". Over 15 seasons on two seeds
it left the spread of wins (2.89 to 2.83, 2.61 to 2.62) and the number of
champions (9, 12) where they were, raised the room clubs finish a season
with by about a fifth and free-agent spending by about a tenth, and lets
a thrifty club bank room: the most any club held by year 15 went from
$127M to $211M and $136M to $257M.

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

**Implemented.** `GmProfile` carries `aggression`, `winNowVsFuture`,
`loyaltyToOwnPlayers`, `riskTolerance` and the GM's `name` - generated in a
generated league (from a stream of its own, so naming GMs moved no other
draw), the real one from a roster file (9.4). The user is his own club's GM;
the name shows in the club picker (save version 18). The user's club has a
general manager of its own, whom the user hires and fires (§4.7).

**Owners replace general managers** (`GmCarousel`, save version 43). After
the coaching carousel, an owner fires a general manager after two losing
seasons in a row - under .350 this year and under .450 the last - once he
has had two seasons in the chair (`GmProfile.since`), and hires one of
three outside candidates or two general managers out of work, by lot.
Nobody knows in March which front office will work, and an owner who
always hired one kind would turn the league into it. The fired man joins
the pool the user's club hires from. The offseason report lists the
changes under "Front offices".

**This spring's general managers (save version 49).** As with coaches
(§4.7), the user's window previews the owners (`OffseasonEngine.springGms`,
after the coaching carousel), so the general manager pool holds the men
the owners let go this spring, marked with the club. Hiring one is
`Dynasty.pendingGm`: he takes the chair when the offseason starts, and no
owner takes him first. An owner's shortlist and his choice are drawn for
that owner and each man by name, not from a shared stream, so the user's
moves change an owner's spring only if he wanted the man the user took. A
chair still empty after the owners have run gets the first name on the
owner's list, as before.

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

**Transition tags, the user's club.** A transition tag lets his club match
any offer sheet another club makes. The auction runs in one go, so the
user answers before it opens: for each tagged man, match offers up to his
market, 10% over, 25% over, or never, with a recommendation (never for a
man who would be a backup, his market past the age a club pays through,
10% over for a starter). An offer sheet above the ceiling takes him; at or
under it, and with the room, the club keeps him. A tagged man nobody bids
for plays on the tender. Handed to the front office, the club matches
whatever fits, as the league's clubs do. The free-agency report says who
was kept and who was lost.

**Room, day by day.** A club's bids are each sized to its room, but it can
win several on one day. A bid stands only if, when he takes it, the club
can still pay the contract's first-year cap hit from what its earlier
signings have left. A club that transition-tagged a man keeps the room to
match him, whichever is more of his ask and his market, while he is on the
market: it bids for others and accepts their terms only with what is left.
The user's own offers were counted against his room when he made them.

**The league's clubs haggle over demands.** A club that can afford a man
who asks opens at 85% of his market, up to 100% for the boldest GM
(`ai.disputeAiOpenBase`, `ai.disputeAiOpenAggression`); at or above his
reservation price he signs, and under it he says no, takes the snub the
user's lowball would cost him, and is paid the figure his agent named. A
user who hands a demand to the front office gets the same. Over four
seasons on two seeds it cut what a league club pays to settle a demand
from $14.9M to $12.9M and $15.4M to $13.2M a year.

**A named figure stands.** When an offer falls short, the figure his agent
names is kept on the demand (`Player.demandFloor`) until it is settled or
refused, or the year turns. The snub costs him morale, and morale is in the
rating a market is priced from, so his market dips - about 9% in a measured
case - and a later offer priced as a share of it could otherwise sign him
below the figure he was just quoted. While it stands the figure is his
market at least: "Pay him", the 90% and 80% offers and the league clubs'
second offer all price from it, and any offer under it is turned down again
naming the same figure.

Still to do: the league's clubs do not talk to a free agent's agent
before the market opens, and there are no incentives or guarantee terms
to trade against, only the annual figure.

### 8.4 Trades

Value function combining a draft-pick chart (make it a tunable table, not Jimmy Johnson gospel), player surplus value (production − cap hit, adjusted for age and years of control), and team-fit modifiers. AI teams both propose and evaluate. Deadline behavior shifts based on whether they're in contention.

**The user's trades** (`season.TradeDesk`, the Trades screen):
- **What and where:** players and picks, both ways, with any club. During the regular season, until week 10 kicks off (the NFL's deadline falls after week 9, `TradeDesk.DEADLINE_WEEK`). In the offseason, at the draft room before the user's first pick, where this year's picks can move.
- **How the other club answers:** as the league's clubs answer each other (ContenderTrades). Everything is valued on its own timeline: players by rosterValue at its win now, picks by the chart tilted the same way. It says yes only when what it gets beats what it gives by `ai.tradeSellerMargin`.
- **What stops a deal:**
  - either club over its roster limit afterwards (53 not counting reserve in the season, 90 out of it);
  - either club with room going over the cap once the contracts and dead money move (a club already over may not go further over);
  - a piece that isn't the sender's to send;
  - a practice-squad man, who is signed, not traded.
- **What the user sees:** the answer as the offer stands, and when it falls short, roughly the pick that would cover the gap. Their GM says it first, in his own words (§10.4 banter), and has a parting line once a trade is made.
- **When a deal goes through:** contracts move as they stand, and each club eats the unamortised bonus of the men it sends (ADR-010). Players go on the wire, and in the offseason the trade goes in the report. The other club's players read through the scouting lens as a newcomer's would (§4.6).
- **Clubs that call** (`season.TradeOffers`): each week before the deadline, each club calls with chance `ai.tradeOfferCallChance` (0.2), and the user hears the best `ai.tradeOffersMax` (2) calls for the calling clubs. A club calls about one of the user's men who would beat its own best at his position by `ai.tradeClearUpgrade`, as a contender judges a star; nobody calls about a FB, K, P or LS. Its offer is built from everything it could part with (its players other than its best at each position, and its picks), singly or in pairs, that keeps both rosters within the limit and both clubs within the cap (a club already over may stay there, not go further). The cap check comes before the best few are picked, so a user tight against the cap still hears from clubs that pay in picks or cheaper men. The pieces must cost it no more than he is worth to it less `ai.tradeSellerMargin`, and must be worth at least the man to the user's club. Of those, the most for the user's club comes first, and it takes its best `ai.tradeOfferPool` (6) to the desk. If none pass, it moves on to its next target. Both can come out ahead because each counts on its own timeline. Who calls is drawn from the dynasty seed split by the season and week, from a stream nothing else reads, so the same week brings the same calls and nothing is saved. The GM pitches it in his own words (§10.4). The user can take the deal, work from it (it goes on the table to change), or turn it down, which hides it for the session. The hub's Trades link counts the calls.
- **The trade block:** the user can put any of his men on the block (the player card) and take them off it (there or on the Trades screen). A club that a man on the block would help, by any upgrade on its best at his position (`ai.tradeBlockUpgrade`, 0), calls that week with chance `ai.tradeBlockCallChance` (0.5) instead of the usual. It calls about him first, with an offer built by the same rules (no lowballs), and its GM says it knows he's available (`gm.offer.block`). Block calls come ahead of other calls. The block is saved with the dynasty (`Dynasty.tradeBlock`), and a man who leaves the club is off it.
- **Deadline deals** (`season.DeadlineDeals`): as the window closes, before week 10's games, the league's clubs trade with each other by the offseason's rules (ContenderTrades). A contender, on win percentage and win now, buys a star from a club losing or rebuilding when both come out ahead on their own timelines. In the season only active men move, a deal must leave both clubs within the 53 (so a star comes for a man back, with or without a pick), and next year's picks are valued toward next year's draft. The user's club is never dealt for. Each deal goes on the wire, and each star makes the news (`trade.deadline`). Across five test leagues a deadline brings 0 to 2 deals.
- **Calls at the draft room** (`TradeOffers.atDraft`): before the user's first pick, clubs call by the same rules on the offseason's book (rosters to 90), and the block counts there too. That is one set of calls a draft, drawn from the dynasty seed split by the draft year. The draft room's trade button counts them.

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
- Size, measured (October 2026, one dynasty played thirty seasons): 0.3 MB at the start, 1.2 MB after five seasons, 1.5 MB after ten, 2.0 MB after twenty and 2.5 MB after thirty. Past the first few seasons it grows about 55 KB a season under the history policy below; the league holds about 2,470 players, because the retired are pruned. Encoding took up to 0.36 s and decoding 0.11 s on a laptop at year thirty.
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
spreadsheets - and, since v1.1 of this document, JSON, because that is the
shape a roster scraped or exported from anywhere else comes in
(`RosterJson`). A JSON file lists `teams` (abbrev, city, nickname,
conference as AFC/NFC or American/Continental, division, `players`) or a
flat `players` list with a `team` on each; a player takes the CSV's fields
under the same names, ratings as fields or in a `ratings` object. It
becomes rows for the same importer, so both formats share every alias,
fidelity level and report below. `RosterJson.template()` is a
self-describing starter file. `RosterExporter.template()` is the documented starting point,
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

**A league from the file** (`LeagueImport`). The league is generated as
usual, then the user's clubs are laid over it: each takes a slot by its
abbreviation if the generated league has one, else by conference and
division, falling back to a free place in its conference (and saying so)
when a division is full; a club he leaves out stays fictional, and a
fictional club whose abbreviation he has taken gets an X. His players
become the club's roster position by position, each taking the contract
of the generated man he replaces in the depth order, so payroll and the
cap stay as the league was built. A club he lists with at least 53
players is his real roster and nobody is invented for it - a club that
carries no fullback has none, and the offence lines up two running backs
where a formation calls for one. Only a position the game cannot play
without, left with nobody at all, keeps one generated man, and the import
says so. A club he lists short of 53 keeps generated men where he left it
short, so it can dress. Past 53 his extras go to the practice squad, and
past that to the street. Players on no club are free agents, alongside the
street every new league is generated with (§6.1).

**Front offices and staffs** (`StaffJson`, `StaffImport`). A club in the
file may also give its `gm` (a name, and any of the four `GmProfile`
tendencies 0-1), its `offenseScheme` and `defenseScheme` (scheme id or
name), and its `staff`: `headCoach`, `offensiveCoordinator`,
`defensiveCoordinator`, `specialTeamsCoordinator` and `positionCoaches` by
group (QB RB WR TE OL EDGE DT LB CB S ST). A coach is a name, or an object
with `age`, `scheme`, `ratings` (development, gameplan, adjustments,
discipline, motivation, evaluation, 0-100), `contractYears`, `hotSeat`
(0-100) and `tendencies` (the GamePlan levers of 5.4). Each takes his slot from the
generated coach, who leaves the league; what the file leaves out of a coach
he takes from that man, and his tendencies are drawn from his scheme under
the file's. One person named in several slots is one coach with every job's
levers. A slot the file leaves empty keeps its generated coach, and the
import says so. The club runs the file's schemes, else its coordinators',
else its head coach's on his side of the ball. A head coach can come from
either side: a defensive scheme is his, and runs the club's defense when
neither the club nor its defensive coordinator names one. When the
carousel hires a head coach, his scheme goes to his side of the ball
through the best of a few coordinators from his tree who run it, and the
club finds the best coordinator it can for the other side. The carousel
reads candidates as the user's pool does (§4.7): a head coach by his
ratings other than game plan, a coordinator by his game plan. Generated head coaches all come
from the offense. Any other unknown scheme, a scheme on the
wrong side of the ball, or a rating, age, contract length or hot seat out of range is an
import error, never a guess.

**The league's names** (`LeagueNames`, save version 19). A generated league
is unnamed, and its conferences are the American and the Continental. A
roster file's top-level `league` block names them: the league (`name`,
`short`), its title game (`championship`), and each conference (`name`,
`short`) keyed AFC/NFC or American/Continental. Division names ("AFC West"),
the hub, standings, club picker, league history ("Super Bowl champions") and
the season export (whose game-by-game table lists the title game by its
name) read them from the league; the app's schedule and scoreboard keep the
short "Final", which their columns have room for. A conference the block leaves
out keeps the game's name. As with players, the names are the user's to
bring - the template shipped with the app uses invented ones.

**In the app.** The title screen's "Start with my own rosters" opens the
phone's file picker (JSON or CSV); the club picker then shows what the
import did - players read, where each club went, what was skipped or not
understood - before the user chooses his club. "Save a roster template"
writes the starter file to Downloads. `docs/ROSTERS.md` is the guide.

### 9.3 v2 — Room **[OPEN]**

If save/load exceeds ~1.5 s or the file exceeds ~25 MB, move career/history tables to Room and keep only live state in the serialized blob. Don't do this speculatively. Revisit at milestone M7.

**Measured after M7 (Sept 2026): not needed for v1.** A dynasty played 30
seasons on seed 91 and saved mid-season - the biggest a save gets, with
the season's play-by-play still in it - is 2.8 MB, growing about 55 KB a
season as results and history accumulate. On a desktop JVM it encodes in
0.31 s and decodes in 0.09 s. That is a ninth of the size line and a
sixteenth of the time line, so the blob stays. Revisit if a phone load
passes 1.5 s, or if something starts keeping per-play data past the
current season.

**Re-measured Oct 2026**, after the coach and GM pools, coach careers and
the hiring window: the same 30 seasons on seed 91, saved at week 9 each
year, end at 2.6 MB, growing about 50 KB a season, with a decode of about
0.1 s and an encode of about 0.35 s. The pools level off rather than grow:
coaches out of work settle near 250, because a man still out of work at
retirement age leaves the league (§4.7), and the GM pool stays at its
limit of 24. The spring preview behind the Staff screen still takes a few
milliseconds in season 30, and a simulated week about 90 ms. The
transactions wire is the one thing that grows without end, about 1,600
lines a season, and is inside the 50 KB.

---

## 10. UI (Compose)

### 10.1 Design language

Dense, tabular, readable, dark-mode-first. Think a well-set spreadsheet with good typography, not a mobile game. Monospace or tabular-figure font for all numbers so columns align. Everything reachable in ≤2 taps from the hub.

### 10.2 Screen inventory

| Screen | Contents |
|---|---|
| **Start** | Every fresh launch opens here, not on the last save: "Continue" for a dynasty still in memory (an offseason half done included), each save slot to load, and "Start a new dynasty". A rotation or a trip to another app keeps the screen the player was on. The hub's "Title screen" link, or back from the hub, returns here with the dynasty kept for Continue; back from here leaves the app |
| **New dynasty** | From the start screen or any empty save slot. The league generated first, then every club by division to choose from: the preseason outlook (a tier - contender, playoff hopeful, middle of the pack, rebuilding - never a rating), schemes, head coach, market size, cap room. "Surprise me" takes a random club |
| **Hub** | Week/phase and the next action (play the week, or call the plays yourself, with a line on the difference); needs attention; top news (five lines, no more than two of a kind, one line a man within a kind, the user's club's stories always; "All the news" for the rest); links grouped as Game day, Your club and League; standings snippet. A new dynasty's first hub opens with a welcome card (how a week works, playing vs calling the plays, what needs attention, when trades and the offseason come), shown once per install. Settings (theme, haptics, saves, player editing, tuning, the design gallery, About, the title screen) are their own screen |
| **Advance** | The single most-used control. Advance week / to next event / to end of phase |
| **Roster** | Sortable table, scouted ratings with error bars, contract, age, scheme fit badge. Below it, the season's defensive report: each defender's reps made (sacks, interceptions, throws covered, runs stopped at the line), times beaten and flags (`StatLine.playsMade`, `timesBeaten`, `defensiveFlags`), worst first, each opening his card |
| **Depth chart** | Per-package, drag to reorder, auto-sort by scheme fit, snap-share sliders; game day: who sits, hurt or scratched, who is called up, and the user's own scratches and call-ups (§6.1); special teams: each unit's men, and the user's core special teamers (§5.10) |
| **Game plan** | Coordinator tendency sliders, weekly opponent plan, focus practice |
| **Player card** | Bio, scouted ratings, revealed traits, career stats, contract, injury history, news |
| **Schedule / Scores** | League-wide, filterable |
| **Game center** | Drive chart, play-by-play feed, box score, snap counts, narrative recap. Watching a game or calling one, the last play's result sits at the top under the scoreboard (a note between plays, the weather or a timeout, is not a play) and the play history stays at the foot. Under the result it says what each side called, named from the club's playbook as the play-calling screen names it ("LV ran Shotgun Doubles - Double Slants"), and the defender the snap turned on, good or bad, by position, name and club (`sim.PlayReport`): a sack, an interception, a throw he had covered (the receiver lost his route), a run or scramble he stopped for no gain, a flag on him, or a catch of `beatenYards` (20) or more he was beaten on. A snap that turned on no one man - most runs - names nobody. Pass interference flags the man covering the target. A man hurt on the snap is named there too, with his club and how long he is out (`PlayLog.injured`). Calling his own game, the user can open his depth chart and roster over the game, which waits at his snap: a change to the depth chart - a hurt man's backup moved up, a struggling starter benched - plays from the next snap (`SnapCaller.depthPins`), and stays changed after the game; men hurt in the game are marked and sit whatever the chart says. The roster is to read there: inactives, call-ups and every other move wait for the final whistle. It opens on his defenders' tally for the game so far, worst first, counted from the play-by-play as the box score counts it, so the man he is losing with today is at the top. Each such rep is counted on the defender's stat line (`coverageWins`, `stuffs`, `timesBeaten`, `defensiveFlags`, with sacks and interceptions), and a game's box score lists each club's defenders by reps lost, worst first. The field shows the ball where it stands now, and when it has just changed hands says why and whose it is ("Interception · DEN ball"): the opening kickoff, a kickoff after a score, the second-half kickoff, a punt, a missed or blocked kick, an interception, a lost fumble, a turnover on downs. Each line of the play-by-play is a snap, a punt, a field goal or a note between plays (`PlayLog.kind`), so the field reads a kick from what it was rather than its words; a log saved before kinds were kept is matched against the game's own punt and field-goal lines, since most punt lines never say "punt". |
| **Standings** | Division/conference/playoff picture with tiebreak explanation |
| **League leaders** | Sortable stat leaderboards, all positions |
| **Finances** | Cap table, dead money, future years, restructure/cut tool with live cap impact |
| **Free agency** | Board, offers, negotiation, day-by-day market |
| **Expiring contracts** | Offseason: each expiring man's market and ask, a recommendation and why, every way to write a re-signing, the two tags, let him go (§7) |
| **Draft room** | Big board, your board vs consensus, needs, live picks, trade offers |
| **Camp** | Offseason: the camp roster with dead money if cut, the street to sign from, suggested cuts and why, the 46–53 bounds (§7) |
| **Trades** | Three views, one at a time: **Calls** (clubs' offers, with a count), **Build a deal** and **Your block**. It opens on Calls when a club has called. Building a deal: the club as one line (Change club opens the list), then *On the table*, which lists every piece both ways (tap one to take it off) with the other club's answer and how far short it is (§8.4). Below that is one roster at a time, yours or theirs, as a table filtered by position (tap a row to add or remove him), then the picks. The answer and Make the trade stay pinned at the top while the rosters scroll. Open to the deadline and at the draft room before the first pick. |
| **Edit players** | With player editing on (§10.5): a finder by club, position group and name over the whole league, and an editor with the player's position, true ratings by group and hidden traits. Behind Settings, and *Edit player* on the user's own players' cards. |
| **Staff** | The general manager and his style, the head coach and his hot seat (advice: nobody fires him but the user), the coordinators with their schemes and tendencies, the position coaches. In the spring window (§4.7), each job opens to let its man go or, when it is open, to hire from the pool, tap a candidate to look closer; a coordinator who would change the scheme says so. The hub offers *Hire and fire your staff first* beside *Start the offseason*, counting the jobs that will be empty when it starts and naming who is leaving |
| **Free agents (in season)** | The street and other clubs' practice squads to sign from, the user's squad and IR, releases with their dead money, the front office roster toggle (§6.1) |
| **Contract demands** | Demands from the user's own men: every way to pay, 90%/80% offers, refuse, or let the front office answer (§10.4) |
| **Transactions** | The league's wire by season, the user's club or everyone, filtered by kind (§4.7, §9.2) |
| **News** | The season's news so far by week, newest first, all of it or the user's club's (§10.1) |
| **About** | Behind Settings: the version ("1.0 (1)"), the privacy policy in short, a button that opens the full policy (https://itstrue54.github.io/gridiron-dynasty/) in the phone's browser, and one that writes to support (amfootballsimtext@gmail.com) with the version in the subject. The app makes no network request itself. |
| **Glossary** | Positions, ratings, roster and contract terms, and the game's basic terms, in plain words. Reached from the hub, Settings and the welcome card |
| **Saves** | Five slots and three autosaves: play, copy, overwrite, delete, restore, start a new dynasty in an empty slot (§9.1) |
| **History** | Champions, awards, records, franchise timeline, hall of fame |
| **Settings / Tuning** | Sliders (§12), sim speed, autosave, export |

Wherever ratings are read (the roster and the player card), a short legend says what the colours mean (90+ elite, 80s good, 70s starter, below 70) and what a range is. The roster says what Ovr, Scheme and Fit are, and gives fit as a letter, not a decimal. The trade screen labels the rating it shows as Ovr, the same number the roster leads with.

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
  swing is the change from its chance to the next snap's, or to the result
  after the last. Only snaps are told: the log's other lines (the weather
  before kickoff, a timeout) are never key plays, and what moves across one
  belongs to the snap before it. The recap tells the three biggest swings, and up to five
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
- **Banter:** the other side of a deal talks (`narrative/Banter`, from
  `narrative/banter.json`). A general manager pitches a call about one of the user's men (§8.4) and answers the user's trade
  offer as the verdict stands: yes; the roster limit; the cap; nothing in it
  for them (the offer is worth nothing to his club); close (it covers what
  they give but not their margin); or far. He signs off once a trade is
  made. A GM whose aggression is 0.5 or more is blunt, the rest are warm.
  An agent answers every contract offer: the counter that names his floor,
  walking away when talks run out, not taking the call, done talking, a
  deal signed (or signed under the market), and a demand refused. When the
  user signs a man before the market opens, the GM of the club he leaves
  has a word too. Agents are a pool of 40 generated names, fixed across
  leagues, and a man's agent is fixed by his id, so clients share agents as
  they do in the league. Words only: each line is drawn from the league
  seed split by the conversation (the key, who is talking, and what was
  offered), so the same offer draws the same answer however often it is
  shown, and nothing said moves the sim or the save.
- **Press conference / storyline beats:** a holdout, a rookie QB controversy, a coach on the hot seat.
  The *hot seat* is weekly news from week 8: a club three or more games under
  .500, once a season. The *rookie QB controversy* (`NewsKind.STORY`,
  `qb.controversy.picks` / `.yards`) is filed when a club two or more games
  under .500 had its veteran starter throw two or more interceptions, or
  under 150 yards on 15 or more throws, and has a healthy rookie QB (no
  accrued seasons) rated within 12 of him. It is filed once per rookie per
  season. It is the town talking: the depth chart, not the news, decides who
  plays. About one a season or two across the test leagues.
  The *holdout* (`ContractDisputes.atCamp`) happens as the new season opens.
  A man holds out of camp when all of these are true:
  - in the spring he told his club he wants paying (PlayerIntent's WANTS_PAYING);
  - he has the case an autumn demand needs: 3 accrued seasons, rated 74 or
    better, and a market past 1.8 times his cap hit;
  - his ego is `ai.holdoutEgo` (80) or more.

  His demand is then on the club's desk before week 1. The league's clubs
  answer at once, as they answer demands. The user's is on the Demands
  screen, and waiting costs morale as any demand does. Paid or not, he missed
  camp, so he starts the season `ai.holdoutForm` (30) short of form and
  `ai.holdoutMorale` (5) down in morale. Both the holdout and the club's
  answer are week 1 news; a new season opens with nothing else said. One to
  four holdouts a year across five test leagues, 2.4 on average.

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


### 10.5 Player editing

A dynasty can let its user rewrite players (`Dynasty.editPlayers`):
- **When it's set:** chosen on the club picker when the dynasty starts (off unless chosen), and switchable any time in Settings. A dynasty saved before the setting existed reads with it off.
- **Who:** any player in the league, from Settings → *Edit players*, a finder by club, position group and name. That covers every club's roster, the practice squads and the free agents. Draft prospects become editable once drafted. On the user's own players the card has *Edit player* too.
- **What:**
  - his **position**: a move to another group takes that group's first archetype, and takes him off his club's depth-chart pins at other positions;
  - all 53 **ratings**, 1–99;
  - his **hidden traits**: development curve, peak-age offset (−3 to +3), and the twelve 0–100 traits.
- **What doesn't change:** his age, contract and club. Overall is derived, so it follows the ratings.
- **True ratings, inside the editor only.** The editor shows and sets true ratings, the one exception to §4.6's lens (ADR: *The editor reads true ratings*). The roster, the card, the draft and the finder still read through the club's scouts.

The engine side is `season.PlayerEdits`: `current` reads a player as an edit, and `apply` writes one back, held to the ranges above, and only with editing on.

## 11. Accessibility & polish (non-optional)

- All tables navigable by screen reader with proper content descriptions.
  Built in `DataTable`, which every table uses: the table announces itself
  as a collection of so many rows and columns, each row is one item read
  names first and then every number with its column's name ("QB, R.
  Harlan, Age 27, OVR 58 to 71"; `describeRow`), a row that opens something
  is a button, and a sortable header says "sort by" and which way it is
  sorted.
- Respect system font scaling — a text game that breaks at 200% font size is broken.
- Sim a full week in under 2 s on a mid-range phone; show a determinate progress indicator.
  The week's progress is determinate: `DynastyEngine.advance` reports each
  finished game to an `onGame` listener that cannot change the week, and
  the busy overlay shows a bar and "Game 5 of 16", then "Finishing the
  week" while the news and the save are written. The playoffs and the
  offseason, which the engine runs as single steps, still show a spinner,
  unless the user is calling his playoff games.
  On the test phone a week takes about 1.8 s from the tap.
- Export a season as CSV/Markdown (people who play these games want to post about them).
  Built: the Standings screen shares the season so far and History any
  season on record (`data/export/SeasonExporter`). "Share this season" sends
  Markdown through the phone's share sheet - the champion, the user's
  record and place, his club game by game (byes included), every
  division's standings, the awards and the league leaders - and "Save the
  standings as CSV" writes one row a club to Downloads. Results and
  aggregates only, so nothing exported reads a rating (§4.6).

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
`ai` (FA aggression, trade frequency, draft BPA-vs-need weighting; how a front office values a player (the age curve) and prices production; the free-agency market, keeping one's own, tags, cap compliance, restructures, fifth-year options, contract demands and holdouts)
`trades` (the Johnson pick chart and its exchange rate, how a contender buys a star)
`intent` (what makes a player unhappy in the spring, when he asks out, and who takes him)
`staff` (the coaching carousel: the hot seat, firing, candidates and hiring; the spring pool; owners replacing general managers)
`needs` (what a club reads as a need at a position, for the draft, the market, extensions and trades)
`honours` (the Hall of Fame vote and the comeback award)
`scouting` (what a club knows of a player: the band, the trait thresholds, how its own men become known, and how a draft budget is spread)
`gameFlow` (tempo, plays per game, clock runoff, when a defence plays prevent)
`calling` (how down, distance and score move a coordinator's pass rate; sneaks, backs kept in to block, blitz adds)
`fourthDown` (go-for-it rates by distance and field position, aggression scaling, kicker range)

Ship 3 presets: **Realistic** (matches §13.2 bands), **Arcade** (higher scoring, more explosives), **Grinder** (lower scoring, run-heavy, more injuries). You will spend happy hours here.

The presets are offsets from Realistic (`TuningTable.ARCADE`, `GRINDER`), so they keep their character as Realistic is retuned. `PresetCharacterTest` holds them to it. On league 2026 (October 2026):
- **Realistic:** 22.0 points, 27.3 carries, 34.3 attempts and 2.30 sacks a team.
- **Arcade:** 26.0 points, more completions (0.71) and long plays (3.0 of 20+).
- **Grinder:** 18.6 points, 29.3 carries to 31.3 attempts, 2.65 sacks, and more close games (0.245).

Grinder had once leant on `pressureScale` for pressure, which only makes protection matter more, and so gave fewer sacks. Its pressure now comes from `sackGivenPressure`, and its lean to the run from the early-down pass rates. A league saved with the old Grinder keeps those values until its user picks a preset again.

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

`GameCalibration` measures every per-game band in this table. Until October 2026 it skipped five of them: rushes, pass attempts, passing touchdowns, sacks and fumbles lost per team. Four of the five were out of band, so the league ran too much and threw too little (`docs/CALIBRATION.md` pass 4).

Also check **distribution shape**, not just means: a league where every team goes 8-9 is broken even if the mean is right. Assert the standard deviation of team wins is 2.6–3.4.

The season bands are measured as means over seasons, because one season is noisy:
- **`SeasonShapeTest`** holds a new league's first season to the best-record, four-wins-or-fewer and spread bands, over eight leagues.
- **Ten-year dynasties** (`docs/CALIBRATION.md` pass 7) show the league settling after its first offseason and staying in band, repeat division winners included.
- **The generator** draws team strength with a spread of `LeagueGenerator.TEAM_STRENGTH_SPREAD` (2.2 overall points), which gives a first season the spread of wins a real season has: about 3.1 (band 2.6-3.4). It was 2.4 until special teams were played as units (§5.10): a club's units add to how far it stands from the rest, so the roster spread gives back what they take (`docs/CALIBRATION.md` pass 27). Over a long dynasty the league settles more even than that, about 2.8, inside the band but flatter than real football (pass 29); narrowing the generator to match would make new leagues flatter still, so it isn't.
- **Schemes are dealt, not rolled.** The generator holds each offensive and defensive scheme as evenly as 32 clubs allow, and shuffles them onto the clubs (`LeagueGenerator.deal`). Leagues differ in who runs what, never in the mix. Rolled club by club, one league in 40 drew six Air Raids and seven West Coasts, and ran past the pass-attempt and yards bands before a snap had anything to do with it (`docs/CALIBRATION.md` pass 10). The team streams still take the draws the schemes once came from, so rosters are unchanged.

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

- ~~**[OPEN]** Room in v1 or defer to v2?~~ **Resolved Sept 2026: not in v1.** A 30-season save is 2.8 MB and loads in 0.09 s on a desktop JVM (§9.3).
- ~~**[OPEN]** Real player names via an import file, or fully fictional only?~~ **Resolved Sept 2026: both.** Ship fictional; support user-supplied roster import. See §9.4.
- ~~**[OPEN]** Do coaches have their own progression/career arcs? Adds a lot of flavor; adds a lot of scope. Candidate for post-1.0.~~ **Resolved Oct 2026: yes** (§4.7 Careers): ratings rise to a prime, hold, and decline very slowly; position coaches can be promoted to coordinator.
- ~~**[OPEN]** Practice squad and gameday inactives — realistic, but is it fun or is it admin?~~ **Resolved Sept 2026: practice squads built** (§6.1); the user's squad is his to manage or hand to the front office. **Gameday inactives resolved Oct 2026:** 47 or 48 dress under the CBA, the front office picks the scratches and the practice-squad call-ups, and the user may name his own (§6.1).
- **[OPEN]** Monetization: free, one-time paid, or free with a paid "commissioner tools" tier? Doesn't affect architecture; decide at M11.

---

*End of specification v1.1 (v1.1: §9.4 reads JSON as well as CSV). Change this file in the same commit as the code it describes.*
