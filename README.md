# NFL Sim Text

A text-based NFL dynasty simulator for Android. No graphics, no play calling — you run the front office and the game reports back in drive summaries, box scores, and league news.

The design target is CPU-vs-CPU slow-sim play: the fun lives in roster construction, scheme fit, player development, and the draft.

---

## Status

**Milestone:** M0 — project skeleton
See [`docs/SPEC.md`](docs/SPEC.md) §14 for the full roadmap.

---

## Architecture

```
engine/       Pure Kotlin/JVM simulation. Zero Android dependencies.
engine-cli/   JVM entry point for batch sims and calibration reports.
data/         Save/load, serialization, schema migration, seed data.
app/          Android + Jetpack Compose UI.
```

Dependency direction: `app → data → engine`. Nothing depends on `app`.

**The engine must never import `android.*`.** CI enforces this.

---

## Building

```bash
./gradlew build              # everything
./gradlew :engine:test       # fast — the tests you run constantly
./gradlew :app:assembleDebug # APK
```

Calibration run (see [`docs/CALIBRATION.md`](docs/CALIBRATION.md)):

```bash
./gradlew :engine-cli:run --args="calibrate --seasons 1000"
```

---

## Documentation

| File | What it holds |
|---|---|
| [`docs/SPEC.md`](docs/SPEC.md) | The technical specification. Source of truth. |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Architecture decision log — what was chosen and why. |
| [`docs/CALIBRATION.md`](docs/CALIBRATION.md) | Target statistical bands and the latest run's results. |
| [`AGENTS.md`](AGENTS.md) | House rules for AI coding assistants working in this repo. |

---

## Conventions

- Model classes are immutable `data class`es with `val` only.
- All randomness comes from the injected `Rng`. Never `Math.random()`, `java.util.Random`, `System.currentTimeMillis()`, or `UUID.randomUUID()`.
- All simulation coefficients live in `TuningTable`, never as literals inside functions.
- Every engine change gets a unit test.
- `docs/SPEC.md` is updated in the same commit as behavior it describes.

Commit prefixes: `feat:` `fix:` `test:` `docs:` `tune:` `refactor:` `chore:`

---

## License

Private project. Fictional players and teams; no licensed NFL content.
