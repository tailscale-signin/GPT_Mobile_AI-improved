# GitHub Verified Engineering and memory

This change is stacked on `fix/github-stress-reliability-2026-10-31` at
`2cbad33dab33f50559ad5f28da70860a779ac42e` (parent PR #766). The initial
inspection used `1f1b779a342896f27b78513db1329498b4a50309`; the later batch
cancellation and progress-state fixes were incorporated before publication.
It extends the existing Android GitHub Chat and versioned v14 gateway modules;
the package remains compatible with the existing 14.3 protocol. It does not
claim to deliver an independently qualified Gateway 15.0 release.

## Publication policy

`GitHubVerifiedSession` wraps the normal GitHub Chat provider session, including
local, gateway and cloud profiles. Draft text stays private. A tools-disabled
formatting pass proposes individual claims; it cannot grant verification.
`GitHubEvidencePolicy` matches each proposed JSON pointer and exact typed value
against an actual native GitHub tool receipt. A tool name containing “GitHub”
does not establish trust. The rendered fact is the source field
and value, not model-supplied assertion text. Missing, failed, foreign, reused
without original provenance, future-dated, contradicted and stale observations
cannot render as verified. Mutable facts expire after two minutes and after an
observed branch change or successful write. Immutable reads require pinned SHAs.

Inferences require supporting observations and are explicitly labeled as
interpretations, never confirmed diagnoses. Unsupported numerical claims are
rejected. Body text, patches, logs, instructions and credential fields are not
eligible scalar facts; original source remains in the private receipt. This is
not a proof of arbitrary prose, causal diagnosis or semantic feature completeness.
An LLM can omit a proposed claim: the final rendering therefore never establishes
full task completion. Full requirements and unobserved tests remain unresolved.

Receipts contain original bounded tool output, arguments, call ID, source time,
head SHA, epoch, result hash and exact errors. Android retains them before replay
compaction in append-only, encrypted workspace records. The evidence sheet shows
the latest 80 records with expandable previews. Temporary chats keep only active
request evidence. Storage failure blocks publication. Context construction favors
recent receipts and declares omissions; the full ledger is retained. A context
limit never turns missing evidence into a successful result.

The gateway records its GitHub API responses with HTTP status, elapsed time and
timestamps in an append-only, hash-linked SQLite ledger. Raw payloads use
AES-256-GCM with authenticated record identities. Update/delete triggers reject
rewriting evidence. Authenticated, principal-scoped routes provide paginated
metadata and individual receipt inspection; Android exposes the latest receipt.
No request headers, bearer tokens or signed redirect URLs are recorded.

The optional `GATEWAY_ENGINEERING_REVIEW_MODEL` selects a separate reviewer model
on the same gateway endpoint. Its judgment supplements deterministic gates and
cannot approve merges or overrule API receipts. Android retains the existing
independent Llama review pass; arbitrary cross-provider reviewer selection is not
part of this change.

## Memory and context

GitHub profile settings offer session-only, persistent, or hybrid memory.
Hybrid is the default. Ordinary chat settings are unchanged. Memory records
include schema version, scope, source receipt, commit, timestamp, confidence and
invalidation instructions. Requirements preserve the user's exact text with a
planned status. User decisions retain their rationale without becoming execution
authority. Tool observations supply code knowledge, CI history, changes and failed
attempts. Existing code-context import graphs and test-path candidates are reused;
these graphs are heuristic, not compiler-verified call graphs.

Android storage is isolated by connection, credential/permission state, repository,
branch and profile. Its existing on-device text encoder and encrypted vector cache
rank bounded historical candidates. The cache is rebuildable and bounded to 4096
vectors. Native admission pressure or unavailable embeddings use a lexical
fallback. Cloud recall requires the separate explicit profile setting; local-only
provenance follows recalled content through the existing delegation/privacy gate.
Historical memory never enters the live evidence verifier as proof.

Gateway memory is scoped by authenticated principal, GitHub actor, credential
scope, repository, branch and profile. It preserves source excerpts, symbol/import
candidates, decisions, requirements, CI and repair history in encrypted SQLite
records. Long jobs retain their existing exact checkpoint/journal and duplicate
patch/failure retry guards. Retrieval ranks a bounded candidate set, reserves
context for current evidence and falls back to lexical retrieval. Optional real
embeddings require `GATEWAY_ENGINEERING_EMBEDDINGS_URL` on loopback and
`GATEWAY_ENGINEERING_EMBEDDING_MODEL`; cloud endpoints are rejected. Gateway
memory stays local even when Android permits its own cloud recall. Session-only
jobs retain their execution journal but do not store/retrieve reusable knowledge.

## Requirements and approval

The durable job request can carry up to 32 acceptance entries with exact `id`,
`paths` and successful CI `checks`. Android's start-job form offers one grouped
entry; API clients can supply multiple entries. The completion gate checks changed
paths, nonempty CI inventory and the exact PR head. It records planned/implemented/
blocked/verified evidence states for those explicit criteria. Freeform requirements
are retained, not silently marked verified by a reviewer model.

Merge approval still requires the existing explicit user action, current required
checks/reviews/branch policy and pinned head/base. Missing or skipped required checks
cannot satisfy completion. After merge, the actual merge result must match the
recorded merge SHA. Benchmark and unexecuted local-test fields stay `NOT_MEASURED`.
The gateway does not gain an arbitrary shell/test runner. CI is the existing test
execution mechanism; the UI describes the limited completion scope.

## Stress-test request audit

| Request | Existing implementation retained | Change here |
| --- | --- | --- |
| Run/jobs flow | CI facade, explicit workflow discovery | Preflight real run ID/head; actionable runs recovery; jobs failures retain run metadata and a run fallback |
| Pagination/state | `GitHubResultViews`, session store, durable cursors | Reused with existing cursor/recovery regressions |
| Draft PR/dispatch | PR health reports draft and real checks | Explicit no draft-skips-CI policy; dispatch rechecks expected head |
| Search/index | Persistent search IDs, index cursors, `max_new_blobs` budgets | Reused; semantic engineering memory is a separate historical layer |
| Context changes | SHA-pinned tree comparison and base tracking | Optional bounded compare patches; binary/omitted patches remain explicit |
| File writes | Staged patch/blob and expected-head fences | No duplicate write path; high-level branch creation requires expected head |
| Releases | Exact dispatch/run receipts, strategy/workflow discovery | Reused; no inferred run IDs or publication approvals |
| Watch checks | Durable PR/run watch state | Explicit `commit_sha` validation and run/PR IDs in watch receipts |
| Commit/branch | Git saga, source branch and existing branch reconciliation | Required facade expected head; existing dependency checks retained |
| Discovery | Progressive authorized tool catalog/fallback | Reused without expanding permission |
| Recovery | Structured recovery calls, execution journal, uncertain-write fences | New run/jobs recovery preserves failures instead of disguising them |
| Performance | Bounded parallel reads, ETags, credential-scoped caches | Reuse existing scheduling; bounded memory/context/vector caches |
| Rate limits | Core/search/GraphQL header accounting and reset backoff | Reused; no invented latency or rate-limit measurements |
| Workflow optimization | Pinned actions, dependency caches, concurrency and required verification | Remove lint branch restriction so stacked PRs receive lint |
| Tracing | Existing tool events and execution receipts | Structured facade timing/outcome/page trace; private source excluded from diagnostics |

Recovery calls are structured tool-plus-arguments objects, not opaque view cursors.
Use a returned cursor only with its matching request; never synthesize an ID.

## Backup and recovery

Android's encrypted complete backup preserves workspace metadata with Conversations
and protected receipt/memory chunks with Memory. Select both when making a selective
backup. Existing vault encryption metadata and chat-ID restore logic remain in use.
Derived embedding caches are rebuilt rather than exported as plaintext vectors.

For the gateway, keep the private `.engineering.key` beside its SQLite database.
A missing key is an error, never permission to generate a replacement for ciphertext.
The offline utility prompts for a password without placing it on the command line:

```bash
cd gateway
python3 -m v14.engineering_backup backup /absolute/path/jobs.db /absolute/path/engineering.enc
# Stop the gateway. Restore only into a new directory, then point its database setting there.
python3 -m v14.engineering_backup restore /absolute/path/engineering.enc /absolute/path/restored
```

The versioned archive encrypts the consistent SQLite snapshot and its encryption
key using Scrypt-derived AES-GCM. Restore validates the password, metadata and
database before exposing a private DB/key pair. It refuses existing destinations.
Memory and evidence limits are explicit; exceeding them reports unavailable state
and never silently relabels work as completed.

## Verification

`gateway/contracts/github-evidence-v1.json` is shared by Kotlin and Python tests.
It covers exact values/types, wrong SHAs, stale epochs/timestamps, failed or foreign
sources, provenance-free cache replay, prompt text and unmeasured benchmarks.
Additional behavior tests exercise private draft withholding, permission revocation,
storage failure, run/jobs recovery, pinned patches, missing CI/acceptance evidence,
encrypted restart, scope separation and password backup/restore. Host JVM harnesses
use Android boundary fixtures; they do not qualify Compose, Room, Keystore, native
embeddings or a physical Android device. Run canonical Android CI before merging.

Observed validation on this branch:

- `bash scripts/validate-local.sh fast`: passed, including 276 host policy/script tests.
- Android host JVM regression harness: 336 tests passed against actual runner, GitHub client/session/facade and verifier code; Android boundary adapters were fixtures.
- `python3 gateway/check_gateway.py`: 262 Python tests and seven browser bootstrap tests passed.
- Authentication/broker suite: 15 tests passed during the full validation attempt.
- ktlint 1.3.1: changed Kotlin sources formatted and checked.
- Gateway Android client/repository/ViewModel and engineering-memory wiring compiled with host boundary fixtures.
- `bash scripts/validate-local.sh full`: stopped at the Gradle 9.8 distribution download (`Network is unreachable`). Android assemble/lint/unit tests and physical native inference were not executed by that attempt.
