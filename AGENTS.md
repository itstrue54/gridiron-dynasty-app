# House rules for AI coding assistants

Read `docs/SPEC.md` before making architectural changes. It is the source of truth for this project; this file is the short version of the rules that are easy to violate.

## Hard rules — CI fails on these

1. **`:engine` is pure Kotlin/JVM.** Never `import android.*` there. Anything needing Android APIs belongs in `:app` or `:data`.
2. **All randomness comes from the injected `Rng`.** Never call `Math.random()`, `java.util.Random`, `kotlin.random.Random.Default`, `System.currentTimeMillis()`, `System.nanoTime()`, or `UUID.randomUUID()` inside `:engine`. Determinism is a product requirement, not a preference (SPEC §5.11).
3. **Dependency direction is `app → data → engine`.** Nothing depends on `app`.

## Strong conventions

4. **All simulation coefficients live in `TuningTable`** (SPEC §12). No magic numbers inside sim functions. If you need a new constant, add a named field to the tuning table.
5. **Model classes are immutable `data class`es with `val` only.** State transitions return new objects.
6. **`overall` is derived, never stored.** It depends on scheme; computing it requires a scheme context.
7. **Ratings are never read raw in the UI.** Everything the player sees goes through `ScoutingLens` (SPEC §4.6).
8. **Scheme, narrative, and name data live in JSON resources**, not in Kotlin source. Adding a scheme must not require a recompile.

## Testing

9. Every engine change needs a unit test. Run `./gradlew :engine:test` before proposing a change.
10. Behavior changes that could move league statistics must be checked against the calibration bands in SPEC §13.2. If a band moves, say so explicitly in the commit message.
11. Do not weaken or delete a failing test to make a build pass. Report the failure instead.

## Documentation

12. **Update `docs/SPEC.md` in the same change** as any behavior it describes. A spec that drifts from the code is worse than no spec.
13. Record non-obvious architectural choices in `docs/DECISIONS.md` as a short ADR entry.

## Commits

Prefix format: `feat:` `fix:` `test:` `docs:` `tune:` `refactor:` `chore:`
One idea per commit. Explain *why* in the body when it isn't obvious.

## Scope

Do what was asked. If a change requires touching an area outside the request — especially the RNG, the tuning table, or the save schema — stop and say so rather than doing it silently. Save-format changes require a migration function in `data/migration/` in the same change (SPEC §9.1).
