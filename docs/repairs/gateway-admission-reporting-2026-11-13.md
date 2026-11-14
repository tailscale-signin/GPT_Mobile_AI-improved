# Gateway admission and report follow-up — November 13, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.
Working branch: `fix/gateway-admission-reporting-2026-11-13`.
Parent: PR #1068, `fix/open-issue-recovery-2026-11-13`, commit `317892d349fbfe941f8c72a6fb06a6ecc679fa7f`.
No draft was open at intake. The existing PR was ready for review, so this work uses one new branch based on that exact head.

## Evidence

Reviewed the bodies and comment threads of all eight open issues, #1069–#1076. All comment threads were empty at review. Rechecked the open-issue set before publication.

The attached `app-diagnostics-7974477296351510877.jsonl` contains 5,669 valid JSONL rows: 1,544 events, 4,122 continuations, two duplicate-reference batches and one summary. Every continuation has a parent event. The summary reconciles 5,701 retained source occurrences. SHA-256: `b37ef1abe4dcaa0be81c7c9a388b0f3a91a1e5537c42a460ec5a6a874b90fa64`.

Capture build: 0.9.66.7 / 141, source `05660b80f1b5ec725a730fe2b275069b532145e5`. There are 26 distinct failed/not-dispatched model invocations: 23 `REASONING_OFF_UNSUPPORTED`, two `REASONING_ON_UNSUPPORTED`, and one `CONTEXT_CAPACITY_EXCEEDED`. These are invocation outcomes, not 26 independent defects. The context rejection records input 9,639 + safety 2,409 + output 4,096 against capacity 5,632; tool schemas are the largest component at 4,414 estimated tokens.

## Implemented behavior

- All four typed request-capacity rejection codes survive report selection and provider error classification. A capacity error no longer becomes `CHAT_FAILED` simply because the reporting vocabulary omitted its code. Reports retain the owning request's measured/estimated input, safety, output, capacity and largest-component counts.
- Review repair: both the app and reporting-service diagnostics validators now accept all four selected capacity codes. Regression coverage constructs and validates each complete provider capture, preventing a selected code from silently discarding its diagnostic payload.
- Review repair: the gateway source manifest was regenerated after the preflight test changed. `gateway.py validate-config` now verifies the reviewed package instead of stopping every platform on a stale test hash.
- Review repair: the strict release-scope gate identified four unlisted source, regression and repair-ledger files. Those four exact paths are now admitted without broadening the release base, qualification, limitations or path patterns.
- A failed text initialization probe ends benchmark readiness and persists its final receipt before any tool probe. Initialization and requested tool probes are required for a positive readiness result, even if remote metadata originally required only configuration. Strict On/Off settings are not silently changed to Auto.
- Validation policies whose JSON root is a list or scalar produce a normal admission blocker rather than an uncaught attribute error. The gateway emits fixed policy categories: not configured, missing/oversized, repository unconfigured, invalid document/entry, unreadable, or validated. No configured path or policy text is exported.
- Both HTTP-success preflight refusals and structured error responses retain gateway report snapshots. Previously a normal preflight refusal could throw before its reason reached the selected Gateway report capture.
- Queue reports retain local lease count, pending deliveries, unsettled submissions, legacy runs, cached active jobs, actual local concurrency limit, and a fixed blocker category. Capture is throttled and optional; capture failures cannot pause a saved prompt.
- Reasoning reports retain capability age, observed model state, and the explicit recovery action alongside requested mode, control state and template hash.
- Acceptance reports count recorded approval/input/live-evidence/continuation blockers over the complete ledger, independently of the compact criterion sample. Ambiguous branch-protection observations retain an explicit permission-check recovery action, without turning inaccessible policy into “no protection.”
- Editing dialogs and the selected-text sheet now compose in a separate function from `ChatScreenContent`, which the capture reports 75 times at ART's instruction limit. Their callbacks, ownership and order are preserved. Device measurements are still needed to confirm the compiled method size and frame-time improvement.

## Per-issue disposition

| Issue | Disposition | What remains external or unverified |
| --- | --- | --- |
| #1069 | Diagnostic follow-up: source-owned queue blocker and occupancy capture replaces an empty Gateway report. | The existing single-job global lease remains. Two-job concurrency is **not implemented**; it requires coordinated changes to model preparation, uncertain-admission reconciliation and same-chat ordering. The supplied report does not establish which runtime blocker held its first turn. |
| #1070 | Preflight report capture and typed policy diagnosis; malformed-policy hardening. | The PC still needs a valid host-approved repository validation policy. No host policy was installed or bypassed. |
| #1071, #1074 | Diagnostic follow-up: explicit protection-read permission recovery, preserving ambiguous observation. | `NOT_FOUND_OR_INACCESSIBLE` does not prove protection is absent. Repository/account permissions must be checked; acceptance stays blocked when required evidence is unavailable. |
| #1072, #1075 | Diagnostic follow-up: capability age/model state and explicit reasoning recovery. | Strict On still requires verified template control. The user can explicitly choose Auto or qualify the exact model/template. No model or setting was silently substituted. |
| #1073 | Diagnostic follow-up: full-ledger blocker census, independent of truncated report rows. | Fixture approval, repository inputs and remaining continuations remain prerequisites. No mutation approval or successful tool coverage was fabricated. |
| #1076 | Fixed reporting/classification defect; added precise capacity evidence. | The oversized request still requires a smaller prepared prompt/tool set or a verified larger model context. Rejection before inference remains correct. |

Issue closures use the user's explicit **fix or add the diagnostics needed for follow-up** workflow. A diagnostic-only closure is not a claim that its original runtime cause has been repaired. Reopen with a report from the matching updated app/gateway if the problem persists.

## Other retained log observations

Gateway metadata timeouts and transport failures require a fresh capture from the original PC route to distinguish host availability from network reachability. This workspace cannot exercise that private phone-to-PC route. Existing timeout evidence is retained; no timeout was converted into acceptance or permission to replay a command.

The log also contains frame stalls, a thread-suspension warning, and Android's WorkManager `onNetworkChanged` compatibility warning. Those observations alone do not establish a new app crash or a safe scheduler override. The confirmed oversized chat method was reduced; physical-device frame profiling and scheduler behavior remain unverified.

## Validation and deployment

- Canonical `bash scripts/validate-local.sh fast`: 495 tests passed, plus CI/dependency/schema/secret checks, regex and resource checks.
- Reporting/authentication service: 90 tests passed after the capacity-code contract repair, including bounded admission values, invalid/private-value rejection and complete provider-capture validation for every capacity code.
- Gateway preflight/validation suites: 26 tests passed, including malformed document and fixed policy-state behavior without dispatch.
- Gateway package validation: `python3 gateway/gateway.py validate-config` passed after regenerating `manifest-v14.json` from the exact reviewed head.
- Focused Kotlin 2.4.20 host harness: 10 tests passed; production diagnostics validator, gateway report builder, capacity code classification and readiness runner compiled and exercised with explicit Android/application/DTO boundaries. This is not a full Android build.
- Changed Kotlin files checked with ktlint 1.3.1.
- Full Android test attempt could not bootstrap Gradle 9.8.0 because the Java download connection was unavailable; no full Android test or device result is claimed. Draft must remain subject to hosted CI and device validation.

Deploy the matching reporting-service update advertising `ADMISSION_DIAGNOSTICS_V1`, and the gateway update for typed validation-policy state, alongside the new APK. Older services are not assumed to accept new approved fields. No service deployment is claimed.
