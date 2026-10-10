# Debug conversation export and Combined editorial recovery

Branch: `feat/debug-export-combined-integrity-2026-10-10`.
Base: `main` plus `feat/benchmark-local-assist-tool-integrity-2026-10-10`.

## Evidence and scope

Reviewed `diagnostic_evidence.md`, a filtered extract of 5,964 JSONL records with source SHA-256 `c134e4887e35ef9087c5737bc41150d1d0a18df9182424ebe0206462ec12738f`. It contains four explicitly marked synthesis requests (estimated inputs 43,679–66,913), all initially stopped at 4,096 output tokens. It does not contain a final-body-to-run mapping, so neither exact contribution coverage nor a particular final-answer defect can be proven from this evidence alone. This implementation adds the missing evidence rather than claiming a measured semantic-coverage score.

## Implemented behavior

- Debug mode exposes a third conversation export card, **Debug export**, with a `.debug.json` extension and JSON MIME type. A ViewModel guard also blocks invocation when Debug mode is off.
- Exports all stored conversation messages, including prompts, thinking, revisions, timeline items, original Combined contributions and attachment metadata. Fetches the complete conversation instead of the 40-turn display window, overlays visible revision selections and in-progress messages, and performs JSON preparation on IO.
- Adds conversation-scoped run, invocation, tool-event, workspace/context-receipt, queued-prompt, conversation-correlated retained log and research-history records without the diagnostics screen's recent-record limits. Exports decrypted retained context receipts when available. Missing/evicted/never-recorded evidence is explicitly described, including the absence of attachment bytes and provider wire captures.
- Exports relevant profile settings, current chat tool configuration, feature settings and composer text. Credentials are removed recursively, including embedded JSON arguments/payloads, authorization headers, credential query parameters and configured profile token echoes. Token-count fields and large results/arrays are preserved.
- Maps response and revision bodies to message/run IDs and SHA-256 fingerprints. Maps the original C1…Cn contributor bodies to profile IDs and fingerprints. Retains bodies for manual semantic review; prompt inclusion is not labeled as proven final coverage.
- While Debug mode is on, records redacted assembled primary/synthesis/continuation request inputs: full turns, system instruction, selected tool schemas and output/tool/reasoning constraints. Temporary conversations do not acquire durable debug request records. Optional debug capture failures do not abort model requests. These are assembled application inputs, not a promise of byte-for-byte provider wire logging.
- Request and finish log records now carry parent run ID, turn key, request kind and profile identity. Request configuration events log the actual effective completion cap. Finish logs classify token counts as estimated if either input or output is estimated.
- Combined synthesis uses its editorial instruction as the system prompt, avoiding unrelated profile system instructions that could override the merge. It explicitly preserves substantive unique details, examples, qualifications and URLs and defaults to a detailed organized answer. No new research or tool execution is allowed.
- Uses a 16,384-token synthesis segment only when both profile and application output limits are unset. Explicit limits remain in force. Bounded continuation scales with full contributor size and requested word goal (3–16 continuation requests), preserving the original contribution envelope, draft and completed evidence.
- Transient tool-free primary/editorial overload/read/reset/timeout failures get at most two text-only retries with cancellable backoff and saved partial output. Quota, authentication, context, certificate/handshake and refused-connection failures do not enter this retry path. Tool-bearing requests are not automatically replayed.
- OpenAI-compatible requests can learn an explicit provider-reported completion ceiling scoped to endpoint/model/routing. The evidence's `max_tokens=256000` rejection can retry once at 131072, before any text/thinking/tool payload is delivered. Later requests in the process apply the learned ceiling; explicit smaller caps win. Context windows and unrelated numeric errors are never guessed as completion ceilings. Credential rotation after partially delivered payload is blocked to prevent duplicated output/actions.
- Bounds total retained continuation text across segments, not just each individual segment.

## Existing safeguards retained

The free-provider limiter already defers requests until the provider's allowance resumes; an 85,492-second allowance is not a short transient outage. A refused llama-server connection requires restoring endpoint reachability. Neither can be made successful by an app retry loop. Existing context-budget checks still reject an oversized current contributor envelope rather than silently removing contributions. Existing overlap removal prevents literal continuation restarts without deleting conflicting dates or distinct facts.

## Validation

Local results: 18 focused JVM checks passed (using platform-type stubs), 51 repository script tests passed, changed Kotlin ktlint passed, and Android regex/resource/icon preflight passed. Adapter integration and serialized-export regression tests are added for the full Android unit suite but could not run locally.

Focused regression tests cover large contributor recovery budgets, transient partial-output recovery and bounded retries, quota exclusion, explicit completion-ceiling learning and route isolation, credential redaction inside embedded JSON, retention of long results/arrays and token counts, and the adapter's once-only ceiling retry. Full Android/Room/Compose validation requires CI after the branch is published. Android build is not available in the local environment because the pinned Gradle distribution download is blocked.

## Device checks

1. Toggle Debug mode off/on: only the enabled state offers Debug export; text/Markdown exports keep their previous response-only behavior.
2. Export a conversation longer than the visible history window with historical revision selection and Combined mode enabled. Verify all messages, original contributors, revision/run mappings and tool records are present.
3. Export during generation and compare the snapshot with the eventual response; snapshots must not claim terminal completeness.
4. Run a long Combined request with a 4,096-token profile cap and multiple contributors. Check continued output and parent-run correlation. Compare unique substantive facts manually, including conflicts and source URLs.
5. Interrupt the synthesis stream; confirm saved text is preserved, retries are bounded, and no tool action is repeated.
6. Simulate an explicit 256,000 → 131,072 provider rejection; confirm one corrected request and unchanged saved profile preference.
