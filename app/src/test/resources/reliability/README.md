# Unified reliability incident regressions

These fixtures contain synthetic identities, URLs and text. Only aggregate counts and payload shapes come from the three original incident exports. Original exports and their private audit hashes are excluded from the repository.

- `IncidentShapeRegressionTest` checks routing-only override presence, full source chunk coverage and reasoning-only stalls.
- `SearchIncidentRegressionTest` reproduces the Markdown `**URL**:` / DuckDuckGo redirect failure and a checkpoint containing fourteen result rows plus five page rows. It also checks that delivery limits do not erase retained acquisition rows.

These deterministic tests are a subset of the acceptance plan, not substitutes for real runtime qualification or complete incident replay.
