# QNN and full reliability V2 implementation

Source specification: `GPT_Mobile_AI_QNN_Full_Reliability_Implementation_Design_V2_2026-10-10.md`.
Implementation base: main `1b669fc6295c26995e7ddf70ff354cba28a77839`.

## Scope and decisions

Extend the existing Android components. Following the additional request to use the latest runtimes, verify official release metadata as of 2026-10-11: LiteRT-LM remains at 0.18.0; upgrade QNN from 2.50.0 to 2.51.0 and rebuild the Qualcomm dispatch against the matching QAIRT 2.51.0.260929 headers. Preserve the exact LiteRT Core revision shipped by LiteRT-LM, rather than adding an unrelated standalone Core AAR. See [native provenance](litert-qualcomm-dispatch-build.md) for source URLs, toolchain and artifact hashes. This is a requested runtime upgrade, not evidence that a particular device failure is fixed. A library inventory, hash check, APK build, or mock test is not NPU execution qualification.

The former 87-tool exposure is bounded through task relevance and context. Ordinary local requests admit 4/8/16 optional tools according to model context; required and explicitly named tools take precedence or fail visibly if they cannot fit. An explicit omission notice and post-planner estimates distinguish model capacity, tool schemas, result reserve and output reserve. Qualification uses zero tools.

## Implemented contracts

| Specification | Implementation |
| --- | --- |
| INF-01, QNN-07 | Preserve APP_COMMIT and pinned ABI/native manifests; export commit and variant; rebuild the dispatch for QNN 2.51.0; package the exact audit manifest as an asset and verify all packaged QNN libraries plus the asset against it. |
| INF-02, UI-02/04 | Separate durable critical log rotation, bounded redacted fields, overflow accounting, batched diagnostic list updates (up to 20 snapshots/s), and structured truncation flags. |
| QNN-01 | Cache installed library digest inventory by APK/firmware/ABI/file identities; hash off the UI thread before execution. Readable files remain prerequisites, not qualification. |
| QNN-02/03 | Correlated probe, journal, native initialization, conversation and decode checkpoints; preserve private cause chains and bounded exported details; quarantine and unsafe artifact codes; unknown native initialization stays unknown. Cancellation and VM failures propagate. |
| QNN-04 | Validate installed digest on NPU and non-NPU paths; distinguish header/size checks, publisher digest verification and identity-only artifacts. |
| QNN-05/06 | Preserve NPU-only routing and crash quarantine; expose read-only journal diagnostics; report stable native errors and separate GPU/CPU edition recovery without speculating that every failure is OOM. Retesting a changed audited tuple does not erase the old tuple's quarantine. |
| QNN-08 | Last confirmed boundary and native-output status in diagnostics, application/runtime identity, bounded critical history, explicit vendor-log-unavailable status. |
| AGENT-01/02/04/08 | Parser limits before buffer/map growth, admission before UI/execution, duplicate/overlong ID rejection, bounded UTF-8 arguments, provider stream termination on an invalid batch, one aggregate notice, one tool-free finalization attempt from completed exchanges. |
| AGENT-03/06 | Shared atomic invocation admission, stable owner/argument identities, duplicate dispatch prevention, finite observed-attempt ceiling even with unlimited configured execution; unknown dispatch outcomes require reconciliation. |
| AGENT-05/07 | Bound content, retained content and trace at ingress; finite retained replay; preserve call/result pairing. Completed results remain in trace persistence. Keep bounded source references when excerpting large text. |
| COMB-01/02/03 | Plan from profile/provider-constrained output cap. Useful cap-limited requests are partial segments in accounting. Segment count scales with input and output capacity; no unconditional three-retry floor. Existing continuation engine remains the sole owner. |
| COMB-04/05/06 | Bounded contributor/paragraph/source inventory, explicit uncertainty in coverage hints, shared-outline continuation with a bounded answer tail, persisted draft/committed headings/pending outline/coverage/cap/index/reason keyed to source identity and parent run. Retrying the same unfinished synthesis resumes writing with tools disabled. Temporary chats are not persisted. |
| UI-01/03/05 | Invalid batches do not create pending per-call cards. Existing lazy keyed chat lists and coordinator lifecycle persistence remain in use. Frame summaries include active run/phase correlation; no per-frame logging. |
| NET-01/02/03/04/05 | Host-scoped DNS and endpoint-scoped connection/protocol health, bounded jittered cooldown, route-generation invalidation of MCP discovery/session caches, explicit refresh clears cooldown, successful handshake/tool discovery required for healthy. Tool calls are never transport-replayed. Existing connection and durable gateway recovery flows remain authoritative. |
| LOCAL-01/02/03/04 | Task/context-ranked tools, native-boundary estimates, honest accelerator diagnostics, and physical NPU tests through the production guarded runtime. Existing benchmark native-versus-estimated counters remain intact. |
| MEM-01/02/03 | Persist model/idle waiting markers and finish WorkManager execution successfully. Model state, work completion and inference availability wake eligible unique work; active work is not replaced. Preserve consent/digest/source/temporary-chat checks, bounded transient retries, no autonomous model loading, empty facts as no-op. |
| PERF-01/02 | Admission, observation retention, coverage and endpoint policies are separate bounded components. Focused regression tests pin storm termination, replay, continuation, budgets and endpoint isolation. Further coroutine decomposition must preserve these gates. |

### Additional integration corrections

- Anthropic thinking/tool blocks and Gemini signed tool parts are keyed by stable call IDs, not positions that change when older exchanges are compacted.
- Protect the crash journal when a probe or journal guard rejects startup: an unentered journal is never cleared by cleanup.
- ObjectBox build telemetry is disabled in validation/build workflows with its built-in `OBX_DISABLE_ANALYTICS=true` setting.
- A nominal HTTP/TCP success does not set MCP healthy. Network link/DNS changes invalidate endpoint caches even when the Android network object is unchanged. A single failed connection increments backoff once.
- Interrupted synthesis saves even a short uncommitted segment while preserving coroutine cancellation and Flow exception transparency.
- Source URLs are kept whole or counted as omitted; truncation never creates an invented URL.
- Report unknown dispatch outcomes conservatively after transport exceptions; never silently retry writes.

### Bounds and rollback

Provider rounds admit at most 32 calls, 16 calls for one tool and 16 KiB argument bytes. The same-tool allowance supports a legitimate 16-call batch while the whole-round and turn ceilings remain independent. No provider overrun is turned into thousands of synthetic results. A turn observes at most 256 calls by default, with an absolute ceiling of 1,024. Retained observations are at most 64 KiB per representation and replay is independently bounded.

Build-time rollback switches: `-PcombinedSectionRecovery=false`, `-PmcpRouteRecovery=false`, `-PtoolStormFinalization=false`. Hard parser/admission limits and crash quarantine cannot be disabled by these switches. Disabling storm finalization returns an explicit partial/failure instead of starting another request. The reliability switches require no schema migration. Reverting the runtime upgrade must restore the QNN dependency, dispatch binary, native manifest, dispatch input manifest and checksum/provenance records together; never mix versions.

## Verification and release gates

- Source-level Python regression suite: 65 tests passed, including stale APK inventory and mixed QNN distribution rejection.
- Resource, icon and QNN provenance checks passed; device execution remains unverified.
- Isolated Kotlin/JUnit suite: 79 tests passed with real agent/policy code and stubs only for unrelated Android logging/DTO/error surfaces. This is not the full Android test suite.
- Full Android unit suite before the UI V2 main-branch sync: 2,246 tests passed, zero failures/errors/skips, with Java 21 and Android SDK 37. The local test JVM uses a preloaded Byte Buddy agent and the environment’s HTTPS proxy for Robolectric dependencies.
- `assembleDebug` and `assembleDebugAndroidTest` passed. The arm64, universal and x86_64 APKs passed `check_local_runtime_apk.py`: respectively 17/18/1 pinned inference libraries and 17/24/7 aligned Android host libraries, plus complete QNN inventory, installed audit asset, MediaPipe and ObjectBox JNI checks. These local APKs contain the validated reliability/runtime implementation before the unrelated UI V2 sync.
- Main commit `e3f542b61f30bf471149ecaee59c75d51e388ecc` was merged into draft PR #651. The single device-workflow conflict was resolved by preserving both QNN qualification and UI review fixture collection. Source/resource/provenance checks were repeated after the merge; Kotlin lint passed on merged source commit `5574590965a3a79571a16fa421979b4a3c2cc310`. CI checks the complete merged source.
- Android lint was stopped locally after more than ten minutes without a result; no Android-lint pass is claimed. Full Android lint and release/R8 validation remain CI gates. The unsigned release workflow skips drafts; the minified native-memory CI workflow exercises the R8/native boundary. Strict dependency checksums remain enabled throughout.
- Physical SM8750 inference, main-thread/heap Perfetto comparison, native cancellation/thermal tests and Tailscale pause/resume: not executed here.

`device-validation.yml` now requires both an app-readable model path and trusted SHA-256 for QNN qualification. The production guarded runtime must generate nonempty NPU text on two cold starts with two conversations per start. A missing model, skipped test, or missing success marker writes **NOT_QUALIFIED**, not QNN success. The instrumented 3,000-call fixture records produced/UI-event counts and PSS for same-device comparison; it does not claim an unmeasured jank improvement.

Coverage manifests deliberately label text presence/reference overlap as hints. They do not certify semantic completeness or turn model-only assertions into externally verified evidence. Device/native blockers and semantic quality evaluation remain explicit release gates.
