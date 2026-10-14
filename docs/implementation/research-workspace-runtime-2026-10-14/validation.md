# Validation record

- Application and unit-test source compilation: PASS, Java 21 / Android 37 / Gradle 9.8, local offline dependency cache.
- Kotlin formatting 1.3.1: PASS after formatting.
- Earlier focused regression suite: 191 tests passed; the full 2,882-test run exposed one new recall-fixture failure, now corrected and awaiting rerun. Exact-version citation refresh regression and synthetic memory-pressure pause passed in the subsequent focused run.
- Python repository checks: 103 tests passed; Android regex validation passed.
- Android lint: PASS before the final checkpoint/share/cache changes; final rerun pending. JaCoCo 0.8.15 directly instrumented all 194 ChatRepository classes after the method refactor.
- GitHub workflows: PENDING on the latest draft changes.
- Live PubMed endpoint smoke: PASS on October 14, ESearch asthma[Title] returned IDs and EFetch returned two XML articles (14,393 bytes). This checks the public endpoints, not target-device dispatch.
- Target ASUS/native denial and restart acceptance: NOT_RUN; the device is not available in this environment.
- Release-like ASUS and comparison-device performance traces: NOT_RUN.
- Live gateway ports 8101/8102 failure injection: NOT_RUN; those servers are not connected to this environment.
- Supplied-capture incident fixture: NOT_RUN; the full raw log was not among the three supplied design/audit files. Synthetic 142-row and repeated-crash regression coverage is included.
- Signed release completion gate: BLOCKED. `python3 scripts/check_unified_reliability_v2.py --require-done` reports 48 unfinished task records and 99 NOT_RUN acceptance cases. The gate is preserved.

CI success is not a substitute for these device or external-system acceptance requirements. No passing device evidence or full-release qualification is claimed.
