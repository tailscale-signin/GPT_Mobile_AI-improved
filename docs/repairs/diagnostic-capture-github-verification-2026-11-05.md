# Diagnostic capture and GitHub verification repair

Repository: `tailscale-signin/GPT_Mobile_AI-improved`  
Base: `main` at `1b8d57128484770d09fe026216a46603803b76f6`  
Branch: `fix/diagnostic-capture-github-verification-2026-11-05`

## Evidence and scope

The user supplied the analysis of `partial-diagnostics-6-1545ad2a-3afc-48cb-be36-c9405eb87415.zip` and a partial GitHub integration report. The original archive and complete conversation receipts were not attached to this implementation turn. The supplied analysis reports a valid two-file ZIP with `JAVA_HEAP_PRESSURE`, `capturedFully: false`, and an empty application-log file. The supplied GitHub report observes graph, transient read, and generic CI failures at the base commit, with incomplete inventories and capabilities that remain blocked or untested.

Source inspection confirmed that the legacy conversation export hydrated message history, tool events, runs, workspace receipts, invocation history, answer segments, and correlated logs before streaming the ZIP. Its low-memory fallback used only the current in-memory log list. It could therefore omit persisted evidence and accept a zero-byte log file. The general log exporter also copied entire persisted files into byte arrays.

The implementation addresses those source paths. It does not establish an external cause for the historical GitHub errors, recover records that were never captured, or claim that a physical phone, gateway, provider, or every GitHub capability has passed verification.

## Capture implementation

| Requirement | Implementation |
|---|---|
| Bound capture memory before ZIP writing | `DiagnosticDatabaseSource` measures rows in SQLite before loading their text and reads bounded pages. The chat path no longer calls `fetchDebugRecords` or hydrates the full message history. |
| Retain critical evidence on disk | `BoundedDiagnosticLogStore` maintains separate critical and recent rings. Critical selection includes errors, crashes, lifecycle events, memory warnings and tool/GitHub failures. Recording remains opt-in and respects temporary-conversation privacy. |
| Bound pending logging | The pending queue is limited to 256 Ki characters and 512 records, with 64 Ki characters and 128 records reserved for critical events. Complete-line redaction preparation, coalescer samples, duplicate tracking and drain batches are also bounded. Critical overflow is counted separately. |
| Use persisted logs in conversation exports | `AppLogRecorder.captureForExport` makes stable, bounded disk snapshots under its writer lock. Export no longer depends on the UI's in-memory entries list. |
| Stream output | `StagedDiagnosticCapture` writes complete bounded records to stage files, then streams those files into a ZIP with fixed buffers. It never retains a history-sized ZIP byte array. |
| Prevent overlapping exports | `DiagnosticExportGate` is shared by conversation and Settings log exports. A concurrent request receives an explicit busy result. |
| Preserve independent sections | Critical logs, recent logs, context, profiles, tools, invocations, runs, conversation, workspace, answer segments, Combined blocks, research, journal events, runtime incidents and selected settings have separate stage receipts. |
| Isolate failures | A failed source, memory admission failure or injected heap exception retains complete earlier records and allows independent stages to run. Cancellation checkpoints completed records and propagates cancellation. A stage that cannot open its working file still receives an explicit failed receipt and empty ZIP entry, preserving other sections. |
| Resume explicitly | The Resume action verifies the source identity, stage plan and each completed stage's byte count, record count and SHA-256 before reusing it. Failed, empty and corrupt stages are retried. Log sources are frozen per stage so a resume does not replace retained failure evidence after ring rotation. Each stage keeps its own capture timestamps. |
| Provide a reduced-memory option | The export dialog offers Low-memory diagnostics. Initial heap headroom can also select that mode. Smaller per-record, per-section and archive limits are explicit in the manifest. |
| Distinguish empty evidence from metadata | Log-capture explanations are metadata records and do not increase the evidence count. No artificial error is inserted to disguise an empty log history. |
| Validate before sharing | The ZIP is fully inflated and checked for safe, unique names, expected sections, exact bytes, newline record counts and SHA-256 hashes. Publication occurs only after validation. |
| Record memory observations | Manifests contain Java maximum/committed/used/headroom bytes and supported native/ART allocation and garbage-collection counters. Unavailable measurements are `null`, not invented zeroes. |
| Protect retained data | Redaction precedes persisted export records. Compressed text is decoded only within an expansion limit and is never exported as concealed raw archive text. Protected workspace evidence is read within its own bound. |
| Honor clear/delete and temporary chats | Capture guards invalidate in-flight work when diagnostics are cleared or a conversation is deleted. Temporary-chat capture uses disposable working files and has no resumable retained checkpoint. |
| Preserve offline inspection | `scripts/replay_conversation_debug.py` validates the new staged schema without loading record bodies into memory. It explicitly reports that provider/reducer replay and application-task success are not established. Legacy conversation formats remain supported. |

### Format and limits

The new archive manifest uses `gptmobile.staged-diagnostics.v1`. Section bodies are JSONL. Database-derived records identify the table and row, and retain database field names; serialized nested JSON is decoded within a bound. Combined documents remain independent durable blocks instead of being assembled into a large message object.

Normal capture uses an 8 KiB I/O buffer, a 512 KiB serialized-record ceiling, a 16 MiB section ceiling and a 64 MiB expanded-archive ceiling. Reduced capture uses 64 KiB, 2 MiB and 8 MiB ceilings respectively. Source readers impose smaller pre-hydration bounds. The manifest records actual limits and all applicable omissions. Reaching a limit never silently turns a truncated record into a successful complete capture.

`captureCompleted` means the requested stages reached their recorded outcomes. `capturedFully` remains false because deleted, evicted and unrecorded data cannot be recovered. `evidenceRecords` excludes explanatory metadata. A successful archive validation establishes integrity of the retained artifact; it does not prove completeness of the underlying application history.

Captures and resumed stages are intentionally timestamped independently. They are not a transaction snapshot of a still-running conversation. Retry is explicit; there is no automatic loop attempting to recover permanently absent evidence.

## GitHub implementation

| Reported observation | Source change and resulting behavior |
|---|---|
| `REPOSITORY_GRAPH_UNAVAILABLE` | A graph status transport failure may perform one independently authorized native status read. Its receipt is retained separately, while the graph result still reports unavailable/incomplete coverage. Permission, admission, TLS, cancellation and graph mutations do not trigger that fallback. The finalized native catalog, inherited request guard, live native grants and native operation approval are checked independently, including after any permission wait and read. |
| `GITHUB_TRANSIENT_NETWORK` on source reads | The existing bounded native read retry policy is retained and regression-tested. No additional retry layer or write retry was introduced. |
| Generic CI `TOOL_FAILURE` | HTTP rejection, TLS, transport, oversized response, malformed response and unclassified failure categories remain distinguishable. Receipts retain requested workflow/run/job/ref/page selectors without inventing a failed run's branch. |
| CI definitions disappear when one source fails | Pinned workflow definitions are captured independently. Successful definitions survive another file's expected read/decoding/size failure, including malformed Base64, unsupported encoding, invalid UTF-8 and binary content. Omitted files include exact revision-bound recovery arguments; missing definitions are not cached as successful captures. |
| Invalid workflow or returned resource identity | Selectors reject invalid/overflowing numeric IDs and traversal paths before dispatch. Run/job responses are checked against requested IDs, job/run relationships and requested head SHAs. |
| Thousands of historical runs obscure relevant evidence | Ordinary integration discovery filters run inventory to the observed commit. Explicit exhaustive-history requests remain unfiltered. |
| Five-row inventories and stranded definition pages | Ordinary inventory pages request 30 rows; workflows request 100. Definition continuations have their own bounded page allowance, separate from sampled history. |
| Unread local continuations | Issued local fragments are consumed before a pending-check watch or a new remote inventory read. Incomplete remote inventory remains explicitly incomplete. |
| CI success without executed-test evidence | Subsequent CI recipes carry the observed SHA and run/job relationships. Existing exact-revision execution-evidence requirements remain in force. |
| Write fixtures, decisions, missing issue/PR IDs and device tests | Authorization, ownership, cleanup, genuine-decision and identifier dependencies remain required. The patch does not relabel these blocked or untested capabilities as passed. |

## Validation and remaining qualification

The task JSON alongside this document records commands, counts and the current validation state. Focused host tests use the production implementations and explicit Android/network boundaries where needed. They are not substitutes for a full Android build.

The staged exporter has been exercised with 163,840,000 bytes of generated records in an actual `-Xmx64m` JVM, including a separate failed stage, checkpoint reuse and archive validation. The CI workflow adds the same dedicated 64 MiB gate while retaining the existing 256 MiB recovery tests. Log-file tests cover bounded critical-priority queues, concurrent producers, snapshot handoff, rotation, oversized records, background writes, bounded snapshots, UTF-8 boundaries, interrupted tails, repeat cleanup and empty evidence. GitHub tests cover definition isolation, revision/resource validation, pagination, graph fallback and bounded read retry.

The editing host cannot run the full Gradle build through its configured Java distribution download path and does not have the required Android SDK/JDK setup. Current-branch Android CI is authoritative for application compilation, integrated unit tests, lint and packaging. Physical-device acceptance requires separate execution evidence. A new export from the affected ASUS device is still needed to determine which original application failures, if any, preceded the heap-pressure capture failure.
