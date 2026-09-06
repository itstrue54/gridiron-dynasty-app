# Architecture Decision Log

## ADR-001: Engine is a pure Kotlin/JVM module
Date: 2026-09-05
Status: Accepted

Context: Android-coupled simulation logic would be untestable at speed and
unusable by the planned college football sim.

Decision: :engine has zero Android dependencies. CI enforces this with a grep.

Consequences: Save/load and file access live in :data. Slightly more
boilerplate crossing the module boundary. Calibration can run headless on the
JVM in seconds.
