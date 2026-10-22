package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.github.GitHubApkBuild
import dev.chungjungsoo.gptmobile.data.github.GitHubApkDownloadState
import dev.chungjungsoo.gptmobile.data.github.GitHubApkDownloadStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubBuildSource
import dev.chungjungsoo.gptmobile.data.github.GitHubDownloadedApk
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun GitHubBuildsPanel(
    connectionUid: String,
    owner: String,
    repo: String,
    branch: String,
    modifier: Modifier = Modifier,
    model: GitHubBuildsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(connectionUid, owner, repo, branch) { model.bind(connectionUid, owner, repo, branch) }
    LaunchedEffect(connectionUid, owner, repo, branch, state.source) { listState.scrollToItem(0) }
    LaunchedEffect(state.savedFileName) { if (state.savedFileName != null) listState.animateScrollToItem(0) }
    val currentList = state.list()
    val builds = preferredGitHubBuildOrder(currentList.builds)
    val scopedDownloads =
        downloads.filter {
            it.connectionUid == connectionUid &&
                it.owner.equals(
                    owner,
                    true
                ) &&
                it.repo.equals(repo, true)
        }
    val downloadsByBuild = scopedDownloads.associateBy { it.buildId }
    val buildIds = builds.mapTo(mutableSetOf()) { it.id }
    val otherDownloads = scopedDownloads.filter { it.buildId !in buildIds }
    val latestRelease = builds.firstOrNull { it.source == GitHubBuildSource.RELEASE && !it.prerelease }?.releaseTag

    LazyColumn(modifier = modifier, state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.github_builds_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.github_builds_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = model::refresh, enabled = !currentList.loading) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.github_builds_refresh))
                }
            }
        }
        currentList.refreshedAt?.let { refreshedAt ->
            item("metadata-freshness") {
                Text("Metadata checked ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(refreshedAt))} · completed build metadata is reused", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.source == GitHubBuildSource.RELEASE, onClick = {
                    model.selectSource(GitHubBuildSource.RELEASE)
                }, label = { Text(stringResource(R.string.github_builds_releases)) })
                FilterChip(selected = state.source == GitHubBuildSource.WORKFLOW, onClick = {
                    model.selectSource(GitHubBuildSource.WORKFLOW)
                }, label = { Text(stringResource(R.string.github_builds_workflows)) })
            }
            Text(
                if (state.source ==
                    GitHubBuildSource.RELEASE
                ) {
                    stringResource(R.string.github_builds_release_description)
                } else {
                    stringResource(R.string.github_builds_branch_description, branch)
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                stringResource(R.string.github_builds_read_access),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (state.actionError != null || state.savedFileName != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color =
                    if (state.actionError !=
                        null
                    ) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            state.actionError?.ifBlank { null }
                                ?: if (state.actionError !=
                                    null
                                ) {
                                    stringResource(R.string.github_builds_action_error)
                                } else {
                                    stringResource(R.string.github_builds_saved, state.savedFileName.orEmpty())
                                },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (state.actionError == null && state.savedFileName != null) {
                            Text(stringResource(R.string.github_builds_saved_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                            Button(onClick = { model.openDownloads(context) }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.github_builds_open_downloads)) }
                        }
                        TextButton(onClick = model::dismissNotice) { Text(stringResource(R.string.github_builds_dismiss)) }
                    }
                }
            }
        }
        currentList.error?.let { error ->
            item {
                GitHubBuildMessage(
                    stringResource(R.string.github_builds_load_error),
                    error.takeIf(String::isNotBlank),
                    error = true,
                    onRetry = model::refresh
                )
            }
        }
        if (currentList.loading) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.github_builds_loading), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (currentList.loaded && builds.isEmpty() && currentList.error == null) {
            item {
                GitHubBuildMessage(
                    title =
                    stringResource(
                        if (state.source ==
                            GitHubBuildSource.RELEASE
                        ) {
                            R.string.github_builds_empty_releases
                        } else {
                            R.string.github_builds_empty_workflows
                        }
                    ),
                    detail =
                    stringResource(
                        if (state.source ==
                            GitHubBuildSource.RELEASE
                        ) {
                            R.string.github_builds_empty_releases_detail
                        } else {
                            R.string.github_builds_empty_workflows_detail
                        }
                    )
                )
            }
        }
        items(builds, key = { it.id }) { build ->
            GitHubBuildCard(build, downloadsByBuild[build.id], latestRelease != null && build.releaseTag == latestRelease, state, model)
        }
        if (currentList.hasMore) {
            item {
                OutlinedButton(onClick = model::loadMore, enabled = !currentList.loading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.github_builds_load_more))
                }
            }
        }
        if (otherDownloads.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.github_builds_other_downloads),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(otherDownloads, key = { "download:${it.downloadKey}" }) { download ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(download.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(
                                if (download.source ==
                                    GitHubBuildSource.RELEASE
                                ) {
                                    R.string.github_builds_releases
                                } else {
                                    R.string.github_builds_workflows
                                }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        download.releaseTag?.takeIf { it.isNotBlank() && it != download.title }?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        download.branch?.takeIf(String::isNotBlank)?.let { branch ->
                            Text(if (download.commitSha.isNullOrBlank()) branch else stringResource(R.string.github_builds_branch_commit, branch, download.commitSha.take(8)), style = MaterialTheme.typography.labelSmall)
                        }
                        download.createdAt?.takeIf(String::isNotBlank)?.let { Text(githubBuildDate(it), style = MaterialTheme.typography.labelSmall) }
                        GitHubDownloadBody(download, state, model)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GitHubBuildCard(
    build: GitHubApkBuild,
    download: GitHubApkDownloadState?,
    latestRelease: Boolean,
    state: GitHubBuildsState,
    model: GitHubBuildsViewModel
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val date = githubBuildDate(build.createdAt)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors =
        CardDefaults.cardColors(
            containerColor = if (latestRelease) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Android, null, Modifier.padding(top = 2.dp, end = 10.dp).size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        build.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    build.releaseTag?.takeIf { it.isNotBlank() && it != build.title }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                IconButton(onClick = {
                    try {
                        uriHandler.openUri(build.detailsUrl)
                    } catch (error: Exception) {
                        model.reportError(error)
                    }
                }) { Icon(Icons.Rounded.Launch, stringResource(R.string.github_builds_open_details)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (latestRelease) GitHubBuildBadge(stringResource(R.string.github_builds_latest_release))
                if (build.prerelease) GitHubBuildBadge(stringResource(R.string.github_builds_prerelease))
                if (build.source == GitHubBuildSource.RELEASE) GitHubApkVariantBadge(build.fileName)
            }
            Text(build.fileName, style = MaterialTheme.typography.bodyMedium)
            val size = Formatter.formatShortFileSize(context, build.sizeBytes)
            Text(
                if (date.isBlank()) size else stringResource(R.string.github_builds_metadata, size, date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            build.branch?.takeIf(String::isNotBlank)?.let { branch ->
                Text(
                    if (build.commitSha.isNullOrBlank()) {
                        branch
                    } else {
                        stringResource(
                            R.string.github_builds_branch_commit,
                            branch,
                            build.commitSha.take(8)
                        )
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (download != null) {
                GitHubDownloadBody(download, state, model, allowRetry = !build.expired)
                if (build.expired && download.status != GitHubApkDownloadStatus.SUCCEEDED) {
                    Text(stringResource(R.string.github_builds_expired), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (build.expired) {
                Text(
                    stringResource(R.string.github_builds_expired),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Button(onClick = { model.download(build) }) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    Text(
                        stringResource(
                            if (build.source ==
                                GitHubBuildSource.RELEASE
                            ) {
                                R.string.github_builds_download
                            } else {
                                R.string.github_builds_download_workflow
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun GitHubDownloadBody(
    download: GitHubApkDownloadState,
    state: GitHubBuildsState,
    model: GitHubBuildsViewModel,
    allowRetry: Boolean = true
) {
    val context = LocalContext.current
    val removing = download.downloadKey in state.removingDownloads
    when (download.status) {
        GitHubApkDownloadStatus.QUEUED, GitHubApkDownloadStatus.RUNNING -> {
            val total = download.totalBytes?.takeIf { it > 0 }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(
                        when {
                            download.status == GitHubApkDownloadStatus.QUEUED -> R.string.github_builds_queued
                            total != null && download.bytesDownloaded >= total -> R.string.github_builds_preparing
                            else -> R.string.github_builds_downloading
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (total == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { (download.bytesDownloaded.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                val received = Formatter.formatShortFileSize(context, download.bytesDownloaded)
                Text(
                    if (total ==
                        null
                    ) {
                        received
                    } else {
                        stringResource(R.string.github_builds_progress, received, Formatter.formatShortFileSize(context, total))
                    },
                    style = MaterialTheme.typography.labelSmall
                )
                TextButton(onClick = { model.cancel(download.downloadKey) }) { Text(stringResource(R.string.github_builds_cancel)) }
            }
        }
        GitHubApkDownloadStatus.SUCCEEDED -> {
            if (download.files.isEmpty()) {
                Text(stringResource(R.string.github_builds_download_missing), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.retry(download.downloadKey) }, enabled = !removing && allowRetry) { Text(stringResource(R.string.github_builds_download_again)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.github_builds_download_ready), style = MaterialTheme.typography.labelMedium)
                }
                download.files.sortedBy { githubApkVariantRank(it.fileName) }.forEach { apk ->
                    GitHubDownloadedFile(
                        apk,
                        apk.uri.toString() in state.savingFiles,
                        removing,
                        model
                    )
                }
            }
        }
        GitHubApkDownloadStatus.FAILED, GitHubApkDownloadStatus.CANCELLED -> {
            Text(
                stringResource(
                    if (download.status ==
                        GitHubApkDownloadStatus.FAILED
                    ) {
                        R.string.github_builds_download_failed
                    } else {
                        R.string.github_builds_download_cancelled
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color =
                if (download.status ==
                    GitHubApkDownloadStatus.FAILED
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            download.error?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { model.retry(download.downloadKey) }, enabled = !removing && allowRetry) { Text(stringResource(R.string.github_builds_retry)) }
        }
    }
    if (download.status !in setOf(GitHubApkDownloadStatus.QUEUED, GitHubApkDownloadStatus.RUNNING)) {
        TextButton(onClick = { model.remove(download) }, enabled = !removing && download.files.none { it.uri.toString() in state.savingFiles }) {
            Text(stringResource(if (removing) R.string.github_builds_removing else R.string.github_builds_remove_download))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GitHubDownloadedFile(
    apk: GitHubDownloadedApk,
    saving: Boolean,
    removing: Boolean,
    model: GitHubBuildsViewModel
) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(apk.fileName, style = MaterialTheme.typography.titleSmall)
            GitHubApkVariantBadge(apk.fileName)
            Text(
                Formatter.formatShortFileSize(context, apk.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { model.save(apk) }, enabled = !saving && !removing) {
                    if (saving) CircularProgressIndicator(Modifier.padding(end = 6.dp).size(14.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Text(stringResource(if (saving) R.string.github_builds_saving else R.string.github_builds_save_apk))
                }
                TextButton(onClick = { model.share(context, apk) }, enabled = !removing) {
                    Icon(Icons.Rounded.Share, null, Modifier.padding(end = 6.dp).size(18.dp))
                    Text(stringResource(R.string.github_builds_share_apk))
                }
            }
        }
    }
}

@Composable
private fun GitHubApkVariantBadge(fileName: String) {
    when (githubApkVariantRank(fileName)) {
        0 -> GitHubBuildBadge(stringResource(R.string.github_builds_device_variant))
        1 -> GitHubBuildBadge(stringResource(R.string.github_builds_universal))
    }
}

@Composable
private fun GitHubBuildBadge(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun GitHubBuildMessage(
    title: String,
    detail: String? = null,
    error: Boolean = false,
    onRetry: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            onRetry?.let { TextButton(onClick = it) { Text(stringResource(R.string.github_builds_retry)) } }
        }
    }
}

internal fun githubApkVariantRank(
    fileName: String,
    deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList()
): Int {
    val name = fileName.lowercase(Locale.ROOT)
    val abi = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").firstOrNull { it in name }
    return when {
        abi != null && abi == deviceAbis.firstOrNull() -> 0
        "universal" in name -> 1
        else -> 2
    }
}

private fun preferredGitHubBuildOrder(builds: List<GitHubApkBuild>): List<GitHubApkBuild> {
    fun group(build: GitHubApkBuild) = if (build.source == GitHubBuildSource.RELEASE) build.releaseTag ?: build.title else build.id
    val groups = builds.groupBy(::group)
    val latestStable = builds.firstOrNull { it.source == GitHubBuildSource.RELEASE && !it.prerelease }?.let(::group)
    return groups.entries
        .sortedBy {
            if (it.key ==
                latestStable
            ) {
                0
            } else {
                1
            }
        }.flatMap { (_, entries) -> entries.sortedBy { githubApkVariantRank(it.fileName) } }
}

private fun githubBuildDate(value: String): String =
    runCatching {
        DateTimeFormatter
            .ofLocalizedDate(
                FormatStyle.MEDIUM
            ).withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(value))
    }.getOrElse { value.take(10) }
