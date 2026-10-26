# Build 129 diagnostic repairs

Source: `app-diagnostics-828675934848396985.jsonl`, app 0.9.61.0,
commit `b20ae8e0f14259d04f05c5c51e996e2149501166`.

The export has 4,435 lines: 4,417 parse as strict JSON and 18 contain
unquoted array values. No private conversation contents are copied here.

| Finding | Repair or interpretation |
| --- | --- |
| Six chat debug exports fail in Android JsonReader | Quote non-JSON primitive literals before emitting structured fields. Normalize retained diagnostic trees during chat redaction too. Keep the bundle reader strict and preserve values and checksums. |
| Profile arrays make 18 diagnostic lines invalid JSON | The same normalization applies at diagnostic field extraction, including nested arrays. Native numbers, booleans and null retain their types. |
| OVH stream aborts after receiving visible answer text | Recognize Android's `Software caused connection abort` and `ECONNABORTED` as transient stream failures. Existing bounded recovery preserves the prefix and cannot replay an initial tool-bearing request. Quotas, authentication and certificate failures remain ineligible. |
| Repeated Vireonix HTTP 502 failures never become unavailable | Count matching generation failures per model and credential and apply the existing bounded circuit breaker. Preserve longer upstream retry deadlines. A successful generation resets the affected route. |
| Combined prompt preparation takes 2.5–9.9 seconds | Share clause fingerprints across all conflict comparisons in one scan, avoiding repeated regex/normalization work per pair. Preserve every claim, qualifier and comparison decision. This is a code-level optimization, not a measured device speedup. |
| Padded `W/JobInfo (pid)` tags are counted as issues | Trim the captured logcat tag before classification, so the existing informational platform-job rule matches actual device logs. |
| Seven provider rate-limit records, including LLM7 daily quota and Cehpoint high demand | Upstream limits are real. Existing durable quota/cooldown admission remains in force; do not bypass it or claim free quota can be restored by client code. |
| Four reasoning-budget failures from BlockRun | Existing provider thinking controls and reasoning-only admission safeguards remain relevant. An upstream model can ignore controls; no successful visible answer can be guaranteed by changing the client. |
| Substantive Combined answers retain coverage/link issues | Keep the existing incomplete/limited delivery reports, frozen contributions and explicit recovery actions. Do not mark uncertain links as verified or add automatic rewriting calls. |
| A two-word final answer and zero claim inventory on a conversational turn | These measurements alone do not establish a defect: greeting/test prompts deliberately bypass substantive claim extraction. Do not force a long answer or borrow a previous topic. |
| Examination worker reports NO_OP with zero items | No retained evidence of a failed requested examination in this export. A scheduled empty outbox is not a missing report. |
| Rendering stalls, including a 263 ms sample | Export failure/logging churn and repeated conflict parsing are addressed above. Actual device frame-time improvement still requires a new ROG Phone capture. |

Regression coverage includes log-style arrays, historical tree redaction, the actual
strict streaming bundle producer/verifier, abort recovery with retained text,
cached/uncached conflict decisions, model/credential circuit-breaker isolation,
upstream retry deadlines, and padded Android tags.

Local validation: 191 repository Python tests, resource preflight, Android regex
checks and reliability scope checks passed. Android unit tests/lint could not run
locally because the Gradle distribution download is unreachable. CI is required
before treating the Android changes as validated.
