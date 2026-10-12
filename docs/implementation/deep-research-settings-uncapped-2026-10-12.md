# Deep Research settings repair and goal-driven execution

Implementation branch: `fix/deep-research-settings-uncapped-2026-10-12`.
Exact base: `7553e4ff14316f0d28639d5cc0ed24cb59511066` (main, October 12).
Specification: `Deep_Research_Settings_Crash_Uncapped_Research_Design_2026-10-12.md`.

## Changes

- Integrated service identities come from `ToolServiceCatalog`; the duplicate private UI catalogue has been removed. Typed destinations preserve Health, Memory and Delegation, with a dedicated Deep Research editor and recoverable unavailable states. Generic editing requires a descriptor capability. Navigation stores IDs rather than stale UI objects.
- The research editor owns a draft in a screen ViewModel, restores it through SavedStateHandle, discards on Cancel, retains save errors, and saves only research defaults through an atomic DataStore transaction. Telemetry and storage diagnostics load separately and cannot prevent editing local settings.
- Research settings migrate to schema 3. Preset anchors remain 0/30/90/180/300; custom positive goals may exceed 300. Legacy source caps remain serialized for audit but do not reduce an active goal. Effort zero and disabled research dispatch no acquisition. Explicit request, download and active-time limits are opt-in fields.
- One resolved policy is used by editor/runtime defaults and academic resolution. A fingerprinted, persisted Long ledger accounts for logical operations, HTTP requests, bytes and active time. A denied explicit budget stays stopped across restore. Corrupt, negative, overflowing or mismatched policies do not become unlimited execution.
- Research-only acquisition wrappers retain bounded per-result handling and individual operation timeouts without cumulative tool-call/output ceilings. Unrelated tools retain their existing budgets. Native URL and search readers record request/download accounting through an app-owned coroutine context; academic requests use the same turn ledger.
- Native acquisition is driven by retained qualifying source count, pending work and objective progress. Legacy round, query, candidate, page-ten and inherited preparation deadlines no longer terminate productive research. Repeated cursors and stagnant discovery stop instead of looping forever. Parallel final batches reserve their remaining source slots through bounded scheduling.
- Source bodies are stored as immutable content-addressed chunks, separately from compact excerpts. Hierarchical index pages keep manifests small. Provider continuation, pending source statuses, queries, source IDs, counters and goals survive checkpoints. Actual storage/memory pressure produces a resource pause instead of an arbitrary corpus ceiling.
- Conversation backup includes source files, body chunks, indexes, Long ledgers and native provider cursors. Chat deletion removes unreferenced body/index data and scoped ledger/cursor files. Temporary chats keep bodies in memory and never silently persist them.
- Legacy incomplete runs require an explicit migration action; migration validates the old ledger, preserves counters/evidence and changes the retained goal. Completed history is not restarted. Editing defaults does not grant a new allowance to the same turn.
- Gateway status advertises policy version 3. The trusted adapter checks capability and consent before using it, sends application-owned policy identifiers and explicit remaining allowances, and reconciles reported gateway accounting. Older gateways report an explicit capability limitation. The academic MCP contract accepts supported positive pages beyond ten, with bounded per-operation payloads. Opaque provider cursors survive persistent gateway restarts.
- Route breadcrumbs and synchronous application crash frames are recorded through existing privacy-aware diagnostics. The existing uncaught-exception handler still owns process termination.

## Regression coverage added

Catalogue route coverage and unavailable-ID tests; enlarged-text/card-expansion Compose tests; migration and >300 serialization tests; >2,400-request/>2-GiB/fake-time ledger tests; explicit-limit restore and overflow tests; a 1,000-source acquisition regression crossing page ten and the old 900-candidate threshold; paged body/index restore and deletion tests; Long counter/profile-switch restore tests; gateway page-eleven, accounting and explicit-zero-allowance tests. The existing 300-source acquisition regression remains intact.

## Validation status

Executed locally:

- Repository script suite: 79 tests passed.
- Academic gateway suite: 15 tests passed.
- Android regex compatibility: 435 literal patterns, zero errors.
- Resource/icon/runtime provenance preflight passed.
- Python gateway module compilation and academic MCP JavaScript syntax checks passed.
- `git diff --check` passed.

Not executed successfully in this environment:

- Gradle unit tests, Android compilation/APK, Android lint and ktlint: the Gradle distribution cannot be downloaded; JDK 21 and the Android SDK are unavailable.
- Compose/device instrumentation: no Android device or emulator is attached.
- Full gateway suite: required `requests`, `fastapi` and `httpx` dependencies are unavailable.
- Full MCP suite: offline npm installation could not obtain all pinned dependencies.

The branch is an implementation candidate, not a verified release. Compilation, the complete cross-layer acceptance matrix, process-death/device/backup scenarios and real native/delegated/gateway integration remain validation gates. Very large restored corpora still need memory-pressure profiling because the existing public snapshot API materializes source metadata/excerpts even though bodies and disk indexes are chunked/paged. Gateway transport failures without an accounting receipt deliberately require reconciliation; they are not treated as successful acquisition.

The local branch has not been pushed or merged. Automatic approval review rejected pushing without explicit authorization to export the modified source. No indirect upload was attempted after that rejection.
