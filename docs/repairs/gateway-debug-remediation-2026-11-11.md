# Gateway debug remediation — 11 November capture

## Scope and publication target

This change addresses the 23 issue groups from the supplied `gateway-debug.zip` against PR #819, `fix/voice-chat-crash-and-diagnostics-2026-11-11`. Work started at `ab77b77dba3e9e7c928b153e2accf44e937d7e0c`, with `main` at `e62bded83b3861816c2c6e97116b30c9eb0920f1`. Existing model, argument-validation, bounded generation, acceptance-ledger, and cancellation safeguards on that branch are retained. Concurrent commit `aa51231a4` was merged, preserving its voice fixes, explicit/passive command reads, scoped negative cache, and request coalescing. Explicit receipt reads bypass polling delays without authorizing another POST.

The integrated source at `9fe97ffa7466f308af75ef8d501c86f21c3d6dc9` preserves the subsequent configured-Gateway preparation, checkpoint, reconnect-fixture, queue-proof, recovery, approval, rendering, and diagnostic changes through `ee02e9135cfbec9169c05e1e1e2c06c5ebe1fb58`. The final guard validates a saved conversation's original execution binding before model preparation and again afterward, and retains that binding ID. Only explicit retryable sleeping/loading observations allow preparation to continue; changed configuration, mismatched identity, uncertain transport, and cancellation do not authorize a wake or a replacement binding.

The source capture's transport SHA-256 is `915d458dbc0705c8621c3b18819ea1f41c5390e1f5d9f2f6a47e31dabdd362d6`; diagnostic SHA-256 is `55e9c61db05955df0f715cb1cbb2221b7e52d420e7b17319b4b10d5c739702fe`. The original export retained only part of its available history. Conclusions concern the captured windows; code validation cannot establish that a particular host listener is now running or that a physical phone meets a performance target.

## Issue-to-resolution register

| # | Observed issue | Resolution and evidence boundary |
| --- | --- | --- |
| 1 | Selected model was sleeping after earlier readiness | Recheck on selection reuse and network changes; ordinary chat Send and engineering admission prepare the exact selected model before dispatch. Engineering preparation uses the verified configured Gateway. Saved conversations validate their original backend before and after preparation and retain the same binding. Preparation preserves the frozen draft. A bounded server observer records completion of pending loads while the phone is detached. |
| 2 | Exact selected model was absent | Preserve exact identifiers and block promptly on definite model/access/configuration failures. No substitution. Missing host models still require host configuration or a different explicit user selection. |
| 3 | Four configured connections, zero usable tool schemas | Expose passive per-connection schema state, tool count, freshness, cause, recovery action, and discovery retry timing through readiness and connections. Configured is distinct from discovered. |
| 4 | MCP connection refused | Classify listener refusal safely; retry discovery with bounded backoff and recover the same configuration when its listener returns. Never export exception text, command arguments, addresses, or credentials as the diagnosis. Starting/fixing the external service remains a host action. |
| 5 | General gateway readiness was mistaken for task readiness | `GET /gateway/ready?required_tools=server_tool,other_tool` checks exact requested tool names only. Unrelated broken connections do not block a task that uses healthy tools. Schema readiness permits dispatch; actual execution proof remains separate and operation-specific. |
| 6 | Repeated admission rejection lacked a retained cause | Persist definitive rejected engineering-admission command outcomes with error code, admission stage, and dispatch evidence. Opt-in command-outcome lookup lets the client terminate reconciliation after a proven rejection even if the original response was lost. |
| 7 | Interrupted submission became an unrecoverable terminal failure | Preserve the original dispatch identity and uncertainty durably, including cancellation during dispatch. Reconcile its receipt before any further action. A preflight interruption remains a separate unsent admission failure. |
| 8 | HTTP cancellation was confused with remote job cancellation | Keep observer cancellation separate from explicit Stop. Preserve cancellation scope in diagnostics. The existing Stop path remains the only explicit remote cancellation request; receipt recovery does not cancel or replay remote execution. |
| 9 | Missing command receipt was polled repeatedly | Persist bounded lookup backoff from ten seconds to five minutes. A 404 never authorizes another POST once dispatch is uncertain. Accepted and rejected outcomes resolve the retained command; unresolved outcomes remain fenced. |
| 10 | Model-load operation identity existed only in memory | Save unresolved preparation identity in the encrypted vault before dispatch. Restore and reconcile that same operation after restart. Storage failure blocks a new load. A completed receipt plus a fresh sleeping observation permits one bounded new wake. |
| 11 | Invalid tool arguments | Retain the branch's existing schema validation, field-specific correction guidance, and identical-invalid-call suppression. Existing completion/contract and Android tool-budget regressions verify that invalid proposals do not dispatch side effects. |
| 12 | Completion repair exhausted its allowance | Retain the single-repair, shared-output-budget and shared-deadline safeguards. Add a specific terminal action to resume from saved evidence with a revised allowance. Budget or missing usage does not authorize an unbounded retry. |
| 13 | Many successful tools still left acceptance criteria unverified | Emit one typed, privacy-bounded diagnostic event per retained criterion across all acceptance pages, including state, category, blocker, required flag, and receipt count. Preserve the acceptance ledger's independent verdict; tool count never upgrades missing proof to PASS. |
| 14 | Terminal failure lost useful stage context | Keep typed failure stages and separately record the last actual observed progress stage from the run journal. Unknown failure location stays unknown; successful progress is not relabeled as the failure's cause. |
| 15 | Dashboard polling dominated diagnostic retention | Reduce desktop polling frequency and sample repeated healthy dashboard exchanges while retaining failures. Expose sampling counts separately from eviction/loss. |
| 16 | Android frame/GC pressure | Reduce avoidable polling/export churn and avoid full-history materialization during export. Preserve measured frame/GC evidence. The capture does not prove a leak or identify every frame-stall cause; physical-device performance qualification remains required. |
| 17 | Tiny frame samples were classified as sustained jank | Require a sufficient frame sample before classifying sustained jank. Individual slow frames remain available as observations. |
| 18 | Export omitted most retained sessions | Stream all retained session captures into the ZIP with per-source coverage, event counts, hashes, retained/omitted/evicted distinctions, and window metadata. Already-evicted history cannot be reconstructed. |
| 19 | Generic HTTP/unknown causes hid actionable failures | Retain allowlisted structured server codes, stages, dispatch evidence, and known nested transport cause types. Bound error parsing and avoid publishing arbitrary response text. |
| 20 | Healthy events suggested failure recovery | Map healthy events to no recovery action. Recovery controls require an actual supported failure classification. |
| 21 | Client route labels hid command polling | Classify command, conversation, message, identity, and job-catalog requests by their actual route family. |
| 22 | Cross-device clock skew created impossible ordering | Export clock domains and ordering limitations. Use per-source sequence/monotonic evidence for ordering; do not infer cross-clock latency from unsynchronized timestamps. |
| 23 | A shared version label concealed different builds | Retain gateway source/manifest identity and bounded backend build version/revision/digest when exposed. Mark unavailable build evidence explicitly. A version label alone is not a unique build identity. |

## Recovery invariants

An accepted command or a durably rejected command is an authoritative outcome for that identity. A lost response, cancellation, missing receipt, or reconnect is not permission to create another remote operation. Admission and rejection share a durable transaction fence; reused identities with changed payloads remain conflicts. The client preserves a stopped, superseded, or backup-restored turn instead of applying a later receipt to it.

Model readiness is exact-selection metadata, not proof of a completed inference. Preparation requires the existing load capability and authorization, records the operation before its first request, and uses a bounded observer only for previously admitted unresolved loads. The server observer sends no load or inference requests.

Diagnostic evidence is intentionally limited to supported enums, digests, counts, identities, and measured observations. Criterion labels, prompts, response bodies, credentials, private addresses, and arbitrary backend build prose do not enter the shared projection.

## Validation

| Check | Result and scope |
| --- | --- |
| Canonical fast validation | `bash scripts/validate-local.sh fast` passed on the integrated source at `9fe97ffa7466f308af75ef8d501c86f21c3d6dc9`: 462 script tests plus schema, dependency, resource, regex, policy, inventory, and whitespace checks. |
| Complete gateway runner | 878 Python tests passed, two platform skips, and seven browser checks passed. Gateway implementation files are unchanged by the later Android integration. The subsequently updated model-preparation HTTP fixture was rerun separately: all 29 tests passed. |
| Authentication/reporting | 35 tests passed. |
| Model, submission, receipt, and queue host tests | 144 tests passed across nine classes at `9fe97ffa7466f308af75ef8d501c86f21c3d6dc9`. The 100-production-file source set was compiled at `1a1f224600780b82226437cec8b1efb12e7dcd6c`; the final changed queue owner and its test were recompiled, with every reused source and class verified unchanged before rerunning all nine classes. Recorded source hashes remained stable. This includes the seven final binding-guard regressions and all 33 queue cases. |
| Diagnostics, acceptance, terminal-stage, and frame host tests | 121 tests passed across 13 classes after compiling 98 production files from `1a1f224600780b82226437cec8b1efb12e7dcd6c`. Read-only comparison verified that all 100 exercised source owners, including all 13 test sources, are byte-identical at `9fe97ffa7466f308af75ef8d501c86f21c3d6dc9`. |
| Changed Kotlin formatting | ktlint 1.3.1 and `git diff --check` passed. |
| Complete Android build, packaging, and device checks | Final-head hosted CI remains authoritative and is not claimed as passed here. |

The focused Android checks use a current-source Kotlin/JUnit/Robolectric host harness with real cached supporting dependencies. Their scope includes the changed implementation and its exercised source dependencies, while prebuilt Room-generated implementations remain a documented limitation. These results do not replace complete Gradle/KSP/KAPT, dependency-injection, APK, or device validation. Local full Gradle processes disappeared during generation/compilation without a compiler verdict; KSP completed after verified immutable caches were moved to disk. No physical-device or deployed-backend pass is claimed.

The canonical v16 capability fixture was recaptured through real HTTP with zero inference requests. The task checkpoint records the focused source-manifest identities and validation scope.

Relevant suites cover exact task readiness, listener recovery, command receipt fencing, process-restart model reconciliation, multi-session export completeness, sampling without losing failures, acceptance-page projection, last-observed-stage privacy, and existing tool/generation limits.

## Deployment qualification

After installing the matching complete gateway package and Android build, verify the originally configured listeners and exact model on the affected host. Repeat a sleep-to-Send transition, a phone disconnect during preparation/admission, and an export spanning multiple sessions. Confirm the original task's remaining acceptance criteria with their required evidence. Collect new device frame/GC measurements against the published revision. These are environment checks, not results claimed by mocked or local JVM/Python tests.
