# October 4 diagnostics: delegation tools and debug provenance

Source: supplied app-diagnostics.log, 18:45:52–18:56:30 UTC. No personal log payload is committed.

## Findings and changes

- Location succeeds at 18:53:08 in a normal run. In local-first delegation, primaryDelegationTools filtered out all non-web/non-GitHub tools at ownership below 35. Preserve other authorized capabilities at every ownership setting, and keep the complete authorized/budgeted catalog for workers even when primary context selection prunes schemas. Prioritize Location before optional schemas, and apply existing explicit-location grounding to local delegated sessions. Android permission checks and MCP approvals remain in place.
- Child agents had an unrelated four-call/three-round cap. Use the configured profile/chat tool allowance and shared parent budget, with the existing watchdog/deadline still enforcing runtime limits.
- Reviewer roles were logged correctly but omitted from live DelegationText events. Preserve a Reviewer role label across streaming/final snapshots. Render reviewer output yellow and score values at three times bodySmall size.
- Debug recall previously displayed only generic labels such as Saved fact. Resolve recorded IDs against scope-eligible encrypted vault facts on an IO dispatcher. Display actual sourced memory text in pink, even with activity collapsed; highlight matching fact phrases in final Markdown only in debug memory mode. Explicit memory recall tool traces now persist IDs/count, not plaintext facts. Deleted, superseded and excluded-scope facts do not resolve. Paraphrases are not guessed to be memory-derived.
- Two enrichment jobs skipped with SOURCE_CHANGED. Schedule from the persisted message, not request-only augmented prompt text; retain stale-source checks for genuine edits/deletions.
- Three enrichment jobs skipped because no model was loaded. This is an expected opportunistic condition, not a reason to load a model or retry repeatedly. Remove the preceding unconditional full semantic-index rebuild, which repeatedly cleared cached embeddings even without an inference model. Normal vault loading synchronizes changed facts.
- Three reviewed research passes returned tiny briefs with structured=0 and pages=0; the primary made 32 web_search calls across this log. Extend source parsing for MCP sources envelopes and aggregate engine detail text. Cache identical successful searches for 30 seconds within the run, including concurrent callers, retaining full evidence. Changed arguments and failed/empty/budget-exhausted results are not reused. Different follow-up questions retain tool access.
- Token totals mixed provider input with estimated output: the first request reports 192 input + 25 output = 217, while compute totals reported 226. Use observed output when supplied; aggregate usage across child rounds instead of taking a single round's maximum. Fall back to estimates only when usage is absent.
- One primary OpenRouter stream abort occurred after partial output. This is a transport failure; the existing runner intentionally avoids replaying a partly emitted response or side effects. This change does not claim to fix the provider/network interruption.
- Three oversized pages were bounded at 1 MiB; a Washington Post read had an HTTP/2 stream reset. These are retained as source limitations, not treated as Android crashes.
- Frame telemetry totals 592 janky frames / 36,936 sampled frames (1.60%), with a 131.49 ms maximum. Removing per-turn full embedding rebuilds and caching repeated research targets unnecessary work; device profiling is still required to establish the actual improvement.

Final answer token limits and final response content are not reduced by this patch. Memory highlighting changes presentation only.

## Validation

Regression coverage added for delegation tool exposure, MCP source parsing, successful/failed repeated searches, and memory span boundaries/debug-off behavior. Android regex/resource preflight and git diff checks pass locally. Gradle compilation/unit tests could not start locally because the environment cannot reach services.gradle.org to download Gradle 9.8.0. Android build, unit tests and device-level display/GPS checks remain required.
