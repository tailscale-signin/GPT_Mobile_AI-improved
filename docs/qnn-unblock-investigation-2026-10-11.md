# QNN unblock investigation — 2026-10-11

## Evidence and result

Reviewed all 15,978 JSONL rows of the previous diagnostic export for app 0.9.41.0, build 110, commit `cc561dbf8933812edc253b8bee054df5ae391b12`. There are 3,829 event rows and 12,149 multiline continuation rows; 133 events are error level and 104 are warning level. Stack traces and repeated symptoms are not separate bugs. Compared the capture with current main at `cb6941e2ecde58b698f8a83a3419995a5ca1f152`.

All five Gemma 4 Qualcomm attempts were rejected by an application filename rule, before QNN initialization. Seven GPU attempts ended in an unsupported CPU fallback error. The capture does not establish what originally failed on GPU or whether the current QNN stack can generate on the phone. It contains no successful local-model generation.

The requested patch removes the Gemma 4 filename rejection from `LocalModelCompatibility.unsupportedReason`, the shared entry point used by Hub discovery, setup, profile/chat selectors, profile creation, chat preflight and the LiteRT adapter. Imported and legacy rows are covered too. Compatibility record revision 2 marks these files `UNVERIFIED`. No qualification is fabricated.

## QNN and local loading issues

| # | Finding | Evidence / disposition |
| ---: | --- | --- |
| 1 | Gemma 4 blocked before QNN runs | Five explicit preflight failures. Removed in this patch; SM8750 discovery and selection can proceed. |
| 2 | The same block hides discovery results and disables profile creation | Confirmed shared-code call sites. Removing the shared rejection fixes those routes together; discovery regression test added. |
| 3 | NPU and GPU files are different compiled artifacts | Keep SoC and backend checks. The QCS8275 export is not the SM8750 phone export. Regression tests retain rejection of a mismatched phone and non-Android QCS discovery. |
| 4 | GPU fallback attempts CPU on a GPU-only package | Seven final CPU capability failures. Current main already filters fallback candidates against declared capabilities; this patch preserves that repair. |
| 5 | The original GPU exception is overwritten in the captured build | The actual first failure cannot be recovered from this log. Main now retains backend attempts and their first cause across IPC and the adapter. Collect a new failure after installing the changed build. |
| 6 | The old message recommends a supposedly verified Gemma 3 alternative | The log's word “verified” is not device execution evidence. The removed message no longer makes that claim; current runtime status distinguishes prerequisites from output. |
| 7 | Native JNI pre-load probe appears as an error | Two `nativeCheckLoaded` messages. Subsequent metadata validation occurs, so these messages alone do not demonstrate a missing native runtime. Preserve packaging validation; diagnose a terminal loader failure separately. |
| 8 | Native execution remains untested by this capture | No QNN engine-init or first-output evidence accompanies the blocked requests. Removing the app rule permits an attempt; it cannot establish a successful DSP initialization. |
| 9 | Crash recovery has an unreachable reset API | Current code implements `allowVerification` through the holder, IPC service and runtime, but has no UI/benchmark caller. A model previously quarantined by an actual worker interruption can remain blocked independently of the removed filename rule. Follow-up should expose a bounded, explicit retry using the exact artifact/runtime/backend identity and exclusive worker ownership. This patch does not silently erase crash evidence. |

The retained runtime checks cover the actual arm64 process, packaged Qualcomm support, HTP/dispatch libraries and hashes, model integrity, declared SoC/backend/context, and interruptions in native code. These are separate from the removed model-name ban. The pinned build provenance check reports LiteRT-LM 0.18.0 / QAIRT 2.51.0; that is package provenance, not hardware verification.

## Other distinct issues in the same capture

These findings explain the remaining errors and warnings; they are not additional reasons for the Gemma 4 preflight block. Existing broader remediation is tracked in [runtime-log-remediation.md](runtime-log-remediation.md). Counts below refer to matching log events, not independent defects.

| # | Finding | Solution / interpretation |
| ---: | --- | --- |
| 10 | 39 chat retry warnings after unexpected EOF on the local server | Diagnose server/proxy stream termination; retry only before output or side effects and preserve partial results. Separate transport availability from model quality in benchmarks. |
| 11 | MCP gateway connections become degraded after EOF | Reconnect with bounded backoff and preserve the last working transport/session. Inspect gateway-side logs if it persists. |
| 12 | MCP POST 400 responses from Jina/Firecrawl and POST 404 from the local endpoint | Treat failed negotiation separately from terminal failure; inspect the final handshake and discovery result. Do not assume every probe response is a broken integration. |
| 13 | Firecrawl GET 405 / SSE exception | The optional GET stream must not invalidate working POST transport. Negotiate transport capabilities and retain the final connection outcome. |
| 14 | OpenRouter returns policy-filtered 404 twice | The response explicitly excludes the endpoint under account data/training policy. Choose a provider allowed by the configured policy; preserve the explanation and avoid unchanged retries. |
| 15 | LLM7 free allowance exhausted twice | Respect the advertised reset/cooldown or choose another provider. Record quota-unavailable rather than a capability failure. |
| 16 | Groq reaches its output cap twice; other benchmark runs are partial | Separate fixed-budget throughput measurements from answer completion and correctness. Preserve partial text, output caps and finish reasons. |
| 17 | Oversized chat coroutine exceeds the ART compiler instruction limit five times | Split the orchestration method and profile the resulting build. Main contains preparation/recovery helpers; improved frame time still requires measurement. |
| 18 | 12 skipped-frame messages and six GC/thread-flip pauses | Keep heavy initialization, hashing and inference off the UI thread; measure frame timings and process memory during model load and chat. No performance improvement is inferred from this patch. |
| 19 | FTS5 is unavailable | One SQLite error is followed by `SEARCH_INDEX_READY` on FTS4, including reuse on restart. Capability-detect first and retain the working fallback; memory search is not proven broken. |
| 20 | ASUS finalizer reads a restricted system setting twice | The stack is in framework/vendor finalization. App permissions cannot grant access to the hidden setting. Track firmware identity and updates; do not attribute this to QNN without matching crash evidence. |
| 21 | SLF4J provider missing twice | Supply the Android logger binding and route through redacted diagnostics. Main already contains an Android provider. |
| 22 | Restricted `ro.hardware.chipname` property access twice | Use supported SoC APIs; do not add privileged property access or permissions. |
| 23 | Optional embedding feedback tensors disabled three times | Inspect the terminal embedding result. This warning alone is not inference failure and does not explain QNN's preflight rejection. |
| 24 | 27 inactive input-connection warnings and two missing IME callbacks | Release focus during navigation/disposal and test keyboard lifecycle across screen changes. |
| 25 | Frame monitoring lacks `READ_DEVICE_CONFIG` twice | Use accessible frame-timing APIs. This privileged permission is not a QNN prerequisite. |
| 26 | One cleared-reference/finalizer warning | Audit resource ownership and close sessions/streams deterministically. A single warning is not proof of a persistent leak. |
| 27 | Counts obscure causes | Preserve raw events but group duplicates, stack frames, retries and terminal outcomes in diagnostic summaries. HTTP errors can be logged at info level and must also be examined. |

## Validation and next device check

Added regression tests cover Gemma 4 admission from catalog and imported paths, discovery of the SM8750 export, continued chip/backend matching, unverified status, and the unrelated MiniCPM allocation rule. Seven focused JVM tests (the compatibility and native-journal classes) compiled and passed using the cached Kotlin compiler and JUnit. All 65 script unit tests, the Android regex check and QNN build provenance check passed. These are host checks, not phone tests. The Android Gradle test attempt stopped before compilation because another process held the shared cache lock. Full Android compilation, discovery integration tests, lint and APK packaging checks remain pending; no PR or CI run has been created yet.

After installing an APK containing this patch, use the existing SM8750 NPU profile and a short text-only prompt. Capture the QNN initialization boundary and first output or the specific terminal failure. If it still fails, distinguish package/SoC/context/library validation, an existing native-crash quarantine, and a new DSP/native initialization failure. Do not label initialization alone as sustained qualification.
