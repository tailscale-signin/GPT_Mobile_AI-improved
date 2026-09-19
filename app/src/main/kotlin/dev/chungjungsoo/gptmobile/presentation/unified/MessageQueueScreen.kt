package dev.chungjungsoo.gptmobile.presentation.unified

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.domain.unified.MessageQueueStatus
import dev.chungjungsoo.gptmobile.domain.unified.QueuedMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageQueueScreen(
    viewModel: UnifiedModelPickerViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queuedMessages by viewModel.queuedMessages.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message Queue (${queuedMessages.size})") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val hasCompleted = queuedMessages.any { it.status is MessageQueueStatus.Completed || it.status is MessageQueueStatus.Cancelled }
                    if (hasCompleted) {
                        IconButton(onClick = { viewModel.clearCompletedQueue() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear Completed")
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            if (queuedMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Queue is empty. Messages waiting for processing will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(queuedMessages, key = { it.id }) { item ->
                        QueuedMessageCard(
                            item = item,
                            onRetry = { viewModel.retryQueueItem(item.id) },
                            onCancel = { viewModel.cancelQueueItem(item.id) },
                            onRemove = { viewModel.removeQueueItem(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuedMessageCard(
    item: QueuedMessage,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Priority: ${item.priority}/5",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                StatusBadge(status = item.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = item.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )

            if (!item.response.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Response: ${item.response}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2
                )
            }

            if (!item.error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Error: ${item.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.status is MessageQueueStatus.Pending) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Clear, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.outline)
                    }
                }

                if (item.status is MessageQueueStatus.Error) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: MessageQueueStatus) {
    when (status) {
        is MessageQueueStatus.Pending -> {
            Text("Pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        is MessageQueueStatus.Processing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Processing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        is MessageQueueStatus.Completed -> {
            Text("Completed", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
        }
        is MessageQueueStatus.Error -> {
            Text("Failed (${status.retryCount})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        is MessageQueueStatus.Cancelled -> {
            Text("Cancelled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
