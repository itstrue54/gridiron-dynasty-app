# Issue seed list — M0 through M5

Paste these into GitHub Issues. Each one is sized for a single sitting. Set the milestone and labels as noted; check off the "Done when" boxes as you go.

Labels to create first: `engine` · `ui` · `data` · `ai-gm` · `calibration` · `bug` · `research` · `blocked` · `good-first-session`

---

## M0 — Project skeleton

**1. Create Android project and push to GitHub** `chore`
Done when: project builds; runs on emulator; `.gitignore` in place before first commit; repo is on GitHub, private.

**2. Add docs: SPEC, README, AGENTS, DECISIONS** `docs` `good-first-session`
Done when: `docs/SPEC.md`, `docs/DECISIONS.md`, `README.md`, `AGENTS.md` committed.

**3. Convert to multi-module: engine, engine-cli, data, app** `chore`
Done when: `settings.gradle.kts` includes all four; `:engine` is a `kotlin("jvm")` module with no Android plugin; `app` compiles against `data`; `data` compiles against `engine`.

**4. Add version catalog** `chore`
Done when: `gradle/libs.versions.toml` exists; every dependency and plugin version referenced from it; no hardcoded versions in any `build.gradle.kts`.

**5. Add CI workflow** `chore`
Done when: `.github/workflows/ci.yml` committed; Actions tab shows a green run; all three jobs (guards, engine, android) pass.

**6. Set up JUnit 5 + kotlin.test in :engine** `test`
Done when: `./gradlew :engine:test` runs and passes one trivial test.

**7. Create milestones M0–M12 and the Projects board** `chore` `good-first-session`
Done when: milestones exist per SPEC §14; board has Backlog / Next up / In progress / Blocked / Done; automation enabled.

**8. Add engine-cli module with a `--help` entry point** `engine`
Done when: `./gradlew :engine-cli:run --args="--help"` prints subcommands (`calibrate`, `simseason`, `dumpleague`).

---

## M1 — Domain model

**9. Value classes for IDs** `engine` `good-first-session`
`PlayerId`, `TeamId`, `CoachId`, `GameId` as `@JvmInline value class`. Done when: serializable and used everywhere instead of raw `Int`.

**10. `Position` and `Archetype` enums** `engine`
Done when: all 18 positions and the archetype list from SPEC §4.3 exist; `Archetype.position` maps back correctly; test asserts every position has ≥3 archetypes.

**11. `Ratings` data class** `engine`
Done when: all rating fields from SPEC §4.4 present; indexed access by `RatingId` enum; `applyDelta()` clamps to 1..99.

**12. `HiddenTraits` data class** `engine`
Done when: all fields from SPEC §4.5; `DevCurve` enum with multipliers.

**13. `Player` data class + serialization round-trip test** `engine` `data`
Done when: `Player` serializes to CBOR and back with full equality.

**14. `Contract` and cap math primitives** `engine`
Done when: signing bonus prorates over min(years,5); `capHit(year)`, `deadCap(year, postJune1)` implemented; unit tests cover a 5-year deal cut in year 3 both pre- and post-June-1.

**15. `Team`, `Staff`, `Coach`, `Facilities` data classes** `engine`

**16. `Scheme` model + JSON loading** `engine` `data`
Done when: `schemes.json` in `data` resources; loader parses archetype fit maps; test asserts all 16 shipped schemes load and every position has fit values.

**17. Author the 7 offensive schemes** `data`
Wide Zone/PA, Gap-Power, Air Raid, West Coast, Spread Option, Vertical/Shot, Run-Heavy Pro. Done when: each has archetypeFit, ratingEmphasis, personnelUsage, basePassRate, tempo.

**18. Author the 9 defensive schemes** `data`
4-3 Over, 4-3 Under, 3-4 Two-Gap, 3-4 One-Gap, 4-2-5 Nickel, Tampa 2, Cover-3 Match, Man-Blitz, 3-3-5.

**19. `schemeFit()` and `effectiveRating()`** `engine`
Done when: matches the formula in SPEC §4.9; tests assert a perfect-fit year-3 vet lands ~+4% and a bad-fit rookie ~-14%.

**20. `Rng` interface + `SplittableRng` implementation** `engine`
Done when: `split(label)` is deterministic; the same seed + label always produces the same stream; test sims the same play twice and asserts identical results.

**21. Name generator** `engine` `data`
Done when: first/last name pools in JSON with regional weighting; 10,000 generated names have <2% exact duplicates.

**22. `TuningTable` with defaults** `engine`
Done when: every group from SPEC §12 present; serializable; three presets (Realistic, Arcade, Grinder) defined.

**23. League generator: 32 teams with cities, divisions, stadiums** `engine`
Done when: 2 conferences × 4 divisions × 4 teams; each team has a stadium with dome/outdoor, altitude, crowd noise.

**24. Roster generator: fill 32 teams with 53-man rosters** `engine`
Done when: positional counts realistic and sum to 53 (3 QB, 4 RB/FB, 6 WR, 3 TE, 9 OL, 9 DL/EDGE, 7 LB, 9 DB, 3 K/P/LS); talent distribution produces a believable overall curve; every team is cap-compliant at generation.

**25. `League` root object + full save/load round trip** `data`
Done when: generate → serialize → deserialize → deep-equals; save under 5 MB gzipped; `saveVersion` field present.

**26. Save migration framework** `data`
Done when: `migrate(from: Int, to: Int)` chain exists with a no-op v1→v1; documented in SPEC §9.1.

---

## M2 — Play engine v0

**27. `PlayState` and `PlayResult` types** `engine`
Down, distance, yard line, clock, score, possession, timeouts. `PlayResult` carries yards, clock runoff, turnover, score change, and a `SimLog`.

**28. `DepthChart` with package overrides** `engine`
Base, nickel, dime, goal line, 3-WR, heavy, ST units. Done when: `personnelFor(package)` returns valid 11.

**29. Fatigue model** `engine`
Done when: per-snap accumulation weighted by position and `stamina`; recovery between drives and at half; test asserts an every-down RB at tempo 0.7 is materially fatigued by Q4.

**30. Run play matchup calculation** `engine`
Per SPEC §5.6. Done when: front advantage computed from blockers vs box; box-count penalty applied.

**31. Gap-scheme bonus lookup table** `engine` `data`
Done when: loads from JSON; covers inside zone / outside zone / power / counter / duo / trap × all 9 defensive fronts; values live in `TuningTable`; test asserts outside zone vs 3-4 two-gap is a penalty and power vs light box is a bonus.

**32. Run outcome sampling** `engine`
Done when: gamma-distributed yardage; long tail present; test over 100k carries shows median 3–4 yds, mean 4.1–4.6, and ≥0.5% of carries over 20 yds.

**33. Pass protection calculation** `engine`
Done when: OL pass block vs rusher power/finesse; RB/TE help; blitz overload bonus when rushers > blockers.

**34. Route/coverage win calculation** `engine`
Done when: per-receiver win value from route ratings vs man/zone coverage; double-team penalty; separation bonus from scheme × coverage table.

**35. Pass outcome sampling: pressure, sack, completion, YAC, INT** `engine`
Done when: staged pipeline per SPEC §5.7; all coefficients from `TuningTable`; test over 100k dropbacks lands completion % 55–75 (band tightens at M4).

**36. Fumble and interception logic** `engine`
Done when: fumble rate keyed to `ballSecurity` + hit power + weather; recovery is a coin-weighted draw; returns generated.

**37. `simPlay` orchestrator** `engine`
Done when: full pipeline per SPEC §5.3 runs; 10,000 random plays with no exception and no out-of-range values.

**38. `SimLog` structured play log** `engine`
Done when: every play emits participants, call, matchup values, and outcome; serializable; used later by narrative generation.

---

## M3 — Game engine

**39. Down-and-distance and drive state machine** `engine`
Done when: first downs, turnovers on downs, change of possession, safety, touchback all handled; test drives always terminate.

**40. Clock model** `engine`
Done when: play clock, runoff by outcome, out of bounds, incompletions, two-minute warning, timeouts, end of half/game. Test asserts a game produces 120–145 total plays.

**41. Coordinator `Tendencies` model + play calling** `engine`
Done when: pass rate varies by down, distance, score, time; 4th-down aggression drives go/punt/FG; two-minute logic exists.

**42. In-game tendency adaptation** `engine`
Done when: coordinators shift within ±0.12 based on HC `adjustments`; test asserts a 90-adjustments coach beats a 40-adjustments coach with identical rosters over 1,000 games at >55%.

**43. Special teams: FG, punt, kickoff, returns** `engine`
Done when: FG success curve by distance and kicker ratings; punt net yards; return TDs are rare but possible.

**44. Weather generation** `engine`
Done when: from stadium + month + regional climate; affects deep passing, kicking, fumbles; domes neutral.

**45. Penalty system** `engine`
Done when: per-type base rates in `TuningTable`; modified by `discipline`, `penaltyProne`, home/road; accept/decline logic; calibrates toward 5.5–7.0 per team per game.

**46. Injury system** `engine`
Done when: two-stage occurrence + severity; recurrence multiplier; `medicalStaff` effect; snap-load risk.

**47. `simGame` orchestrator + full box score** `engine`
Done when: a complete game produces a box score where every stat reconciles (rushing + passing + penalties = total yards; individual stats sum to team totals). Add a property test for this.

**48. Home field advantage** `engine`
Done when: crowd noise → road false starts and communication penalties; test asserts home win rate 53–58% over 10,000 games with equal teams.

---

## M4 — Calibration pass 1

**49. `engine-cli calibrate` command** `calibration`
Done when: `--seasons N` runs N seasons and prints a metric / target band / actual / pass-fail table; `--out file.md` writes markdown.

**50. Implement all 18 calibration metrics from SPEC §13.2** `calibration`

**51. Distribution-shape assertions** `calibration`
Done when: team-win standard deviation 2.6–3.4; best record mode ~14; teams at ≤4 wins between 2 and 5.

**52. Tune passing coefficients to band** `calibration` `tune`
**53. Tune rushing coefficients to band** `calibration` `tune`
**54. Tune sack and pressure rates to band** `calibration` `tune`
**55. Tune scoring and plays-per-game to band** `calibration` `tune`
**56. Tune third-down and red-zone rates to band** `calibration` `tune`

**57. Commit `docs/CALIBRATION.md` with the first passing report** `docs`
Done when: ≥12 of 18 bands pass (M4 exit criterion).

**58. Golden-seed snapshot test** `test`
Done when: a fixed seed's season output is snapshotted; CI fails if it changes; the snapshot is regenerated only in a commit that explains why.

**59. Property tests for league invariants** `test`
Done when: no player on two rosters; roster sizes valid per phase; cap ledger balances; every game has a valid result. Runs over 100 randomly seeded seasons.

**60. Nightly calibration workflow** `chore`
Done when: `.github/workflows/calibration.yml` runs 1,000 seasons on a schedule and uploads the report as an artifact.

---

## M5 — Season

**61. Schedule generator** `engine`
Done when: 17 games, 1 bye, correct division/rotation structure per SPEC §6; constraints honored (bye weeks 5–14, no 3 straight road).

**62. Standings and the full NFL tiebreaker cascade** `engine`
Done when: implemented as an ordered list of testable predicates; unit tests cover 2-way and 3-way division ties, H2H sweeps, and conference-record breaks.

**63. Playoff bracket: 7 seeds, bye, reseeding** `engine`

**64. Season stat aggregation and league leaders** `engine`

**65. Awards voting** `engine`
Done when: MVP, OPOY, DPOY, OROY, DROY, CPOY, COY, All-Pro, Pro Bowl; weighted score + deliberate voter noise; test asserts the MVP is a QB 60–75% of the time.

**66. `simSeason` end to end** `engine`
Done when: `engine-cli simseason --seed 42` produces a champion, full standings, and league leaders with no errors.

**67. Playoff-picture explanation strings** `engine`
Done when: for any team the engine can state why it is in or out ("wins division on conference record, 9-3 vs 8-4").

---

*After M5, the next issue set is M6 — the minimum playable app. Write those issues when you get there; by then you'll know what the UI actually needs.*
