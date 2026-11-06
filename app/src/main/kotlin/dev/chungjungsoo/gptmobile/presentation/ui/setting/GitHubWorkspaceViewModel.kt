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
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
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
    val uiReady: Boolean = false,
    val authRefresh: Boolean = false,
    val repositoryRefresh: Boolean = false,
    val composerReadiness: dev.chungjungsoo.gptmobile.data.github.GitHubComposerReadiness = dev.chungjungsoo.gptmobile.data.github.GitHubComposerReadiness.NOT_READY,
    val graphReadiness: dev.chungjungsoo.gptmobile.data.github.GitHubOptionalReadiness = dev.chungjungsoo.gptmobile.data.github.GitHubOptionalReadiness.NOT_REQUESTED,
    val backgroundJobState: dev.chungjungsoo.gptmobile.data.github.GitHubBackgroundJobState = dev.chungjungsoo.gptmobile.data.github.GitHubBackgroundJobState.IDLE,
    val sessionEpoch: Long = 0,
    val repositoryId: String? = null,
    val cachedProfileUid: String? = null,
    val recentChatId: Int? = null,
    val error: String? = null,
    val notice: String? = null,
    val appAuthorization: Boolean = false,
    val installations: List<JsonObject> = emptyList(),
    val installationId: String? = null,
    val installationPage: Int = 1,
    val moreInstallations: Boolean = false,
    val recoveryRepository: String? = null,
    val repositories: List<JsonObject> = emptyList(),
    val repositoryPage: Int = 1,
    val moreRepositories: Boolean = false,
    val repositoryChoicesLoaded: Boolean = false,
    val setupRequired: Boolean = false,
    val selection: GitHubRepositoryContext? = null,
    val defaultBranch: String = "",
    val canWrite: Boolean = false,
    val branches: List<String> = emptyList(),
    val branchPage: Int = 1,
    val moreBranches: Boolean = false,
    val branchesLoaded: Boolean = false,
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
    private val gitHubPublicAuth: dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator? = null,
    private val bootstrapper: dev.chungjungsoo.gptmobile.data.github.GitHubSessionBootstrapper? = null
) : ViewModel() {
    private val _state = MutableStateFlow(GitHubWorkspaceState())
    val state = _state.asStateFlow()
    private var client: GitHubWorkspaceClient? = null
    private var tool: GitHubTool? = null
    private var credentialDigest: ByteArray? = null
    private var authorizationIdentity: String? = null
    private val sessionEpoch = dev.chungjungsoo.gptmobile.data.github.GitHubSessionEpoch()
    private val workEpoch = ThreadLocal<Long?>()
    private var refreshJob: Job? = null

    private fun currentSession(): Boolean = workEpoch.get()?.let(sessionEpoch::accepts) != false
    private fun checkSession() {
        if (!currentSession()) throw CancellationException("GitHub workspace session changed")
    }
    private inline fun updateState(update: (GitHubWorkspaceState) -> GitHubWorkspaceState) {
        _state.update { if (currentSession()) update(it) else it }
    }
    private fun supersedeRefresh(): Long {
        refreshJob?.cancel()
        refreshJob = null
        return sessionEpoch.next()
    }

    @kotlinx.serialization.Serializable
    private data class Draft(val selection: GitHubRepositoryContext?, val baseSha: String, val directory: String, val file: JsonObject?, val staged: List<GitHubStagedFile>, val edits: Map<String, String>, val reviewBaseline: String = "", val patchStage: JsonObject? = null) {
        val dirty: Boolean get() = staged.isNotEmpty() || edits.isNotEmpty() || patchStage != null
    }

    @Volatile private var pendingRecovery: Draft? = null

    @Volatile private var draftRestorationPending = false

    private val draftWrites = kotlinx.coroutines.sync.Mutex()
    private val draftSequence = java.util.concurrent.atomic.AtomicLong()
    private val persistedDraftSequence = mutableMapOf<String, Long>()
    private data class PendingDraft(val sequence: Long, val connection: String, val value: Draft)
    private fun draft(state: GitHubWorkspaceState): Pair<String, Draft>? = state.connection?.connectionUid?.let { connection ->
        state.selection?.let { connection to Draft(it, state.headSha, state.directory, state.file, state.staged, state.editorDrafts, state.reviewBaseline, state.patchStage) }
    }
    private suspend fun persist(pending: PendingDraft) = persistDraft(pending, automatic = false)

    private suspend fun persistDraft(pending: PendingDraft, automatic: Boolean) = draftWrites.withLock {
        // A debounced snapshot may predate a restore or repository refresh. Recheck
        // ownership at the write boundary, including an unreadable encrypted draft.
        if (draftRestorationPending || automatic && (_state.value.busy || pendingRecovery != null)) return@withLock
        val recovery = pendingRecovery
        if (recovery != null && (pending.value.selection != recovery.selection || pending.value.baseSha != recovery.baseSha)) return@withLock
        val current = draft(_state.value)
        if (current == null || current.first != pending.connection || current.second != pending.value) return@withLock
        if (pending.sequence <= (persistedDraftSequence[pending.connection] ?: -1)) return@withLock
        drafts.write(pending.connection, Json.encodeToString(Draft.serializer(), pending.value))
        persistedDraftSequence[pending.connection] = pending.sequence
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeDrafts() = viewModelScope.launch {
        _state.map { if (it.busy || draftRestorationPending || pendingRecovery != null) null else draft(it) }.filterNotNull().distinctUntilChanged()
            .map { PendingDraft(draftSequence.incrementAndGet(), it.first, it.second) }.debounce(400).collect { pending ->
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { persistDraft(pending, automatic = true) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    updateState { it.copy(error = "Workspace edits could not be saved for recovery. Keep this screen open and retry.") }
                }
            }
    }
    init {
        observeDrafts()
    }

    fun editDraft(path: String, content: String, original: String) {
        updateState { state -> state.copy(editorDrafts = if (content == (state.staged.firstOrNull { it.path == path }?.content ?: original)) state.editorDrafts - path else state.editorDrafts + (path to content)) }
    }

    fun open(connection: ToolConnection, initialContext: dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext? = null) {
        if (_state.value.busy) return
        val epoch = supersedeRefresh()
        client = null
        tool = null
        credentialDigest = null
        authorizationIdentity = null
        draftRestorationPending = true
        pendingRecovery = null
        val requestedContext = initialContext?.takeIf { it.connectionUid == connection.connectionUid }
        _state.value = GitHubWorkspaceState(
            connection = connection,
            selection = requestedContext?.repository,
            headSha = requestedContext?.commitSha.orEmpty(),
            sessionEpoch = epoch
        )
        refreshWork(auth = true) {
            // Restore the local scope before any network or credential refresh. Cached
            // context is display-only until the transport revalidates the repository.
            val recovered = draftWrites.withLock { drafts.read(connection.connectionUid)?.let { Json.decodeFromString(Draft.serializer(), it) } }
            checkSession()
            val cached = bootstrapper?.read(connection.connectionUid)
            checkSession()
            pendingRecovery = recovered
            draftRestorationPending = false
            val requested = requestedContext?.repository
            require(requested == null || recovered?.dirty != true || recovered.selection == requested) {
                "Saved edits belong to another branch. Open GitHub mode from the main menu to save or discard them before opening this chat."
            }
            val previousSelection = requested ?: recovered?.selection ?: cached?.repository ?: store.get(connection.connectionUid)
            val cachedDraft = recovered?.takeIf { it.selection == previousSelection }
            updateState {
                it.copy(
                    selection = previousSelection,
                    headSha = requestedContext?.commitSha ?: cachedDraft?.baseSha ?: cached?.observedHeadSha.takeIf { cached?.repository == previousSelection }.orEmpty(),
                    observedAt = requestedContext?.observedAtMillis ?: cached?.headObservedAtMillis,
                    uiReady = true,
                    login = cached?.accountLogin.orEmpty(),
                    repositoryId = cached?.repositoryId.takeIf { cached?.repository == previousSelection },
                    defaultBranch = cached?.defaultBranch.takeIf { cached?.repository == previousSelection }.orEmpty(),
                    cachedProfileUid = requestedContext?.mainProfileUid ?: cached?.profileUid,
                    recentChatId = cached?.recentChatId.takeIf { cached?.repository == previousSelection },
                    composerReadiness = dev.chungjungsoo.gptmobile.data.github.GitHubComposerReadiness.REFRESH_REQUIRED,
                    setupRequired = previousSelection == null,
                    directory = cachedDraft?.directory.orEmpty(),
                    file = cachedDraft?.file,
                    staged = cachedDraft?.staged.orEmpty(),
                    editorDrafts = cachedDraft?.edits.orEmpty(),
                    reviewBaseline = cachedDraft?.reviewBaseline.orEmpty(),
                    patchStage = cachedDraft?.patchStage,
                    recoveryRepository = recovered?.takeIf { saved -> saved.dirty }?.selection?.fullName
                )
            }
            val current = connections.getConnection(connection.connectionUid) ?: error("Connection was removed.")
            require(current.type == ToolConnectionType.GITHUB) { "This connection is no longer a GitHub connection. Reopen GitHub mode with a GitHub connection." }
            val appAuthorization = current.authType == "OAUTH" && gitHubPublicAuth?.connectedAccount(current.connectionUid) != null
            updateState { it.copy(connection = current, appAuthorization = appAuthorization) }
            refreshTransport(current)
            updateState { it.copy(authRefresh = false, repositoryRefresh = true) }
            if (previousSelection != null) {
                if (appAuthorization) {
                    // Permission metadata is required before enabling writes, but the
                    // repository and branch inventories are not startup dependencies.
                    loadInstallations(1)
                    val installation = _state.value.installations.firstOrNull {
                        it["account"]?.jsonObject?.text("login").equals(previousSelection.owner, true) &&
                            (it["suspended_at"] == null || it["suspended_at"] == kotlinx.serialization.json.JsonNull)
                    }
                    updateState { it.copy(installationId = installation?.text("id")) }
                }
                // A saved repository can be revalidated directly. Account inventories,
                // branch menus, commit history and indexing are loaded only when opened.
                selectRepositoryInternal(previousSelection.owner, previousSelection.repo, previousSelection.ref, restoring = true)
            } else {
                loadRepositoryChoices()
            }
        }
    }

    fun completeSetup() {
        val current = _state.value
        if (current.busy || current.composerReadiness != dev.chungjungsoo.gptmobile.data.github.GitHubComposerReadiness.READY || current.selection == null || current.headSha.isBlank()) return
        work {
            current.connection?.let { store.set(it.connectionUid, current.selection) }
            updateState { it.copy(setupRequired = false) }
        }
    }

    private suspend fun loadRepositoryChoices() {
        val account = api("get_account").jsonObject
        updateState { it.copy(login = account.text("login")) }
        if (_state.value.appAuthorization) {
            loadInstallations(1)
            val current = _state.value
            val installation = current.installations.firstOrNull { it.text("id") == current.installationId }
                ?: current.installations.firstOrNull { it["account"]?.jsonObject?.text("login").equals(current.selection?.owner, true) }
                ?: current.installations.singleOrNull()
            val id = installation?.takeIf { it["suspended_at"] == null || it["suspended_at"] == kotlinx.serialization.json.JsonNull }?.text("id")
            updateState { it.copy(installationId = id) }
        }
        loadRepositories(1)
        updateState { it.copy(repositoryChoicesLoaded = true) }
    }

    fun close(onSaved: () -> Unit = {}) {
        if (_state.value.busy) return
        supersedeRefresh()
        // A failed restore may leave another branch's draft protected on disk.
        // Never replace that draft with the unrelated conversation's cached scope.
        val recovery = pendingRecovery
        val pending = draft(_state.value)?.takeIf { recovery == null || it.second.selection == recovery.selection && it.second.baseSha == recovery.baseSha }
            ?.let { PendingDraft(draftSequence.incrementAndGet(), it.first, it.second) }
        updateState { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                if (pending != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { persist(pending) }
                client = null
                tool = null
                credentialDigest = null
                authorizationIdentity = null
                _state.value = GitHubWorkspaceState()
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateState { it.copy(busy = false, error = "The latest draft could not be saved. Keep the workspace open and retry.") }
            }
        }
    }

    fun clearError() = updateState { it.copy(error = null) }
    fun unstage(path: String) {
        if (_state.value.busy) return
        updateState { it.copy(staged = it.staged.filterNot { file -> file.path == path }) }
    }
    fun discard() = updateState { it.copy(staged = emptyList(), editorDrafts = emptyMap(), patchStage = null) }
    fun discardSavedRecovery() = work {
        if (pendingRecovery?.dirty != true || _state.value.recoveryRepository == null) return@work
        val connection = requireNotNull(_state.value.connection)
        draftWrites.withLock { drafts.write(connection.connectionUid, Json.encodeToString(Draft.serializer(), Draft(null, "", "", null, emptyList(), emptyMap()))) }
        pendingRecovery = null
        updateState { it.copy(recoveryRepository = null, notice = "Saved edits discarded.") }
    }
    fun moreRepositories() = work { loadRepositories(_state.value.repositoryPage + 1) }

    fun moreInstallations() = work { loadInstallations(_state.value.installationPage + 1) }

    private suspend fun loadInstallations(page: Int) {
        val result = api("list_installations", buildJsonObject { put("page", page) }).jsonObject
        val items = result["items"]?.jsonArray.orEmpty().map { it.jsonObject }
        updateState { it.copy(installations = if (page == 1) items else (it.installations + items).distinctBy { installation -> installation.text("id") }, installationPage = page, moreInstallations = result.flag("has_more")) }
    }

    fun selectInstallation(id: String) = work {
        require(_state.value.staged.isEmpty() && _state.value.editorDrafts.isEmpty() && _state.value.patchStage == null) { "Commit or discard edits before switching installations." }
        selectInstallationInternal(id)
    }

    private suspend fun selectInstallationInternal(id: String) {
        val current = _state.value
        val installation = current.installations.singleOrNull { it.text("id") == id }
        require(current.appAuthorization && installation != null && (installation["suspended_at"] == null || installation["suspended_at"] == kotlinx.serialization.json.JsonNull)) { "Choose an active installation for the connected GitHub App." }
        _state.value = GitHubWorkspaceState(connection = current.connection, login = current.login, busy = true, appAuthorization = true, installations = current.installations, installationId = id, installationPage = current.installationPage, moreInstallations = current.moreInstallations, recoveryRepository = current.recoveryRepository, setupRequired = current.setupRequired, repositoryChoicesLoaded = true)
        loadRepositories(1)
    }

    private suspend fun loadRepositories(page: Int) {
        val installation = _state.value.installationId
        if (_state.value.appAuthorization && installation == null) return
        val result = api(
            if (_state.value.appAuthorization) "list_installation_repositories" else "list_repositories",
            buildJsonObject {
                put("page", page)
                installation?.let { put("installation_id", it) }
            }
        ).jsonObject
        val items = result["items"]?.jsonArray.orEmpty().map { it.jsonObject }
        updateState { it.copy(repositories = if (page == 1) items else (it.repositories + items).distinctBy { repo -> repo.text("full_name") }, repositoryPage = page, moreRepositories = result.flag("has_more")) }
    }

    fun selectRepository(owner: String, repo: String) = work {
        require(_state.value.staged.isEmpty() && _state.value.editorDrafts.isEmpty() && _state.value.patchStage == null) { "Commit or discard staged edits before switching repositories." }
        selectRepositoryInternal(owner, repo)
    }

    private suspend fun selectRepositoryInternal(owner: String, repo: String, preferredRef: String? = null, restoring: Boolean = false) {
        val current = _state.value
        val recovery = pendingRecovery
        require(recovery?.dirty != true || recovery.selection?.fullName.equals("$owner/$repo", true)) { "Restore ${recovery?.selection?.fullName} or explicitly discard its saved edits before choosing another repository." }
        require(restoring && current.selection?.fullName.equals("$owner/$repo", true) || !current.appAuthorization || current.installationId != null && current.repositories.any { it.text("full_name").equals("$owner/$repo", true) }) { "Choose a repository accessible through the selected GitHub App installation." }
        val metadata = api(
            "get_repository",
            buildJsonObject {
                put("owner", owner)
                put("repo", repo)
            }
        ).jsonObject
        val default = metadata.text("default_branch")
        require(default.isNotBlank()) { "Repository has no default branch." }
        val recoveredRef = recovery?.selection?.takeIf { it.fullName.equals("$owner/$repo", true) }?.ref
        val selected = GitHubRepositoryContext(owner, repo, preferredRef ?: recoveredRef ?: default)
        val appCanWrite = !current.appAuthorization || current.installations.firstOrNull { it.text("id") == current.installationId }?.get("permissions")?.jsonObject?.text("contents") == "write"
        val canWrite = appCanWrite && metadata["permissions"]?.jsonObject?.flag("push") == true && !metadata.flag("archived") && current.connection?.toolPolicy != "READ_ONLY"
        updateState { it.copy(selection = selected, repositoryId = metadata.text("id").takeIf(String::isNotBlank), defaultBranch = default, canWrite = canWrite, branches = emptyList(), branchesLoaded = false, file = null, staged = emptyList(), editorDrafts = emptyMap(), pullRequests = emptyList(), pullsLoaded = false, pullPage = 1, morePullRequests = false, commits = emptyList(), commitDetail = null, activityUpdatedAt = null, pullDetail = null, directory = "", headSha = if (restoring) current.headSha else "", entries = emptyList(), snapshot = null, indexStatus = null, searchResult = null, ciDigest = null, patchStage = null, changedFiles = null, reviewBaseline = "", activityLoaded = false, directoryLoaded = false, pullPackage = null) }
        var refreshed = false
        try {
            loadHeadAndDirectory()
            refreshed = true
        } finally {
            recovery?.takeIf { currentSession() && it.selection?.fullName.equals(selected.fullName, true) && it.selection?.ref == selected.ref }?.let { draft ->
                val changed = refreshed && draft.dirty && _state.value.headSha != draft.baseSha
                updateState { currentState ->
                    currentState.copy(
                        headSha = if (draft.dirty) draft.baseSha else currentState.headSha,
                        directory = draft.directory,
                        file = draft.file.takeIf { draft.dirty || currentState.headSha == draft.baseSha },
                        directoryLoaded = draft.file != null && (draft.dirty || currentState.headSha == draft.baseSha),
                        staged = draft.staged,
                        editorDrafts = draft.edits,
                        reviewBaseline = draft.reviewBaseline,
                        patchStage = draft.patchStage,
                        recoveryRepository = null,
                        notice = when {
                            !refreshed -> "Saved edits are preserved. Reconnect to refresh this branch."
                            changed -> "Edits recovered at their original base. The branch moved; review before committing."
                            else -> "Workspace edits recovered."
                        }
                    )
                }
            }
            // Keep the saved draft protected if transport failed before resolving the head.
            if (currentSession()) {
                if (refreshed) pendingRecovery = null else updateState { it.copy(canWrite = false) }
            }
        }
    }

    fun moreBranches() = work { loadBranches(_state.value.branchPage + 1) }
    private suspend fun loadBranches(page: Int) {
        val result = api("list_branches", buildJsonObject { put("page", page) }).jsonObject
        val branches = result["items"]?.jsonArray.orEmpty().map { it.jsonObject.text("name") }
        updateState { it.copy(branches = if (page == 1) branches else (it.branches + branches).distinct(), branchPage = page, moreBranches = result.flag("has_more"), branchesLoaded = true) }
    }

    fun selectBranch(branch: String, programmerChatId: Int? = null) = work {
        require(_state.value.staged.isEmpty() && _state.value.editorDrafts.isEmpty() && _state.value.patchStage == null) { "Commit or discard staged edits before switching branches." }
        updateState { it.copy(selection = it.selection?.copy(ref = branch), directory = "", file = null, entries = emptyList(), headSha = "", commits = emptyList(), commitDetail = null, activityUpdatedAt = null, snapshot = null, indexStatus = null, searchResult = null, ciDigest = null, changedFiles = null, activityLoaded = false, directoryLoaded = false, pullPackage = null) }
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

    fun acceptPublishedCommit(sha: String) {
        require(sha.matches(Regex("[a-fA-F0-9]{40}")))
        updateState { it.copy(headSha = sha, snapshot = null, commits = emptyList(), observedAt = System.currentTimeMillis(), activityLoaded = false, directoryLoaded = false, pullsLoaded = false, ciDigest = null) }
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
        check(sha.matches(Regex("[a-fA-F0-9]{40}"))) { "Missing or invalid branch head." }
        // Publish the resolved head immediately. Optional commit history belongs to
        // Status and must not block opening the conversation.
        updateState { it.copy(headSha = sha, uiReady = true, composerReadiness = dev.chungjungsoo.gptmobile.data.github.GitHubComposerReadiness.READY, file = null, snapshot = result["snapshot"] as? JsonObject, indexStatus = result["index"] as? JsonObject, observedAt = System.currentTimeMillis(), directoryLoaded = false, activityLoaded = false, pullsLoaded = false, searchResult = null, ciDigest = null, changedFiles = null, pullPackage = null, pullDetail = null) }
        checkSession()
        val saved = _state.value
        saved.connection?.let { connection ->
            bootstrapper?.rememberRepository(
                dev.chungjungsoo.gptmobile.data.github.GitHubSessionSnapshot(
                    connection.connectionUid, authorizationIdentity, saved.login, saved.repositoryId, selected,
                    saved.defaultBranch, sha, saved.observedAt, hasRecoverableDraft = saved.staged.isNotEmpty() || saved.editorDrafts.isNotEmpty() || pendingRecovery?.dirty == true
                )
            )
        }
    }

    fun onSectionVisible(section: Int) {
        if (_state.value.busy || _state.value.error != null || _state.value.selection == null || _state.value.headSha.isBlank()) return
        when {
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
        updateState { it.copy(directory = path, directoryLoaded = true, directoryPage = page, moreEntries = result.flag("has_more"), entries = ((if (page == 1) emptyList() else it.entries) + result["entries"]?.jsonArray.orEmpty().map { entry -> entry.jsonObject }).distinctBy { entry -> entry.text("path") }.sortedWith(compareBy<JsonObject> { entry -> entry.text("type") != "dir" }.thenBy { entry -> entry.text("name") }), file = null, notice = if (result.flag("directory_limit_reached")) "GitHub limited this directory to 1,000 entries." else it.notice) }
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
        updateState { it.copy(file = JsonObject(file + ("evidence_id" to result.getValue("evidence_id"))), directory = path.substringBeforeLast('/', ""), directoryLoaded = true) }
    }

    fun moreFileRange() = work {
        val previous = _state.value.file ?: return@work
        val continuation = previous["continuation"] as? JsonObject ?: return@work
        val result = api("read_ranges", continuation).jsonObject
        val file = result["items"]?.jsonArray?.firstOrNull()?.jsonObject ?: error("File range is unavailable.")
        updateState { it.copy(file = JsonObject(file + ("evidence_id" to result.getValue("evidence_id")))) }
    }

    fun stage(path: String, original: String, content: String): Boolean {
        if (_state.value.file?.flag("complete_file") != true || _state.value.file?.text("path") != path || _state.value.patchStage != null) {
            updateState { it.copy(error = "Only a complete file may be replaced. Use an exact patch for a source excerpt.") }
            return false
        }
        if (content.toByteArray().size > 256_000) {
            updateState { it.copy(error = "File exceeds the text budget.") }
            return false
        }
        var accepted = false
        updateState { state ->
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

    fun useInChats() = work {
        val state = _state.value
        state.connection?.let { store.set(it.connectionUid, state.selection) }
        updateState { it.copy(notice = "${it.selection?.fullName} @ ${it.selection?.ref} is now the default for this GitHub connection in chats.") }
    }

    fun clearChatContext() = work {
        _state.value.connection?.let { store.set(it.connectionUid, null) }
        updateState { it.copy(notice = "Default GitHub repository context cleared.") }
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
        updateState { it.copy(selection = it.selection?.copy(ref = name.trim()), notice = "Working branch created.", directory = "") }
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
        updateState { it.copy(staged = emptyList(), editorDrafts = emptyMap(), headSha = result.text("sha"), file = null, notice = "Committed ${state.staged.size} file(s) to ${state.selection?.ref}.") }
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
        updateState { it.copy(notice = "Draft pull request created: $url") }
        loadPullRequestsInternal(1)
    }

    fun refreshActivity() = work { loadActivityInternal() }
    fun moreCommits() = work { loadCommits(_state.value.commitPage + 1) }
    fun closeCommit() = updateState { it.copy(commitDetail = null) }

    private suspend fun loadActivityInternal() {
        loadCommits(1)
        loadPullRequestsInternal(1)
        updateState { it.copy(activityUpdatedAt = System.currentTimeMillis(), activityLoaded = true) }
    }

    private suspend fun loadCommits(page: Int) {
        val result = api("list_commits", buildJsonObject { put("page", page) }).jsonObject
        val rows = result["items"]?.jsonArray.orEmpty().map { it.jsonObject }
        updateState { it.copy(commits = if (page == 1) rows else (it.commits + rows).distinctBy { row -> row.text("sha") }, commitPage = page, moreCommits = result.flag("has_more")) }
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
        updateState { it.copy(commitDetail = detail) }
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
        updateState { it.copy(pullRequests = if (page == 1) result else (it.pullRequests + result).distinctBy { pr -> pr.text("number") }, pullPage = page, pullsLoaded = true, morePullRequests = response.flag("has_more"), pullDetail = null) }
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
        updateState { it.copy(pullPackage = packet, pullDetail = detail) }
    }

    fun refreshVisibleChecks() = work {
        val rows = _state.value.pullRequests
        val enriched = enrichPullRequests(rows.take(5))
        updateState { it.copy(pullRequests = enriched + rows.drop(5)) }
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
        updateState { it.copy(searchResult = result) }
        val index = api("index_status", buildJsonObject { put("commit_sha", _state.value.headSha) }).jsonObject
        updateState { it.copy(indexStatus = index) }
    }
    fun explainFailedCi() = work {
        val result = api("ci_failure_digest").jsonObject
        updateState { it.copy(ciDigest = result) }
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
        updateState {
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
        updateState { it.copy(reviewBaseline = it.headSha, changedFiles = null, notice = "Review baseline saved at ${it.headSha.take(8)}.") }
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
        updateState { it.copy(changedFiles = result) }
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
        updateState { it.copy(patchStage = staged, notice = "Exact patch prepared. Review its preview in Changes before committing.") }
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
        updateState { it.copy(patchStage = null, notice = "Reviewed patch committed.") }
        loadHeadAndDirectory()
    }

    /** Optional display refreshes never hold the composer or conversation shell hostage. */
    private fun refreshWork(auth: Boolean = false, block: suspend () -> Unit) {
        if (_state.value.busy || refreshJob?.isActive == true) return
        val epoch = sessionEpoch.current()
        updateState { it.copy(authRefresh = auth, repositoryRefresh = !auth, backgroundJobState = dev.chungjungsoo.gptmobile.data.github.GitHubBackgroundJobState.REFRESHING, error = null) }
        refreshJob = viewModelScope.launch(workEpoch.asContextElement(epoch)) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updateState {
                    it.copy(
                        error = failure.message ?: "GitHub refresh failed.",
                        canWrite = if (auth) false else it.canWrite,
                        composerReadiness = if (auth) dev.chungjungsoo.gptmobile.data.github.GitHubComposerReadiness.UNAVAILABLE else it.composerReadiness
                    )
                }
            } finally {
                updateState { it.copy(authRefresh = false, repositoryRefresh = false, backgroundJobState = dev.chungjungsoo.gptmobile.data.github.GitHubBackgroundJobState.IDLE) }
            }
        }
    }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        val epoch = supersedeRefresh()
        updateState { it.copy(busy = true, authRefresh = false, repositoryRefresh = false, sessionEpoch = epoch, backgroundJobState = dev.chungjungsoo.gptmobile.data.github.GitHubBackgroundJobState.WRITING, error = null) }
        viewModelScope.launch(workEpoch.asContextElement(epoch)) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updateState { it.copy(error = failure.message ?: "GitHub request failed.") }
            } finally {
                updateState { it.copy(busy = false, backgroundJobState = dev.chungjungsoo.gptmobile.data.github.GitHubBackgroundJobState.IDLE) }
            }
        }
    }

    fun onRepositoryPickerOpened() {
        if (!_state.value.repositoryChoicesLoaded) refreshWork { loadRepositoryChoices() }
    }

    fun onBranchPickerOpened() {
        if (!_state.value.branchesLoaded && _state.value.selection != null) refreshWork { loadBranches(1) }
    }

    fun refreshOptionalMetrics() = refreshWork {
        val metrics = api("efficiency") as? JsonObject
        updateState { it.copy(efficiency = metrics) }
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
        checkSession()
        val opened = requireNotNull(_state.value.connection) { "Reopen the GitHub workspace before continuing." }
        val current = connections.getConnection(opened.connectionUid) ?: error("This GitHub connection was removed. Reopen the workspace with an available connection.")
        check(current.type == ToolConnectionType.GITHUB && current.type == opened.type) { "This connection is no longer a GitHub connection. Reopen the workspace before continuing." }
        check(current.authType == opened.authType && current.oauthClientId == opened.oauthClientId && current.endpointUrl == opened.endpointUrl) {
            "GitHub account configuration changed. Reopen the workspace before continuing."
        }
        if (mutation) {
            check(_state.value.canWrite && current.toolPolicy != "READ_ONLY") { "Current GitHub connection policy and repository write access are required for this action." }
        }
        refreshTransport(current)
    }

    private suspend fun refreshTransport(current: ToolConnection) {
        val token = if (gitHubPublicAuth != null) {
            gitHubPublicAuth.accessToken(current)
        } else {
            val bytes = current.secretRef?.let { vault.read(it) }
            try {
                bytes?.decodeToString()?.trim().orEmpty()
            } finally {
                bytes?.fill(0)
            }
        }
        require(token.isNotBlank()) { "Reconnect GitHub before continuing." }
        checkSession()
        val digest = MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray())
        val identity = gitHubPublicAuth?.durableIdentity(current.connectionUid) ?: dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(token)
        checkSession()
        check(authorizationIdentity == null || authorizationIdentity == identity) { "GitHub account changed. Reopen the workspace to review the new account; staged edits remain retained." }
        authorizationIdentity = identity
        if (client != null && credentialDigest?.let { MessageDigest.isEqual(it, digest) } == true && _state.value.connection?.toolPolicy == current.toolPolicy && _state.value.connection?.approvedReadTools == current.approvedReadTools) return
        val features = settings.getFeatureSettings()
        val legacy = gitHubPublicAuth?.legacyCredentialIdentities(current.connectionUid).orEmpty()
        checkSession()
        val provider: (suspend () -> String)? = gitHubPublicAuth?.let { auth ->
            {
                val live = requireNotNull(connections.getConnection(current.connectionUid)) { "GitHub connection removed" }
                check(live.type == current.type && live.authType == current.authType && live.endpointUrl == current.endpointUrl && live.toolPolicy == current.toolPolicy && live.approvedReadTools == current.approvedReadTools) { "GitHub authorization changed" }
                val fresh = auth.accessToken(live)
                check(auth.durableIdentity(live.connectionUid) == identity) { "GitHub account changed" }
                fresh
            }
        }
        client = GitHubWorkspaceClient(token, conditionalReads = features.githubConditionalReads, blobCache = features.githubBlobCache, freshnessSeconds = features.pluginExecution[dev.chungjungsoo.gptmobile.data.model.ToolPluginId.GITHUB]?.githubCacheSeconds ?: 15, connectionUid = current.connectionUid, conversationId = "workspace", taskId = "workspace:${current.connectionUid}", stateStore = repositoryState, requestPriority = 10, durableIdentity = identity, legacyCredentialIdentities = legacy, credentialProvider = provider, apiOrigins = dev.chungjungsoo.gptmobile.data.github.GitHubApiOrigins.fromEndpoint(current.endpointUrl))
        tool = GitHubTool(token, featureSettings = features, connectionUid = current.connectionUid, conversationId = "workspace", taskId = "workspace:${current.connectionUid}", stateStore = repositoryState, durableIdentity = identity, legacyCredentialIdentities = legacy, credentialProvider = provider, apiOrigins = dev.chungjungsoo.gptmobile.data.github.GitHubApiOrigins.fromEndpoint(current.endpointUrl))
        credentialDigest = digest
        updateState { it.copy(connection = current) }
    }

    private suspend fun api(action: String, extra: JsonObject = buildJsonObject {}): kotlinx.serialization.json.JsonElement {
        requireActiveConnection(mutation = action !in GitHubWorkspaceClient.readActions)
        checkSession()
        val result = requireNotNull(client).execute(action, arguments(extra))
        checkSession()
        return result
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
        checkSession()
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
