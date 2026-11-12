# Open report follow-up — November 12, 2026

Repository: `tailscale-signin/GPT_Mobile_AI-improved`. Target: PR #819,
`fix/voice-chat-crash-and-diagnostics-2026-11-11`. Starting head:
`0df839f0bc2f11810e598885bf146bae51bc3a86`; base `main`:
`e62bded83b3861816c2c6e97116b30c9eb0920f1`.

This follow-up repairs failures reproduced against the current implementation.
The implementation revision is `2abed28ab3682571e5f0914da73205342b1981c4`; the accompanying task checkpoint records
validation and publication scope. Concurrent update `3d773506` was integrated before
publication; its Gateway setup, Voice Mode and chat-following changes are retained.

The implementation tree `9005517f806771989ae0a8eca812efb0a4e01879` exactly matches the locally
validated tree. The subsequent publication update changes only this record and
the package source-commit identity; tested production and regression sources are
unchanged. The checkpoint preserves the local compilation revisions and source hashes.

## Gateway instruction recovery

An instruction was persisted before its POST, but an explicit retry sent another
POST when its command lookup returned no receipt. This could replay an instruction
whose original response was lost, including one already confirmed as accepted.

Saved instructions now reconcile through command reads. A missing receipt retains
the original command and its uncertainty. An accepted receipt remains usable if a
later lookup is missing, and reconciliation can continue after the original job
finishes. A fresh instruction requires the selected job to remain the conversation's
active job, preventing a stale Job A card from sending to a newer Job B.

New local reservations include the original job ID. This metadata does not change
the wire request or require a database migration. Accepted receipts are checked
against the command, schema, Gateway, account, conversation, execution kind and
original job. Legacy records keep their original payload and recover by command
identity; they never gain permission to repeat an uncertain POST.

Two acceptance races are also covered. A later rejected observation cannot replace
the accepted receipt, and a direct POST conflict cannot overwrite acceptance saved
by a concurrent observer. The latter decision and conditional update are transactional.

## GitHub live-read verification

A bounded GET retry could successfully recover from a lost response and still be
rejected as `GITHUB_LIVE_OBSERVATION_REQUIRED`. The verifier previously required an
HTTP response for every physical attempt, including the unanswered first attempt.

Each logical read now has a separate recovery scope and numbered attempts. An
unanswered earlier attempt is superseded only when the final successful retry
observes the same complete path and query. Actual response counts and HTTP failures
remain recorded; `recovered_read_requests` accounts separately for recovered attempts.
Native GitHub reads now supply their resource path to this accounting boundary.

The behavior tests include changed pagination, separate same-path reads, exhausted
retries, cancellation and uncertain mutations. In particular, an A503 response,
then an unanswered A request, then a successful B request cannot certify A. The
same sequence ending with a successful A request can recover while retaining its
observed 503 failure.

## Voice recovery

The Android recognizer previously cancelled its startup watchdog after readiness
without setting a capture deadline. A service that stopped issuing callbacks could
therefore retain the microphone indefinitely.

The Android path now requests final text after 30 seconds, matching the existing
local-recognition turn bound, and allows the existing eight-second finalization
window. Duplicate readiness/end callbacks cannot extend these deadlines. A stalled
service releases the microphone, preserves partial words and provides explicit
recovery without an automatic rebinding loop. Non-streamed playback failures now
retain the answer and report `VOICE_PLAYBACK_FAILED` consistently with streamed audio.

## Actionable public reports

The fixed public report allowlist omitted GitHub circuit/rate-limit/permission
failures, selected-model blockers and MCP configuration errors. Separate failures
were consequently reduced to `APP_ERROR` or `CHAT_FAILED`, and then could be
deduplicated as if they were the same incident.

The Android and service contracts now share 138 fixed application-owned codes.
Structured diagnostic events project only exact string values in the approved
error-code fields. Warning events retain their approved code, so a later report can
include the earlier classification. Event names, free-form messages, provider
payloads, connection identifiers and private-session data do not enter this projection.
Unknown values still use the generic fallback. Service tests verify that every
Android code and projected event is accepted without losing the failure identity.

## Concurrent Gateway benchmark repair

The concurrent desktop benchmark claimed tool-free measurements, but its HTTP
payload omitted the explicit option that disables configured Gateway-local tools.
The shared measurement request now sends an empty tools list and `tool_choice=none`,
keeping model fixtures and their timings independent of tool execution. A regression
checks the actual posted payload and retained model/budget values.

Malformed model catalogs previously raised an uncaught `TypeError` in the desktop
worker, leaving the benchmark controls busy. The catalog boundary now requires a
list and raises the existing recoverable validation error otherwise. Missing, null,
numeric, object and string catalog values are covered.

## Release scope and PR validation

The release validator identified 19 exact paths from the earlier model-preparation,
native-approval, selection and reporting work that were absent from the scoped
manifest. Each was checked against its implementation and recorded change. Four
new Gateway benchmark/dashboard paths from concurrent update `3d773506` are also
included explicitly. No broad directory allowance, qualification, required
workflow or existing limitation was changed.

The independent PR gate now fetches the complete Git history and runs the existing
release-scope validator against the checked-out commit. Scope omissions therefore
fail PR verification before a release attempt. The strict reliability-completion
check and required-check aggregation remain in place.

## Validation

The new regressions first failed against the original production behavior and pass
with the repairs. Focused host runs compile the relevant production/test sources
with cached supporting Android/app dependencies and, where required, generated
Room/Hilt implementations. They do not constitute a complete Gradle, APK, JNI,
installed speech-service or physical-device qualification.

| Check | Result |
| --- | --- |
| Integrated voice, entry and report incidents, 15 suites | 129 tests passed; 66 compiled source/test hashes verified |
| Report incidents and GitHub fallback | 15 tests passed, including Unicode URL bounds and private-data exclusion |
| Gateway conversation/instruction repository | 44 tests passed |
| GitHub read retry, dispatch accounting, native tool and workspace HTTP paths | 26 tests passed |
| Authentication and issue-reporting services | 36 tests passed |
| Gateway benchmark and desktop dashboard | 10 tests passed |
| Strict scoped release check | Passed; 23 existing/concurrent exact paths restored and PR validation now enforces the check |
| Kotlin formatting and whitespace | Passed for changed Kotlin files |
| Canonical fast checks | 462 tests passed; 1,551 Android regex patterns and 34 XML files validated |
| Complete hosted Android/security/packaging checks | Required separately for the published head |

The report-incident tests overlap with the integrated voice run; these counts must
not be added as a unique-test total. Repository JUnit regressions are committed in
their normal test locations for standard Gradle/CI execution.

## Issue disposition

| Issues | Available evidence and remaining requirement |
| --- | --- |
| #820, #821, #827, #828, #829 | Generic tool-error codes and event ordering. A cause-bearing reproduction is needed to attribute each incident and verify its resolution. |
| #822, #825, #826, #830 | Generic chat-error codes and event ordering. The originating terminal cause is not included. |
| #823, #824 | Generic voice-session failures without the original recognizer/playback cause. Physical Android speech-service reproduction remains necessary for those incidents. |
| #674 | Formal reliability acceptance remains incomplete: 48 tasks have no DONE entries and 99 of 110 cases remain NOT_RUN. Its physical/live-service and release requirements remain separate gates. |

The retained `7aa34f86` diagnostic capture and the newer reports name build
`58690d69dbc450002906727029f6968f1638cfe8`. That capture records a circuit-open
stop without a subsequent successful recovery. A shared build does not establish
that the capture and a particular public report describe the same failure.

No historical report is automatically closed by this follow-up. The repairs above
have reproduced code-level evidence; the report bodies do not yet establish
incident-specific resolution. No acceptance case is promoted using unrelated host
or general CI results.
