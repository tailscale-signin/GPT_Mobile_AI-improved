package com.gptmobileai.presentation.ui.messagequeue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gptmobileai.domain.model.MessageQueueStatus
import com.gptmobileai.domain.model.QueuedMessage

/**
 * Message Queue UI Component
 * 
 * Displays and manages the queue of AI requests with priority handling
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageQueueScreen(
    modifier: Modifier = Modifier,
    onRetryClick: (String) -> Unit,
    onClearCompletedClick: () -> Unit,
    onDismissMessage: (String) -> Unit
) {
    var showPriorityDialog by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Message Queue") },
                actions = {
                    IconButton(onClick = onClearCompletedClick) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear completed")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Queue Stats Card
            QueueStatsCard()

            Spacer(modifier = Modifier.height(16.dp))

            // Priority Filter
            OutlinedTextField(
                value = "All Priorities",
                onValueChange = {},
                readOnly = true,
                label = { Text("Priority Filter") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { showPriorityDialog = null }) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Filter")
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Queue List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(emptyList<QueuedMessage>(), key = { it.id }) { message ->
                    QueuedMessageCard(
                        message = message,
                        onRetryClick = onRetryClick,
                        onDismissMessage = onDismissMessage,
                        onPriorityChange = { showPriorityDialog = message.id }
                    )
                }
            }

            if (showPriorityDialog != null) {
                AlertDialog(
                    onDismissRequest = { showPriorityDialog = null },
                    title = { Text("Set Priority") },
                    text = {
                        Column {
                            PriorityOption(priority = 5, label = "Highest", onClick = { 
                                showPriorityDialog = null 
                            })
                            PriorityOption(priority = 4, label = "High", onClick = { 
                                showPriorityDialog = null 
                            })
                            PriorityOption(priority = 3, label = "Normal", onClick = { 
                                showPriorityDialog = null 
                            })
                            PriorityOption(priority = 2, label = "Low", onClick = { 
                                showPriorityDialog = null 
                            })
                            PriorityOption(priority = 1, label = "Lowest", onClick = { 
                                showPriorityDialog = null 
                            })
                        }
                    },
                    confirmButton = { }
                )
            }
        }
    }
}

@Composable
private fun QueueStatsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(
                icon = Icons.Default.Queue,
                label = "Pending",
                value = "3"
            )
            StatItem(
                icon = Icons.Default.LocalFireDepartment,
                label = "Processing",
                value = "1"
            )
            StatItem(
                icon = Icons.Default.CheckCircle,
                label = "Completed",
                value = "47"
            )
        }
    }
}

@Composable
private fun StatItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun QueuedMessageCard(
    message: QueuedMessage,
    onRetryClick: (String) -> Unit,
    onDismissMessage: (String) -> Unit,
    onPriorityChange: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = { onPriorityChange(message.id) }),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header with status and priority
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Message #${message.id.take(8)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PriorityBadge(priority = message.priority)
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(status = message.status)
                    }
                }

                Row {
                    IconButton(onClick = onRetryClick) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry",
                            tint = if (message.isRetryable()) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    IconButton(onClick = onDismissMessage) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content preview
            Text(
                text = message.content.take(100) + if (message.content.length > 100) "..." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Metadata
            Row {
                Chip(
                    onClick = { },
                    label = { Text("Priority ${message.priority}/5") }
                )
                
                if (message.status is MessageQueueStatus.Error) {
                    Chip(
                        onClick = { },
                        label = { Text("Retry: ${message.retryCount}/${message.maxRetries}") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Timestamp
            Text(
                text = "Queued at ${formatTimestamp(message.createdAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PriorityBadge(priority: Int) {
    val colors = when (priority) {
        5 -> Color.Red
        4 -> Color(0xFFFB923C)
        3 -> Color(0xFFFBBF24)
        2 -> Color(0xFFA3E635)
        else -> Color(0xFF60A5FA)
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.copy(alpha = 0.2f))
    ) {
        Text(
            text = "P${priority}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = colors
        )
    }
}

@Composable
private fun StatusBadge(status: MessageQueueStatus) {
    val (color, icon) = when (status) {
        is MessageQueueStatus.Pending -> Pair(
            MaterialTheme.colorScheme.tertiary,
            Icons.Default.Timer
        )
        is MessageQueueStatus.Processing -> Pair(
            MaterialTheme.colorScheme.primary,
            Icons.Default.LocalFireDepartment
        )
        is MessageQueueStatus.Completed -> Pair(
            if (status.success) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.error,
            if (status.success) Icons.Default.CheckCircle else Icons.Default.Error
        )
        is MessageQueueStatus.Error -> Pair(
            Color.Red,
            Icons.Default.Error
        )
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.2f))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = color)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = when (status) {
                    is MessageQueueStatus.Pending -> "Pending"
                    is MessageQueueStatus.Processing -> "Processing"
                    is MessageQueueStatus.Completed -> if (status.success) "Done" else "Failed"
                    is MessageQueueStatus.Error -> "Error"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun PriorityOption(priority: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label)
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    return java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
}
