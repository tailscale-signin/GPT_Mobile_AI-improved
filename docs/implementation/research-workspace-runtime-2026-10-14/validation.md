# Validation record

- Application and unit-test source compilation: PASS, Java 21 / Android 37 / Gradle 9.8, local offline dependency cache.
- Kotlin formatting 1.3.1: PASS after formatting.
- Focused regression suite: RUNNING. The final result will be recorded before review.
- GitHub workflows: PENDING on the draft branch.
- Target ASUS/native denial and restart acceptance: NOT_RUN; the device is not available in this environment.
- Release-like ASUS and comparison-device performance traces: NOT_RUN.
- Live gateway ports 8101/8102 failure injection: NOT_RUN; those servers are not connected to this environment.
- Supplied-capture incident fixture: NOT_RUN; the full raw log was not among the three supplied design/audit files. Synthetic 142-row and repeated-crash regression coverage is included.
- Signed release completion gate: BLOCKED. `python3 scripts/check_unified_reliability_v2.py --require-done` reports 48 unfinished task records and unpassed cases. The gate is preserved.

CI success is not a substitute for these device or external-system acceptance requirements. No passing device evidence or full-release qualification is claimed.
