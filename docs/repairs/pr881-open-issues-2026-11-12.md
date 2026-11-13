# PR 881: open repository issue repairs

## Scope and provenance

Repository: `tailscale-signin/GPT_Mobile_AI-improved`.

Target: PR [#881](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/881), branch
`verify/session-reliability-77c55cc-2026-11-12`.

The investigation started from `641a5750544a45935ce7fdeb0c1037fdc50955f9`, with
`main` at `97f10ff7a1f3789fb82d958b2dc6b22c8074dfdf`. Seven open issues were
returned by the repository-wide open-issue query: #884 through #890. The
reports identify app version 0.9.66.7, build 141, source
`342b48d9e9559fb89e06dbd0b2a3f835eae802ec`. Their observations are historical;
the source fixes and validation below apply to the candidate named by this
document's enclosing commit.

## Findings and implemented repairs

| Issues | Reported behavior | Source-confirmed defect and repair |
| --- | --- | --- |
| [#884](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/884) | Automatic exact-model preparation failed and the manual report contained no model-preparation capture. | Preserve structured preparation failures in Android diagnostics. Handle a sleeping exact model through its supported wake path rather than submitting a second load request that a router rejects. The original report lacks backend state, so this does not prove which condition affected that private gateway. |
| [#885](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/885), [#886](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/886) | Review comparison stopped before model dispatch and appeared as generic `CHAT_FAILED`. | Resolve an explicitly supplied review baseline to an immutable commit and retain input-required failure semantics. A default-branch review still needs a real comparison baseline; comparing a branch to itself cannot establish a source review. |
| [#887](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/887), [#888](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/888), [#889](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/889), [#890](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues/890) | Acceptance remained partial after 52 calls, with workflow/job and fixture prerequisites unmet. | Inspect other observed runs at the selected revision when the first run has no usable jobs. Exercise the supported failure-query read at the exact revision even if there is no failed run. Retain the distinction between executed queries, unread pages, missing job evidence and fixture approval requirements. |

The seven reports group into three repair areas. Repeated reports are not counted
as separate proven root causes. Remaining call budget does not itself authorize
mutations or prove that required workflow jobs and owned fixtures exist.

### Exact selected-model preparation and diagnostic capture

A sleeping llama.cpp model is already running. Submitting `/models/load` for that
model can therefore be rejected. Preparation now wakes the exact sleeping model
with one empty `/tokenize?autoload=false` request, while an unloaded model retains
the separate load path. The wake request sends no user prompt and performs no
generation. Foreign model identity and ambiguous multi-model properties cannot
authorize a wake.

Fresh properties take precedence over a stale catalogue that says `LOADED`.
Accepted or lost wake responses keep their durable operation fence while the
result remains uncertain, including across restart and a new operation ID.
Android also retains its saved operation until an exact metadata-qualified
receipt or independently fresh readiness resolves it. A stale `LOADED` field
inside `LOAD_OUTCOME_UNKNOWN` cannot clear that operation and authorize replay.

The Android API decoder now retains typed, allowlisted preparation failures and
bounded, sanitized GATEWAY captures. This supplies evidence that issue #884 did
not contain; it does not establish that sleeping state caused that historical
private-host failure.

The app checks hosted reporter capabilities before submitting the new schema:
`GATEWAY_MODEL_PREPARATION_V1` for preparation fields and `REVIEW_COMPARISON_V1`
for the new review codes, including codes carried in approved diagnostic events.
An older reporter leaves the approved payload saved with
`REPORT_SERVICE_UPDATE_REQUIRED`, without a POST or an automatic retry. The
matching server capability advertisements and validation are included. Native
GitHub reporting retains its separately selected route and approval.

### Review intent, immutable baselines and truthful failures

An explicit branch, tag or abbreviated commit baseline is resolved through the
existing GitHub context capability and then pinned to its full immutable SHA.
Reviewing tools or open issues on a branch no longer incorrectly demands a
source diff. Explicit branch, code, change and pull-request review requests still
require the appropriate comparison evidence. Foreign-repository, excluded,
untrusted and unrelated source-read receipts cannot provide that evidence.

`REVIEW_INPUT_REQUIRED` and `GITHUB_REVIEW_COMPARISON_REQUIRED` now survive the
failure-record and reporting path. A network, authentication, rate-limit or
ambiguous not-found failure during baseline lookup retains its actual cause and
bounded recovery facts. Only an explicit baseline validation failure asks for a
replacement baseline. The failure receipt also distinguishes an attempted
prerequisite read from a review blocked before model dispatch. These changes
grant no new replay or mutation authority.

### Acceptance prerequisite discovery and reporting

The read planner can inspect another already-observed run at the same repository
and selected commit when one run has empty, skipped or otherwise unusable jobs.
It respects the existing call budget and reserve, stops when the required
capability is established and does not retry an authorization failure.

The supported exact-revision failure query is exercised even if no failed run is
known. A `no_failed_run` result proves that the query ran; it does not prove that
failure logs, a repair, or fixture cleanup occurred. Reports distinguish remote
pagination from retained local fragments, workflow resources from fixture
prerequisites, and unused budget from permission. Real missing fixture policy,
ownership or lifecycle evidence continues to leave the corresponding acceptance
criterion incomplete.

## Validation

The focused Kotlin harnesses compiled current production sources with explicit
platform boundaries for Android, DI, logging, persistence and unrelated
pagination infrastructure. They are behavior checks, not a full Android build.

| Check | Result |
| --- | --- |
| Review baseline and failure-cause behavior | 16 tests passed. |
| Profile failure classification | 34 tests passed. |
| Acceptance planner, workflow jobs and evidence reporting | 59 tests passed; an original-source negative control reproduced the three repaired planner failures. |
| Model preparation API, operation persistence, queue and diagnostic capture | 46 tests passed; original sleeping-model defects were reproduced by negative controls. |
| Approved report schema, hosted capability negotiation and native GitHub submission | 35 tests passed; three exact Kotlin-approved payloads also passed unchanged through the Python report validator and renderer. |
| Canonical `bash scripts/validate-local.sh fast` | Passed, including all 495 script tests, diagnostic schema, dependency catalog, secret scan, Android regex and resource checks. |
| Authentication service suite | All 68 tests passed with the pinned gateway dependencies. |
| Complete gateway host suite | Passed: 70 Python module invocations, 925 reported test executions with three expected skips, plus all seven JavaScript tests. The skipped checks require Windows Credential Manager, Windows file-sharing semantics or a bindable LAN IPv4 interface. Focused preparation suites passed 21 direct and 35 HTTP tests. |
| Pinned ktlint 1.3.1 | Passed for all 25 changed Kotlin files. |

The first broad gateway run exposed a host dependency setup problem: its bounded
engineering subprocess deliberately removes `PYTHONPATH`, so dependencies in an
arbitrary target directory are unavailable there. The final run uses a real
isolated virtual environment with the unchanged hash-locked requirements. No
dependency lock or production subprocess isolation was weakened.

Architecture and application audit inventories were regenerated, and package
hashes and the release-scope contract are checked before publication. Gateway
manifest provenance is bound to the published source commit, whose tree is
verified against the tested local source tree.

The complete repaired source is commit
[`4fc665accca064a3f35703a0837d13ab3e42df86`](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/4fc665accca064a3f35703a0837d13ab3e42df86).
Each of the five published source commits has the same Git tree as its reviewed
local counterpart. The following metadata commit contains this register,
generated inventories, release-scope documentation and the gateway manifest.

Full Android compilation, instrumentation, packaged Windows behavior and private
gateway execution require their own evidence and are not implied by these host
tests. No real device, private llama.cpp process, credential or fixture was
changed. Hosted CI must qualify the new PR head; successful checks from the
starting revision do not qualify this candidate.

## Issue handling

Each issue receives a specific resolution identifying the code repair and its
validation. Where historical private-host evidence is absent, closure under the
requested diagnostic-follow-up policy identifies the new capture and the
remaining external qualification. It does not claim that a host configuration,
credential, model file or original failure was repaired remotely.
