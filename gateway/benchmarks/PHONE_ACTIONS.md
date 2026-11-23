# Phone generation → disposable GitHub Actions

This route grades a proposal from the phone's explicitly selected saved model
profile without a running Gateway. A separately provisioned, immutable fixture
base supplies the prompts, patch validator, hidden oracle, sandbox driver and CI
workflow. The phone writes only a bounded proposal JSON file and one exact
workflow rendered from that base. Actual candidate code is applied and executed
by the trusted CI grader.

The selected GitHub connection UID, disposable repository, base SHA, task,
grader, environment, model profile revision, generation settings, and attempt
identity belong to the frozen plan. Account or endpoint fallback is not part of
this route. Generation receipts remain authenticated client observations;
independent provider model identity is not inferred.

## Provision a fixture base

Run from an exact committed source checkout with its existing Gateway Python
dependencies installed:

```bash
PYTHONPATH=gateway python -m benchmarks.phone_provision \
  --source-revision <exact-40-hex-source-commit> \
  --python-image python@sha256:<approved-64-hex-image-digest> \
  --seeds 17,18,19 \
  --output /path/to/disposable-repository/.benchmark-phone
```

The provisioner compares the source files it is packaging with an actual archive
of the selected Git commit. Dirty or different source is rejected. It writes a
new local directory, refuses to overwrite an existing fixture bundle, and makes
no GitHub calls. Review and commit the generated directory in a disposable
repository. Select the resulting immutable **fixture base commit**, which is
distinct from the grader's source commit, in the phone's benchmark plan.

The Python workflow runs on the declared hosted Ubuntu runner and explicitly
prepares only the operator-pinned Python image. Native tasks are opt-in:

```bash
PYTHONPATH=gateway python -m benchmarks.phone_provision \
  --source-revision <exact-40-hex-source-commit> \
  --python-image python@sha256:<approved-64-hex-image-digest> \
  --repository-image native-runtime@sha256:<approved-64-hex-image-digest> \
  --native-runner-label gateway-native-benchmark \
  --seeds 17 \
  --output /path/to/disposable-repository/.benchmark-phone
```

The native workflow requires a matching self-hosted Linux runner with the
immutable image already installed. That image must contain the Kotlin/JVM,
SQLite and PyYAML tools required by the native pack. The native runner does not
pull an image automatically. Missing tools, image, dependency installation, or
reference/negative calibration produce setup/infrastructure evidence, never a
model failure disguised as a completed test.

Each grading job creates its own Python environment from the existing
hash-pinned Gateway requirements. It uses the trusted runtime source from the
fixture base. Candidate containers have no network, root privilege, host
credentials or writable fixture mounts. Hidden expected values remain outside
native candidate containers; the Python contract interpreter independently owns
its input snapshots and grading envelope.

## Published bundle

The bundle starts at `.benchmark-phone/catalog.json`, schema
`gateway.phone-benchmark-catalog.v1`. Each `packs.python` or `packs.repository`
entry contains:

| Field | Meaning |
| --- | --- |
| `presets` | Ordered task IDs for Quick/Standard/Deep: Python 12/36/100, native 4/8/12. |
| `seeds` | The exact provisioned seeds; an unavailable seed is a planning blocker. |
| `tasksBySeed` | Maps seed and task ID to `fixtureDigest` and `descriptorDigest`. |
| `fixturePathTemplate` | `.benchmark-phone/fixtures/<pack>/{seed}/{task_id}.json`. |
| `graderDigest` | Material grader/source identity. |
| `environmentDigest`, `environment` | Immutable image and actual execution limits. |
| `workflowTemplatePath`, `workflowTemplateDigest` | Exact trusted workflow bytes. |
| `settings` | Host-owned runtime settings; the proposal cannot select an image or command. |

Public descriptors use the same task ID, prompt, output schema and digest fields
as the Gateway one-task API. They omit hidden expected values and reference
sources. The pinned runtime contains the original trusted oracle source; these
fixtures are versioned executable contracts, not a claim of secret public
benchmark data or resistance to prior benchmark contamination.

`descriptorDigest` and `workflowTemplateDigest` are SHA-256 of the exact file
bytes. JSON files use compact sorted-key JSON, ASCII escapes and no trailing
newline. `bundleManifest` maps every bundle path except `catalog.json` to its
raw-byte SHA-256. `bundleDigest` is SHA-256 of the canonical JSON representation
of that mapping. The CI grader verifies every manifested file before grading.
Task and grader digests are opaque frozen identities, not hashes to reconstruct
from the public prompt alone.

## Attempt publication contract

An attempt ID has the form `phone_` followed by 32 lowercase hexadecimal digits.
A cohort contains 8–96 ASCII letters, digits, underscores or hyphens. A new
attempt must have exactly one parent: the selected immutable fixture base.

The only two added paths are:

```text
.benchmark-phone/submissions/<attempt-id>.json
.github/workflows/phone-benchmark-<attempt-id>.yml
```

The branch is `benchmark/phone/<cohort>/<attempt-id>`. The workflow is rendered
from the pinned template by substituting only
`__BENCHMARK_SUBMISSION_ID__`, `__BENCHMARK_BASE_SHA__`, and
`__BENCHMARK_BRANCH__`. Those values have closed syntax; model text never enters
the workflow or shell command.

The submission schema is `gateway.phone-benchmark-submission.v1`:

```json
{
  "schema": "gateway.phone-benchmark-submission.v1",
  "request_id": "phone_0123456789abcdef0123456789abcdef",
  "task_id": "pagination-1",
  "task_pack": "python",
  "seed": 17,
  "fixture_digest": "<taskDigest>",
  "grader_digest": "<graderDigest>",
  "environment_digest": "<environmentDigest>",
  "bundle_digest": "<bundleDigest>",
  "base_sha": "<immutable-fixture-base-sha>",
  "repository": "owner/disposable-fixture",
  "branch": "benchmark/phone/cohort0001/phone_0123456789abcdef0123456789abcdef",
  "workflow_path": ".github/workflows/phone-benchmark-phone_0123456789abcdef0123456789abcdef.yml",
  "proposal_digest": "<SHA256 of exact proposal UTF-8 string>",
  "proposal": "<one complete proposal JSON string>",
  "generation_receipt": {
    "model": "selected-model",
    "profile_uid": "saved-profile-id",
    "profile_revision": "saved-profile-revision",
    "settings_digest": "<selected-settings-digest>",
    "provider_kind": "OPENROUTER",
    "route_identity": "<selected-route-identity>",
    "request_ids": ["observed-request-or-invocation-id"],
    "observed_model": null,
    "identity_verified": true,
    "input_tokens": null,
    "output_tokens": null,
    "duration_ms": 1234,
    "usage_complete": false
  }
}
```

Optional `comparison_context` uses the same frozen session/pair/repetition
identity as the external Gateway API. Repository identity is compared without
case sensitivity; SHA, branch, file paths, task identity and all digests remain
exact. The client must reconcile the original attempt after a lost publication
response instead of creating a new attempt or replaying the write.

## Typed result and independent readback

The workflow runs one job named **Independent phone benchmark**, with required
steps **Grade frozen proposal** and **Upload benchmark result**. It triggers on
push to the exact owned branch. Before evaluating, the host checks both checkout
SHAs, their clean tracked contents, the sole base parent, exactly two added
regular files, exact workflow bytes, bundle hashes, task/environment identity,
proposal digest and selected generation receipt.

The artifact is named `phone-benchmark-<attempt-id>` and contains only
`result.json`, schema `gateway.phone-benchmark-result.v1`. It includes:

- Request/task/pack/seed and repository/branch/base identities.
- `testedRevision`, `publishedRevision`, `workflowPath`, `workflowRunId` and
  `workflowRunAttempt` from the verified CI context.
- Proposal, patch, fixture, grader, environment and bundle digests.
- Typed `status`, `graded`, `executed`, `qualified`, `resolved`, `outcomeKnown`
  and `failureDomain`, plus the actual case, calibration and generation receipt.
- `receiptDigest`, the canonical JSON SHA-256 of the result before that field is
  added. The artifact contains no raw proposal or candidate source.

The grading process exits successfully when it has produced a typed result,
including a model failure. Therefore a green grading job is not a model pass.
Conversely, a red workflow, missing artifact or truncated readback is not enough
to classify the model as failed. Read the matching artifact.

The phone must independently match the observed push run, exact head SHA,
branch, workflow path, job identity, run attempt, required steps, artifact
identity and all frozen result fields. It must also verify the committed
proposal/workflow bytes and unchanged owned branch head. Only after that
readback may it mark `remoteReadbackVerified`. An artifact's own assertion is
not a substitute for those observations.

Completed malformed JSON and measured candidate contract failures are known
model failures and count in the comparison denominator. Unavailable setup,
unknown dispatch, grader/reference failure and mismatched immutable identities
remain incomplete. Native outcomes can qualify without a fabricated nine-part
rubric score. First/final/best results and usage retain the same accounting
requirements as other executable benchmark routes.
