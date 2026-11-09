# Android scheduling, catalog, and diagnostics UI repair

This change responds to the supplied diagnostic capture. The capture establishes repeated research-worker replacement, slow frames, and platform warnings. It does not establish a Java or native heap leak, an application-owned ASUS framework exception, or a measured performance improvement from this patch.

## Implementation and behavior evidence

| Area | Change | Regression evidence |
| --- | --- | --- |
| Research startup and connectivity wakeups | Check the durable outbox before a reconciliation wakeup. Serialize scheduling until the WorkManager enqueue receipt completes. Reuse an already pending worker; append a successor when only an active worker remains. `APPEND_OR_REPLACE` preserves an active attempt and its retry backoff. Unexpected worker exceptions produce a bounded WorkManager retry, preserving cancellation, so they cannot fail an appended successor. | `ExaminationWakeupCoordinatorTest` exercises a committed item arriving after the active worker's final queue read, concurrent wakeups, completion during admission, delayed enqueue receipts, and failed admission followed by recovery. The WorkManager retry adapter requires the Android failure-injection check below. |
| Durable queue race | A `RUNNING` worker alone is insufficient evidence that a new item will be read. Its successor remains eligible even when the active worker completes between observation and enqueue. Startup and connectivity reconciliation can recover retained work after process death. | The queue tests assert the late item is processed and an active attempt is never canceled by the coordinator. The WorkManager adapter still requires Android execution below. |
| Independent MCP discovery | Each selected connection reports not discovered, pending, ready, stale, failed, recovery backoff, authentication required, or configuration required. Unknown tool names in incomplete discovery are not presented as proof of an empty server catalog. | `McpCatalogueDeadlineTest` exercises independent in-flight producers, a short interactive wait, late success, a real discovery deadline, and network-epoch invalidation through the production client with mock HTTP responses. |
| Late catalog completion | Acquisition and omission deduplication use observed connection revisions. A producer finishing after the interactive snapshot can make new schemas available within the same run. Caches record the requested revision, so an older snapshot cannot be stamped with a newer successful revision. Ready authorized tools remain selectable. | `ToolCatalogAcquisitionTest` covers unchanged pending/failure states, late completion, completion during snapshot return, verified empty catalogs, and bounded receipt details. `McpCatalogOmissionsTest` verifies one recovery receipt after an omission. |
| Diagnostics list | Display at most 100 grouped rows per page, with previous/next navigation through all retained matches. Older-page navigation freezes the snapshot. Filtering, grouping, previews and repetition timestamps are prepared on `Dispatchers.Default`. | `AppLogProjectionTest` visits every retained row without duplication or loss and verifies filtering clamps the page. |
| Expanded diagnostic details | Each detail page contains at most 16,384 Kotlin characters (UTF-16 code units) and ten text blocks, with a maximum 4,096 characters per block. Pagination replaces the visible page. Formatting runs on `Dispatchers.Default`; cursors avoid splitting surrogate pairs. Full capture and export remain governed by existing retention. | `AppLogDetailPageTest` reconstructs every formatted event across bounded pages, including long Unicode and multiline records, and checks repeatable cursors. |
| Debug activity lists | Memoize filtered requests, distinct tool events, and run-indexed collections by their source snapshots. Timer-only recompositions do not rebuild those collections. | Static review; physical rendering and allocation evidence remains a qualification item. |

These changes use the existing worker, MCP supervisor, authorization checks, catalog stores, and log retention. A cached catalog remains schema metadata; it does not establish successful tool execution or current authorization.

## Required Android and device qualification

The focused JVM harness compiles the production queue coordinator, catalog selection, MCP client and transport, and log projections with boundary adapters for Android and persistence. It cannot validate WorkManager persistence, Hilt wiring, Compose layout, OEM framework code, or physical frame/memory behavior. Run the repository's full Android build and tests with JDK 21 and Android SDK 37 before merge. Record their actual results separately.

### Durable scheduling

Use the real WorkManager test driver or an instrumented debug build. Hold a worker immediately after its last durable outbox read, commit a new examination, and signal the queue. Verify an appended successor processes that examination after the active worker completes. Repeat with simultaneous configuration and connectivity signals, an existing blocked successor, a retry in backoff, and a failed/canceled previous chain. An active attempt must not receive cancellation solely because of a wakeup. Kill the process between durable commit and enqueue receipt, restart, and verify reconciliation eventually processes the retained examination exactly under the repository's existing claim/idempotency rules.

### Rendering and memory

Compare the base revision and this revision on the same device, refresh rate, dataset, build variant, logging settings, network and thermal state. Collect at least five cold starts and ten repeated open/filter/page/expand/close cycles through Debug/Logs, including the largest retained group. Capture a Perfetto trace and frame metrics; report frame counts and p50/p95/p99/worst durations, plus the counts above the device's frame budget, 100 ms and 500 ms. Sum only non-overlapping incremental windows. Keep cumulative stop summaries separate.

Inspect main-thread slices for grouping, formatting, file I/O and database work. Compare Java allocations, native allocations, retained heap and actual GC pause slices after the same idle period. A rising aggregate GC-duration counter is not a pause-duration measurement. A single native-heap jump is not leak proof. Acceptance requires responsive pagination, access to every retained record, unchanged complete-export semantics, no new main-thread I/O violation, and no reproducible frame or retained-memory regression. Report numerical improvement only when paired measurements support it.

### ASUS and platform warnings

Reproduce the protected-setting finalizer trace on the original ASUS firmware and compare with an AOSP/Pixel emulator or device at the same Android API level. Capture the complete stack, OS build and whether app-owned frames exist. The supplied trace originates in framework/OEM code, so this patch does not request protected permissions, use hidden APIs, or attempt to alter the protected setting. Confirm that the app continues, and distinguish the framework incident from an application crash using process-exit evidence. Escalate a minimal reproducible case to the firmware vendor if it persists.

Keep optional platform capability messages separate from failures: unavailable vendor logging properties, unavailable `READ_DEVICE_CONFIG` monitoring, ignored foreground importance hints, optional model feedback tensors, and JNI attachment probes each require their surrounding operation outcome to establish user impact. Device validation must also confirm log capture retains those outcomes and does not suppress the underlying evidence.

## Release evidence to retain

Record the exact application revision and APK digest, gateway revision where used, Android build/model, refresh rate, thermal state, workload revision, test commands and outcomes. Keep private endpoints, prompts, credentials, account identifiers and raw captures out of the repository. The follow-up report should state which Android/device checks ran and which remain unqualified.
