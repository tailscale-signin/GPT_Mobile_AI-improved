# Issue reporting diagnostic follow-up — November 12, 2026

## Scope and closure policy

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. All source changes stay on
`fix/session-reliability-reporting-2026-11-12`, in [PR #838](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/838).
This follow-up starts from `a08f99e2adbf5a20fd3fafbc7fa3e63ba8b0542b`, with main at
`97f10ff7a1f3789fb82d958b2dc6b22c8074dfdf`, and preserves the earlier session repairs.
It also incorporates concurrent commit `b0148a61a464345085b7aef8c9fc0c8e4cf85470`,
including full provider-journal binding, shared cooldown scheduling, and the
numbered voice link-label and legacy-failure regressions.

The review covers 43 reports, #839–#880 and #882; #881 is a separate pull request.
Ten reports were already closed with matching repair evidence. The remaining 33
are listed below. This inventory is a checkpoint, not a claim about future issues.

The user explicitly requested two dispositions: close demonstrated defects after
repair and verification; when the historical cause cannot be established, implement
and test the additional data collection needed to diagnose a recurrence, then close
with that diagnostic follow-up recorded. The second disposition does not establish
that the old failure was reproduced, that an incomplete task finished, that an
external provider recovered, or that physical-device latency improved.

The individual GitHub closure comments record the published revision and matching
execution evidence. This source document describes the implemented capture and the
planned disposition; it does not preempt the execution gates or the issue readback.

## Additional evidence collected by the app

| Area | New observations | Attribution boundary |
| --- | --- | --- |
| AI response | Configured and effective output limits; measured input/output/reasoning token counts; finish reason; transport/generation/replay facts; backend attempt counts and the last backend attempt's limit, status and output sizes; hashed model, profile, request, generation and original error identifiers | The owning provider stream and invocation only. Delivery wrappers do not replace the original producer's evidence. Missing usage is never borrowed from another invocation. |
| OpenRouter admission | Hash of the selected policy, ZDR and fallback flags, provider selection counts, and whether the observation was a cached rejection or no compatible endpoint | The actual selected model/profile and the admission result. Diagnostics do not weaken privacy requirements or dispatch a probe. |
| Tools and connections | Persisted tool/action identity hashes, schema and argument-shape fingerprints, original typed error/reason hashes, missing/invalid field counts and finite validation categories | The exact persisted call and its execution receipt. Arguments, scalar values, custom field names and result prose are excluded from public diagnostics. |
| GitHub task completion | Required/verified/remaining counts plus effective/used/remaining call budget, verification reserve, pending continuations and unresolved outcomes; up to eight unmet criterion IDs, reasons, categories, states and blockers; checkpoint hash | The current or explicitly retained verdict for the same run. A partial task remains partial; omitted criteria are counted. |
| Voice | Session hash and generation; engine, phase, permission, foreground, mute/focus/input state; actual capture/read/sample/nonzero-sample and recognized-character counts; response-to-queue, synthesis and first-audio timings plus warmup | The owning session, turn and immutable backend callback ticket. Android recognizer paths without PCM reads leave PCM measurements unavailable. Text and audio are never included. |
| Gateway | Owned job/run/profile/model hashes, observed job state and saved checkpoint identity; transport/replay facts only when actually supplied | The retained run and matching saved job. No history scan, additional poll, model request or remote write is performed for collection. |
| App | Original typed error hash and actual JVM crash, native crash, ANR or startup failure kind, alongside the existing bounded safe exception frames | The current failure or Android's matching historical process-exit record. Raw exception messages are excluded. |

## Review and follow-up flow

1. Reproduce the problem with Debug Mode enabled outside a temporary/private session.
2. Open **Report an issue**, enable **Include recent diagnostics**, and choose the
   affected problem area. Selecting an area is explicit; another area's latest
   failure cannot silently become this report's cause.
3. Choose **Refresh details** within ten minutes of the observation. The dialog
   shows the actual capture time and the complete expandable public preview.
   Unavailable measurements are listed. When there is no suitable observation,
   the report explicitly records `NO_CAPTURE` and can still include a description.
4. Approve the destination and the rendered report. Submission uses that exact
   preview; it does not refresh measurements after the click.
5. For a submitted report, **Report this problem again** opens a new draft with a
   new report ID and a visible reference to the original issue. It leaves the
   original approved payload and delivery receipt unchanged.

The diagnostic cache is memory-only, retains at most one observation per area,
and expires observations after ten minutes. Enabling privacy or disabling Debug
Mode clears captures, including when general application logging remains enabled.
Voice callback epochs prevent an old capture from repopulating the cache after a
privacy change. Completed/stopped voice observations keep their original timestamp.

## Public payload and delivery contract

Context schema 5 adds one typed `diagnostics` object. It accepts only closed keys,
finite enums, booleans, bounded numbers and SHA-256 values. The nested object is
limited to 3,072 bytes and the entire approved report remains limited to 8,192
bytes. Lists of criteria are aligned and bounded; unavailable and omitted fields
are disclosed before approval. An oversized complete report asks the user to
shorten the description; it does not silently discard already approved facts.

Validation runs at construction/projection, approval, durable outbox admission,
native GitHub delivery and hosted service delivery. The Kotlin app and Python
service share the same field/type/range contract and are checked with actual
Kotlin-approved payloads. Unknown/private extension fields and invalid outer
context fields are rejected. Context schemas 1–4 retain their original bytes and
lost-response reconciliation behavior.

Hosted delivery requires a service that advertises context schema 5. An older
service is rejected before dispatch and the approved report remains available.
Native GitHub delivery carries the exact approved payload. Collection never
authorizes a retry of an uncertain remote write or model execution.

## Issue disposition map

| Reports | Existing evidence and repair or diagnostic follow-up |
| --- | --- |
| #853 | The supplied archive joins the exact run and invocation to a rejected HTTP 429 and retained successful tool receipts. Source repair preserves one recovery owner and completed receipts across repeated provider rejection. Closure concerns that demonstrated local recovery defect; live upstream recovery was not observed. |
| #854 | The exact archived terminal cause is `PROVIDER_WAIT_CHECKPOINT_CHANGED`. Checkpoint/current-authority, continuation and classification repairs retain safe recovery and reconciliation requirements. |
| #865 | The durable Gateway send path omitted preparation of the exact requested model. Queue preparation/admission now waits for the selected model and retains cancellation/reselection ownership. Private-host model availability remains a separate runtime observation. |
| #848, #851, #856, #880 | Original required criteria and proof are incomplete or missing. Acceptance captures now retain the bounded criterion categories/states/blockers, reason hashes, budget and checkpoint needed for a recurrence. #851's 53 completed receipts and 203 remaining calls do not establish task completion. |
| #849, #850, #875, #882 | Provider completion/output failures need exact limits, usage, finish reason and backend attempt evidence. The new provider capture retains those owned observations when supplied. HTTP 200 alone does not establish a usable completion. |
| #855, #868, #869, #871 | Generic failures lack a complete original causal record. New provider/run/invocation diagnostics preserve owned identifiers and typed failure evidence. The time-only candidate for #855 and another provider's adjacent failure are not promoted into a causal join. |
| #858, #859, #878 | Claim extraction now preserves its attempt identity and specific failure/recovery semantics, including local deadlines and unknown remote outcomes. New provider diagnostics retain limits and completion evidence; historical causes remain unproven where the original receipt is absent. |
| #857, #860, #861, #862, #863, #864, #872, #873, #874, #876, #877 | Tool precheck/execution reports need the exact call's rejection and schema facts. New tool capture adds them without publishing arguments or replaying the call. Separate call IDs remain separate incidents; `dispatched=false` is not reported as a remote failure. |
| #867, #870 | OpenRouter privacy admission rejected the selected configuration without dispatch. Capture now records bounded selected-policy and model/profile observations while preserving the user's privacy settings. |
| #866, #879 | Voice failure and latency evidence was absent. Speech sanitization and lifecycle repairs are retained; new actual capture and playback timing data makes a recurrence diagnosable. The reported five-second delay remains unmeasured on the affected ASUS device. |

## Verification and limits

The machine-readable checkpoint
`docs/repairs/report-diagnostic-followup-2026-11-12-task.json` records local execution
counts, source manifests and the publication state. Focused tests exercise actual
production projection, serializers, approval/outbox logic, both delivery routes,
provider/tool/acceptance producers, snapshot expiry, private-session invalidation,
voice generation ownership and manual preview/follow-up behavior.

Local checks on the combined source passed before publication:

| Check | Result |
| --- | --- |
| Canonical `validate-local.sh fast` | 484 tests; all accompanying fast checks passed |
| Authentication and reporting service | 66 tests passed |
| Reporting contract, routes, outbox, cache and manual flow | 63 JVM tests passed, including eight manual-flow cases |
| Kotlin-approved payloads through Python validation/rendering | All 18 payloads passed: six areas and three capture modes |
| Provider/cache/gateway/tool diagnostic producers | 22 tests passed |
| Acceptance and GitHub reliability | 45 tests passed |
| Actual recorder and privacy preflight | Six tests passed |
| Voice and collector integration | 112 voice tests and three selected incident-collector methods passed |
| Changed Kotlin across the PR | All 304 files passed ktlint 1.3.1 |
| Generated inventory and release scope | 2,517 source files in 26 areas; exact scoped paths validated |

The suite counts overlap and are not summed into a single coverage claim. An
independent review reproduced a credential fragment caused by truncating a
follow-up before redaction. That defect and a repeated-rejection edge case after
Debug Mode opt-out were corrected and regression-checked before publication.

Isolated JVM checks use explicitly declared Android environment boundaries. They
do not establish Android lifecycle, device durability, OEM microphone behavior or
physical audio latency. Hosted Android compilation, unit tests and required CI
remain separate gates bound to the published source revision. Physical-device and
private gateway/provider qualification are not inferred from host fixtures.

## Bounded recovery validation and current microphone permission

The later integration preserves `a78ffb85` and `1caf6016`: queued provider
authority is rechecked after suspended ownership reads, the deferred microphone
factory checks permission immediately before recorder creation, and CodeQL
generation and compilation use separate bounded traced processes. Those changes
retain their own Android execution gates.

A Windows/Python 3.13 gateway run on `7ad3ae53` exceeded the engineering module's
120-second watchdog while its real worker was still progressing. Two unchanged
Windows repeats later passed that module in 51.197 and 52.714 seconds. A controlled
reproduction added a synthetic 50 ms delay after each actual store transaction:
the original 36-case module reached the same watchdog and stack locations, while
the separated 34-case state module and two-case worker module passed in 89.687
and 37.863 seconds. The synthetic delay is not a measurement of Windows storage.

The two worker methods and their helper move verbatim into
`test_gateway_v14_engineering_worker.py`. They reuse the original fixture and
retain real SQLite, normal worker scheduling, the 45-second readiness wait,
10-second join, and every recovery/no-replay assertion. The canonical 120-second
module watchdog is unchanged. The related evidence class had inherited the same
two worker methods with identical fixture configuration; removing those duplicate
registrations changes the affected collection from 78 to 76 cases while retaining
all 42 unique test bodies. Actual final-file checks passed 34 state, 40 evidence
and two worker cases. A deliberately non-recovering worker still failed the
unchanged readiness assertion after 45.013 seconds, stopped its thread, and did
not repeat its committed operation. Windows validation of the separated modules
remains a published-source CI check.

The recognizer owner also refreshes permission from its actual `hasPermission()`
check when capture raises `SecurityException`. This complements the adapter's
creation/start checks by covering a read failure after successful startup. It
does not infer revocation from the exception. The new read-path regression first
starts capture and schedules both deadlines, then revokes permission during the
read. Before the correction, one of 20 focused recognizer methods failed because
the report retained `true`; afterward all 20 passed. A still-granted exception,
no automatic retry, original error/recovery data, resource cleanup, retired
generations and private snapshots remain covered. This state-machine harness
omits concrete Android/JNI adapters; their permission-factory tests still require
actual Android execution.

The Windows installer smoke also exposed an independent status-publication
failure on `1caf6016`. Its intentionally incompatible candidate timed out, as
the rollback scenario requires; the previous runtime then became healthy, but
`os.replace` of `status.json` raised native Windows error 5. Another run on the
same source passed. Reader contention is a plausible explanation for the
intermittent replacement failure, not an established cause of every access
denial.

The preserved `7e90e579` correction, packaged in `2debde02`, allows only status
publication to retry Windows errors 5/32. It reuses the same closed, fsynced
temporary file for at most six replacement attempts and 310 ms of scheduled
backoff. Unrelated errors and non-status writes retain a single attempt.
Persistent denial still raises; the last valid file and in-memory status remain
unchanged. Startup, rollback and smoke deadlines are unchanged. Actual packaged
Windows smoke execution remains a separate source-bound CI check.

The final source integration also preserves `6f193a55`, which pins manual CI
validation to its requested base. Combined canonical fast checks pass 495
tests, and all 306 changed Kotlin/Kotlin-script files pass ktlint 1.3.1.
All 49 inputs to the 20-method recognizer check, including 35 repository
sources, retain the exact tested hashes after these Gateway/CI integrations.

The later `e4ea9ca0` integration preserves four additional Windows status
regressions: a non-retryable error after contention, failure before rename,
the actual Windows file-sharing boundary, and healthy rollback publication.
The native Windows case is explicitly skipped on Linux. Before that test-only
addition, the canonical Gateway suite completed 912 cases: 910 passed and
two host-dependent cases were skipped; all seven browser-bridge cases passed.
The combined manifest and subsequent CI keep their own source identities.

Live published revisions, CI outcomes and issue closures continue to be recorded
on PR #838 and individual issues. These source checkpoints do not turn queued,
canceled or historical runs into current validation passes.
