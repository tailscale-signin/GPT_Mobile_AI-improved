# GitHub Engineering Agent 2.0 — implementation contract

Status: Android controller, Room ledger, approval, Git saga and CI watcher implemented; end-to-end and device acceptance remains open. See `docs/engineering/2026-10-26-integration-and-runtime-acceptance.md`.
Target: Android Kotlin/Room, existing GitHubOperationTool and native GitHub backend.
Branch: `fix/local-tools-answers-gguf-gpu-2026-10-26`.

## Non-negotiable invariants

1. Model text never changes execution state. Only the controller, after recording evidence, may transition a task.
2. Every mutation is scoped to repository, working branch, expected head commit and expected file blob SHA.
3. A staged patch is not a committed change. A commit object is not a pushed branch. HTTP 200 is not CI success.
4. All mutation attempts are auditable; credentials, secrets and raw untrusted tool payloads must never be persisted in the ledger.
5. A task cannot be COMPLETED without its policy-specific verification receipts. Native device tests require a separate HARDWARE_VERIFIED receipt when configured.
6. Never assume a Git Data API commit operation is atomic across requests; reconcile remote state before retrying.
7. The caller must authorize writes according to repository policy; branch protection and review requirements are never bypassed.

## Task state machine

CREATED -> INSPECTING -> PATCH_GENERATED -> VALIDATING_LOCAL -> PATCH_READY
PATCH_READY -> AWAITING_APPROVAL -> COMMITTING (exact user approval required)
COMMITTING -> PUSHED -> CI_PENDING -> CI_VERIFIED
CI_VERIFIED -> AWAITING_HARDWARE_VERIFICATION -> HARDWARE_VERIFIED (when required)
CI_VERIFIED or HARDWARE_VERIFIED -> COMPLETED (when policy requirements satisfied)

BLOCKED, CONFLICTED, FAILED, CANCELLED and CI_FAILED are explicit outcomes.
Retry creates a new attempt with immutable prior events, rather than erasing failure evidence.
Approval tokens are scoped to task ID, patch digest, expected head, actor, and expiry.

## Room persistence model

- EngineeringTaskEntity: taskId PK; owner; repo; baseBranch; workingBranch; baseSha; currentState; validationPolicy; approvalPolicy; attemptCount; timestamps.
- EngineeringEventEntity: eventId PK; taskId FK/index; monotonic per-task sequence unique; eventType; evidenceDigest; safe metadata; timestamp. Append-only.
- GitOperationEntity: operationId PK; taskId FK/index; idempotencyToken unique; expectedHeadSha; baseTreeSha; blobManifestDigest; stagedTreeSha; createdCommitSha; remoteRefUpdatedSha; sagaStage; failureCode; timestamps.
- VerificationReceiptEntity: receiptId PK; taskId FK/index; commitSha; checkSuiteId; checkRunId; workflowRunId; checkName; status; conclusion; verificationType; environment; artifactDigest; signatureMetadata; verifiedAt.
- ApprovalReceiptEntity: approvalId PK; taskId FK; patchDigest; expectedHeadSha; actor; scope; expiresAt; decision.

Use Room transactions for ledger event + current-state projection. No cascading deletion of audit evidence in production retention policy.

## Git Data API saga

PENDING -> BLOBS_STAGED -> TREE_CREATED -> COMMIT_CREATED -> REF_UPDATED.

Persist the operation intent before the first network mutation. Record each confirmed remote object SHA. On recovery, fetch the target branch ref, commit and tree; if the expected commit is already the branch head, mark REF_UPDATED without writing again. If the head advanced, mark CONFLICTED and require regeneration or explicit safe reconciliation. Never force-update a branch. The commit trailer carries an operation ID for tracing, but is not itself a server-enforced idempotency key.

Note: GitHub's update-ref endpoint is not generally a transactional compare-and-swap primitive. The implementation must use the available expected-head lease mechanism where provided, recheck after update, and surface residual concurrency risk. For strict concurrency guarantees use a trusted server-side coordinator.

## Validation and approval

- Inspect exact source blobs and pin expected SHA before generating edits.
- Apply patch in a sandbox; run parser/syntax validation where available. Parsing is not equivalent to full compilation.
- Scan added content for secrets; redact detected values from all evidence.
- Evaluate changed file count, line count, protected paths and permissions.
- Every patch transitions to AWAITING_APPROVAL; reject expired, revoked or mismatched approval tokens. Blast-radius thresholds may block a patch but cannot waive approval.
- Treat build failures as CI_FAILED; classify and retain bounded sanitized log excerpts.
- Check conclusions and required-check policy against the exact current head SHA, not HTTP transport status.
- Verify commit signatures separately from commit SHA identity.

## Android execution and security

- Use WorkManager unique work for resumable verification, with persisted cursor and bounded polling. WorkManager is durable scheduling, not a guarantee of uninterrupted or immediate execution.
- Use foreground execution only when Android version, foreground service type, and user-visible notification policy permit it. Consider push/webhook relay for reliable long-duration monitoring.
- Store refresh credentials using Android Keystore-backed encryption. Avoid new dependencies on deprecated EncryptedSharedPreferences; prefer a maintained Keystore-backed solution. Never ship GitHub App private keys in the APK: mint installation tokens server-side.
- Installation tokens are short-lived, scoped, redacted and refreshed through the trusted backend.
- Respect Retry-After and secondary rate limits; avoid fixed mutation cadence as a substitute for server feedback.
- Treat hardware validation as a separate signed receipt with device model, ABI, build digest, test suite and time; signatures establish receipt origin only when independently verified.

## Delivery sequence

P0: repository audit, existing tool contract tests, Room schema + migration + DAO tests, state reducer and transition tests.
P0: approval policy, validation interceptors, GitHub App auth architecture, Git operation saga + recovery tests.
P1: WorkManager verification watcher, CI evidence reconciliation, bounded remediation attempts.
P1: physical-device validation receipts for JNI/GGUF/GPU/NPU changes.
P2: Compose task dashboard, metrics, replayable diagnostics.

## First acceptance matrix

1. Kill process after COMMIT_CREATED and before REF_UPDATED; resume without duplicate commit.
2. Kill process after remote ref update and before local persistence; reconcile without duplicate write.
3. Advance branch during patch staging; reject stale head and report conflict.
4. Return HTTP 200 with timed_out check conclusion; never mark CI_VERIFIED.
5. Provide a secret-bearing patch; block, redact and require safe regeneration.
6. Exceed blast radius; require patch-specific approval.
7. Modify JNI source; CI_VERIFIED must not imply HARDWARE_VERIFIED.
8. Reboot device while waiting on CI; resume watcher without duplicating work.
9. Provide an attachment name absent from Git tree; route to attachment source, not repository search.
10. Validate required checks against latest commit and branch-protection policy.
