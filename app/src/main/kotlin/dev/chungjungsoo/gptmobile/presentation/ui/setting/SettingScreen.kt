package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.presentation.common.RadioItem
import dev.chungjungsoo.gptmobile.presentation.common.Route
import dev.chungjungsoo.gptmobile.presentation.common.SettingItem
import dev.chungjungsoo.gptmobile.presentation.common.ThemeViewModel

@Composable
fun ModelBadge(isLocal: Boolean) {
    val backgroundColor = if (isLocal) Color(0x33448AFF) else Color(0x33FF5252)
    val textColor = if (isLocal) Color(0xFF82B1FF) else Color(0xFFFF8A80)
    val text = if (isLocal) stringResource(R.string.local) else stringResource(R.string.remote)

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    modifier: Modifier = Modifier,
    onNavigateTo: (Route) -> Unit = {},
    onBack: () -> Unit = {},
    themeViewModel: ThemeViewModel,
    settingViewModel: SettingViewModel = hiltViewModel()
) {
    val platformState by settingViewModel.platforms.collectAsState()
    val backupState by settingViewModel.backupState.collectAsState()
    val currentTheme by themeViewModel.themeSetting.collectAsState()
    val context = LocalContext.current

    var showPasswordDialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var isRestoreMode by remember { mutableStateOf(false) }
    var selectedRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showThemeDialog by remember { mutableStateOf(false) }

    // Launcher for creating a backup file
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            settingViewModel.backupDatabase(it, password)
            password = ""
        }
    }

    // Launcher for opening a backup file to restore
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedRestoreUri = it
            isRestoreMode = true
            showPasswordDialog = true
        }
    }

    LaunchedEffect(backupState) {
        when (backupState) {
            is BackupState.Success -> {
                val message = (backupState as BackupState.Success).message
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                settingViewModel.resetBackupState()
            }
            is BackupState.Error -> {
                val message = (backupState as BackupState.Error).message
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                settingViewModel.resetBackupState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.arrow_icon)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // AI Models Category
            Text(
                stringResource(R.string.ai_models),
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            // Add Platform (pinned at top)
            SettingItem(
                title = stringResource(R.string.add_platform),
                description = stringResource(R.string.add_platform_description),
                onItemClick = { onNavigateTo(Route.ADD_PLATFORM) },
                showTrailingIcon = true,
                showLeadingIcon = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_platform),
                        modifier = Modifier.size(24.dp)
                    )
                }
            )

            // Local Models (pinned directly below Add Platform)
            SettingItem(
                title = stringResource(R.string.local_models),
                onItemClick = { onNavigateTo(Route.LOCAL_MODELS) },
                showTrailingIcon = true,
                showLeadingIcon = true,
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_model),
                        contentDescription = stringResource(R.string.local_models),
                        modifier = Modifier.size(24.dp)
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Dynamically listed added platforms
            platformState.forEach { platform ->
                val isLocal = platform.compatibleType == ClientType.LITERT_LM
                SettingItem(
                    title = platform.name,
                    description = platform.models.map { it.name }.joinToString(", "),
                    onItemClick = {
                        onNavigateTo(Route.EditPlatform(platform.id))
                    },
                    showTrailingIcon = true,
                    showLeadingIcon = true,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = platform.icon),
                            contentDescription = platform.name,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    trailingBadge = {
                        ModelBadge(isLocal = isLocal)
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Tools Category
            Text(
                stringResource(R.string.tool_connections),
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            SettingItem(
                title = stringResource(R.string.tool_connections),
                onItemClick = { onNavigateTo(Route.TOOL_CONNECTIONS) },
                showTrailingIcon = true,
                showLeadingIcon = true,
                leadingIcon = {
                    Icon(
                        ImageVector.vectorResource(id = R.drawable.ic_extension),
                        contentDescription = stringResource(R.string.tool_connections),
                        modifier = Modifier.size(24.dp)
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Backup & Restore Category
            Text(
                stringResource(R.string.backup_restore),
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            SettingItem(
                title = stringResource(R.string.theme),
                description = stringResource(currentTheme.labelRes),
                onItemClick = { showThemeDialog = true },
                showTrailingIcon = false,
                showLeadingIcon = true,
                leadingIcon = {
                    Icon(
                        ImageVector.vectorResource(id = R.drawable.ic_palette),
                        contentDescription = stringResource(R.string.theme),
                        modifier = Modifier.size(24.dp)
                    )
                }
            )

            SettingItem(
                title = stringResource(R.string.chat_backup),
                description = stringResource(R.string.chat_backup_description),
                onItemClick = {
                    isRestoreMode = false
                    showPasswordDialog = true
                },
                showTrailingIcon = false,
                showLeadingIcon = true,
                leadingIcon = {
                    Icon(
                        ImageVector.vectorResource(id = R.drawable.ic_backup),
                        contentDescription = stringResource(R.string.chat_backup),
                        modifier = Modifier.size(24.dp)
                    )
                }
            )

            SettingItem(
                title = stringResource(R.string.chat_restore),
                description = stringResource(R.string.chat_restore_description),
                onItemClick = {
                    openDocumentLauncher.launch(arrayOf("application/json"))
                },
                showTrailingIcon = false,
                showLeadingIcon = true,
                leadingIcon = {
                    Icon(
                        ImageVector.vectorResource(id = R.drawable.ic_restore),
                        contentDescription = stringResource(R.string.chat_restore),
                        modifier = Modifier.size(24.dp)
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Advanced Category
            Text(
                stringResource(R.string.advanced),
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            SettingItem(
                title = stringResource(R.string.max_tool_calls),
                description = stringResource(R.string.max_tool_calls_description),
                onItemClick = {},
                showTrailingIcon = false,
                showLeadingIcon = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.max_tool_calls),
                        modifier = Modifier.size(24.dp)
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // About (pinned at the very bottom)
            SettingItem(
                title = stringResource(R.string.about),
                onItemClick = { onNavigateTo(Route.ABOUT) },
                showTrailingIcon = true,
                showLeadingIcon = true,
                leadingIcon = {
                    Icon(
                        ImageVector.vectorResource(id = R.drawable.ic_info),
                        contentDescription = stringResource(R.string.about),
                        modifier = Modifier.size(24.dp)
                    )
                }
            )
        }
    }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showPasswordDialog = false
                password = ""
            },
            title = {
                Text(
                    text = if (isRestoreMode) {
                        stringResource(R.string.chat_restore)
                    } else {
                        stringResource(R.string.chat_backup)
                    }
                )
            },
            text = {
                Column {
                    Text(text = "Enter password for encryption/decryption:")
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPasswordDialog = false
                        if (isRestoreMode) {
                            selectedRestoreUri?.let { uri ->
                                settingViewModel.restoreDatabase(uri, password)
                                password = ""
                                selectedRestoreUri = null
                            }
                        } else {
                            createDocumentLauncher.launch("gpt_mobile_backup_${System.currentTimeMillis()}.json")
                        }
                    },
                    enabled = password.isNotBlank()
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasswordDialog = false
                        password = ""
                        selectedRestoreUri = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.theme)) },
            text = {
                Column {
                    dev.chungjungsoo.gptmobile.data.model.ThemeSetting.entries.forEach { theme ->
                        RadioItem(
                            title = stringResource(theme.labelRes),
                            selected = theme == currentTheme,
                            onClick = {
                                themeViewModel.setTheme(theme)
                                showThemeDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
