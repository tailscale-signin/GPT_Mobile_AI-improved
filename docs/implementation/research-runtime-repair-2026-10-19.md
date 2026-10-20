# Research and local runtime repair implementation audit

Base: main `b95d8d82e52a9af1e833f9989513a3bc5c244173` (includes PR 723). Branch: `feat/research-runtime-complete-repair-2026-10-19`.

This audit distinguishes implemented changes from the larger acceptance specification in the three 19 October design documents. Passing CI is not proof of the exact phone/model qualification. The final section records external acceptance that cannot be established in this host environment.

## Implemented changes

| Requirement | Production changes |
| --- | --- |
| Main menu profile transparency | Profile subtitles use 25 percent opacity, half the previous 50 percent opacity. |
| Research permission retry interruption | Remove the automatic permission modal. Show a retained-work card with an explicit Review setup action. Evaluate relevant saved tool dependencies; ignore unrelated tools, cosmetic profile edits, connection labels and timestamps. Return typed destination/conversation/source denial reasons. Actual revoked grants remain enforced. |
| Research progress card | One compact rounded card with icon, phase, readable target, discovered count, independent passage review status and Evidence/Summarize actions. Existing detailed evidence remains available. |
| Study cards | Compact title, type/year, conclusion, assessment state, save state, Examine and Details. Card tap opens the full-screen article evidence page. Unexamined and unscored results remain distinct from zero quality. |
| Primary-only recovery | Choose one designated participant before acceptance and run creation. Freeze the original research request as the objective. |
| No-research turns | Deterministic greeting bypass, authoritative noResearchNeeded decisions, no forced report for a greeting, and no inherited corpus from another task. Reject planning questions about operational source-goal/control text. |
| Recovery evidence | Exclude known standalone application errors from answer evidence and hashes. Preserve genuine prose about errors. |
| Content validation | Versioned shared content validator; cookie gates receive no readable credit after cache/restore/shared-source reuse. Preserve long articles with cookie footers. PubMed metadata/abstract coverage is not full-article coverage. |
| Corpus integrity | Preserve stronger captures; retain separate versions; bind merged claims to retained bytes. Give question definitions stable content identities rather than conflating contributor-local Q1 labels. |
| Bibliography | Infer DOI, PMID and PMCID only from recognized URL authorities. Existing scoped alias and retention transactions are retained. |
| Extraction | Reject nonempty malformed claim arrays as stage success. A nonempty extraction with zero grounded claims does not mark that source version extracted. |
| Report packets | Preserve every question definition, select a supporting quote window instead of always taking the beginning of a source, and retain explicit omission accounting. |
| Citations | Normalize grouped citations, recognize registered URL citations, generate missing links from the registered source map, and preserve rejection of invented links and citations. |
| Research revisions | Replace an exact draft through a transactional ownership fence and digest comparison. Retain the old revision; stale replacement owners cannot append a fallback inside an unfinished word or URL. Status notices stay outside answer prose. |
| Stop and summarize | A stopped acquisition remains write-only; it cannot reopen discovery to finish the report. |
| Required completion funding | Reconcile required model input/output capacity against the compiled physical request, including its safety allowance. Keep hard limits and other protected work. Do not silently renew tool operations through this mechanism. |
| Combined record repair | Accept optional null quotes without accepting object/array quotes; metadata-only decision corrections can make progress. A failed event followed by normal flow completion does not complete required work. |
| Output and usage | Allocate framing/decision capacity separately from visible word guidance. Settle measured single-attempt partial usage rather than retaining it as unknown. |
| Acquisition and verification | Fund discovery fan-out separately from retrieval attempts. Block search-only verifiers that have no authorized evidence-reading path before model dispatch. |
| Examine queue | Persist QUEUED before permit acquisition; cancellation and queue timeout preserve resumable state without leaking permits. Restore and retention safeguards include queued work. |
| Examine source reuse | Start with the requested authorized retained version. Preserve a reused-version checkpoint and historical validation receipt. Fetch metadata-only or missing captures; route eligible transport failures/timeouts to authorized alternatives. |
| Durable scheduling | Do not acknowledge automatic outbox work merely because a detached coroutine started. Await its terminal state. Persist configuration waits, suppress unchanged retries, and preserve explicit user pause. |
| Examine lifecycle continuation | Schema 5 adds per-attempt queued/start/heartbeat/phase/deadline/worker metadata and a transactional transition ledger. Monotonic phase deadlines retain checkpoints, recovery increments the lease, and terminal reviews reject late cancellation or failure. Restore clears live ownership. Automatic process-recovery work is awaited before settlement. |
| Examine state | Commit stage changes before logging; log paused and failed terminal paths. Derive archive Processing from job activity rather than a retention hold. Existing atomic assessment/protection commits remain intact. |
| GGUF context | Add persisted per-profile Auto/Manual context independent of output length, duplicate it with a profile, replan the complete prompt/tools against a memory-feasible context, and use the same policy for load verification. Fixed-context exports retain their compiled context. |
| GGUF allocation | Release incompatible idle engines under the holder lease, resample memory, retain reserve, and readmit initial/fallback native allocation after cleanup. Preserve the original Vulkan failure as a suppressed cause if CPU fallback fails. |
| GGUF metadata | Cache inspected descriptors behind validated artifact identity. Retain bounded attention geometry; use conservative fallback for unsupported/recurrent layouts and never reduce the existing conservative coefficient on unqualified geometry. |
| Local readiness | INSPECT/UNLOAD no longer imply READY. Cancellation preserves readiness only when the worker explicitly reports a healthy matching resident engine. |
| Admission diagnostics | Numeric immutable admission receipts include accepted/rejected state, context, reserve, availability, shortfall and residency. Emit receipts into invocation journal records, including pre-load denials. Route selection is labelled loadPipelineStarted, not native load started. |
| General diagnostics | Bundle and conversation event schema use one authority; distinguish incomplete Combined quality/admission issues; identify Assist-only handoff counts; move budget journal file checks off Main. |

## Completion of the host implementation audit

| Previously outstanding area | Added implementation |
| --- | --- |
| Durable accepted task | Immutable accepted-task envelope with original request, chat/message/root identity, participant roster, primary profile and settings/authority revisions. Resume requests and per-stage evidence dependencies survive process restart. Temporary tasks stay in memory. Checkpoints reject changed objectives. Snapshot v5 and delivery carry accepted task lineage; older records retain explicit legacy defaults. |
| Acquisition scheduling | Persisted task-wide search, read and metadata lanes protect reading/metadata reservations. Gateway upper bounds are reserved before dispatch and settled against known physical counts; unknown dispatch retains its reservation. Native academic dispatch shares host-level spacing, with NCBI subdomains sharing one service gate and consent rechecked after waiting. |
| Historical source repair | Additive Room 5→6 migration introduces validation, identity-resolution and repair-cursor records. Bounded restartable repair classifies retained exact-hash bytes, downgrades challenge/preview/metadata captures, invalidates scores, preserves protection and records work kind. Proven public identities receive canonical manifestation links; restricted identities stay isolated. No unproven destructive merging occurs. |
| Examination recovery | Application-owned watchdog fences expired leases before cancelling uncooperative jobs. Persisted configuration-wait revisions suppress unchanged automatic retries. Automatic recovery cannot reopen a closed Combined root or silently accept changed policy. Transition history is portable and diagnostics include worker/heartbeat/deadline/cancellation records. |
| Evidence and review | Quote verification checkpoints precede source-version batch reviews. Each batch item is validated separately against source version/hash, question and exact evidence; accepted neighbours survive malformed rows. Contradictions require a rationale and exact counterevidence. Packet allocation represents questions and mixed/negative findings, records omitted claim identities when they fit, and explicitly points to the authorized journal when they do not. Report validation exposes typed blocks and checks question/counterevidence coverage. |
| Native memory and progress | Conservative weights/KV/metadata/compute/backend-overlap components include native context padding and batch/thread settings. Exact artifact/build/device/plan keys retain measured allocation floors. JNI reports model/context/compute buffers by host/device, effective context/batches, and real load progress. Calibration retains peak components observed after generation. Admission receipts include requested/effective context, numeric components, plan identity and system memory/low-memory snapshots; process errors preserve the typed denial receipt. |
| Diagnostics and extraction | Recovery loading is extracted from the repository coroutine. Incident tracking spans rotated files and recognizes continuation lineage. App events carry monotonic/process/boot anchors; exports flag wall-clock jumps and monotonic resets rather than inventing legacy timing. Backups include task/resume/checkpoint sidecars, validation records and transition history. |

These additions preserve existing source grants, immutable retained versions, deletion fences, archive protection, user budgets and conservative memory floors. Public identity repair records relationships rather than deleting or coalescing restricted historical records. The dependency checkpoint records exact retained passage hashes and provider receipts; legacy records without accepted-task lineage remain identifiable as legacy.

## Validation

Final production-source commit `3728846` passed **3,679 Android JVM tests across 607 suites**, with **zero failures, errors or skips** (unfiltered run, 5m 19s). This includes task persistence/lineage, protected acquisition, batch review, content-hash binding, historical reclassification/score invalidation, Room migration and portable records. Room/Robolectric upgrades from schemas 1, 3, 4 and 5 to 6 passed. The final metadata regression keeps generic journal articles distinct from empirical studies while preserving archive protection.

Android `lintDebug` passed on `d256efd` after the focused regression run (13m 10s), and the following unfiltered JVM/lint invocation passed (3m 25s). The only subsequent production adjustment is the conservative Article/Editorial work-kind label; the complete JVM suite was rerun after that adjustment. Repository CI should rerun Android lint for the published tree. Passing lint does not mean warning-free.

**147 Python tests passed**, including executable SQLite 4→5 and 5→6 schema parity and checkpoint preservation. Changed Kotlin passes ktlint 1.3.1; whitespace, Android regex/resources and committed Room schemas pass. Android regex validation covered 661 literal patterns with zero errors; 38 dynamic patterns were skipped. Resource preflight checked 29 XML files.

Native validation passed:

- Real CPU JNI inference with both pinned synthetic ChatML and custom-Jinja fixtures: real load progress, effective context/batches and allocation components, repeated sessions, partial token receipts, cancellation before entry/during prefill, stale cancellation/handles, context rejection and reopening.
- A requested 513-token context produced an actual padded context of 768. The observed CPU model/context/compute buffers were 18,350,208 / 5,308,416 / 8,735,232 bytes. These are measurements of the synthetic tiny model, not the affected user model.
- Host CMake build and chat-template CTest passed. Android ARM64 Release/CPU cross-compilation passed using NDK 29.0.14206865 and API 31. All four ELF LOAD segments use 16 KB alignment; optimized portable flags passed for 18 inference sources.
- The engine source is pinned to `4413a192410fad1f054e9216114087a3e01527a0`, archive SHA-256 `4d0cb840af6e67de6afc72cd85aaa5d1419a9c6071a403d7a2069086f0bfe21b`. The Android-specific JNI thread-attachment signature is compiled separately from the host JDK signature.

Validation used Java 21, Gradle 9.8.0, the app's `:app:testDebugUnitTest` and `:app:lintDebug` tasks, and a host-only Gradle init script that starts the cached Byte Buddy 1.18.2 agent. Dynamic self-attachment is unavailable on this host. No app assertions or test selection were weakened. Intermediate validation caught stale-score revalidation and outdated snapshot-version assertions; both were corrected. Stale generated Hilt/ASM outputs and a concurrent cache-lock launch were repaired before the successful runs. Logs are retained locally as `/tmp/outstanding-repair-final-metadata-validation.log`, `/tmp/outstanding-repair-targeted-validation.log`, `/tmp/outstanding-repair-final-validation.log`, `/tmp/outstanding-python-final.log`, and `/tmp/outstanding-native-*-final.log`.

## External acceptance gates

Keep the PR in draft until device/integration acceptance is complete. No Android device is attached to this workspace, and the exact affected GGUF artifact is unavailable. Host results cannot establish:

- Process death/rotation, source-revocation races, six-profile Research/summary/primary recovery and closed-parent behavior on Android.
- Live reader/MCP transport, real permission changes, archive restore and UI continuity across app/service death.
- Five cold loads and twenty warm requests for the affected GGUF on every claimed device/backend/configuration, including Vulkan fallback, memory pressure and thermal behavior.
- Minified APK instrumentation, zero StrictMode filesystem violations in the reproduction paths, and device-specific native allocation peaks.

No release qualification or merge is claimed. Native observations calibrate only the exact artifact/build/device/plan key and never reduce the conservative estimator. Legacy diagnostic time gaps remain unknown. The repository's existing physical-device smoke workflow remains the acceptance harness; synthetic host measurements are reported separately.
