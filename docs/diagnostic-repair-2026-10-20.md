# October 20 diagnostic repair

Branch: `fix/diagnostic-research-actions-2026-10-20`  
Base: `159730e40ce196111a3a6b3647ad9b23e3373904` (0.9.55.0, build 124)

## Evidence and scope

The supplied export contains 4,631 JSONL records: 2,365 events and 2,266 continuation records. Events span 17:32–17:51 UTC on October 20. Continuations are not additional incidents. This change does not include the export or credentials in the repository.

| Observed issue | Change | Remaining constraint |
| --- | --- | --- |
| Examine rejects optional field values as non-extractive; multiple attempts fail at chunk 0/2 | Match case/whitespace differences back to literal source bytes. Unsupported values become null/UNAVAILABLE with recorded limitations; exact passages and valid fields survive. Duplicate field keys preserve conflicts rather than selecting a convenient value. Incomplete extraction cannot earn a full-coverage assessment score. Strict schema, source hashes, passage IDs and quotation checks remain mandatory. | A fabricated quote, malformed JSON or unsupported outcome cannot become a grounded assessment. |
| Examination output failures are repeatedly retried by automatic work | Park output-invalid outbox rows for an explicit Examine/Resume action. Preserve waiting/paused state when acquisition revisits the same article version. | Manual retries still use the selected provider and current permissions. |
| AUTO_BLOCKED repeatedly reports missing examiner | Show the crawler selector and admission reason before starting from a study card. Configuration revision excludes appearance and pricing-refresh metadata. Wake workers on source discovery/completion rather than every progress save. | User must select an eligible profile; disabled retrieval and paper consent are respected. |
| Profile does not permit article retrieval | Same admission check drives UI enablement and execution; classify the blocker as a configuration wait. | The app does not silently override profile tool permissions. |
| DISPATCH_POLICY_CHANGED while the user selects an examiner | Crawler choice is excluded from the writer's policy revision; scope, depth, tool permissions and budgets still invalidate stale requests. | Real authorization changes continue to stop dispatch. |
| Report repair repeats UNSOURCED_SCIENTIFIC_PARAGRAPH and omits leading studies | Remove Markdown heading lines from paragraph claim checks, provide paragraph-specific repair feedback, and show actual source IDs in writer instructions. Explicitly require readable and unread lead coverage. | Unsupported scientific findings remain rejected; evidence fallback remains available. |
| Structured extraction reaches the provider output limit | Request compact claims and shorter exact quotations within the existing token allowance. Preserve negative findings and limitations. | A provider can still truncate; incomplete JSON cannot be accepted as a finished extraction. |
| DNS failures and aborted requests during research | Give DNS and network-unreachable retries a bounded 1.5/3-second recovery window rather than subsecond retries. Existing payload and tool-activity replay fences remain in force. | This cannot restore device DNS, VPN routes or an offline server. |
| MCP ports 8101/8102 time out; publishers return 403 | Preserve existing degraded/unreachable server status and retained research evidence. | The servers must be reachable and publishers must permit access. No claim that application changes repair those external services. |
| Android JobInfo ignored important-while-foreground hint appears 301 times | Classify only that specific warning as informational while retaining every record. | Other JobInfo warnings and errors remain actionable. |
| Research actions appear as text; narrow action rows can overflow | Use outlined buttons for workspace, archive, article, comparison and recovery actions; tonal/filled buttons for primary article actions. Wrap action rows. Study cards use explicit action buttons; admission is guarded against double taps. Same-article progress refreshes retain visible details and preserve the latest action error instead of blanking the dialog. | Device visual and interaction checks remain necessary. |

## Verification

Completed preflight: 147 Python checks, Android resource validation, Room schema-history validation, and Kotlin formatting checks. Twelve standalone JVM regression tests pass, including the corrected deterministic configuration serialization. Final standard Android Gradle validation succeeded: 76 targeted JVM tests across 11 classes, zero failures, zero errors, zero skipped. KSP, KAPT, Hilt, resources, production Kotlin compilation and unit-test compilation completed through the normal task graph. No generation tasks were excluded in the successful final run. Kotlin formatting used ktlint 1.3.1. On-device visual checks and live provider/MCP connectivity tests were not performed. Regression coverage includes strict and tolerant extraction, formatting-only recovery, source integrity, writer authorization boundaries, blocked-job configuration revisions, DNS retry timing and replay prevention, report paragraph repair hints, and scheduling-hint classification.

On-device checks: Examine with an eligible profile; missing/disabled crawler setup; slow/offline networking; pause/resume; blocked publisher; report repair from retained evidence; workspace/archive buttons at large font size; repeated taps while examination is being admitted. Confirm no unsupported value earns a complete assessment score.
