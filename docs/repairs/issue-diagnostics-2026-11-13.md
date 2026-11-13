# November 13 issue and diagnostic repairs

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Main was `97f10ff7a1f3789fb82d958b2dc6b22c8074dfdf`. No open draft existed at selection; PRs #881 and #838 were ready for review at the same `b3eb5e1ec71cd38d78acfa9462c174f3e06a0f8e` head. This single branch, `fix/issue-diagnostics-2026-11-13`, starts from that repair candidate. Existing repairs are preserved. This register covers the initial 31 open issues #891–#921 and six reports #922–#927 that arrived during review; it does not assert that every historical incident has been reproduced or corrected on the installed device.

## Evidence and implemented fixes

All 80 section-file SHA-256 checks passed across four supplied staged archives. JSONL records parsed without malformed rows: `b844233e` 8,552; `62a4cb28` 8,059; `a74d18ed` 10,117; `2984dfe7` 5,293. The earlier standalone app diagnostic, app audit and gateway debug bundle were also examined. Retention windows and staged exports are not complete lifetime histories. Public issue reports refer to build 141 at `36ac21b6e70cf288f44b01c47e8cfec98dc76990`, before these repairs.

1. The built-in review prompt says `head/base revisions`. The baseline parser interpreted `base revisions` as the literal Git ref `revisions`, causing the observed commit lookup HTTP 422. Baseline labels no longer match inside slash-delimited prose. Explicit `base: release/1.0` and other real references remain supported.
2. The runner routes aliases and adds default actions before executing tools. Previously, that normalized call also replaced the original provider call in continuation history. The gateway then correctly rejected the changed identity. Calls now retain both identities, journals persist them in version 2 (version 1 remains readable), continuation validation and provider serialization use the original identity, and execution uses normalized arguments. Token accounting measures the serialized original arguments. Completed receipts are reused without replay. The logged identity failure is consistent with this verified defect; the original gateway-side payload was not retained, so exact historical argument attribution remains unproven.
3. `github_issue` now advertises the already-supported `response_detail` presentation option, matching related GitHub tools. Historical header-query reports do not identify the rejected field, so this schema fix is not claimed as a demonstrated root cause of every rejection.
4. Incident reports now include allowlisted readable action, error, reason and dispatch-phase values, unknown-field counts, and separate unmet requirement/capability counts. Provider reports distinguish wait eligibility, checkpoint presence and continuation permission, and retain consecutive rejection counts only for the owning profile/model. Provider failures also retain allowlisted stage, domain and error classifications. Voice reply-delivery fallbacks distinguish failed turns without metadata from empty completed replies and include a bounded character count. Generic chat failures gain code-presence classification and hashed run/invocation correlation. Private argument values, free-form error text and unknown enum strings remain excluded. Receipt-provided values cannot replace call-owned readable identity fields.
5. The Android and hosted reporter schemas agree on these additions. Reports containing the new fields require the `INCIDENT_FAILURE_DETAILS_V1` service feature. An older hosted service keeps the approved payload saved and returns update-required before POST; it does not retry incompatible submissions or silently discard the new evidence. Native GitHub reporting retains its separate authorization path. Deploy the matching reporting service with the app before expecting hosted delivery of these extended reports.

## Issue disposition map

“Diagnostic follow-up” means the requested collection improvement is implemented and the report can be closed under the owner's explicit policy. It does **not** mean the original symptom is verified resolved. No verification, branch-binding, approval or remote-uncertainty safeguard was bypassed.

| Issues | Evidence / disposition | Next capture or verified behavior |
| --- | --- | --- |
| #892, #893 | Reproduced baseline parser defect; fixed | Built-in review prompt no longer resolves the ref `revisions`; explicit refs still resolve. |
| #894 | Reproduced continuation identity defect; fixed code path | Original identity survives alias/default routing, serialization and journal reload; exact historical gateway payload unavailable. |
| #891, #920, #924 | Diagnostic follow-up: generic chat failure | Code absent/known/unrecognized plus owning run/invocation hashes; no fabricated exception cause. |
| #895, #896 | Diagnostic follow-up: partial integration acceptance | Separate unmet requirements from capabilities and retain existing blockers, budget, pending continuations and hashed checkpoint. Missing approvals or executable validation configuration still need real setup. |
| #897, #898 | Diagnostic follow-up: provider rate limits | Capture may-wait, actual checkpoint presence, continuation permission and repeated-rejection count. Existing durable provider-wait repairs remain in the parent candidate. External capacity cannot be guaranteed by client changes. |
| #899–#905, #907, #909, #915, #916 | Schema compatibility fix plus diagnostic follow-up | Accept supported `response_detail` for issue reads; capture safe action/reason/schema and unknown-field count. The historical rejected field is not known. |
| #906 | Diagnostic follow-up: saved-view unavailable | Safe action, error and reason distinguish expired/wrong-operation recovery; preserve existing source-recipe and cursor checks. |
| #908, #919, #921 | Diagnostic follow-up: generic tool/HTTP failure | Safe tool action/error/phase plus retained HTTP stage and call hashes; HTTP 422 alone does not identify a bad selector or remote state. |
| #910–#913, #917 | Diagnostic follow-up: local precheck / unavailable tool | Readable allowlisted rejection cause alongside dispatch status and call-owned correlation; unavailable private MCP service cannot be repaired from this capture alone. |
| #914, #918, #922 | Diagnostic follow-up: missing acceptance evidence | Separate required/capability totals and retain criterion state/category/blocker; NOT_TESTED remains unverified. |

| #923 | Diagnostic follow-up: voice reply delivery | Speech capture and recognition succeeded; record FAILED_WITHOUT_DETAILS versus EMPTY_REPLY and response character count. Keep recognized draft; never resend automatically. |
| #925 | Observed configuration requirement, not a microphone/provider outage | ZDR was required and endpoint selection observed NO_COMPATIBLE_ENDPOINT before dispatch. Choose a compatible profile or explicitly review the privacy policy; no automatic relaxation. Existing privacy snapshot already identifies the required configuration change. |
| #926, #927 | Diagnostic follow-up: NVIDIA HTTP 410 / Anthropic HTTP 400 | Retain safe provider failure stage/domain/code alongside HTTP status and owning invocation hashes. A 410 response alone is not proof of model retirement; the response body/configuration is unavailable. |

The earlier archives also contain provider-wait checkpoint, voice focus/startup, review/claim verification, pagination, gateway admission and UI/reporting failures. The selected parent already contains the associated repair registers, including `pr881-open-issues-2026-11-12.md`, `diagnostic-62a4cb28-pr881.md`, and `session-reliability-reporting-2026-11-12.md`. This follow-up preserves those changes; their private-host, device and complete acceptance qualification remains outstanding. Host evidence is not proof of end-to-end performance or successful microphone playback.

## Validation and limits

- Baseline host regressions: 24 passed using current production baseline/preset/validation code and unrelated platform boundary substitutes.
- Identity/journal host regressions: 13 passed using current call/journal/continuation/compaction code. Provider serializers and runner normalization compile in the focused fixture. Android AtomicFile and unrelated DTO/runtime boundaries are substituted; this is not an Android lifecycle or OS crash test.
- Report projection, approved outbox, capability negotiation and native submission host regressions: 39 passed. Android context/build/file boundaries are substituted.
- Voice delivery diagnostic: 1 focused host regression covers failed and empty replies, actual voice session/diagnostic projection with an incident-sink/profile boundary, and draft/no-resend behavior.
- Focused tool/provider report producers: 2 passed with data-carrier boundaries; the provider-limit test uses a directly constructed equivalent receipt.
- Hosted reporting tests: 74 passed, including extended closed-schema acceptance/rejection and existing service/broker contracts.
- Canonical `bash scripts/validate-local.sh fast`: 495 script tests plus CI policy, schema, dependency, secret, regex and resource checks. Passed; final status is recorded in the task checkpoint.
- Five actual Kotlin-approved extended reports were accepted unchanged by the Python diagnostic validator.
- Kotlin formatting, regenerated architecture/source inventories and bounded release-scope coverage are checked before publication.
- Android Gradle tests were attempted but provisioning failed while downloading Gradle 9.8.0 due to unavailable network access. No full APK build, instrumented test, private gateway execution, microphone playback or hosted rollout is claimed. Hosted CI must qualify the published commit before merge. Unified Reliability v2 remains incomplete.

## Follow-up on PR #941

PR #941 carries the consolidated source and latest commits from #929, #928 and the shared #838/#881 candidate. Four staged diagnostic archives, the earlier app and Gateway captures, and the new public report evidence were reconciled against the integrated code. The older app reports all came from debug build 141 at `36ac21b6e70c`.

| Reports | Disposition and remaining evidence |
| --- | --- |
| #930 | A typed terminal-catch location, bounded exception type hashes and a fingerprint are captured when a throwable is available. The original cause was unavailable. |
| #931 | The catalog reported no endpoint compatible with the selected zero-data-retention policy. Preserve the policy and capture catalog age and exclusion categories when observed. |
| #932, #933, #938–#940 | OpenRouter returned HTTP 404 on two model identities. The revised reporting differentiates an explicit model-unavailable error from a generic 404 and hashes the owning response request ID when provided. The historical provider body and route are unavailable. |
| #934–#936 | Diagnostic fallback. Reports retain the affected settings sections, the stage at which authorization was invalidated, and the resume requirement when available. Raw settings, profile fields and argument values stay private. Existing dispatch and reconciliation fences remain active. |
| #937, #942 | Diagnostic fallback. Keep known receipt exclusions across several failed criteria and retain unmet requirement and capability counts. #942 verified 3/5 requirements and retained missing workflow/job and fixture-approval prerequisites. Those prerequisites still need live evidence. |

The API 34 acceptance run `43375248044` failed inside Compose's forced redraw before the conversation-glass assertions could run. The test now samples both controls from a single composited frame, preserving the existing opacity, blur, restoration and pressure-recovery assertions. Fresh Android acceptance on API 34 and API 36 is still required.

Deploy a reporter advertising `DISPATCH_POLICY_DIAGNOSTICS_V1` before submitting extended policy-change reports to the hosted service. Older hosts preserve the approved report for retry. No live OpenRouter or on-device replay is claimed for these historical incidents.
