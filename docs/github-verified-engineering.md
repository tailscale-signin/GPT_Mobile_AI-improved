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
cannot render as verified. Cached observations retain their original source time. Mutable facts expire after two minutes and after an
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
encrypted restart, scope separation, native-adapter/commit invalidation and password
backup/restore. SQLite backup handles are closed explicitly for Windows; POSIX file
mode assertions run only on platforms that implement those modes. Host JVM harnesses
use Android boundary fixtures; they do not qualify Compose, Room, Keystore, native
embeddings or a physical Android device. Run canonical Android CI before merging.

Observed validation on this branch:

- `bash scripts/validate-local.sh fast`: passed, including 276 host policy/script tests.
- Android host JVM regression harness: 346 tests passed against actual runner, GitHub client/session/facade and verifier code; Android boundary adapters were fixtures.
- `python3 gateway/check_gateway.py`: 264 Python tests and seven browser bootstrap tests passed.
- Context, connection/probe and diagnostic host regressions: 53 tests passed; one unchanged Free-provider integration case remains for Android CI.
- Authentication/broker suite: 15 tests passed during the full validation attempt.
- ktlint 1.3.1: changed Kotlin sources formatted and checked.
- Gateway Android client/repository/ViewModel and engineering-memory wiring compiled with host boundary fixtures.
- `bash scripts/validate-local.sh full`: stopped at the Gradle 9.8 distribution download (`Network is unreachable`). Android assemble/lint/unit tests and physical native inference were not executed by that attempt.


## Diagnostic investigation follow-up

The supplied investigation describes the exported build at
`622a8848fcc9e3e55502320953ffc1724c4f260a`. Its counts and device timings are
historical observations from that report, not new measurements of this branch.
The current source was checked against each proposed remedy.

| Finding | Implementation and verification boundary |
| --- | --- |
| Unsent actions marked dispatched | Per-invocation `github.dispatch.v1` receipts distinguish preflight rejection, observations, accepted/rejected mutations, partial acceptance and unknown outcomes. Read preflight does not become a sent mutation. Definite rejection and lost acknowledgement have separate regressions. |
| Reconciliation deadlocks | Persist dispatch receipts in batch and ToolEvent checkpoints. Proven preflight/HTTP rejection may resume; partial acceptance and uncertain writes remain fenced. Unknown-tool and recovery guards report that no new action was sent. |
| Local context overflow | Preserve the upstream advertised set through the native adapter. Required tools come from the current task and its bounded follow-up, excluding generic system policy. Budget rendered bridge schemas and preserve at least 512 reply tokens for contexts of 4096 or larger, subject to an explicitly smaller requested output. Capacity errors retain input and recommend fewer tools or a compatible larger-context artifact. |
| Tool catalog expands | Authorized definitions remain discoverable, while only admitted/advertised definitions enter the initial local prompt. Discovery can add an authorized schema for a later step. The 14-authorized/one-advertised case is covered. |
| Unparseable tool markup | Reuse complete-stream argument assembly, schema validation, malformed-frame rejection and the existing single protocol repair. The compatibility error now names build/template/function-calling checks and a read-only smoke test. No arbitrary control text is executed. |
| Invalid workflow triggers/inputs | Read the selected workflow path under the actual credential. Verify default-branch manual registration and parse the definition at the expected commit. Validate declared input names, required values, scalar types and choices. Top-level ref remains separate. Unsupported YAML fails closed with an explicit inspection blocker; this is not a general YAML evaluator. |
| Repeated 404/invalid workflow calls | Persist normalized request fingerprints scoped to the credential/connection/conversation. An unchanged blocked request, including filename aliases and new call IDs, performs no HTTP retry. Corrected arguments/head/credential or newly observed workflow inventory can permit fresh preflight. A 404 remains ambiguous; push permission is not assumed to grant Actions access. |
| Primary low/turbo defaults | GitHub engineering primary/review requests select quality/high requested controls and their admitted output allowance. Lightweight workers retain their own settings. The explicit github-primary/review role receives the normal request deadline. The legacy worker flag retains client-owned isolation and does not select low effort. These are requested controls; absent serving receipts keep effective reasoning/settings unverified. |
| Saved view expiration | Reuse durable scoped result storage, original evidence receipts and exact structured continuation objects. Recovery calls are not cursor strings. Existing restart/scope/pagination regressions remain applicable. |
| Probe EOFs | Normalize local connection recovery by origin across props, apply-template and chat completions. Synthetic probes retry an EOF once, apply the fresh-connection hint, and retain the existing bounded positive/negative capability cache. TLS/cancellation failures are not replayed. Tests cover both probes, recovery and failed-probe cooldown. |
| MCP instability | Retain current per-endpoint connectivity leases, network-health failure recording, bounded backoff and supported session/replay handling. Protocol negotiation fallback is distinct from transport failure. Durable job state stays outside SSE. |
| UI stalls | Reuse background Markdown preparation and 33 ms streaming publication coalescing. New receipt pretty-printing runs on Default. A physical-device trace is still required to attribute CPU/GPU/blur cost or claim improvement. |
| Misleading diagnostics | Source preview truncation and explicit truncation markers feed export metadata. Preserve structured error code, dispatch phase, HTTP status and reconciliation state independently of previews. GitHub and Tool records share a causal incident ID. |
| Selected branch cleanup | Keep the selected-branch deletion guard; its regression now checks the unsent receipt. Select a surviving branch and refresh its head before separately authorized cleanup. |
| Model import permissions | Retain persisted document-provider access, managed-copy fallback and controlled file/stream lifetimes. No blanket storage permission is added. Physical provider restart/load validation remains necessary. |
| ART/teardown/optional warnings | Retain existing incident classification, generation/termination receipts and explicit unknowns. No OOM, ANR, failed feature, or blur root cause is inferred from the reported warnings or teardown timing alone. |

The dispatch receipt is retained before replay compaction and is included in
expandable evidence. A partially accepted or uncertain mutation invalidates older
mutable observations even when the outer tool reports an error. No successful
write is inferred from the phase name alone; exact API/journal receipts remain
authoritative.
