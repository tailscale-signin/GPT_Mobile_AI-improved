# Shared-input multi-chat and diagnostic recovery

Input: app-diagnostics-3611955424703685780.jsonl, 7,740 exported records, app 0.9.53.0 / build 122 / e1dec3cd1107.

## Implemented changes

- New multi-chat creates one STANDARD room containing every selected profile. Existing generation orchestration persists one question and dispatches separate responses to active, unpaused profiles. Combined synthesis remains a distinct mode.
- Legacy split workspaces upgrade the root profile registry and display the shared ChatScreen. Other rooms and their histories remain intact and accessible using previous-chat buttons. They are not silently merged into another model's context.
- A themed composer panel identifies broadcast versus explicit one-message targeting, displays profile readiness/paused/generating status, and opens profile management. Per-response profile selectors continue comparing model responses.
- Keep input focus after sending to reduce unnecessary IME disconnect/reconnect cycles. Navigation still releases focus.
- Replace hardcoded gray pending-profile backgrounds, foregrounds and borders with theme roles. Neutral Material backgrounds, surfaces and outlines receive the active accent; chromatic custom surfaces retain their colors.
- DNS failures are deferred discoveries, retried on a later research invocation after recovery. They no longer add a five-minute negative reader cache entry. Shared network-outage detection stops page acquisition and reports Waiting for network rather than claiming evidence is exhausted. HTTP access denials remain terminal for the affected page.
- Invalid tool arguments return the required schema and correction instructions before any action is dispatched. No missing question is invented, and invalid calls do not consume an execution slot.
- MCP retry intervals progress from 5 seconds through 5 minutes for persistent failure. Existing authentication/configuration gates, manual refresh and health-reset behavior remain in place.
- Diagnostic persistence drains batches continuously instead of sleeping 250 ms between batches. UI snapshots are separately coalesced, and the queue remains bounded. Historical dropped records cannot be recovered.

## External conditions and verification limits

- The configured private MCP endpoint on port 8101 is unreachable. The server, address, local-network permission and network route must work before tools can connect. Backoff does not repair server availability.
- Publisher HTTP 403 and device DNS failures cannot be guaranteed away by an application patch. Research retains explicit incomplete/no-readable-source outcomes; snippets are not upgraded to verified evidence.
- The ASUS `asus_leak_detect_proj` SecurityException originates in ActivityThread/BinderInternal finalization, with no application frame. The app cannot make a restricted ASUS settings key readable. This export does not demonstrate an application-native crash or ANR.
- MediaPipe's feedback-tensor warning reports unsupported optional feedback for a multi-signature model; it is not evidence of failed inference.
- OS process-exit reason 10 is not treated as proof of an app crash.

Validation completed locally: 121 Python contract tests; Android resource preflight (28 XML files); Android regex checks; git diff whitespace check. Kotlin regressions cover one-room multi-chat creation, DNS recovery and reader-health behavior, and theme surface contrast. Android build/Kotlin test execution is blocked by Gradle distribution download network restrictions. No device verification or CI success is claimed. Publication initially required explicit approval; after approval, the branch was published through the connected GitHub integration.

## Combined Mode audit follow-up and home interaction changes

The same branch additionally implements:

- User length, language, topic and example-count instructions take priority over exhaustive merging. All contributor inputs remain frozen in full. Small word targets and strict ceilings are parsed separately from continuation budgets. A single editorial rewrite can fix a demonstrated length/duplication violation; a deterministic sentence-boundary ceiling prevents overlong buffered output if that rewrite fails. No prompt requires all unrelated examples to appear.
- Lexical coverage remains a hint and never triggers automatic appendix generation. Missing/paraphrased text is retained in Debug metadata, not duplicated in stored, copied or exported prose. No factual or semantic certification is inferred from zero lexical misses or output-segment integrity.
- PASSAGES_V2 uses newline-delimited paragraph records with contributor and claim IDs, canonical citation IDs and per-claim inclusion/exclusion explanations. Protocol records are decoded off the UI thread and withheld from prose. Explicit attribution is model-declared contribution lineage, not independently verified factual ownership. All jointly contributing IDs are retained; a sorted stable primary display color is used in normal prose. Invalid/stale ranges or references do not confer ownership. Legacy retries preserve their original plain-text contract.
- Broad answers progressively publish completed paragraph records. Requests with explicit scope/length/current-news constraints are buffered for instruction checks. Only one quality rewrite is allowed, rather than repeated lexical repairs. A run-level claim inventory is reused across checks; no source is discarded to reduce repair cost.
- A canonical tool-evidence ledger retains article URLs, publication dates, readable passages versus discovery snippets, original tool-event IDs and contributing profile IDs. It is frozen with the synthesis snapshot. Native current_date receipts and the device-clock turn date/timezone outrank model date guesses. Existing authorized read-only request sharing and research corpus reuse remain permission-scoped.
- Current-news requests with no readable, dated, specific article return an explicit evidence limitation without executing a speculative synthesis. A model consensus, homepage or search snippet cannot satisfy this gate. This is a conservative evidence check, not proof the newest article on the internet was found.
- Per-tool permission and credential failures open a turn-scoped circuit even if the model changes arguments. Authentication failures remain distinct from transient reads; publisher access denials do not disable the entire reader. Grants are still validated before dispatch; no automatic permission expansion occurs.
- Completion and quality are saved separately in timeline metadata. Debug Mode exposes instruction issues, unresolved lexical hints, unverified passages, frozen date/timezone, source references, all contributor IDs and model-reported dispositions. Structural attribution, passage support, semantic completeness and factual accuracy are explicitly distinct.
- Markdown preparation has an Android trace section; explicit paragraph attribution maps during streaming without repeatedly comparing every model sentence. Existing streaming sampling stays in place. A five-profile streaming fixture and FrameTiming macrobenchmark cover simultaneous rendering, typing and scrolling. Device performance results are not claimed until that benchmark runs.
- The main menu has no bottom navigation buttons and uses one large accessible New Chat icon. Other root screens retain navigation. Conversation-body long press selects, a dedicated drag handle handles drag/drop, and normal-body swipes still archive/delete. Selected rows expose three-dot bulk archive/delete/select-all actions; normal rows hide the menu. Bulk actions retain workspace membership behavior.
- In Multi-chat and Combined Mode a single themed profile dock sits below the shared input, above system navigation insets. It selects the response for the visible turn without changing broadcast recipients. It remains available while inspecting a contributor. Per-response tab rows are removed.

Additional regression coverage exercises short word constraints, user-turn timezone, lexical false positives, chunk boundaries, malformed delivery records, joint ownership, canonical article provenance, no-evidence news, permission circuits, interrupted streams, selection gestures and dock placement. Android build and device verification must be checked in CI; source heuristics and model-declared semantic reviews are not an independent fact-checking service.

The full-context overflow/multipass handoff also preserves user constraints and the PASSAGES_V2 contract, contributor registry, canonical citation registry and authoritative date/timezone. It no longer overrides short-answer requests. Local isolated JVM validation covers 22 core regression checks plus a quality-metadata serialization round-trip; these checks use lightweight surrounding model/test stubs and do not substitute for the Android CI suite.
