package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.chat.ConversationFolder
import dev.chungjungsoo.gptmobile.data.chat.ConversationFolderStyle

@Composable
internal fun ConversationFolderStrip(
    folders: List<ConversationFolder>,
    selectedId: String?,
    hoveredId: String?,
    onSelect: (String?) -> Unit,
    onEdit: (ConversationFolder) -> Unit,
    onBounds: (String, Rect) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onSelect(null) }, modifier = Modifier.onGloballyPositioned { onBounds("", it.boundsInRoot()) }) {
            Text("Chats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        folders.forEach { folder ->
            val emphasized = folder.id == selectedId || folder.id == hoveredId
            val scale by animateFloatAsState(if (folder.id == hoveredId) 1.08f else 1f, tween(180), label = "folderHover")
            Surface(
                color = Color(folder.color).copy(alpha = if (emphasized) 1f else 0.82f),
                contentColor = Color.White,
                shape = RoundedCornerShape(14.dp),
                border = if (emphasized) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                tonalElevation = if (emphasized) 4.dp else 0.dp,
                modifier = Modifier.onGloballyPositioned { onBounds(folder.id, it.boundsInRoot()) }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .combinedClickable(onClick = { onSelect(if (selectedId == folder.id) null else folder.id) }, onLongClick = { onEdit(folder) })
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp))
                    Text(folder.name, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
internal fun ConversationOrganizationTargets(
    visible: Boolean,
    pinHovered: Boolean,
    folderHovered: Boolean,
    onPinBounds: (Rect) -> Unit,
    onFolderBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(180)), modifier = modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(false, true).forEach { isFolder ->
                val hovered = if (isFolder) folderHovered else pinHovered
                Surface(
                    modifier = Modifier.weight(1f).height(56.dp).onGloballyPositioned {
                        if (isFolder) onFolderBounds(it.boundsInRoot()) else onPinBounds(it.boundsInRoot())
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = if (hovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (hovered) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    shadowElevation = if (hovered) 10.dp else 4.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (hovered) 1f else 0.35f))
                ) {
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (isFolder) Icons.Rounded.CreateNewFolder else Icons.Rounded.PushPin, null)
                        Column {
                            Text(if (isFolder) "New folder" else "Pin to top", style = MaterialTheme.typography.labelLarge)
                            Text("Drop here", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConversationFolderEditor(
    folder: ConversationFolder?,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember(folder?.id) { mutableStateOf(folder?.name.orEmpty()) }
    var color by remember(folder?.id) { mutableStateOf(folder?.color ?: ConversationFolderStyle.colors.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (folder == null) Icons.Rounded.CreateNewFolder else Icons.Rounded.Folder, null) },
        title = { Text(if (folder == null) "Create folder" else "Customize folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Folder name") }, singleLine = true)
                Text("Background colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConversationFolderStyle.colors.forEachIndexed { index, value ->
                        Box(
                            Modifier.size(36.dp).background(Color(value), CircleShape)
                                .clickable { color = value }
                                .semantics { contentDescription = "Folder colour ${index + 1}${if (color == value) ", selected" else ""}" },
                            contentAlignment = Alignment.Center
                        ) {
                            if (value == color) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Remove folder · keep conversations") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), color) }, enabled = name.isNotBlank()) { Text(if (folder == null) "Create" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
