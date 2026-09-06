package dev.melo.gptmobile.improved.presentation.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.data.model.ClientType
import dev.melo.gptmobile.improved.data.model.Platform
import dev.melo.gptmobile.improved.presentation.ui.localmodel.LocalModelDownloadDialogHost
import dev.melo.gptmobile.improved.presentation.ui.localmodel.rememberLocalModelDownloader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupPlatformListScreen(
    modifier: Modifier = Modifier,
    setupViewModel: SetupViewModelV2 = hiltViewModel(),
    onAddNewPlatform: () -> Unit,
    onStartChatting: () -> Unit,
    onNavigateToLocalModels: () -> Unit = {}
) {
    val platforms by setupViewModel.platforms.collectAsStateWithLifecycle()
    val activePlatformId by setupViewModel.activePlatformId.collectAsStateWithLifecycle()
    val catalogModels by setupViewModel.catalogLocalModels.collectAsStateWithLifecycle()
    val downloadState by setupViewModel.localModelDownloadState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var platformToDelete by remember { mutableStateOf<Platform?>(null) }
    val requestDownload = rememberLocalModelDownloader { entry ->
        setupViewModel.selectLocalModel(entry.id)
    }

    LaunchedEffect(Unit) {
        setupViewModel.messageFlow.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.platforms)) },
                actions = {
                    if (platforms.isNotEmpty()) {
                        IconButton(onClick = onStartChatting) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = stringResource(R.string.start_chatting)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddNewPlatform
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.add_platform)
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (platforms.isEmpty()) {
            EmptyPlatformsView(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                onAddPlatform = onAddNewPlatform
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.select_active_platform),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(platforms, key = { it.id }) { platform ->
                    val isActive = platform.id == activePlatformId
                    val isMissingLocalModel = platform.clientType == ClientType.LITERT_LM &&
                        platform.model.isNotBlank() &&
                        catalogModels.firstOrNull { it.entry.id == platform.model }?.installed != true

                    PlatformCard(
                        platform = platform,
                        isActive = isActive,
                        hasMissingLocalModel = isMissingLocalModel,
                        onSelect = {
                            if (isMissingLocalModel) {
                                val entry = catalogModels.firstOrNull { it.entry.id == platform.model }?.entry
                                if (entry != null) {
                                    requestDownload(entry)
                                } else {
                                    onNavigateToLocalModels()
                                }
                            } else {
                                setupViewModel.setActivePlatform(platform.id)
                            }
                        },
                        onDelete = { platformToDelete = platform }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp)) // Space for FAB
                }
            }
        }
    }

    // Delete Confirmation Dialog
    platformToDelete?.let { platform ->
        AlertDialog(
            onDismissRequest = { platformToDelete = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(text = stringResource(R.string.delete_platform))
            },
            text = {
                Text(text = stringResource(R.string.delete_platform_confirmation, platform.name))
            },
            confirmButton = {
                Button(
                    onClick = {
                        setupViewModel.deletePlatform(platform.id)
                        platformToDelete = null
                    }
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { platformToDelete = null }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    LocalModelDownloadDialogHost(
        dialog = downloadState.dialog,
        onConfirmRamWarning = setupViewModel::confirmRamWarning,
        onConfirmMeteredDownload = setupViewModel::confirmMeteredDownload,
        onDismissDialog = setupViewModel::dismissDownloadDialog,
        onStartSignIn = setupViewModel::startHuggingFaceSignIn,
        onAuthActivityResult = setupViewModel::onAuthActivityResult,
        onLicenseTabClosed = setupViewModel::onLicenseTabClosed,
        onRetryAfterLicense = setupViewModel::retryAfterLicense,
        onEnterAccessToken = setupViewModel::openAccessTokenDialog,
        onSaveAccessToken = setupViewModel::saveHuggingFaceAccessToken
    )
}

@Composable
private fun EmptyPlatformsView(
    modifier: Modifier = Modifier,
    onAddPlatform: () -> Unit
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Radio,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.no_platforms_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.no_platforms_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onAddPlatform) {
            Text(text = stringResource(R.string.add_platform))
        }
    }
}

@Composable
private fun PlatformCard(
    platform: Platform,
    isActive: Boolean,
    hasMissingLocalModel: Boolean = false,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Icon(
                imageVector = if (platform.clientType == ClientType.LITERT_LM) {
                    Icons.Default.PhoneAndroid
                } else {
                    Icons.Default.Radio
                },
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.size(16.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = platform.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isActive) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = stringResource(R.string.active),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${platform.clientType.name} • ${platform.model}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (hasMissingLocalModel) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.local_model_requires_download),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (hasMissingLocalModel) {
                IconButton(onClick = onSelect) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = stringResource(R.string.download_local_model),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else if (isActive) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.active),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // More Options Menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more_options)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}
