package dev.melo.gptmobile.improved.presentation.ui.migrate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.data.repository.MigrationProgress
import dev.melo.gptmobile.improved.data.repository.MigrationState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrateScreen(
    viewModel: MigrateViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.migration_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.migration_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Export Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.export_data),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    Text(
                        text = stringResource(R.string.export_data_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = { viewModel.startExport() },
                        enabled = uiState.migrationState !is MigrationState.InProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.export_now))
                    }
                }
            }

            // Import Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.import_data),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    Text(
                        text = stringResource(R.string.import_data_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedButton(
                        onClick = { viewModel.startImport() },
                        enabled = uiState.migrationState !is MigrationState.InProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.import_now))
                    }
                }
            }

            // Progress / Status
            when (val state = uiState.migrationState) {
                is MigrationState.InProgress -> {
                    MigrationProgressView(progress = state.progress)
                }
                is MigrationState.Success -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is MigrationState.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                MigrationState.Idle -> {}
            }
        }
    }
}

@Composable
private fun MigrationProgressView(
    progress: MigrationProgress,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = progress.stage,
                style = MaterialTheme.typography.titleSmall,
            )

            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.current.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${progress.current} / ${progress.total}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
