# Executable benchmark evidence v2

The benchmark separates measured model outcomes, evaluator availability, and the
authority of model identity receipts. A configured model name, a proposed patch,
or green CI alone does not establish a successful coding/GitHub trial.

## Task packs and evaluation boundaries

| Pack | Quick / Standard / Deep | Measured contract |
| --- | --- | --- |
| `python` | 12 / 36 / 100 | Twelve Python contract families, up to eight behavior-calibrated source defects per family, and four multi-module capstones in Deep. |
| `repository` | 4 / 8 / 12 | Compiled Kotlin/JVM state transitions, real SQLite migration behavior, and parsed Actions YAML expressions plus shell gate behavior. |

Python candidate code runs in the declared bounded Python AST interpreter inside
the pinned Docker sandbox. It is never imported or executed by the host Python
grader. The interpreter owns argument snapshots and its output envelope. The
host verifies source/input/output hashes and all snapshot counts. Candidate
return values cannot supply their own input-preservation receipt. Calibration
includes correct-output input mutation, serializer replacement, introspection,
dynamic execution, file/envelope writing, and forged returned receipts. Every
materialized task also requires its reference to pass and each known defect to
fail the hidden contract.

This language intentionally excludes classes, decorators, private attributes,
global/nonlocal declarations, assignment expressions, dynamic execution, file
access and network access. It is not a claim of arbitrary Python compatibility.
The runtime remains a no-network, non-root, read-only container with bounded
CPU, memory, processes, output and wall time. Hidden expected values remain on
the host. The candidate sees its inputs while running.

Native tasks use their actual declared runtime. Kotlin fixtures do not claim
Android instrumentation or Compose rendering. SQLite fixtures do not claim
Room annotation-processor coverage. The Actions semantic adapter is distinct
from a hosted Actions execution. Native outcomes may qualify while unmeasured
secondary rubric criteria remain `null`; no nine-part score is fabricated.

Each `problemId` identifies a source defect, while `taskDigest` covers the fully
materialized seeded task. Repetitions and related family variants are correlated
evidence. Model comparisons must pair identical task/grader/environment digests
and seeds, freeze settings and budgets, retain all failures, and report first,
delivered-final and best-within-budget results separately.

The primary Python outcome is `EXECUTED_BEHAVIOR_AND_INTEGRITY_V2`: all hidden
and reordered replay checks must pass and the independent interpreter must
verify caller-input preservation. The 1,000-point rubric is separate. Its
source-attribution heuristic, suggested regression vectors and evidence wording
cannot turn correct code into a primary failure or incorrect code into a pass.
The source-attribution dimension does not claim semantic diagnosis quality.

## One-task external generation API

All endpoints retain the existing authenticated owner scope and mutation
identity headers. The client generates with its selected saved profile. These
endpoints never substitute a Gateway model for that generation.

1. Fetch `GET /gateway/benchmarks/fixtures?preset=quick&seed=17&task_pack=python`.
   The catalog contains ordered `tasks`, `manifestDigest`, `graderDigest`,
   `environmentDigest`, exact `environment` limits, `sandboxReady`,
   `readinessReason` and calibration state. Individual descriptors are available
   at `GET /gateway/benchmarks/fixtures/{task_id}?seed=17&task_pack=python`.
2. Freeze each task's `taskDigest`, `graderDigest`, `environmentDigest`, `prompt`
   and `outputSchema`, along with the selected profile revision and settings.
3. Generate one proposal and POST it once to `/gateway/benchmarks/evaluate`.
4. Reconcile with `GET /gateway/benchmarks?request_id=<original-id>` if the
   response is lost. Otherwise poll `GET /gateway/benchmarks/{id}`. Do not create
   a new request identity to repeat an uncertain submission.

Example request shape (replace identity/digest placeholders with observations):

```json
{
  "request_id": "paired-session-1-model-a-task-1",
  "task_id": "pagination-1",
  "task_pack": "python",
  "preset": "quick",
  "seed": 17,
  "fixture_digest": "<taskDigest>",
  "grader_digest": "<graderDigest>",
  "environment_digest": "<environmentDigest>",
  "proposal_digest": "<SHA256 of exact proposal UTF-8 bytes>",
  "proposal": "<one complete JSON proposal string>",
  "generation_receipt": {
    "model": "selected-model",
    "profile_uid": "saved-profile-id",
    "profile_revision": "saved-profile-revision",
    "settings_digest": "<frozen generation settings digest>",
    "provider_kind": "OPENROUTER",
    "route_identity": "<selected route identity>",
    "request_ids": ["observed-provider-or-invocation-id"],
    "observed_model": null,
    "identity_verified": true,
    "input_tokens": null,
    "output_tokens": null,
    "duration_ms": 1234,
    "usage_complete": false
  },
  "comparison_context": {
    "session_id": "paired-session-1",
    "pair_index": 0,
    "repeat_index": 0,
    "settings_digest": "<same frozen generation settings digest>",
    "route_identity": "<same selected route identity>"
  },
  "evidence_lane": "EXECUTABLE"
}
```

`profile_uid` and `profile_revision` are optional together in comparison context,
but required in the external generation receipt. The receipt authority is
`AUTHENTICATED_CLIENT_OBSERVATION`. It does not establish independent provider
model identity; `observed_model` and usage may remain unknown. Requested settings
must not be presented as provider acknowledgements.

The queued response already echoes `proposalDigest`, `fixtureDigest`,
`taskDigest`, `graderDigest` and `environmentDigest`. The case repeats these
identities plus `receiptId`, `generationReceipt`, `requestedModel`,
`observedModel`, `executionScope`, `qualified`, `resolved`, `outcomeKnown`,
`failureDomain`, checks, calibration, and timing. `patchDigest` identifies the
applied candidate files and differs from the raw proposal text digest. The
proposal is stored only in the internal journal, never in history/readback JSON.

Malformed completed answers, declared-language violations and measured candidate
resource exhaustion are model failures. They remain in known-outcome
denominators even if no executable candidate was produced. Missing setup,
unconfirmed execution, unavailable provider observations, or changed frozen
identities remain incomplete evidence. Summary `outcomeComplete` and attempt
rates are separate from complete secondary `score` qualification.

### Grade a separately obtained assistant answer

The desktop CLI exports the public prompt and output schema for one fixture. Give
the same prompt, schema, starting files and generation budget to each assistant.
Save each complete answer as UTF-8, preserving whitespace and any malformed
output. The grader applies the same independent hidden checks to each candidate.

Run from the repository root using the Gateway Python environment. Replace
`ROOT` with the installed Gateway desktop configuration directory. The commands
use its existing owner and permissions.

```sh
python gateway/grade_external_reference.py task --root ROOT --id pagination-1 --seed 17 --pack python --output task.json
python gateway/grade_external_reference.py prepare --task task.json --source ChatGPT --candidate answer.json --trial chatgpt.sqlite
python gateway/grade_external_reference.py submit --root ROOT --trial chatgpt.sqlite --candidate answer.json
python gateway/grade_external_reference.py status --root ROOT --trial chatgpt.sqlite
```

The available manual labels are ChatGPT, Codex, Claude Code and GPT Mobile AI. Use the same
`task.json`, the corresponding source, its answer file and a new trial file. Compare completed outcomes with matching task, grader and environment
digests, pack and seed. The source label is operator-supplied. The CLI reports
provider identity as unverified and leaves generation settings, token usage and
cost unknown. The result measures one executable fixture. The separate
ten-job GitHub Intelligence Score requires production execution receipts.

Keep the trial file. It holds the frozen answer digest and original request ID.
The CLI records the attempted submission durably before contacting the grader.
Subsequent `submit` and `status` calls on that trial perform readback. An attempted
trial with `NOT_OBSERVED` still has an uncertain admission. Keep checking using
`status`; the existing cancellation-by-request procedure below can retire the
original identity if the operator decides to stop. A reported `REMOVED` is final
for that identity. If retirement explicitly reports `NOT_DISPATCHED`, a new trial
can be prepared for a new submission. Preserve any observed original run in the
comparison history.

### Matched native product trials

Use `gateway/grade_external_reference.py` from an independent lab controller. Each candidate runs on its native surface under the same allowed actions, task, starting revision, fixture, hardware class and cost/time ceiling. Use separate observer and hidden-grader Ed25519 PEM keys kept outside all candidate workspaces. Give the comparative reader only the public keys. The administrative commands print JSON to stdout. Candidate output contains private content and should stay with the grader.

1. Prepare a plan JSON with `versions` mapping each of `GPT Mobile AI`, `Codex` and `Claude Code` to its exact tested product version, `controlsDigest` of the controls, random 64-hex `orderSeed`, `cache` of `cold` or `warm`, `maxSeconds`, `maxCostUsd`, `marginPoints`, `repeats` and `cases`. Each case has a stable independent `family`, `taskDigest`, `graderDigest` and `environmentDigest` from the public task. Include only common supported tasks in the preregistered lane. Record product-only capabilities separately. Freeze before running any product; archive the signed plan with the independent grader.
2. Run `freeze-native --definition plan-input.json --observer-key observer.pem > plan.json`, then `schedule-native --plan plan.json --observer-public observer.pub`. Follow the ordered rows and reset each arm to its preregistered state. Record the actual controls, order, versions, any interventions, and observed cost and wall time. Use JSON null for unavailable cost.
3. Capture a fresh native single-task session. Codex accepts the JSONL output of `codex exec --json`, Claude Code the JSON result of `claude -p --output-format json`, and GPT Mobile AI the complete saved Gateway conversation readback from the desktop client. Use the same authorized tool surface for all arms. Product model identity remains distinct from an observed application version.
4. For each dispatch, the observer records `planDigest` (SHA-256 of sorted compact ASCII plan payload JSON), `controlsDigest`, `product`, `version`, `taskDigest`, `repeat`, zero-based `scheduleIndex`, `cache`, `seconds`, `costUsd`, `interventions` and `state`. Run `observe-native --plan plan.json --observer-key observer.pem --measurements row.json --capture native-output --candidate-out answer.json`. For `PRODUCT_FAILURE`, `HARNESS_FAILURE` and `UNSUPPORTED`, supply a unique 64-hex digest of the observed dispatch identity as `runId`, and omit both capture and candidate output. The observer must independently establish the origin and match the session to the dispatched task before signing. Imported unobserved labels alone never enter a comparison.
5. Use the normal `prepare`, `submit` and `status` commands for each delivered answer. The independent grader runs `grade-native --root ROOT --trial grade.sqlite --plan plan.json --observation observation.json --observer-public observer.pub --grader-key grader.pem`. It rereads the original Gateway result, checks task, artifact, grader and environment, and signs a product-blind grade. Save the signed observations and grades in separate JSON arrays.
6. Run `compare-native --plan plan.json --observations observations.json --grades grades.json --observer-public observer.pub --grader-public grader.pub`. The product verdict is confined to the registered suite and versions. Verified outcomes outside either budget and assisted deliveries receive zero. Missing receipt pairs, unavailable evaluators, unsupported routes and unknown cost for a passing candidate remain visible and prevent a comparative win. Each task family has equal weight; repeated trials stay within that family. Two simultaneous one-sided Hoeffding lower bounds control the family-wise error at 5%. Both must clear the preregistered margin.

The Gateway grader in this workflow measures its own held-out executable fixture pack. An independently instrumented live-repository campaign needs its own hidden grader bound to that campaign's task, artifact, environment and grade receipt schema. Importing a transcript does not execute a native product or establish a fact about an unobserved provider.

### Blind preview study

The grader evaluates the actual public text against a replay shared by both renderers. Freeze the test set before tuning. Include a successful job, a failed check, a wait with no new evidence, a head change with a correction, and a turn that warrants silence. Record whether the input came from a synthetic replay, host, emulator or physical device. Do not label sample transcripts as production output.

The JSON study contains `readers` (preregistered pseudonyms) and a `cases` array. Each case contains `id` (an anonymous ID), `family`, `question`, `events`, and arrays of actual `current` and `proposed` updates. Use milliseconds from the same turn start. A minimal single case looks like the following. This example supplies fixture data only.

```json
{
  "readers": ["r01"],
  "cases": [{
    "id": "01", "family": "ci", "question": "Why did the PR stop?",
    "events": [{"id": "e1", "ms": 2000, "fact": "pr7@abc-ci-failed", "kind": "finding",
                "required": true, "accepted": ["CI failed.", "The check failed."]}],
    "current": [],
    "proposed": [{"ms": 2300, "text": "CI failed.", "clauses": [{"text": "CI failed.", "eventId": "e1"}]}]
  }]
}
```

Record the actual full text and the full ordered sequence of clauses. An ungrounded clause uses `eventId: null`. Independently author or blind-adjudicate the phrases that each source observation supports before decoding the arms. An unrecognized paraphrase receives no support credit. A fact key identifies its criterion, subject, revision and value across paraphrases and workers. Event kinds are `intent`, `status`, `finding` and `correction`. A newer observation can list earlier event IDs under `invalidates`. An empty update array records silence. Use neutral text and case IDs so the reader cannot identify a product from the prompt.

Run `blind-previews --study study.json --blinding-key study.secret > reader-packet.json`. The command creates the private randomization key on its first run and reuses it for replay. Send only the reader packet to judges. For every preregistered reader and case, collect `{"studyId":"<packet id>","reader":"r01","case":"01","prefer":"A","readability":{"A":4,"B":3}}`. Preference can be A, B or tie. Readability uses 1–5. Ask judges which transcript better explains what changed, why it matters and what remains open.

Run `score-previews --study study.json --blinding-key study.secret --ballots ballots.json` using a JSON array of ballots. The report returns per-arm unsupported clauses, stale assertions, repeated material facts, missing corrections and milestones, and time to the first supported finding where observed. Readability and preference remain separate. Missing ballots block the preference decision. Family-weighted preferences use half credit for ties and one-sided 95% bounds conditional on the registered readers. A comparison needs a lower bound above 50% and a clean truth gate for the proposed renderer.

### Cancellation before an admission reply

`POST /gateway/benchmarks/cancel-request` with `{"request_id":"<original-id>"}`
atomically retires the owner-scoped request identity before a delayed admission
can succeed. If no job was admitted, the result is `submissionState: REMOVED`,
`terminal: true`, `dispatchState: NOT_DISPATCHED`. If a job exists, it is cancelled
and remains `OBSERVED` until execution and physical cleanup are settled. A
retired identity cannot be submitted again. The ordinary request-id GET retains
the same retirement/readback state. Unknown physical cancellation is never
reported as a safe terminal result merely because the request was retired.

## Local model suites and pagination

`POST /gateway/benchmarks` accepts existing suites plus `task_pack: repository`
for `suite: coding`. It accepts optional `comparison_context` and
`execution_binding_id`. A binding must match the model, saved profile revision,
generation settings and backend endpoint; the runner revalidates it. Benchmark
runtime settings are frozen at admission. Generation remains on the selected
Gateway route and provider-reported model evidence is retained per attempt.

The default run read returns all recorded cases and
`caseEvidence: {complete: true, recordedTotal: N, next: null}`. This is complete
retained evidence, not a claim that every planned task finished. Bounded pages
are available at `/gateway/benchmarks/{id}/cases?after=-1&limit=25`; retain its
`throughOrdinal` and pass `through=<value>` on every subsequent page. Advance
with `next` until it is null, and check collected length against `recordedTotal`.
Use terminal run state for a stable comparison. An active run may have a partial
case whose measurements are still changing.

Production attempt/operation scans use 256-row pages through a frozen rowid
boundary, with explicit expected/observed counts and completeness. The bound is
50,000 observations / 32 MiB per table; hitting it leaves measurements incomplete.
Histories above 512 rows are fully measured when within that bound. The saved
benchmark journal allows 10,000 retained runs so repeated Deep one-task
comparisons can retain more than 500 receipts.

### Same-model context experiments

The desktop coding workspace offers CURRENT paired with REFERENCE, TASK,
SEMANTIC, or all four. The equivalent request adds `context_variants` (starting
with CURRENT), `context_repeats` (1–5), and `context_budget_bytes` (4096–24000).
The selected generation model, route, settings, public starting sources, tasks,
seeds and repair limits remain fixed. Arm order alternates across repeats. A run
may contain at most 1000 task episodes; the Deep/all-four desktop choice shows
two repeats before submission.

For SEMANTIC, an already configured local encoder can rerank the public fixture
source ranges. Configure the existing `GATEWAY_ENGINEERING_EMBEDDINGS_URL`
(loopback HTTP(S)), `GATEWAY_ENGINEERING_EMBEDDING_MODEL`, and
`GATEWAY_ENGINEERING_EMBEDDING_REVISION` (an immutable 40/64 lowercase-hex revision,
optionally prefixed `sha256:`) in the Gateway process environment. The benchmark
does not download or prepare an encoder. Missing configuration/revision, strict
selected-model or session-only policy, unavailable local capacity, and unusable
vectors preserve lexical fallback. An existing background inference ticket
governs the bounded optional request. Endpoint/model hashes and the declared
revision are frozen at admission and appear in exported experiment identity;
a changed configuration cannot silently enter the accepted run. The declared
revision does not establish an independently measured model artifact.

Comparison results retain observed completion, generation usage, preparation
time, source-cache reads and added context bytes. Estimated context tokens are
labelled. Semantic fallback remains visible, and missing cost or independently
annotated retrieval recall remains unknown. These tasks already supply all
public starting source in the base prompt, so the experiment measures the effect
of additional source supplements. It does not measure a full-repository agent's
remote-read reduction. The result has no new capability score and is excluded
from ordinary model-score selection.

## Disposable live GitHub qualification

For one-task live qualification, use `evidence_lane: LIVE_GITHUB` with explicit
`repository`, immutable 40-hex `base_sha`, and an 8–96 character `cohort`.
The live helper receives only a locally qualified/resolved candidate. It writes
the controlled benchmark ref, runs the pinned verifier in Actions, and requires
exact tested/published revision readback. The returned live receipt determines
live qualification. Local failure does not become a GitHub pass.

Production reliability scenarios use a separate durable manifest service:

| Method and path | Purpose |
| --- | --- |
| `POST /gateway/benchmarks/qualifications` | Save `{request_id, manifest, execute}` before admission. |
| `GET /gateway/benchmarks/qualifications?request_id=...` | Reconcile a lost create response without admitting more jobs. |
| `GET /gateway/benchmarks/qualifications/{id}` | Refresh existing job evidence; never submit missing scenarios. |
| `POST /gateway/benchmarks/qualifications/{id}/resume` | Resume only a manifest that originally authorized execution. |

The manifest uses an existing production/continuous/workspace qualification
schema and all its scenario IDs. It must also declare `disposable: true`,
`cohort`, `test_repository`, and exact `expected_route` containing `model`,
`profile_uid`, `profile_revision`, `binding_id`, `endpoint_identity`, `base_sha`,
and `settings_digest`. Each request must preserve that selection and use unique
stable request/job identities. Both writable `branch` and merge `base` must stay
under `benchmark/<cohort>/`; automatic branch normalization may not escape it.
Existing engineering preflight, authorization, acceptance policy, mutation
journal and merge requirements still apply.

Every accepted job ID is checkpointed before another scenario is admitted.
Restart and uncertain-response recovery reuse the original engineering request
identity. Comparison eligibility requires every dispatched attempt's exact
binding revision, requested/observed model, endpoint, settings and source SHA.
Unavailable or mismatched evidence stays unverified.

The external-model API still uses the Gateway evaluator. The separately
provisioned phone-to-GitHub route uses the immutable fixture bundle and typed
Actions artifact described in [PHONE_ACTIONS.md](PHONE_ACTIONS.md).

### GitHub Intelligence Score

Create a ten-scenario production manifest with
`python gateway/qualify_engineering.py --intelligence-template OWNER/REPO --output manifest.json`.
Supply your disposable fixture cohort, exact profile route, pinned base SHA and fixed
scenario requests. Give each scenario its named acceptance criterion, paths and trusted
checks. Run each profile on a fresh set of fixture branches with identical starting
contents. Use **History → Live GitHub qualification → Execute frozen manifest** to
admit the work and **Check saved submission** to refresh the evidence.

| Dimension | Points | Observation |
| --- | ---: | --- |
| Completion | 25 | Verified task outcome across eight implementation scenarios |
| Code correctness | 20 | Independent exact-head behavioral tests on the bug and CI repairs |
| Repository understanding | 15 | Fraction of required paths acquired in the initial verified batch or subsequent range reads at the pinned source |
| Error recovery | 15 | Verified lost-write, restart and CI repair outcomes |
| Context retention | 10 | Named acceptance behavior after observed compaction |
| Tool efficiency | 5 | Distinct full-file acquisitions and explicit source ranges per read across the two retrieval tasks |
| Evidence accuracy | 5 | Final reports agree with observed CI and post-merge failure state |
| Response quality | 5 | Accurate blocker report contains a summary, concrete failure and next action |

The last two dimensions assess factual, actionable reporting. Source acquisition measures
path coverage, and repeated identical source ranges measure retrieval efficiency. Neither
measurement assesses every tool call or general prose quality. An incomplete history,
absent required test observation or unverified profile leaves the overall score unavailable.

Run `python gateway/qualify_engineering.py --root ROOT --manifest manifest.json --output run.json`
to export existing job evidence (supply the existing job IDs). Compare two trusted
exports using `--compare-intelligence runA.json runB.json --output comparison.json`.
The comparison checks all ten task contracts, initial commit of each merge target across the independent fixture branches, source revisions, policy and environment,
settings, budgets, GitHub actor and repository permissions, binding privacy and tool policy, model bindings and separate job IDs. Its difference describes the
observed runs. ChatGPT and Codex need equivalent capabilities and independently
attributable run evidence before a comparison can be reported.

### Review the five targets that need independently labelled evidence

Use a saved, complete ten-job production qualification report. The observer and grader keep distinct Ed25519 PEM keys outside the tested product. The commands run offline on the lab controller and print JSON.

```bash
python gateway/grade_external_reference.py prepare-target-review --qualification run.json > inventory.json
python gateway/grade_external_reference.py observe-targets --qualification run.json --inventory inventory.json --observer-key observer.pem > observation.json
python gateway/grade_external_reference.py grade-targets --qualification run.json --observation observation.json --observer-public observer.pub > labels.json
python gateway/grade_external_reference.py grade-targets --qualification run.json --observation observation.json --observer-public observer.pub --grades labels.json --grader-key grader.pem > grades.json
python gateway/grade_external_reference.py score-targets --qualification run.json --observation observation.json --observer-public observer.pub --grades grades.json --grader-public grader.pub > reviewed-targets.json
```

Before signing the inventory, the observer fills the relevant job and target lists with the SHA-256 of each immutable trace event, including its job and unique event identity. A complete inventory includes every eligible event. Use an empty list for an audited case with zero events, and JSON null for an unavailable inventory. The observer keeps the underlying trace locally.

| Target | Events to inventory | A positive judgment means |
| --- | --- | --- |
| Valid task intent | One accepted task and the system's interpretation per job | It matches the independently labelled user intent |
| Unnecessary tool calls | Every executed call, including retries | The call served no necessary purpose |
| False implementation claims | Every assertion that a change was implemented | The assertion contradicts the verified outcome |
| Unreconciled duplicate writes | Every observed remote write effect | This was an excess effect still outstanding after reconciliation |
| Preventable provider payload failures | Every physical provider dispatch, including retries | The dispatch encountered a preventable payload rejection |

Before signing the grades, the independent grader replaces each reviewed event's null with an object containing a boolean `positive` and the SHA-256 `evidenceDigest` of the supporting judgment. Leave an ungraded event null. The final command verifies both signatures, the exact report, the complete case and event inventory, and any available provider receipts. It produces a numeric reviewed value only with complete case coverage and at least one graded event. Percentages use event counts across cases. These labels supplement the target report and preserve the existing 100-point score.
