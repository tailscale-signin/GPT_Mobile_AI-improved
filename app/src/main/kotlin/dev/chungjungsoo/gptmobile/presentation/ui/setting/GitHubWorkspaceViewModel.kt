package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceStore
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@kotlinx.serialization.Serializable
data class GitHubStagedFile(val path: String, val original: String, val content: String)

data class GitHubWorkspaceState(
    val connection: ToolConnection? = null,
    val login: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val repositories: List<JsonObject> = emptyList(),
    val repositoryPage: Int = 1,
    val moreRepositories: Boolean = false,
    val selection: GitHubRepositoryContext? = null,
    val defaultBranch: String = "",
    val canWrite: Boolean = false,
    val branches: List<String> = emptyList(),
    val branchPage: Int = 1,
    val moreBranches: Boolean = false,
    val headSha: String = "",
    val directory: String = "",
    val entries: List<JsonObject> = emptyList(),
    val directoryPage: Int = 1,
    val moreEntries: Boolean = false,
    val file: JsonObject? = null,
    val staged: List<GitHubStagedFile> = emptyList(),
    val editorDrafts: Map<String, String> = emptyMap(),
    val commits: List<JsonObject> = emptyList(),
    val moreCommits: Boolean = false,
    val commitPage: Int = 1,
    val activityUpdatedAt: Long? = null,
    val commitDetail: String? = null,
    val pullRequests: List<JsonObject> = emptyList(),
    val pullDetail: String? = null,
    val pullPage: Int = 1,
    val morePullRequests: Boolean = false,
    val snapshot: JsonObject? = null,
    val indexStatus: JsonObject? = null,
    val efficiency: JsonObject? = null,
    val observedAt: Long? = null,
    val activityLoaded: Boolean = false,
    val pullsLoaded: Boolean = false,
    val directoryLoaded: Boolean = false,
    val searchResult: JsonObject? = null,
    val ciDigest: JsonObject? = null,
    val patchStage: JsonObject? = null,
    val reviewBaseline: String = "",
    val changedFiles: JsonObject? = null,
    val pullPackage: JsonObject? = null
)

@HiltViewModel
class GitHubWorkspaceViewModel @Inject constructor(
    private val connections: ToolConnectionRepository,
    private val vault: SecretVault,
    private val store: GitHubWorkspaceStore,
    private val drafts: dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceDraftStore,
    private val settings: dev.chungjungsoo.gptmobile.data.repository.SettingRepository,
    private val repositoryState: dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore? = null,
    private val gitHubPublicAuth: dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator? = null
) : ViewModel() {
    private val _state = MutableStateFlow(GitHubWorkspaceState())
    val state = _state.asStateFlow()
    private var client: GitHubWorkspaceClient? = null
    private var tool: GitHubTool? = null
    private var credentialDigest: ByteArray? = null

    @kotlinx.serialization.Serializable
    private data class Draft(val selection: GitHubRepositoryContext?, val baseSha: String, val directory: String, val file: JsonObject?, val staged: List<GitHubStagedFile>, val edits: Map<String, String>, val reviewBaseline: String = "", val patchStage: JsonObject? = null)

    private val draftWrites = kotlinx.coroutines.sync.Mutex()
    private val draftSequence = java.util.concurrent.atomic.AtomicLong()
    private val persistedDraftSequence = mutableMapOf<String, Long>()
    private data class PendingDraft(val sequence: Long, val connection: String, val value: Draft)
    private fun draft(state: GitHubWorkspaceState): Pair<String, Draft>? = state.connection?.connectionUid?.let { connection ->
        state.selection?.let { connection to Draft(it, state.headSha, state.directory, state.file, state.staged, state.editorDrafts, state.reviewBaseline, state.patchStage) }
    }
    private suspend fun persist(pending: PendingDraft) = draftWrites.withLock {
        val current = draft(_state.value)
        if (current == null || current.first != pending.connection || current.second != pending.value) return@withLock
        if (pending.sequence <= (persistedDraftSequence[pending.connection] ?: -1)) return@withLock
        drafts.write(pending.connection, Json.encodeToString(Draft.serializer(), pending.value))
        persistedDraftSequence[pending.connection] = pending.sequence
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeDrafts() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        _state.map { if (it.busy) null else draft(it) }.filterNotNull().distinctUntilChanged()
            .map { PendingDraft(draftSequence.incrementAndGet(), it.first, it.second) }.debounce(400).collect { pending ->
                try {
                    persist(pending)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(error = "Workspace edits could not be saved for recovery. Keep this screen open and retry.") }
                }
            }
    }
    init {
        observeDrafts()
    }

    fun editDraft(path: String, content: String, original: String) {
        _state.update { state -> state.copy(editorDrafts = if (content == (state.staged.firstOrNull { it.path == path }?.content ?: original)) state.editorDrafts - path else state.editorDrafts + (path to content)) }
    }

    fun open(connection: ToolConnection) {
        if (_state.value.busy) return
        client = null
        tool = null
        credentialDigest = null
        _state.value = GitHubWorkspaceState(connection = connection)
        work {
            val current = connections.getConnection(connection.connectionUid) ?: error("Connection was removed.")
            require(current.type == ToolConnectionType.GITHUB) { "This connection is no longer a GitHub connection. Reopen the workspace with a GitHub connection." }
            if (current.secretRef != null) gitHubPublicAuth?.accessToken(current)
            val bytes = current.secretRef?.let { vault.read(it) }
            val token = try {
                credentialDigest = bytes?.let { MessageDigest.getInstance("SHA-256").digest(it) }
                bytes?.decodeToString()?.trim().orEmpty()
            } finally {
                bytes?.fill(0)
            }
            require(token.isNotBlank()) { "Edit this GitHub connection and add a token first." }
            val features = settings.getFeatureSettings()
            client = GitHubWorkspaceClient(token, conditionalReads = features.githubConditionalReads, blobCache = features.githubBlobCache, freshnessSeconds = features.pluginExecution[dev.chungjungsoo.gptmobile.data.model.ToolPluginId.GITHUB]?.githubCacheSeconds ?: 15, connectionUid = current.connectionUid, conversationId = "workspace", taskId = "workspace:${current.connectionUid}", stateStore = repositoryState, requestPriority = 10)
            tool = GitHubTool(token, featureSettings = features)
            _state.update { it.copy(connection = current) }
            val account = api("get_account").jsonObject
            _state.update { it.copy(connection = current, login = account.text("login")) }
            loadRepositories(1)
            val recovered = draftWrites.withLock { drafts.read(current.connectionUid)?.let { Json.decodeFromString(Draft.serializer(), it) } }
            (recovered?.selection ?: store.get(current.connectionUid))?.let { selectRepositoryInternal(it.owner, it.repo, it.ref) }
            recovered?.takeIf { it.selection == _state.value.selection }?.let { draft ->
                val dirty = draft.staged.isNotEmpty() || draft.edits.isNotEmpty() || draft.patchStage != null
                val changed = dirty && _state.value.headSha != draft.baseSha
                _state.update { currentState ->
                    currentState.copy(
                        headSha = if (dirty) draft.baseSha else currentState.headSha,
                        directory = draft.directory,
                        file = draft.file.takeIf { dirty || currentState.headSha == draft.baseSha },
                        directoryLoaded = draft.file != null && (dirty || currentState.headSha == draft.baseSha),
                        staged = draft.staged,
                        editorDrafts = draft.edits,
                        reviewBaseline = draft.reviewBaseline,
                        patchStage = draft.patchStage,
                        notice = if (changed) "Edits recovered at their original base. The branch moved; review before committing." else "Workspace edits recovered."
                    )
                }
            }
        }
    }

    fun close(onSaved: () -> Unit = {}) {
        if (_state.value.busy) return
        val pending = draft(_state.value)?.let { PendingDraft(draftSequence.incrementAndGet(), it.first, it.second) }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                if (pending != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { persist(pending) }
                client = null
                tool = null
                credentialDigest = null
                _state.value = GitHubWorkspaceState()
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, error = "The latest draft could not be saved. Keep the workspace open and retry.") }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun unstage(path: String) {
        if (_state.value.busy) return
        _state.update { it.copy(staged = it.staged.filterNot { file -> file.path == path }) }
    }
    fun discard() = _state.update { it.copy(staged = emptyList(), editorDrafts = emptyMap(), patchStage = null) }
    fun moreRepositories() = work { loadRepositories(_state.value.repositoryPage + 1) }

    private suspend fun loadRepositories(page: Int) {
        val result = api("list_repositories", buildJsonObject { put("page", page) }).jsonObject
        val items = result["items"]?.jsonArray.orEmpty().map { it.jsonObject }
        _state.update { it.copy(repositories = if (page == 1) items else (it.repositories + items).distinctBy { repo -> repo.text("full_name") }, repositoryPage = page, moreRepositories = result.flag("has_more")) }
    }

    fun selectRepository(owner: String, repo: String) = work {
        require(_state.value.staged.isEmpty() && _state.value.editorDrafts.isEmpty() && _state.value.patchStage == null) { "Commit or discard staged edits before switching repositories." }
        selectRepositoryInternal(owner, repo)
    }

    private suspend fun selectRepositoryInternal(owner: String, repo: String, preferredRef: String? = null) {
        val metadata = api(
            "get_repository",
            buildJsonObject {
                put("owner", owner)
                put("repo", repo)
            }
        ).jsonObject
        val default = metadata.text("default_branch")
        require(default.isNotBlank()) { "Repository has no default branch." }
        val selected = GitHubRepositoryContext(owner, repo, preferredRef ?: default)
        val canWrite = metadata["permissions"]?.jsonObject?.flag("push") == true && !metadata.flag("archived") && _state.value.connection?.toolPolicy != "READ_ONLY"
        _state.update { it.copy(selection = selected, defaultBranch = default, canWrite = canWrite, branches = emptyList(), file = null, staged = emptyList(), editorDrafts = emptyMap(), pullRequests = emptyList(), commits = emptyList(), commitDetail = null, activityUpdatedAt = null, pullDetail = null, directory = "", headSha = "", entries = emptyList(), snapshot = null, indexStatus = null, searchResult = null, ciDigest = null, patchStage = null, changedFiles = null, reviewBaseline = "", activityLoaded = false, directoryLoaded = false, pullPackage = null) }
        loadBranches(1)
        loadHeadAndDirectory()
    }

    fun moreBranches() = work { loadBranches(_state.value.branchPage + 1) }
    private suspend fun loadBranches(page: Int) {
        val result = api("list_branches", buildJsonObject { put("page", page) }).jsonObject
        val branches = result["items"]?.jsonArray.orEmpty().map { it.jsonObject.text("name") }
        _state.update { it.copy(branches = if (page == 1) branches else (it.branches + branches).distinct(), branchPage = page, moreBranches = result.flag("has_more")) }
    }

    fun selectBranch(branch: String, programmerChatId: Int? = null) = work {
        require(_state.value.staged.isEmpty() && _state.value.editorDrafts.isEmpty() && _state.value.patchStage == null) { "Commit or discard staged edits before switching branches." }
        _state.update { it.copy(selection = it.selection?.copy(ref = branch), directory = "", file = null, entries = emptyList(), headSha = "", commits = emptyList(), commitDetail = null, activityUpdatedAt = null, snapshot = null, indexStatus = null, searchResult = null, ciDigest = null, changedFiles = null, activityLoaded = false, directoryLoaded = false, pullPackage = null) }
        loadHeadAndDirectory()
        syncProgrammerBranch(programmerChatId)
    }

    private suspend fun syncProgrammerBranch(programmerChatId: Int?) {
        if (programmerChatId != null) {
            val current = _state.value
            val selected = requireNotNull(current.selection)
            settings.mutateFeatureSettings { features ->
                val prior = features.conversationGitHub[programmerChatId]
                if (prior != null && prior.connectionUid == current.connection?.connectionUid && prior.owner.equals(selected.owner, true) && prior.repo.equals(selected.repo, true)) {
                    features.copy(conversationGitHub = features.conversationGitHub + (programmerChatId to prior.copy(ref = selected.ref, commitSha = current.headSha)))
                } else {
                    features
                }
            }
        }
    }

    fun refreshBranch() = work {
        require(_state.value.staged.isEmpty() && _state.value.editorDrafts.isEmpty() && _state.value.patchStage == null) { "Discard or commit staged edits before refreshing the snapshot." }
        loadHeadAndDirectory()
    }

    private suspend fun loadHeadAndDirectory() {
        val selected = requireNotNull(_state.value.selection)
        val result = api(
            "resolve_context",
            buildJsonObject {
                put("ref", selected.ref)
                put("refresh", true)
            }
        ).jsonObject
        val sha = result.text("commit_sha")
        check(sha.isNotBlank()) { "Missing branch head." }
        _state.update { it.copy(headSha = sha, file = null, snapshot = result["snapshot"] as? JsonObject, indexStatus = result["index"] as? JsonObject, observedAt = System.currentTimeMillis(), directoryLoaded = false, activityLoaded = false, pullsLoaded = false, searchResult = null, ciDigest = null, changedFiles = null, pullPackage = null, pullDetail = null) }
    }

    fun onSectionVisible(section: Int) {
        if (_state.value.busy || _state.value.error != null || _state.value.selection == null || _state.value.headSha.isBlank()) return
        when {
            section == 3 && !_state.value.activityLoaded -> refreshActivity()
            section == 0 && !_state.value.directoryLoaded -> browse(_state.value.directory)
            section == 2 && !_state.value.pullsLoaded -> loadPullRequests()
        }
    }

    fun browse(path: String) = work { browseInternal(path) }
    fun moreFiles() = work { browseInternal(_state.value.directory, _state.value.directoryPage + 1) }
    private suspend fun browseInternal(path: String, page: Int = 1) {
        val result = api(
            "browse_files",
            buildJsonObject {
                put("path", path)
                put("page", page)
                put("ref", _state.value.headSha)
            }
        ).jsonObject
        _state.update { it.copy(directory = path, directoryLoaded = true, directoryPage = page, moreEntries = result.flag("has_more"), entries = ((if (page == 1) emptyList() else it.entries) + result["entries"]?.jsonArray.orEmpty().map { entry -> entry.jsonObject }).distinctBy { entry -> entry.text("path") }.sortedWith(compareBy<JsonObject> { entry -> entry.text("type") != "dir" }.thenBy { entry -> entry.text("name") }), file = null, notice = if (result.flag("directory_limit_reached")) "GitHub limited this directory to 1,000 entries." else it.notice) }
    }

    fun readFile(path: String) = work {
        val result = api(
            "read_ranges",
            buildJsonObject {
                put("path", path)
                put("commit_sha", _state.value.headSha)
                put("end_line", 2000)
            }
        ).jsonObject
        val file = result["items"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("File evidence is unavailable.")
        _state.update { it.copy(file = JsonObject(file + ("evidence_id" to result.getValue("evidence_id"))), directory = path.substringBeforeLast('/', ""), directoryLoaded = true) }
    }

    fun moreFileRange() = work {
        val previous = _state.value.file ?: return@work
        val continuation = previous["continuation"] as? JsonObject ?: return@work
        val result = api("read_ranges", continuation).jsonObject
        val file = result["items"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("File range is unavailable.")
        _state.update { it.copy(file = JsonObject(file + ("evidence_id" to result.getValue("evidence_id")))) }
    }

    fun stage(path: String, original: String, content: String): Boolean {
        if (_state.value.file?.flag("complete_file") != true || _state.value.file?.text("path") != path || _state.value.patchStage != null) {
            _state.update { it.copy(error = "Only a complete file may be replaced. Use an exact patch for a source excerpt.") }
            return false
        }
        if (content.toByteArray().size > 256_000) {
            _state.update { it.copy(error = "File exceeds the text budget.") }
            return false
        }
        var accepted = false
        _state.update { state ->
            val existing = state.staged.firstOrNull { it.path == path }
            val actualOriginal = existing?.original ?: original
            val remaining = state.staged.filterNot { it.path == path }
            val next = if (actualOriginal == content) remaining else remaining + GitHubStagedFile(path, actualOriginal, content)
            if (next.size > 20 || next.sumOf { it.content.toByteArray().size } > 1_000_000) {
                state.copy(error = "Stage at most 20 files and 1 MB of text.")
            } else {
                accepted = true
                state.copy(staged = next, editorDrafts = state.editorDrafts - path, notice = "Changes staged for review.")
            }
        }
        return accepted
    }

    fun useInChats() {
        val state = _state.value
        state.connection?.let { store.set(it.connectionUid, state.selection) }
        _state.update { it.copy(notice = "${it.selection?.fullName} @ ${it.selection?.ref} is now the default for this GitHub connection in chats.") }
    }

    fun clearChatContext() {
        _state.value.connection?.let { store.set(it.connectionUid, null) }
        _state.update { it.copy(notice = "Default GitHub repository context cleared.") }
    }

    fun createBranch(name: String, expectedHead: String = _state.value.headSha, programmerChatId: Int? = null) = work {
        val state = _state.value
        check(state.headSha == expectedHead && expectedHead.matches(Regex("[a-fA-F0-9]{40}"))) { "Branch head changed; review the creation request again." }
        require(state.canWrite && state.staged.isEmpty() && state.editorDrafts.isEmpty() && state.patchStage == null) { "Commit or discard staged changes first; write access is required." }
        toolRequest(
            "create_branch",
            buildJsonObject {
                put("branch", name.trim())
                put("base", requireNotNull(state.selection).ref)
                put("expected_head_sha", state.headSha)
            }
        )
        _state.update { it.copy(selection = it.selection?.copy(ref = name.trim()), notice = "Working branch created.", directory = "") }
        loadBranches(1)
        loadHeadAndDirectory()
        syncProgrammerBranch(programmerChatId)
    }

    fun commit(message: String) = work {
        val state = _state.value
        require(state.canWrite) { "Repository write access is required." }
        val result = api(
            "commit_files",
            buildJsonObject {
                put("branch", requireNotNull(state.selection).ref)
                put("expected_head_sha", state.headSha)
                put("message", message)
                put(
                    "files",
                    buildJsonArray {
                        state.staged.forEach { file ->
                            add(
                                buildJsonObject {
                                    put("path", file.path)
                                    put("content", file.content)
                                }
                            )
                        }
                    }
                )
            }
        ).jsonObject
        _state.update { it.copy(staged = emptyList(), editorDrafts = emptyMap(), headSha = result.text("sha"), file = null, notice = "Committed ${state.staged.size} file(s) to ${state.selection?.ref}.") }
        browseInternal(state.directory)
        loadCommits(1)
    }

    fun createPullRequest(title: String, body: String) = work {
        val state = _state.value
        require(state.canWrite && state.staged.isEmpty() && state.selection?.ref != state.defaultBranch) { "Commit changes on a working branch before opening a pull request." }
        val result = toolRequest(
            "create_pull_request",
            buildJsonObject {
                put("title", title)
                put("body", body)
                put("head", requireNotNull(state.selection).ref)
                put("base", state.defaultBranch)
                put("draft", true)
            }
        )
        val url = Json.parseToJsonElement(result).jsonObject.text("html_url")
        _state.update { it.copy(notice = "Draft pull request created: $url") }
        loadPullRequestsInternal(1)
    }

    fun refreshActivity() = work { loadActivityInternal() }
    fun moreCommits() = work { loadCommits(_state.value.commitPage + 1) }
    fun closeCommit() = _state.update { it.copy(commitDetail = null) }

    private suspend fun loadActivityInternal() {
        loadCommits(1)
        loadPullRequestsInternal(1)
        _state.update { it.copy(activityUpdatedAt = System.currentTimeMillis(), activityLoaded = true) }
    }

    private suspend fun loadCommits(page: Int) {
        val result = api("list_commits", buildJsonObject { put("page", page) }).jsonObject
        val rows = result["items"]?.jsonArray.orEmpty().map { it.jsonObject }
        _state.update { it.copy(commits = if (page == 1) rows else (it.commits + rows).distinctBy { row -> row.text("sha") }, commitPage = page, moreCommits = result.flag("has_more")) }
    }

    fun inspectCommit(sha: String) = work {
        val result = api("get_commit_detail", buildJsonObject { put("ref", sha) }).jsonObject
        val detail = buildString {
            appendLine(result["commit"]?.jsonObject?.text("message"))
            appendLine("\n${result.text("sha")}\n${result["stats"]}")
            result["files"]?.jsonArray.orEmpty().forEach { row ->
                val file = row.jsonObject
                appendLine("\n${file.text("filename")} · ${file.text("status")}")
                appendLine(file.text("patch").ifBlank { "Patch unavailable. Open this commit on GitHub for the complete change." })
            }
            if (result.flag("files_truncated")) appendLine("\nMore changed files may be available on GitHub.")
        }
        _state.update { it.copy(commitDetail = detail) }
    }

    private suspend fun enrichPullRequests(rows: List<JsonObject>): List<JsonObject> {
        val summaries = java.util.concurrent.ConcurrentHashMap<String, String>()
        // Status checks are optional dashboard enrichment. Slow/missing permissions must
        // not hold the workspace indefinitely or make an unchecked PR appear green.
        kotlinx.coroutines.withTimeoutOrNull(15_000L) {
            kotlinx.coroutines.coroutineScope {
                val gate = kotlinx.coroutines.sync.Semaphore(4)
                rows.take(5).map { row ->
                    async {
                        gate.acquire()
                        try {
                            val sha = row["head"]?.jsonObject?.text("sha").orEmpty()
                            val summary = try {
                                if (sha.isBlank()) "UNKNOWN" else api("get_commit_status_summary", buildJsonObject { put("ref", sha) }).jsonObject.text("state")
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                "UNKNOWN"
                            }
                            summaries[row.text("number")] = summary
                        } finally {
                            gate.release()
                        }
                    }
                }.forEach { it.await() }
            }
        }
        return rows.map { row ->
            buildJsonObject {
                row.forEach { (key, value) -> put(key, value) }
                put("check_state", summaries[row.text("number")] ?: "UNKNOWN")
            }
        }
    }

    fun loadPullRequests() = work { loadPullRequestsInternal(1) }
    fun morePullRequests() = work { loadPullRequestsInternal(_state.value.pullPage + 1) }
    private suspend fun loadPullRequestsInternal(page: Int) {
        val response = api("list_open_pull_requests", buildJsonObject { put("page", page) }).jsonObject
        val result = response["items"]?.jsonArray.orEmpty().map { it.jsonObject }
        _state.update { it.copy(pullRequests = if (page == 1) result else (it.pullRequests + result).distinctBy { pr -> pr.text("number") }, pullPage = page, pullsLoaded = true, morePullRequests = response.flag("has_more"), pullDetail = null) }
    }

    fun inspectPullRequest(number: Int, page: Int = 1) = work {
        reviewPull(
            buildJsonObject {
                put("pull_number", number)
                put("page", page)
            }
        )
    }
    fun continuePullReview(checks: Boolean = false) = work {
        val packet = _state.value.pullPackage ?: return@work
        val continuation = packet[if (checks) "checks_continuation" else "files_continuation"] as? JsonObject ?: return@work
        reviewPull(continuation)
    }
    private suspend fun reviewPull(arguments: JsonObject) {
        val incoming = api("pr_context", JsonObject(arguments + ("include_patches" to JsonPrimitive(true)))).jsonObject
        val packet = retainGitHubPullReviewSections(_state.value.pullPackage, incoming)
        val pr = packet["pull_request"]?.jsonObject ?: error("Pull request is unavailable")
        val detail = buildString {
            appendLine("#${pr.text("number")} ${pr.text("title")}")
            appendLine("Checked head ${packet.text("expected_head")}")
            appendLine("Page ${packet.text("page")} · ${packet.text("files_returned")} of ${packet.text("files_total")} files")
            packet["files"]?.jsonArray.orEmpty().forEach { row ->
                val file = row.jsonObject
                appendLine("\n${file.text("filename")} · ${file.text("status")}")
                appendLine(file.text("patch").ifBlank { "Patch unavailable." })
                if (file.flag("patch_truncated")) appendLine("Partial patch; read the file at this head for complete evidence.")
            }
            appendLine("\nChecks: ${packet["checks"]}")
        }
        _state.update { it.copy(pullPackage = packet, pullDetail = detail) }
    }

    fun refreshVisibleChecks() = work {
        val rows = _state.value.pullRequests
        val enriched = enrichPullRequests(rows.take(5))
        _state.update { it.copy(pullRequests = enriched + rows.drop(5)) }
    }

    fun searchRevision(query: String, mode: String = "literal", continuation: Boolean = false) = work {
        val extra = if (continuation) _state.value.searchResult?.get("continuation") as? JsonObject else null
        val result = api(
            "search_repository",
            extra ?: buildJsonObject {
                put("query", query.trim())
                put("mode", mode)
                put("commit_sha", _state.value.headSha)
            }
        ).jsonObject
        _state.update { it.copy(searchResult = result) }
        val index = api("index_status", buildJsonObject { put("commit_sha", _state.value.headSha) }).jsonObject
        _state.update { it.copy(indexStatus = index) }
    }
    fun explainFailedCi() = work {
        val result = api("ci_failure_digest").jsonObject
        _state.update { it.copy(ciDigest = result) }
    }

    fun prepareCodeIndex() = work {
        val state = _state.value
        require(state.headSha.isNotBlank()) { "Select a repository snapshot first." }
        val current = state.indexStatus
        val next = if (current?.text("commit_sha") == state.headSha) current["next_index_cursor"]?.jsonPrimitive?.content else null
        val result = api(
            "build_index",
            buildJsonObject {
                put("commit_sha", state.headSha)
                next?.let { put("index_cursor", it) }
            }
        ).jsonObject
        _state.update {
            it.copy(
                indexStatus = result,
                notice = when {
                    result["next_index_cursor"] != null -> "Code context prepared in a bounded batch. Continue when useful; other files remain available on demand."
                    result.flag("preparation_finished") -> "Index preparation finished. Files not cached remain available on demand."
                    else -> "The repository tree is partial. Cached files are available, but complete index coverage could not be established."
                }
            )
        }
    }
    fun markReviewed() {
        _state.update { it.copy(reviewBaseline = it.headSha, changedFiles = null, notice = "Review baseline saved at ${it.headSha.take(8)}.") }
    }
    fun changesSinceReview(next: Boolean = false) = work {
        val state = _state.value
        require(state.reviewBaseline.isNotBlank()) { "Mark a revision reviewed first." }
        val arguments = if (next) state.changedFiles?.get("continuation") as? JsonObject else null
        val result = api(
            "changed_since",
            arguments ?: buildJsonObject {
                put("base", state.reviewBaseline)
                put("commit_sha", state.headSha)
            }
        ).jsonObject
        _state.update { it.copy(changedFiles = result) }
    }
    fun stageExactPatch(oldText: String, newText: String) = work {
        val state = _state.value
        require(state.canWrite && state.selection?.ref != state.defaultBranch && state.staged.isEmpty()) { "Prepare exact patches on an editable working branch without other staged files." }
        val file = requireNotNull(state.file)
        val staged = api(
            "stage_patch",
            buildJsonObject {
                put("commit_sha", state.headSha)
                put("expected_head_sha", state.headSha)
                put(
                    "edits",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("path", file.text("path"))
                                put("expected_blob_sha", file.text("sha"))
                                put("old_text", oldText)
                                put("new_text", newText)
                            }
                        )
                    }
                )
            }
        ).jsonObject
        _state.update { it.copy(patchStage = staged, notice = "Exact patch prepared. Review its preview in Changes before committing.") }
    }
    fun commitExactPatch(message: String) = work {
        val state = _state.value
        require(state.canWrite && message.isNotBlank())
        val stage = requireNotNull(state.patchStage)
        api(
            "commit_staged_patch",
            buildJsonObject {
                put("stage_id", stage.text("stage_id"))
                put("expected_head_sha", stage.text("expected_head_sha"))
                put("commit_sha", state.headSha)
                put("branch", requireNotNull(state.selection).ref)
                put("message", message)
            }
        )
        _state.update { it.copy(patchStage = null, notice = "Reviewed patch committed.") }
        loadHeadAndDirectory()
    }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
                val metrics = client?.execute("efficiency", buildJsonObject {}) as? JsonObject
                _state.update { it.copy(efficiency = metrics) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = error.message ?: "GitHub request failed.") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun arguments(extra: JsonObject): JsonObject = buildJsonObject {
        _state.value.selection?.let {
            put("owner", it.owner)
            put("repo", it.repo)
            put("ref", it.ref)
        }
        extra.forEach { (key, value) -> put(key, value) }
    }

    private suspend fun requireActiveConnection(mutation: Boolean) {
        val opened = requireNotNull(_state.value.connection) { "Reopen the GitHub workspace before continuing." }
        val current = connections.getConnection(opened.connectionUid) ?: error("This GitHub connection was removed. Reopen the workspace with an available connection.")
        check(current.type == ToolConnectionType.GITHUB && current.type == opened.type) { "This connection is no longer a GitHub connection. Reopen the workspace before continuing." }
        check(current.secretRef == opened.secretRef && current.authType == opened.authType && current.oauthClientId == opened.oauthClientId && current.endpointUrl == opened.endpointUrl) {
            "GitHub connection credentials or endpoint changed. Reopen the workspace before continuing."
        }
        if (mutation) {
            check(_state.value.canWrite && current.toolPolicy != "READ_ONLY") { "Current GitHub connection policy and repository write access are required for this action." }
        }
        val expected = credentialDigest ?: error("Reopen the GitHub workspace before continuing.")
        val bytes = current.secretRef?.let { vault.read(it) }
        val unchanged = try {
            bytes != null && MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(bytes))
        } finally {
            bytes?.fill(0)
        }
        check(unchanged) { "GitHub credentials changed or are unavailable. Reopen the workspace before continuing." }
    }

    private suspend fun api(action: String, extra: JsonObject = buildJsonObject {}): kotlinx.serialization.json.JsonElement {
        requireActiveConnection(mutation = action !in GitHubWorkspaceClient.readActions)
        return requireNotNull(client).execute(action, arguments(extra))
    }

    private suspend fun toolRequest(action: String, extra: JsonObject): String {
        requireActiveConnection(mutation = action !in GitHubWorkspaceClient.readActions)
        val result = requireNotNull(tool).execute(
            "workspace",
            buildJsonObject {
                arguments(extra).forEach { (key, value) -> put(key, value) }
                put("action", action)
            }
        )
        val text = when (val content = result.content) {
            is ToolResultContent.Text -> content.text
            is ToolResultContent.Json -> content.value.toString()
            else -> error("GitHub returned no structured result")
        }
        check(!result.isError) { text }
        return text
    }
}

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeUnless { it.toString() == "null" }?.content.orEmpty()
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
