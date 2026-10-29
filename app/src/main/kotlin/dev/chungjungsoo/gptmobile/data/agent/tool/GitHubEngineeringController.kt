package dev.chungjungsoo.gptmobile.data.agent.tool

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Android entry point. UI grants approval; background recovery never invents it. */
@Singleton
class GitHubEngineeringController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connections: ToolConnectionRepository,
    private val vault: SecretVault,
    private val apiFactory: GitHubEngineeringApiFactory,
    private val benchmarks: dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore,
    private val workQueue: GitHubEngineeringWorkQueue,
    private val gitHubPublicAuth: dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator? = null
) {
    private val database get() = GitHubEngineeringDatabaseFactory.open(context)
    private val dao get() = database.ledgerDao()
    private val ledger get() = GitHubEngineeringRoomLedger(database)

    private suspend fun credential(uid: String): String {
        val connection = requireNotNull(connections.getConnection(uid)) { "GitHub connection removed; reconnect" }
        require(connection.type == ToolConnectionType.GITHUB)
        gitHubPublicAuth?.let { return it.accessToken(connection) }
        val bytes = connection.secretRef?.let { vault.read(it) }
        return try {
            requireNotNull(bytes?.decodeToString()?.takeIf { it.isNotBlank() }) { "GitHub credential unavailable; reconnect" }
        } finally {
            bytes?.fill(0)
        }
    }
    private fun api(uid: String) = apiFactory.create { credential(uid) }
    private fun gateway(uid: String) = GitHubEngineeringCiHttpGateway({ credential(uid) }, api(uid))
    private suspend fun actor(api: GitHubEngineeringApi): String = JSONObject(api.request("/user")).getLong("id").also { require(it > 0) }.toString()
    private fun root(task: EngineeringTaskRow) = GitHubEngineeringApi.root(task.repositoryOwner, task.repositoryName)
    private suspend fun head(api: GitHubEngineeringApi, task: EngineeringTaskRow) = JSONObject(api.request("${root(task)}/git/ref/heads/${GitHubEngineeringApi.segment(task.workingBranch)}")).getJSONObject("object").getString("sha")

    internal suspend fun recent(): List<EngineeringTaskRow> = dao.recentTasks()
    internal fun observeChanges() = database.invalidationTracker.createFlow("engineering_tasks", "engineering_runs", emitInitialState = true)
    internal suspend fun events(taskId: String): List<EngineeringEventRow> = dao.events(taskId)
    internal suspend fun run(taskId: String): EngineeringRunRow? = dao.run(taskId)

    private suspend fun advance(taskId: String, evidence: EngineeringEvidence, receipt: String) {
        val task = requireNotNull(ledger.task(taskId))
        val state = EngineeringState.valueOf(task.state)
        ledger.append(
            UUID.randomUUID().toString(),
            EngineeringLedgerEvent(
                taskId,
                task.sequence + 1,
                task.attempt,
                state,
                evidence,
                GitHubEngineeringStateMachine.advance(state, evidence),
                engineeringDigest(receipt)
            )
        )
    }

    internal suspend fun prepare(
        uid: String,
        owner: String,
        repo: String,
        branch: String,
        baseSha: String,
        files: List<EngineeringFilePatch>,
        message: String,
        requiresHardware: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        require(message.isNotBlank() && message.length <= 4000)
        require(branch.isNotBlank() && baseSha.matches(Regex("[a-f0-9]{40}")))
        GitHubEngineeringApi.root(owner, repo)
        val patch = GitHubEngineeringAuthorization.patchJson(files)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val task = EngineeringTaskRow(id, owner, repo, branch, baseSha, requiresHardwareVerification = requiresHardware, updatedAtEpochMs = now)
        for (existing in ledger.activeTasks().filter {
            it.repositoryOwner.equals(owner, true) &&
                it.repositoryName.equals(repo, true) &&
                it.workingBranch == branch &&
                it.baseSha == baseSha &&
                it.requiresHardwareVerification == requiresHardware &&
                it.state in setOf("AWAITING_APPROVAL", "COMMITTING")
        }) {
            val retained = dao.run(existing.taskId) ?: continue
            if (retained.connectionUid == uid && retained.patchJson == patch && retained.commitMessage == message) return@withContext existing.taskId
        }
        val api = api(uid)
        val metadata = JSONObject(api.request(root(task)))
        val defaultBranch = metadata.getString("default_branch")
        require(branch != defaultBranch) { "Use a working branch; direct default-branch writes are disabled" }
        check(head(api, task) == baseSha) { "Branch moved; inspect and review again" }
        val run = EngineeringRunRow(id, uid, patch, "", message, now, defaultBranch)
        database.withEngineeringTransaction {
            ledger.createTask(task)
            dao.saveRun(run.copy(patchDigest = GitHubEngineeringAuthorization.digest(task, run)))
            advance(id, EngineeringEvidence.INSPECTION_STARTED, baseSha)
            advance(id, EngineeringEvidence.PATCH_GENERATED, patch)
            advance(id, EngineeringEvidence.LOCAL_VALIDATION_STARTED, "text_budget_paths_secret_scan")
            advance(id, EngineeringEvidence.LOCAL_VALIDATION_PASSED, "Structural validation only; compilation requires CI")
            advance(id, EngineeringEvidence.APPROVAL_REQUIRED, "Exact patch and destination require user approval")
        }
        id
    }

    /** AI tools can stage a review task, but cannot grant its approval. */
    internal suspend fun prepareFromTool(uid: String, repositoryRoot: String, arguments: String): String = withContext(Dispatchers.IO) {
        val parts = repositoryRoot.split('/')
        require(parts.size == 4 && parts[1] == "repos")
        val owner = java.net.URLDecoder.decode(parts[2], "UTF-8")
        val repo = java.net.URLDecoder.decode(parts[3], "UTF-8")
        val root = GitHubEngineeringApi.root(owner, repo)
        val args = JSONObject(arguments)
        val base = args.getString("expected_head_sha").also { require(it.matches(Regex("[a-f0-9]{40}"))) }
        val changes = args.getJSONArray("files").also { require(it.length() in 1..20) }
        val api = api(uid)
        val commit = JSONObject(api.request("$root/git/commits/$base"))
        val tree = JSONObject(api.request("$root/git/trees/${commit.getJSONObject("tree").getString("sha")}?recursive=1"))
        check(!tree.getBoolean("truncated"))
        val entries = tree.getJSONArray("tree").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.associateBy { it.getString("path") } }
        val files = (0 until changes.length()).map { index ->
            val change = changes.getJSONObject(index)
            val path = change.getString("path")
            dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient.filePath(path)
            val existing = entries[path]
            val delete = change.optBoolean("delete", false)
            require(!delete || existing != null) { "Cannot delete a missing file: $path" }
            val expectedBlob = if (change.has("expected_blob_sha") && !change.isNull("expected_blob_sha")) change.getString("expected_blob_sha") else null
            require(existing == null || expectedBlob == existing.getString("sha")) { "Read the current file and supply its expected_blob_sha: $path" }
            require(existing != null || expectedBlob == null) { "Original file no longer exists: $path" }
            val before = if (existing == null) {
                ""
            } else {
                require(existing.getString("type") == "blob" && existing.getLong("size") <= 2 * 1024 * 1024)
                val blob = JSONObject(api.request("$root/git/blobs/${existing.getString("sha")}"))
                check(blob.getString("sha") == existing.getString("sha") && blob.getString("encoding") == "base64")
                val bytes = java.util.Base64.getMimeDecoder().decode(blob.getString("content"))
                require(bytes.size <= 2 * 1024 * 1024)
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().also { check(gitBlob(it) == existing.getString("sha")) }
            }
            val after = if (delete) {
                require(!change.has("content") || change.getString("content").isEmpty()) { "A deletion cannot include replacement content" }
                ""
            } else {
                change.getString("content")
            }
            EngineeringFilePatch(path, before, after, delete)
        }
        prepare(uid, owner, repo, args.getString("branch"), base, files, args.getString("message"))
    }

    /** Called only by the explicit Android review confirmation, never registered as an AI tool. */
    internal suspend fun approve(taskId: String, expectedDigest: String) {
        val task = requireNotNull(ledger.task(taskId))
        withRepositoryLock(task) {
            val run = requireNotNull(dao.run(taskId))
            require(task.state == EngineeringState.AWAITING_APPROVAL.name || task.state == EngineeringState.COMMITTING.name)
            require(expectedDigest == run.patchDigest && run.patchDigest == GitHubEngineeringAuthorization.digest(task, run))
            val api = api(run.connectionUid)
            val actor = actor(api)
            check(head(api, task) == task.baseSha) { "Branch moved; approval cannot apply" }
            val now = System.currentTimeMillis()
            database.withEngineeringTransaction {
                dao.saveRun(run.copy(approvedActor = actor, approvedAtEpochMs = now, approvalExpiresAtEpochMs = now + 30 * 60_000L, approvalRevoked = false))
                if (task.state == EngineeringState.AWAITING_APPROVAL.name) advance(taskId, EngineeringEvidence.APPROVAL_GRANTED, "$actor|${run.patchDigest}|$now")
            }
        }
    }

    internal suspend fun revoke(taskId: String) {
        val task = requireNotNull(ledger.task(taskId))
        withRepositoryLock(task) { dao.run(taskId)?.let { dao.saveRun(it.copy(approvalRevoked = true, notice = "Approval revoked")) } }
    }

    internal suspend fun cancel(taskId: String) {
        val task = requireNotNull(ledger.task(taskId))
        withRepositoryLock(task) {
            val current = requireNotNull(ledger.task(taskId))
            if (current.state !in setOf("COMPLETED", "CANCELLED", "FAILED", "BLOCKED", "CONFLICTED", "CI_FAILED")) {
                advance(taskId, EngineeringEvidence.USER_CANCELLED, "User cancelled; already published Git objects are retained")
            }
            dao.run(taskId)?.let { dao.saveRun(it.copy(approvalRevoked = true)) }
            workQueue.cancel(taskId)
        }
    }

    internal suspend fun execute(taskId: String): String = withContext(Dispatchers.IO) {
        val task = requireNotNull(ledger.task(taskId))
        withRepositoryLock(task) {
            val run = requireNotNull(dao.run(taskId))
            val api = api(run.connectionUid)
            val identity = actor(api)
            api.expectedWriteActor = identity
            GitHubEngineeringAuthorization.requireApproval(requireNotNull(ledger.task(taskId)), run, identity, System.currentTimeMillis())
            val metadata = JSONObject(api.request(root(task)))
            require(task.workingBranch != metadata.getString("default_branch"))
            var row = ledger.operations(taskId).firstOrNull()
            if (row == null) {
                row = EngineeringGitOperationRow(UUID.randomUUID().toString(), taskId, UUID.randomUUID().toString(), task.baseSha, updatedAtEpochMs = System.currentTimeMillis())
                ledger.createOperation(row)
            }
            var snapshot = row.snapshot()
            val files = GitHubEngineeringAuthorization.files(run.patchJson)
            if (snapshot.stage in setOf(GitSagaStage.PENDING, GitSagaStage.BLOBS_STAGED, GitSagaStage.TREE_CREATED)) {
                if (head(api, task) != task.baseSha) {
                    advance(taskId, EngineeringEvidence.REF_CONFLICT, "Remote head moved before staging")
                    error("Branch changed; review a new patch")
                }
                val base = JSONObject(api.request("${root(task)}/git/commits/${task.baseSha}"))
                val treeSha = base.getJSONObject("tree").getString("sha")
                val tree = JSONObject(api.request("${root(task)}/git/trees/$treeSha?recursive=1"))
                check(!tree.getBoolean("truncated")) { "Incomplete repository tree" }
                val rows = tree.getJSONArray("tree")
                val entries = (0 until rows.length()).map { rows.getJSONObject(it) }.associateBy { it.getString("path") }
                files.forEach { file ->
                    val existing = entries[file.path]
                    require(!file.delete || existing != null) { "Cannot delete a missing file: ${file.path}" }
                    require(existing == null && file.before.isEmpty() || existing?.optString("sha") == gitBlob(file.before)) { "Original blob changed: ${file.path}" }
                    require(existing == null || existing.getString("mode") in setOf("100644", "100755")) { "Only ordinary text files can be replaced" }
                }
                suspend fun authorize() = GitHubEngineeringAuthorization.requireApproval(requireNotNull(ledger.task(taskId)), requireNotNull(dao.run(taskId)), identity, System.currentTimeMillis())
                if (snapshot.stage == GitSagaStage.PENDING) {
                    for (file in files) {
                        if (file.delete) continue
                        authorize()
                        val blob = JSONObject(api.request("${root(task)}/git/blobs", "POST", JSONObject().put("content", file.after).put("encoding", "utf-8")))
                        check(blob.getString("sha") == gitBlob(file.after))
                    }
                    val next = snapshot.copy(stage = GitSagaStage.BLOBS_STAGED, blobManifestDigest = run.patchDigest)
                    ledger.checkpointOperation(snapshot.operationId, snapshot.stage, next)
                    snapshot = next
                }
                if (snapshot.stage == GitSagaStage.BLOBS_STAGED) {
                    authorize()
                    val changes = JSONArray().apply { files.forEach { file -> put(JSONObject().put("path", file.path).put("mode", entries[file.path]?.optString("mode") ?: "100644").put("type", "blob").put("sha", if (file.delete) JSONObject.NULL else gitBlob(file.after))) } }
                    val created = JSONObject(api.request("${root(task)}/git/trees", "POST", JSONObject().put("base_tree", treeSha).put("tree", changes)))
                    val next = GitHubGitSaga.recordTree(snapshot, created.getString("sha"))
                    ledger.checkpointOperation(snapshot.operationId, snapshot.stage, next)
                    snapshot = next
                }
                if (snapshot.stage == GitSagaStage.TREE_CREATED) {
                    authorize()
                    // Fixed identity and date make a retry of an ambiguous object creation deterministic.
                    val user = JSONObject(api.request("/user"))
                    check(user.getLong("id").toString() == identity)
                    val login = user.getString("login")
                    val author = JSONObject().put("name", login).put("email", "$identity+$login@users.noreply.github.com").put("date", Instant.ofEpochMilli(run.createdAtEpochMs).toString())
                    val created = JSONObject(api.request("${root(task)}/git/commits", "POST", JSONObject().put("message", run.commitMessage).put("tree", snapshot.treeSha).put("parents", JSONArray().put(task.baseSha)).put("author", author).put("committer", author)))
                    val next = GitHubGitSaga.recordCommit(snapshot, created.getString("sha"))
                    ledger.checkpointOperation(snapshot.operationId, snapshot.stage, next)
                    snapshot = next
                }
            }
            check(snapshot.stage == GitSagaStage.COMMIT_CREATED || snapshot.stage == GitSagaStage.REF_UPDATED)
            val commit = JSONObject(api.request("${root(task)}/git/commits/${snapshot.commitSha}"))
            val parents = commit.getJSONArray("parents")
            check(parents.length() == 1)
            val evidence = RemoteCommitEvidence(commit.getString("sha"), parents.getJSONObject(0).getString("sha"), commit.getJSONObject("tree").getString("sha"))
            val remoteHead = head(api, task)
            when (EngineeringRemoteReconciliation.verify(snapshot, remoteHead, evidence)) {
                is GitSagaReconciliation.ReadyToUpdateRef -> {
                    GitHubEngineeringAuthorization.requireApproval(requireNotNull(ledger.task(taskId)), requireNotNull(dao.run(taskId)), actor(api), System.currentTimeMillis())
                    check(head(api, task) == task.baseSha) { "Branch moved immediately before ref update" }
                    api.request("${root(task)}/git/refs/heads/${GitHubEngineeringApi.segment(task.workingBranch)}", "PATCH", JSONObject().put("sha", snapshot.commitSha).put("force", false))
                }
                is GitSagaReconciliation.AlreadyApplied -> Unit
                else -> {
                    advance(taskId, EngineeringEvidence.REF_CONFLICT, remoteHead)
                    error("Remote commit or ref conflicts with the approved patch")
                }
            }
            check(head(api, task) == snapshot.commitSha) { "Remote ref verification failed; reconcile before retrying" }
            if (snapshot.stage != GitSagaStage.REF_UPDATED) ledger.checkpointOperation(snapshot.operationId, snapshot.stage, snapshot.copy(stage = GitSagaStage.REF_UPDATED, remoteRefSha = snapshot.commitSha))
            database.withEngineeringTransaction {
                advance(taskId, EngineeringEvidence.REMOTE_REF_CONFIRMED, "${snapshot.commitSha}|${snapshot.treeSha}|${task.baseSha}")
                advance(taskId, EngineeringEvidence.CI_WATCH_STARTED, requireNotNull(snapshot.commitSha))
                val now = System.currentTimeMillis()
                dao.saveRun(requireNotNull(dao.run(taskId)).copy(ciStartedAtEpochMs = now, ciDeadlineEpochMs = now + 24 * 60 * 60_000L, notice = "Remote commit verified; waiting for repository-required checks"))
            }
            workQueue.enqueue(taskId)
            requireNotNull(snapshot.commitSha)
        }
    }

    /** Called by WorkManager; only exact authenticated receipts can advance CI. */
    internal suspend fun pollCi(taskId: String): EngineeringWorkerOutcome = withContext(Dispatchers.IO) {
        val original = ledger.task(taskId) ?: return@withContext EngineeringWorkerOutcome.INVALID
        withRepositoryLock(original) {
            val task = requireNotNull(ledger.task(taskId))
            if (task.state == EngineeringState.CANCELLED.name) return@withRepositoryLock EngineeringWorkerOutcome.CANCELLED
            if (task.state in setOf("CI_VERIFIED", "AWAITING_HARDWARE_VERIFICATION", "HARDWARE_VERIFIED", "COMPLETED")) {
                finishVerified(taskId)
                return@withRepositoryLock EngineeringWorkerOutcome.VERIFIED
            }
            if (task.state != EngineeringState.CI_PENDING.name) return@withRepositoryLock EngineeringWorkerOutcome.INVALID
            val run = dao.run(taskId) ?: return@withRepositoryLock EngineeringWorkerOutcome.INVALID
            val now = System.currentTimeMillis()
            if (run.ciStartedAtEpochMs == null || run.ciDeadlineEpochMs == null || now < run.ciStartedAtEpochMs || now >= run.ciDeadlineEpochMs) {
                advance(taskId, EngineeringEvidence.EXECUTION_FAILED, "Durable CI deadline expired or missing")
                return@withRepositoryLock EngineeringWorkerOutcome.INVALID
            }
            if (run.retryAtEpochMs?.let { now < it } == true) return@withRepositoryLock EngineeringWorkerOutcome.RETRY
            val op = ledger.operations(taskId).singleOrNull { it.stage == GitSagaStage.REF_UPDATED.name && it.commitSha == it.remoteRefSha }
                ?: return@withRepositoryLock EngineeringWorkerOutcome.INVALID
            try {
                val sha = requireNotNull(op.commitSha)
                val api = api(run.connectionUid)
                if (head(api, task) != sha) {
                    advance(taskId, EngineeringEvidence.REF_CONFLICT, "Branch moved after push; CI cannot verify this task")
                    return@withRepositoryLock EngineeringWorkerOutcome.INVALID
                }
                val gateway = gateway(run.connectionUid)
                val required = gateway.requiredChecks(task.repositoryOwner, task.repositoryName, run.policyBranch)
                val checks = gateway.fetch(task.repositoryOwner, task.repositoryName, sha)
                if (head(api, task) != sha) {
                    advance(taskId, EngineeringEvidence.REF_CONFLICT, "Branch moved during CI inspection")
                    return@withRepositoryLock EngineeringWorkerOutcome.INVALID
                }
                check(gateway.requiredChecks(task.repositoryOwner, task.repositoryName, run.policyBranch) == required) { "Repository check policy changed during inspection" }
                val verdict = GitHubEngineeringCiPolicy.evaluateRules(sha, required, checks)
                val requiredJson = JSONArray().apply { required.sortedBy { it.name + it.appId }.forEach { put(JSONObject().put("name", it.name).put("appId", it.appId)) } }.toString()
                dao.saveRun(run.copy(requiredChecksJson = requiredJson))
                val receipt = JSONObject().put("sha", sha).put("observedAtEpochMs", System.currentTimeMillis()).put("required", JSONArray(requiredJson)).put(
                    "checks",
                    JSONArray().apply {
                        checks.forEach { put(JSONObject().put("name", it.name).put("id", it.runId).put("appId", it.appId).put("source", it.source).put("status", it.status.name).put("conclusion", it.conclusion?.name).put("headSha", it.headSha)) }
                    }
                ).toString()
                if (System.currentTimeMillis() >= run.ciDeadlineEpochMs) {
                    advance(taskId, EngineeringEvidence.EXECUTION_FAILED, "CI deadline expired during inspection")
                    return@withRepositoryLock EngineeringWorkerOutcome.INVALID
                }
                ledger.recordCi(taskId, receipt, verdict)
                if (verdict == EngineeringCiVerdict.PASSED) {
                    finishVerified(taskId)
                    EngineeringWorkerOutcome.VERIFIED
                } else if (verdict == EngineeringCiVerdict.FAILED) {
                    EngineeringWorkerOutcome.INVALID
                } else {
                    EngineeringWorkerOutcome.RETRY
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val retryAt = (error as? EngineeringApiException)?.retryAtEpochMs
                dao.saveRun(requireNotNull(dao.run(taskId)).copy(retryAtEpochMs = retryAt, notice = "CI inspection unavailable: ${error.javaClass.simpleName}; no success recorded"))
                EngineeringWorkerOutcome.RETRY
            }
        }
    }

    /** Only measured local trials for this exact installed commit can satisfy this gate. */
    internal suspend fun attachHardwareVerification(taskId: String) = withContext(Dispatchers.IO) {
        val task = requireNotNull(ledger.task(taskId))
        withRepositoryLock(task) {
            require(task.state == EngineeringState.AWAITING_HARDWARE_VERIFICATION.name)
            val run = requireNotNull(dao.run(taskId))
            val operation = ledger.operations(taskId).single { it.stage == GitSagaStage.REF_UPDATED.name }
            val sha = requireNotNull(operation.commitSha)
            val installed = dev.chungjungsoo.gptmobile.BuildConfig.APP_COMMIT
            require(installed.length >= 8 && sha.startsWith(installed)) { "Install the build for this commit and run the local runtime benchmark first" }
            require(!android.os.Build.FINGERPRINT.contains("generic", true) && !android.os.Build.MODEL.contains("sdk", true)) { "Emulator evidence cannot satisfy physical-device verification" }
            benchmarks.load()
            val qualifications = benchmarks.runtimeQualifications.value.filter {
                it.status == dev.chungjungsoo.gptmobile.data.localruntime.QualificationStatus.QUALIFIED &&
                    it.actualBackendConfirmed &&
                    it.qualityEquivalent &&
                    it.failedRuns == 0 &&
                    it.measuredAt >= run.createdAtEpochMs &&
                    it.sourceRunIds.isNotEmpty()
            }
            val trials = benchmarks.runtimeTrials.value.filter {
                !it.historical &&
                    it.completed &&
                    it.finished &&
                    it.failure == null &&
                    it.outputCharacters > 0 &&
                    it.appCommit.length >= 8 &&
                    sha.startsWith(it.appCommit) &&
                    it.timestamp >= run.createdAtEpochMs
            }
            val qualified = qualifications.filter { qualification -> qualification.sourceRunIds.all { id -> trials.any { it.id == id && it.key == qualification.key } } }
            require(
                qualified.groupBy { it.key.artifactDigest to it.key.runtimeIdentity }.values.any { group ->
                    group.any { it.key.backend == "cpu" } && group.any { it.key.backend in setOf("gpu", "opencl") }
                }
            ) { "No qualified CPU plus GPU trials for the same artifact/runtime and commit; run both configurations on this device" }
            val actor = actor(api(run.connectionUid))
            require(actor == run.approvedActor && !run.approvalRevoked) { "The approving account must confirm the device evidence" }
            val receipt = JSONObject().put("commitSha", sha).put("deviceModel", android.os.Build.MODEL)
                .put("firmwareDigest", engineeringDigest(android.os.Build.FINGERPRINT)).put("approvedActor", actor)
                .put("patchDigest", run.patchDigest).put("observedAtEpochMs", System.currentTimeMillis())
                .put("scope", "qualified_local_runtime_benchmark;not_general_UI_acceptance")
                .put(
                    "runtimeEvidence",
                    JSONArray().apply {
                        qualified.forEach { q ->
                            put(
                                JSONObject().put("runtime", q.key.runtimeIdentity).put("artifactDigest", q.key.artifactDigest)
                                    .put("actualBackend", q.key.backend).put("actualBackendConfirmed", q.actualBackendConfirmed)
                                    .put("testRunIds", JSONArray(q.sourceRunIds)).put("result", q.status.name)
                            )
                        }
                    }
                ).toString()
            database.withEngineeringTransaction {
                dao.saveRun(run.copy(hardwareReceiptJson = receipt, notice = "Physical-device runtime benchmark evidence attached"))
                advance(taskId, EngineeringEvidence.HARDWARE_RECEIPT_VERIFIED, receipt)
                finishVerified(taskId)
            }
        }
    }

    private suspend fun finishVerified(taskId: String) {
        val task = requireNotNull(ledger.task(taskId))
        val run = requireNotNull(dao.run(taskId))
        // A state name alone cannot substitute for durable upstream evidence.
        check(!run.verifiedChecksJson.isNullOrBlank())
        if (task.state == EngineeringState.CI_VERIFIED.name) {
            advance(taskId, if (task.requiresHardwareVerification) EngineeringEvidence.HARDWARE_REQUIRED else EngineeringEvidence.POLICY_COMPLETION_CONFIRMED, run.verifiedChecksJson)
        }
        if (task.state == EngineeringState.HARDWARE_VERIFIED.name) {
            check(!run.hardwareReceiptJson.isNullOrBlank())
            advance(taskId, EngineeringEvidence.POLICY_COMPLETION_CONFIRMED, run.hardwareReceiptJson)
        }
    }

    /** Read-only remote reconciliation after process death. Never auto-grants approval. */
    internal suspend fun recover() {
        for (task in ledger.activeTasks()) {
            val run = dao.run(task.taskId) ?: continue
            try {
                withRepositoryLock(task) {
                    if (task.state in setOf("CI_PENDING", "CI_VERIFIED", "HARDWARE_VERIFIED")) {
                        if (task.state == "CI_PENDING") {
                            workQueue.enqueue(task.taskId)
                        } else {
                            finishVerified(task.taskId)
                        }
                    } else if (task.state in setOf(EngineeringState.COMMITTING.name, EngineeringState.PUSHED.name)) {
                        val op = ledger.operations(task.taskId).singleOrNull() ?: return@withRepositoryLock
                        if (op.commitSha == null || op.treeSha == null) {
                            dao.saveRun(run.copy(notice = "Interrupted staging; review approval and resume deterministically"))
                            return@withRepositoryLock
                        }
                        val api = api(run.connectionUid)
                        val commit = JSONObject(api.request("${root(task)}/git/commits/${op.commitSha}"))
                        val parents = commit.getJSONArray("parents")
                        check(parents.length() == 1)
                        val decision = EngineeringRemoteReconciliation.verify(op.snapshot(), head(api, task), RemoteCommitEvidence(commit.getString("sha"), parents.getJSONObject(0).getString("sha"), commit.getJSONObject("tree").getString("sha")))
                        when (decision) {
                            is GitSagaReconciliation.AlreadyApplied -> {
                                if (op.stage != GitSagaStage.REF_UPDATED.name) ledger.checkpointOperation(op.operationId, GitSagaStage.valueOf(op.stage), decision.snapshot)
                                database.withEngineeringTransaction {
                                    if (task.state == EngineeringState.COMMITTING.name) advance(task.taskId, EngineeringEvidence.REMOTE_REF_CONFIRMED, "Recovered verified ref: ${op.commitSha}")
                                    advance(task.taskId, EngineeringEvidence.CI_WATCH_STARTED, op.commitSha)
                                    val now = System.currentTimeMillis()
                                    dao.saveRun(run.copy(ciStartedAtEpochMs = now, ciDeadlineEpochMs = now + 24 * 60 * 60_000L, notice = "Recovered remote ref; CI scheduled"))
                                }
                                workQueue.enqueue(task.taskId)
                            }
                            is GitSagaReconciliation.Conflict -> advance(task.taskId, EngineeringEvidence.REF_CONFLICT, decision.actualHeadSha)
                            else -> dao.saveRun(run.copy(notice = "Remote objects reconciled; approval required to resume ref update"))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                dao.saveRun(requireNotNull(dao.run(task.taskId)).copy(notice = "Recovery waiting: ${error.javaClass.simpleName}"))
            }
        }
    }

    private suspend fun <T> withRepositoryLock(task: EngineeringTaskRow, block: suspend () -> T): T = locks.getOrPut("${task.repositoryOwner.lowercase()}/${task.repositoryName.lowercase()}") { Mutex() }.withLock { block() }
    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun gitBlob(text: String): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return java.security.MessageDigest.getInstance("SHA-1").digest("blob ${bytes.size}\u0000".toByteArray() + bytes).joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun EngineeringGitOperationRow.snapshot() = GitSagaSnapshot(operationId, expectedHeadSha, GitSagaStage.valueOf(stage), blobManifestDigest, treeSha, commitSha, remoteRefSha)
internal suspend fun <T> GitHubEngineeringDatabase.withEngineeringTransaction(block: suspend () -> T): T = withTransaction { block() }

internal enum class EngineeringWorkerOutcome { VERIFIED, CANCELLED, RETRY, INVALID }
