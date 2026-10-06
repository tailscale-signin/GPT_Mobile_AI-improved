package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.ClipData
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Launch
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import dev.chungjungsoo.gptmobile.presentation.common.FadingDialog as Dialog
import dev.chungjungsoo.gptmobile.presentation.common.FadingDropdownMenu as DropdownMenu
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubWorkspaceScreen(connection: ToolConnection, onDismiss: () -> Unit, viewModel: GitHubWorkspaceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var repositoryMenu by remember { mutableStateOf(false) }
    var branchMenu by remember { mutableStateOf(false) }
    var repositoryQuery by remember { mutableStateOf("") }
    var fileQuery by remember(state.selection?.fullName, state.directory) { mutableStateOf("") }
    var branchName by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var prTitle by rememberSaveable { mutableStateOf("") }
    var prBody by rememberSaveable { mutableStateOf("") }
    var createPr by remember { mutableStateOf(false) }
    var confirmCommit by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var editorDirty by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(connection.connectionUid) { viewModel.open(connection) }
    fun copy(value: String) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("GitHub workspace", value))) }
    }
    fun close() {
        if (state.busy) return
        if (state.staged.isNotEmpty() || editorDirty) {
            confirmClose = true
        } else {
            viewModel.close()
            onDismiss()
        }
    }
    val canNavigate = !state.busy && !editorDirty && state.staged.isEmpty()
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("GitHub Workspace")
                            Text(if (state.login.isBlank()) connection.name else "@${state.login}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { close() }, enabled = !state.busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close workspace") } },
                    actions = {
                        state.selection?.let { repo ->
                            IconButton(onClick = { uriHandler.openUri("https://github.com/${repo.fullName}") }) { Icon(Icons.Rounded.Launch, "Open repository on GitHub") }
                            IconButton(onClick = viewModel::refreshBranch, enabled = canNavigate) { Icon(Icons.Rounded.Refresh, "Refresh branch") }
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { repositoryMenu = true }, enabled = canNavigate, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.FolderOpen, null, Modifier.padding(end = 8.dp))
                                Text(state.selection?.fullName ?: "Choose a repository", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (state.selection != null) WorkspaceBadge(if (state.canWrite) "Can edit" else "Read only")
                            DropdownMenu(expanded = repositoryMenu, onDismissRequest = { repositoryMenu = false }) {
                                OutlinedTextField(repositoryQuery, { repositoryQuery = it }, label = { Text("Find repository") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, modifier = Modifier.padding(12.dp))
                                state.repositories.filter { it.text("full_name").contains(repositoryQuery, true) }.forEach { repo ->
                                    DropdownMenuItem(text = { Text(repo.text("full_name")) }, leadingIcon = { Icon(Icons.Rounded.Folder, null) }, onClick = {
                                        repositoryMenu = false
                                        viewModel.selectRepository(repo["owner"]!!.jsonObject.text("login"), repo.text("name"))
                                    })
                                }
                                if (state.moreRepositories) DropdownMenuItem(text = { Text("Load more repositories") }, enabled = !state.busy, onClick = viewModel::moreRepositories)
                            }
                        }
                        if (state.selection != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { branchMenu = true }, enabled = canNavigate) {
                                    Icon(Icons.Rounded.AccountTree, null, Modifier.size(18.dp).padding(end = 4.dp))
                                    Text(state.selection?.ref.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(expanded = branchMenu, onDismissRequest = { branchMenu = false }) {
                                    state.branches.forEach { branch ->
                                        DropdownMenuItem(text = { Text(branch) }, leadingIcon = { Icon(Icons.Rounded.AccountTree, null) }, onClick = {
                                            branchMenu = false
                                            viewModel.selectBranch(branch)
                                        })
                                    }
                                    if (state.moreBranches) DropdownMenuItem(text = { Text("Load more branches") }, enabled = !state.busy, onClick = viewModel::moreBranches)
                                }
                                if (state.headSha.isNotBlank()) WorkspaceBadge(state.headSha.take(8))
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = viewModel::useInChats, enabled = !state.busy && state.headSha.isNotBlank()) {
                                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp).padding(end = 4.dp))
                                    Text("Use in chats")
                                }
                                TextButton(onClick = viewModel::clearChatContext, enabled = !state.busy) { Text("Clear chat context") }
                            }
                        }
                        state.error?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                        }
                        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.heightIn(max = 72.dp).verticalScroll(rememberScrollState())) }
                    }
                }
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf("Code" to Icons.Rounded.Code, "Changes ${state.staged.size}" to Icons.Rounded.Commit, "Pull requests" to Icons.Rounded.AccountTree).forEachIndexed { index, (label, icon) ->
                        Tab(selected = tab == index, enabled = !editorDirty || index == 0, onClick = {
                            tab = index
                            if (index == 2 && state.selection != null) viewModel.loadPullRequests()
                        }, text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(icon, null, Modifier.size(18.dp))
                                Text(label, maxLines = 1)
                            }
                        })
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.selection == null) {
                        WorkspaceEmpty(Icons.Rounded.FolderOpen, "Your code, within reach", "Choose a repository above to browse files, prepare changes and review pull requests.")
                    } else {
                        when (tab) {
                            0 -> {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { viewModel.browse("") }, enabled = !state.busy && !editorDirty) {
                                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                                        Text("Root")
                                    }
                                    val segments = state.directory.split('/').filter(String::isNotBlank)
                                    segments.forEachIndexed { index, segment ->
                                        Text("/", color = MaterialTheme.colorScheme.outline)
                                        TextButton(onClick = { viewModel.browse(segments.take(index + 1).joinToString("/")) }, enabled = !state.busy && !editorDirty) { Text(segment) }
                                    }
                                }
                                if (state.file == null) {
                                    OutlinedTextField(fileQuery, { fileQuery = it }, singleLine = true, label = { Text("Filter loaded files") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.fillMaxWidth())
                                    val entries = state.entries.filter { it.text("name").contains(fileQuery, true) }.sortedWith(compareBy({ it.text("type") != "dir" }, { it.text("name").lowercase() }))
                                    if (entries.isEmpty() && !state.busy) WorkspaceEmpty(Icons.Rounded.Search, if (fileQuery.isBlank()) "This folder is empty" else "No matching files", if (state.moreEntries) "Load more files to keep looking." else "Try another folder or search.")
                                    entries.forEach { entry ->
                                        Surface(onClick = { if (entry.text("type") == "dir") viewModel.browse(entry.text("path")) else viewModel.readFile(entry.text("path")) }, enabled = !state.busy, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Icon(if (entry.text("type") == "dir") Icons.Rounded.Folder else Icons.Rounded.Description, null, Modifier.size(22.dp))
                                                Text(entry.text("name"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                if (state.staged.any { it.path == entry.text("path") }) WorkspaceBadge("Staged")
                                            }
                                        }
                                    }
                                    if (state.moreEntries) TextButton(onClick = viewModel::moreFiles, enabled = !state.busy) { Text("Load more files") }
                                } else {
                                    val file = requireNotNull(state.file)
                                    val original = file.text("content")
                                    val path = file.text("path")
                                    var edited by rememberSaveable(path, state.headSha) { mutableStateOf(state.staged.firstOrNull { it.path == path }?.content ?: original) }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(path.substringAfterLast('/'), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        IconButton(onClick = { copy(path) }) { Icon(Icons.Rounded.ContentCopy, "Copy file path") }
                                        file.text("html_url").takeIf { it.startsWith("https://github.com/") }?.let { url -> IconButton(onClick = { uriHandler.openUri(url) }) { Icon(Icons.Rounded.Launch, "Open file on GitHub") } }
                                    }
                                    Text("${original.lines().size} lines · ${original.toByteArray().size} bytes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { viewModel.browse(state.directory) }, enabled = !state.busy && !editorDirty) { Text("Back to files") }
                                    if (file.flag("has_more")) {
                                        Text("Preview of the first 2,000 lines. Open the complete file on GitHub to edit it.", style = MaterialTheme.typography.bodySmall)
                                        WorkspaceCode(original)
                                    } else {
                                        OutlinedTextField(edited, {
                                            if (it.toByteArray().size <= 256_000) {
                                                edited = it
                                                editorDirty = edited != (state.staged.firstOrNull { staged -> staged.path == path }?.content ?: original)
                                            }
                                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 480.dp), label = { Text("File content") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), readOnly = !state.canWrite || state.busy || state.selection?.ref == state.defaultBranch)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { if (viewModel.stage(path, original, edited)) editorDirty = false }, enabled = state.canWrite && !state.busy && state.selection?.ref != state.defaultBranch && editorDirty) {
                                                Icon(Icons.Rounded.Add, null, Modifier.padding(end = 6.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                                Text("Stage")
                                            }
                                            TextButton(onClick = {
                                                edited = state.staged.firstOrNull { it.path == path }?.content ?: original
                                                editorDirty = false
                                            }, enabled = !state.busy && editorDirty) { Text("Reset editor") }
                                        }
                                        if (state.selection?.ref == state.defaultBranch) Text("Create a working branch in Changes to start editing.", style = MaterialTheme.typography.bodySmall)
                                        if (editorDirty) Text("Stage or reset your edits before leaving this file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            1 -> {
                                Text("Prepare your changes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                if (state.selection?.ref == state.defaultBranch) {
                                    OutlinedTextField(branchName, { branchName = it }, label = { Text("New working branch") }, leadingIcon = { Icon(Icons.Rounded.AccountTree, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Button(onClick = { viewModel.createBranch(branchName) }, enabled = state.canWrite && canNavigate && branchName.isNotBlank()) { Text("Create branch") }
                                }
                                if (state.staged.isEmpty()) WorkspaceEmpty(Icons.Rounded.Commit, "No staged changes", "Edit a file in Code, then stage it here for review.")
                                state.staged.forEach { file ->
                                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Rounded.Description, null, Modifier.size(20.dp).padding(end = 6.dp))
                                                Text(file.path, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                                IconButton(onClick = { viewModel.unstage(file.path) }, enabled = !state.busy && !editorDirty) { Icon(Icons.Rounded.Close, "Unstage ${file.path}") }
                                            }
                                            Text("${file.original.lines().size} → ${file.content.lines().size} lines", style = MaterialTheme.typography.labelSmall)
                                            Text("Before", style = MaterialTheme.typography.labelMedium)
                                            WorkspaceCode(file.original)
                                            Text("After", style = MaterialTheme.typography.labelMedium)
                                            WorkspaceCode(file.content)
                                        }
                                    }
                                }
                                if (state.staged.isNotEmpty()) {
                                    OutlinedTextField(message, { message = it.take(4000) }, label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth())
                                    Button(onClick = { confirmCommit = true }, enabled = state.canWrite && !state.busy && !editorDirty && message.isNotBlank()) {
                                        Icon(Icons.Rounded.Commit, null, Modifier.padding(end = 8.dp), tint = MaterialTheme.colorScheme.onPrimary)
                                        Text("Commit ${state.staged.size} files")
                                    }
                                    TextButton(onClick = viewModel::discard, enabled = !state.busy && !editorDirty) { Text("Discard staged changes") }
                                }
                            }
                            2 -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Pull requests", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    IconButton(onClick = viewModel::loadPullRequests, enabled = !state.busy) { Icon(Icons.Rounded.Refresh, "Refresh pull requests") }
                                    IconButton(onClick = { createPr = !createPr }, enabled = state.canWrite) { Icon(if (createPr) Icons.Rounded.Close else Icons.Rounded.Add, "Create draft pull request") }
                                }
                                if (createPr) {
                                    OutlinedTextField(prTitle, { prTitle = it }, label = { Text("Pull request title") }, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(prBody, { prBody = it }, label = { Text("Description & validation") }, modifier = Modifier.fillMaxWidth())
                                    Text("Target branch: ${state.defaultBranch}", style = MaterialTheme.typography.labelMedium)
                                    Button(onClick = { viewModel.createPullRequest(prTitle, prBody) }, enabled = state.canWrite && canNavigate && state.selection?.ref != state.defaultBranch && prTitle.isNotBlank()) { Text("Create draft pull request") }
                                }
                                if (state.pullRequests.isEmpty() && !state.busy) WorkspaceEmpty(Icons.Rounded.AccountTree, "No pull requests loaded", "Refresh to see the repository's open requests, or create a draft from your working branch.")
                                state.pullRequests.forEach { pr ->
                                    Surface(onClick = { viewModel.inspectPullRequest(pr.text("number").toInt()) }, enabled = !state.busy, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Icon(Icons.Rounded.AccountTree, null, Modifier.size(22.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(pr.text("title"), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                Text("#${pr.text("number")} · ${if (pr.flag("draft")) "Draft" else pr.text("state")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            pr.text("html_url").takeIf { it.startsWith("https://github.com/") }?.let { url -> IconButton(onClick = { uriHandler.openUri(url) }) { Icon(Icons.Rounded.Launch, "Open pull request") } }
                                        }
                                    }
                                }
                                if (state.morePullRequests) TextButton(onClick = viewModel::morePullRequests, enabled = !state.busy) { Text("Load more pull requests") }
                                state.pullDetail?.let { WorkspaceCode(it) }
                            }
                        }
                    }
                }
            }
        }
        if (confirmCommit) {
            AlertDialog(
                onDismissRequest = { confirmCommit = false },
                title = { Text("Commit reviewed changes?") },
                text = { Text("${state.staged.size} files → ${state.selection?.fullName} @ ${state.selection?.ref}\n\n$message") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmCommit = false
                        viewModel.commit(message)
                    }) { Text("Commit") }
                },
                dismissButton = { TextButton(onClick = { confirmCommit = false }) { Text("Cancel") } }
            )
        }
        if (confirmClose) {
            AlertDialog(
                onDismissRequest = { confirmClose = false },
                title = { Text("Discard uncommitted changes?") },
                text = { Text("Uncommitted editor changes and ${state.staged.size} staged files will be discarded.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.close()
                        onDismiss()
                    }) { Text("Discard and close") }
                },
                dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep editing") } }
            )
        }
    }
}

@Composable
private fun WorkspaceBadge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun WorkspaceEmpty(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkspaceCode(content: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        SelectionContainer {
            Text(content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp))
        }
    }
}
