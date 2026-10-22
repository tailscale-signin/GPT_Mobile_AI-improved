package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.github.GitHubApkBuild
import dev.chungjungsoo.gptmobile.data.github.GitHubApkDownloadManager
import dev.chungjungsoo.gptmobile.data.github.GitHubApkDownloadState
import dev.chungjungsoo.gptmobile.data.github.GitHubApkDownloadStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubBuildCatalog
import dev.chungjungsoo.gptmobile.data.github.GitHubBuildSource
import dev.chungjungsoo.gptmobile.data.github.GitHubDownloadedApk
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GitHubBuildListState(
    val builds: List<GitHubApkBuild> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val page: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
    val refreshedAt: Long? = null
)

data class GitHubBuildsState(
    val connectionUid: String = "",
    val owner: String = "",
    val repo: String = "",
    val branch: String = "",
    val source: GitHubBuildSource = GitHubBuildSource.RELEASE,
    val releases: GitHubBuildListState = GitHubBuildListState(),
    val workflows: GitHubBuildListState = GitHubBuildListState(),
    val actionError: String? = null,
    val savedFileName: String? = null,
    val savingFiles: Set<String> = emptySet(),
    val removingDownloads: Set<String> = emptySet()
) {
    fun list(source: GitHubBuildSource = this.source): GitHubBuildListState =
        if (source ==
            GitHubBuildSource.RELEASE
        ) {
            releases
        } else {
            workflows
        }
}

@HiltViewModel
class GitHubBuildsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val connections: ToolConnectionRepository,
    private val vault: SecretVault,
    private val downloadManager: GitHubApkDownloadManager
) : ViewModel() {
    private val mutableState = MutableStateFlow(GitHubBuildsState())
    val state = mutableState.asStateFlow()
    val downloads = downloadManager.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val loads = mutableMapOf<GitHubBuildSource, Job>()
    private var generation = 0L

    fun bind(
        connectionUid: String,
        owner: String,
        repo: String,
        branch: String
    ) {
        val previous = mutableState.value
        val sameRepository = previous.connectionUid == connectionUid && previous.owner == owner && previous.repo == repo
        if (sameRepository && previous.branch == branch) return
        generation++
        loads.values.forEach { it.cancel() }
        loads.clear()
        mutableState.value =
            if (sameRepository) {
                previous.copy(
                    branch = branch,
                    releases = previous.releases.copy(loading = false),
                    workflows = GitHubBuildListState(),
                    actionError = null,
                    savedFileName = null
                )
            } else {
                GitHubBuildsState(connectionUid = connectionUid, owner = owner, repo = repo, branch = branch, source = previous.source, savingFiles = previous.savingFiles, removingDownloads = previous.removingDownloads)
            }
        if (!mutableState.value.list().loaded) load(mutableState.value.source)
    }

    fun selectSource(source: GitHubBuildSource) {
        mutableState.update { it.copy(source = source) }
        if (!mutableState.value.list(source).loaded) load(source)
    }

    fun refresh() = load(mutableState.value.source)

    fun loadMore() {
        val state = mutableState.value
        if (state.list().hasMore) load(state.source, state.list().page + 1)
    }

    private fun load(
        source: GitHubBuildSource,
        page: Int = 1
    ) {
        val destination = mutableState.value
        if (destination.connectionUid.isBlank() ||
            destination.owner.isBlank() ||
            destination.repo.isBlank() ||
            destination.list(source).loading
        ) {
            return
        }
        val requestGeneration = generation
        mutableState.update { it.withList(source, it.list(source).copy(loading = true, error = null)) }
        loads[source] =
            viewModelScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val connection = requireNotNull(connections.getConnection(destination.connectionUid)) { context.getString(R.string.github_builds_connection_missing) }
                        require(connection.type == ToolConnectionType.GITHUB) { context.getString(R.string.github_builds_connection_missing) }
                        val bytes = connection.secretRef?.let { vault.read(it) }
                        val token = try {
                            bytes?.decodeToString()?.trim().orEmpty()
                        } finally {
                            bytes?.fill(0)
                        }
                        require(token.isNotBlank()) { context.getString(R.string.github_builds_reconnect) }
                        val catalog = GitHubBuildCatalog(token)
                        if (source == GitHubBuildSource.RELEASE) {
                            catalog.releases(destination.owner, destination.repo, page)
                        } else {
                            catalog.workflowBuilds(destination.owner, destination.repo, destination.branch, page)
                        }
                    }
                    if (generation == requestGeneration) {
                        mutableState.update { current ->
                            val previous = current.list(source)
                            current.withList(
                                source,
                                GitHubBuildListState(
                                    builds =
                                    (
                                        (if (page == 1) emptyList() else previous.builds) +
                                            result.builds
                                        ).distinctBy { it.id }.sortedWith(compareByDescending<GitHubApkBuild> { it.createdAt }.thenBy { it.fileName }),
                                    loaded = true,
                                    page = page,
                                    hasMore = result.hasMore,
                                    refreshedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (generation ==
                        requestGeneration
                    ) {
                        mutableState.update { it.withList(source, it.list(source).copy(error = error.message.orEmpty().take(1500))) }
                    }
                } finally {
                    if (generation ==
                        requestGeneration
                    ) {
                        mutableState.update { it.withList(source, it.list(source).copy(loading = false)) }
                    }
                }
            }
    }

    fun download(build: GitHubApkBuild) =
        action {
            val destination = mutableState.value
            require(build.owner.equals(destination.owner, ignoreCase = true) && build.repo.equals(destination.repo, ignoreCase = true))
            downloadManager.enqueue(destination.connectionUid, build)
        }

    fun retry(downloadKey: String) = action { downloadManager.retry(downloadKey) }

    fun cancel(downloadKey: String) = action { downloadManager.cancel(downloadKey) }

    fun remove(download: GitHubApkDownloadState) {
        val state = mutableState.value
        if (download.status in setOf(GitHubApkDownloadStatus.QUEUED, GitHubApkDownloadStatus.RUNNING) ||
            download.downloadKey in state.removingDownloads ||
            download.files.any { it.uri.toString() in state.savingFiles }
        ) {
            return
        }
        mutableState.update { it.copy(removingDownloads = it.removingDownloads + download.downloadKey, actionError = null, savedFileName = null) }
        viewModelScope.launch {
            try {
                downloadManager.remove(download.downloadKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
            } finally {
                mutableState.update { it.copy(removingDownloads = it.removingDownloads - download.downloadKey) }
            }
        }
    }

    fun openDownloads(context: Context) = action { downloadManager.openDownloads(context) }

    fun share(
        context: Context,
        apk: GitHubDownloadedApk
    ) = action { downloadManager.share(context, apk) }

    fun save(apk: GitHubDownloadedApk) {
        val key = apk.uri.toString()
        if (key in mutableState.value.savingFiles) return
        if (downloads.value.any { it.downloadKey in mutableState.value.removingDownloads && it.files.any { file -> file.uri == apk.uri } }) return
        mutableState.update { it.copy(savingFiles = it.savingFiles + key, actionError = null, savedFileName = null) }
        viewModelScope.launch {
            try {
                downloadManager.saveToDownloads(apk)
                mutableState.update { it.copy(savedFileName = apk.fileName) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
            } finally {
                mutableState.update { it.copy(savingFiles = it.savingFiles - key) }
            }
        }
    }

    fun dismissNotice() = mutableState.update { it.copy(actionError = null, savedFileName = null) }

    fun reportError(error: Exception) = mutableState.update { it.copy(actionError = error.message.orEmpty().take(1500)) }

    private fun action(block: () -> Unit) {
        mutableState.update { it.copy(actionError = null, savedFileName = null) }
        try {
            block()
        } catch (error: Exception) {
            reportError(error)
        }
    }
}

private fun GitHubBuildsState.withList(
    source: GitHubBuildSource,
    list: GitHubBuildListState
): GitHubBuildsState =
    if (source ==
        GitHubBuildSource.RELEASE
    ) {
        copy(releases = list)
    } else {
        copy(workflows = list)
    }
