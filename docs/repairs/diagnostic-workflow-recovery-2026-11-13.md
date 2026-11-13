# Diagnostic and workflow repair ledger — 2026-11-13

## Scope and source identity

The owner requested a review of all open GitHub issues and the supplied application diagnostic log, implementation on one draft branch, and closure of each issue after either a verified code repair or a reporting improvement that collects the evidence missing from that incident.

- Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
- Working branch: `fix/diagnostic-workflow-recovery-2026-11-13`.
- Starting candidate: `b3eb5e1ec71cd38d78acfa9462c174f3e06a0f8e`, shared by existing ready-for-review PRs #838 and #881. Neither PR was a draft when this task began, so a new branch was created. All repairs remain on that branch.
- Base: `main` at `97f10ff7a1f3789fb82d958b2dc6b22c8074dfdf`.
- Uploaded application source: `36ac21b6…`; its Git tree matches the starting candidate. This establishes applicability of the uploaded log without equating different commit identities.
- Issue inventory: initially #891–#921 (31); refreshed to #891–#927 (37) during implementation. Pull requests are excluded from the issue count.
- Task checkpoint: `diagnostic-workflow-recovery-2026-11-13-task.json` in this directory.

Closing an issue for a diagnostic improvement means the requested reporting work is delivered. It does not establish that an external provider, a private gateway, or the historical device incident has been repaired. The corresponding resolution comment must preserve that distinction.

## Issue-by-issue disposition

| Issues | Observed failure | Repair or additional diagnostic evidence |
| --- | --- | --- |
| [#891](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/891), [#924](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/924) | Generic `CHAT_FAILED` with no useful failure identity or phase | A bounded incident capsule records explicit phase, dispatch/acceptance evidence, cause provenance and missing-evidence markers. Missing evidence remains unknown; no cause is invented. |
| [#892](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/892), [#893](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/893) | The shipped review request was parsed as a branch named `revisions`, causing GitHub 422 | Baseline parsing excludes embedded head/base and path/ref fragments and handles the complete shipped preset. Chat/tool deduplication requires the same explicit causal identity. |
| [#894](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/894) | LLAMA gateway rejected a successful tool result with `CLIENT_TOOL_RESULT_IDENTITY_MISMATCH` | Capture the original provider call before alias/default normalization; preserve it through durable checkpoints, all provider histories, compaction and continuation validation. Original ID, name, arguments and signatures remain bound together. |
| [#895](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/895) | Full status completed 3/7 requirements under a six-call stage limit | Separate quick status from full health inspection. Full inspection gets a bounded budget up to 32, still constrained by request/profile/workflow limits. Persist the actual stage budget, exact next read and unmet requirements. A valid uncertainty disclosure can satisfy its own reporting criterion while health obligations remain blocked. |
| [#896](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/896) | Integration acceptance completed 3/5; workflow/job evidence and fixture approval mode were absent | Keep integration fixture testing on its intended native path, rather than accidentally admitting it as an implementation job. Preserve missing prerequisite gates and expose eligible/excluded receipts and unmet requirements. This incident used 82/256 calls; it was not proven to be budget exhaustion. |
| [#897](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/897), [#898](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/898) | Remote OpenRouter 429 after actual dispatch, with retry delays | Preserve the existing durable provider wait and selected profile. Record whether the wait was persisted, the validated recovery authority, actual delay and its header/backoff provenance in a separately captured follow-up report. This does not remove the provider's rate limit. |
| [#899](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/899)–[#905](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/905) | Seven same-run `UNKNOWN_FIELD` tool precheck failures, with changing argument values and call IDs | Enforce the advertised one-correction allowance by schema, action and failed constraint. Changing values cannot evade the guard. One unsuccessful correction ends with typed exhaustion; a genuinely valid correction or a different constraint remains possible. Safe shape diagnostics identify allowed paths/types without retaining raw arguments. |
| [#906](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/906)–[#910](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/910) | Saved view unavailable/wrong-operation errors and opaque search failures | Record bounded view provenance, source operation, load outcome, scope and continuation checks. The known error-code hashes for #908/#910 identify `GITHUB_SEARCH_CURSOR_UNAVAILABLE`; additional cursor diagnostics distinguish a missing retained search from an unobserved page boundary, with hashed search/cursor identities. Distinguish missing, failed, identity-rejected and restored views. Preserve cursor/identity enforcement and never replay the source mutation to reconstruct a view. Historical root causes remain unconfirmed where the original report omitted them. |
| [#911](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/911), [#912](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/912), [#915](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/915), [#916](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/916), [#917](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/917) | Opaque tool/precheck errors, including a local failure inside an HTTP 200 envelope | Preserve typed precheck versus execution phase, failed constraints, bounded value types and explicit cause provenance. The known code hashes identify #911/#912 as `DEPENDENCY_DEFERRED` and #917 as `DEPENDENCY_FAILED`; bounded prerequisite linkage now identifies the blocking edge without leaking arguments. HTTP success is not promoted to tool success. The absent original field/cause cannot be recovered from hashes alone. |
| [#913](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/913) | MCP unavailable; uploaded log records a refused connection while the gateway is healthy | Preserve MCP-specific failure classification, observed failure phase, a hashed connection identity, and separately labeled retained readiness/health with its age. The original code hash identifies `MCP_CONNECTION_UNAVAILABLE`. The private MCP service must be checked independently of gateway health. The report improvement supports that diagnosis; an external service restart or configuration change has not been performed. |
| [#914](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/914), [#918](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/918), [#922](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/922) | Generic `GITHUB_EVIDENCE_REQUIRED` (0/1), with no explanation of the acceptance evaluator | Record the actual evaluator branch, mismatch reason, and linked eligible/excluded evidence counts. Preserve that data through the acceptance ledger and report projection. Do not weaken evidence requirements to obtain a successful result. |
| [#919](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/919), [#921](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/921) | GitHub PR operation failed with 422 but was reported as `APP_ERROR` | Normalize the known validation alias and retain safe validation categories, allowed parameter roots and bounded/hash-only details. The historical payload does not establish which PR field GitHub rejected. |
| [#920](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/920) | Generic chat failure in the same run as a PR validation failure | Propagate explicit causal tool identity when available. Deduplicate only an established same-run cause, never a timing-based guess. Capture missing evidence when propagation is absent. |
| [#923](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/923) | Voice failure called “delivery” while the voice phase was `THINKING` | Report response-wait versus reply phase accurately, retain bounded voice/run/invocation correlation and classified upstream cause in the follow-up snapshot. Preserve one chat-owned incident when that is the true owner. No audio or recognized text is collected. The report already showed microphone permission/focus and nonzero captured audio; it did not establish a microphone failure. |
| [#925](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/925) | Privacy admission blocked before dispatch: no compatible zero-data-retention endpoint | Preserve the required privacy policy. Record catalog age and aggregate counts for model endpoints, compatible endpoints and allowlist/denylist/fallback exclusions, with closed blocker codes. Malformed metadata is unknown rather than falsely incompatible. Provider names, credentials and raw policy are excluded. |
| [#926](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/926) | NVIDIA HTTP 410, with no failure phase or cause category | Add actual model-invocation/dispatch evidence and safe provider-rejection classification. 410 is classified only as `GONE`; the report does not prove whether a model, endpoint or other resource was retired. |
| [#927](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/927) | Anthropic HTTP 400, with no public validation category/parameter evidence | Project bounded rejection categories, provider-type hashes and allowed parameter roots/hashed full paths through HTTP/SSE failure handling. Raw provider error text, request data and credentials stay private. |

## Additional repairs from the uploaded log and existing CI failures

### Conversation and workflow routing

An ordinary conversation could be reused as if it already had an engineering repository binding. New engineering admission now retains the local chat linkage but creates the correct server-side engineering conversation without attaching an ordinary remote revision. When both kinds are present after restart, engineering and ordinary lookups select the correct snapshot and reject ambiguous engineering bindings.

The integration preset now has an explicit integration-testing intent. Quoted markers cannot upgrade intent, while an actual request to implement/fix remains implementation. Existing retained command reconciliation remains authoritative.

Compatibility review starts with the existing compact prompt. The selected model, reasoning policy and bounded fallback remain unchanged. Rate-limited preflight retains the single failed probe and its delay evidence; it does not proceed to repository work.

Read-only CI queries no longer incorrectly imply a write action. PR labels no longer duplicate the PR number. Four stale CI fixtures were corrected to use trusted status aliases, action-specific payloads with pinned SHAs, valid completed-job conclusions and the facade's actual compact projection.

### Gateway availability and command recovery

Cloud-provider profiles no longer enter local engineering-model readiness probes. This does not select another model. A command receipt 404 is `COMMAND_RECEIPT_UNAVAILABLE`, an unknown outcome requiring reconciliation, rather than proof that the gateway contract is unsupported. The existing 5/10/20/40/60-second reconciliation backoff remains bounded; 405/501 still signal unsupported behavior.

Concurrent gateway identity reads for the same authenticated route share one in-flight response. Every later sequential read remains fresh. Authentication/header identity and runtime-epoch validation remain part of the binding. The uploaded request totals alone do not prove that every metadata request was redundant.

### Reporting, privacy and review

New context schema 7 / diagnostics schema 3 adds a closed, bounded incident capsule (maximum 3,072 bytes), safe tool/provider/acceptance projections and a separately captured manual follow-up snapshot. Collection is debug-enabled, non-private and run-correlated. It does not rewrite an approved or queued payload or silently send a report. Explicit missing-evidence markers are retained rather than guessed.

All 37 historical approved issue payloads continue to validate unchanged. Schemas 1–6 remain accepted. A hosted reporting service must advertise schema 7 before a new schema-7 report is dispatched; an older host must block rather than mutate/downgrade the approved bytes. The direct GitHub report path accepts the new schema. The hosted validator and capability update are included in this branch; deployment is a separate operational step.

Manual submission now uses the platform metadata already present in the user's reviewed preview, instead of rereading Android `Build` during submission. This preserves preview identity and fixes the JVM failure in `GatewayReportDiagnosticsTest`.

### Log and UI performance

The recorder formerly held a shared file lock across a burst of up to 64 writes/fsyncs. It now takes one bounded record from the queue and persists it within the same file lock, preserving the queue-or-disk snapshot invariant. Recent-entry retention/publication uses a separate lock so the UI does not wait for that disk burst. Privacy epochs and checkpoint/clear lock ordering are preserved.

Repeated text layout observations now include width/height constraints, density, font scale and layout direction. These fields distinguish a real layout change from redundant measurement without collecting text. The existing log did not provide enough evidence for a new text-measurement cache. No physical-device latency improvement is claimed from a code-only review.

The uploaded archive's 20 sections, byte/hash/count records and 8,552 JSONL rows were checked; 51 chunks reconstructed four large records. The observed 18,727 actual provider tokens are kept separate from a rejected 18,636-token estimate. Export duration and concurrent heap growth are observations, not proof that export alone caused the allocation peak.

## Validation and publication status

This ledger is updated as checks finish. Passing host subsets are not substituted for the repository's required aggregate check.

| Check | Current evidence |
| --- | --- |
| Canonical fast host gate | Passed final source snapshot: 495 script tests plus CI policy, generated schema, dependency catalog, secret scan, Android regex, resources and diff whitespace. |
| Gateway package and Python/JS behavior | Passed: 943 Python tests across 70 modules, including the 50,000-event diagnostic stress test, and all 7 JavaScript tests. Clean final run exited 0. |
| Authentication/reporting | 76 authentication tests passed; subsequent focused reporting/configuration suite passed 62. All 37 historical issue payloads validate unchanged. |
| Review/status/acceptance host harness | 86 passing executions against actual policy, planner, acceptance and review code; one parser-dependent budget test and full session/facade integration remain for Gradle. |
| Provider replay/rejection host harness | 27 tests passed against actual replay, continuation, checkpoint, saved-view and bounded rejection code. Boundary stubs do not qualify Android or HTTP integration. |
| Report host harness | 34 tests passed against real report/capsule/manual/privacy/projection code, including the original JVM report failure. Android scheduling/database/network boundaries are not simulated as proof. |
| Root policy/diagnostic host harness | 25 tests passed: integration intent, privacy endpoint observation, frame context and bounded queue. Android layout, persistence and transport integration remain for Gradle. |
| Kotlin formatting | Owned changed files pass pinned ktlint 1.3.1; final full changed-file check is required. |
| Android Gradle | JDK 21, verified Gradle 9.8.0, SDK 37 and NDK 29 provisioned. Two source-generation attempts lost the Gradle daemon during KAPT stub generation without a compiler diagnostic. Full Android compilation/tests are not yet qualified; hosted checks remain necessary. |
| Hosted checks | Pending publication of the new draft PR. The old candidate's 10,022-test run (six failures, five skipped) is historical evidence, not new-branch validation. |
| Physical phone / private gateway / QNN or GPU execution | Not run. No device qualification or private-service deployment is claimed. |

The gateway package manifest binds its regenerated `v14/diagnostic_schema.py` to source commit `7fd99490171368aa3369cac7ed8b8c1d13c5debc`. This is a parent of the repair publication and avoids claiming a package came from an unrelated source tree. Other package files retain their verified hashes.

## Operational follow-up

Build and install the review artifact produced by this draft branch. Deploy the included reporting-service update before using schema-7 reports through the hosted path. Reproduce unresolved incidents with debug reporting enabled, then review the newly captured diagnostic preview before submitting it. Previously approved reports remain unchanged.

For MCP refusal, inspect the configured MCP service independently from the healthy gateway. For provider 429, preserve the reported wait and verify the provider's quota/rate limit. For privacy admission, select a model/provider combination that satisfies the required policy; do not disable the policy as an automatic recovery action. For HTTP 400/410, use the new rejection category and safe parameter evidence before deciding whether configuration, model availability or request construction must change.

The PR remains a draft until current-head required checks are satisfied and the remaining environment-specific evidence is reviewed. Issue closure under the owner's requested diagnostic fallback is not merge or deployment approval.
