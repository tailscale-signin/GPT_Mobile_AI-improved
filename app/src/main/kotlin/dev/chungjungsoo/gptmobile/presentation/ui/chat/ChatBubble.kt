package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.*
import dev.chungjungsoo.gptmobile.data.localruntime.DiagnosticsTelemetryProvider
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.presentation.theme.fastEffectsSpec
import dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun formatMessageTimestamp(timestampMillis: Long?): String {
    if (timestampMillis == null || timestampMillis <= 0) return ""
    return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestampMillis))
}

internal fun shouldShowContinuePrompt(
    text: String,
    isLoading: Boolean,
    isLastMessage: Boolean = false
): Boolean {
    return ChatResponseActionParser.shouldShowContinuePrompt(text, isLoading, isLastMessage)
}

@Composable private fun CopyTextIcon(onClick: () -> Unit) = IconButton(onClick = onClick) {
    Icon(ImageVector.vectorResource(R.drawable.ic_copy), stringResource(R.string.copy_text))
}
@Composable private fun SelectTextIcon(onClick: () -> Unit) = IconButton(onClick = onClick) {
    Icon(ImageVector.vectorResource(R.drawable.ic_select), stringResource(R.string.select_text))
}
@Composable
private fun FavoriteIcon(
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onFavoriteLongPress: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onFavoriteClick() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onFavoriteLongPress()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            stringResource(if (isFavorite) R.string.unfavorite else R.string.favorite),
            tint = if (isFavorite) Color.Cyan else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
@Composable private fun RetryIcon(onClick: () -> Unit) = IconButton(onClick = onClick) {
    Icon(Icons.Rounded.Refresh, stringResource(R.string.retry))
}
@Composable private fun EditTextIcon(onClick: () -> Unit) = IconButton(onClick = onClick) {
    Icon(Icons.Outlined.Edit, stringResource(R.string.edit))
}

@Composable
internal fun TelemetryBadge(notice: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { contentDescription = notice },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = notice,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Rich diagnostics inspection card rendered when Debug Mode is turned on.
 * Displays real-time hardware status, QNN HTP NPU native readiness, and token generation speed.
 */
@Composable
internal fun ChatDebugDiagnosticsCard(
    agentRun: AgentRun?,
    telemetryNotice: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }

    val snapshot = remember(agentRun, telemetryNotice) {
        DiagnosticsTelemetryProvider.getSnapshot(
            context = context,
            backendName = agentRun?.modelSnapshot ?: "On-Device",
            accelerator = "NPU / Hexagon HTP"
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Debug Diagnostics",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Debug Diagnostics HUD",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                TextButton(
                    onClick = {
                        val fullReport = DiagnosticsTelemetryProvider.formatDiagnosticsText(snapshot, telemetryNotice)
                        clipboardManager.setText(AnnotatedString(fullReport))
                        isCopied = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (isCopied) "Copied!" else "Copy Diagnostics",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Processor & Native Accelerator Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Processor: ${snapshot.socModel}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Text(
                    text = if (snapshot.qnnReady) "Hexagon NPU: Ready" else "Hexagon NPU: Inactive",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (snapshot.qnnReady) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(4.dp))

            // Memory & Thermals
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "RAM: ${snapshot.availableRamMb} MB avail / ${snapshot.totalRamGb} GB",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Text(
                    text = "Thermal: ${snapshot.thermalStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }

            if (!telemetryNotice.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = telemetryNotice,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp
                )
            }
        }
    }
}

internal fun isTelemetryNotice(message: String): Boolean =
    message.startsWith("Local: ") && message.contains("tok/s")

internal fun extractTelemetryNotice(notices: List<String>): Pair<String?, List<String>> {
    val telemetry = notices.firstOrNull(::isTelemetryNotice)
    val remaining = notices.filterNot(::isTelemetryNotice)
    return telemetry to remaining
}

internal fun buildDiagnosticsHudText(
    agentRun: AgentRun?,
    telemetryNotice: String?,
    debugMode: Boolean
): String? {
    if (!debugMode) return telemetryNotice

    val parts = mutableListOf<String>()
    agentRun?.modelSnapshot?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    agentRunDurationSeconds(agentRun ?: return telemetryNotice)?.let { duration ->
        parts.add("${duration}s")
    }
    telemetryNotice?.takeIf { it.isNotBlank() }?.let { parts.add(it) }

    return if (parts.isNotEmpty()) parts.joinToString(" • ") else telemetryNotice
}

@Preview
@Composable
fun UserChatBubblePreview() {
    val sampleText = "How can I print hello world in Python?"
    GPTMobileTheme {
        UserChatBubble(text = sampleText, files = emptyList(), onLongPress = {})
    }
}

@Composable
internal fun MessageFileThumbnailRow(files: List<String>, modifier: Modifier = Modifier, usePrimaryColors: Boolean = true) {
    val validFiles = remember(files) { files.filter(String::isNotBlank) }
    if (validFiles.isEmpty()) return
    Row(
        modifier = modifier.wrapContentHeight().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { validFiles.forEach { MessageFileThumbnail(it, usePrimaryColors) } }
}

@Composable
private fun MessageFileThumbnail(filePath: String, usePrimaryColors: Boolean) {
    val file = remember(filePath) { File(filePath) }
    val isImage = remember(file.extension) { isImageFile(file.extension) }
    val container = if (usePrimaryColors) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .7f) else MaterialTheme.colorScheme.surfaceVariant
    val content = if (usePrimaryColors) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(container)) {
            Icon(
                ImageVector.vectorResource(if (isImage) R.drawable.ic_image else R.drawable.ic_file), file.name,
                modifier = Modifier.fillMaxWidth().padding(8.dp), tint = content
            )
        }
        Text(
            file.name, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).width(56.dp)
        )
    }
}

private fun isImageFile(extension: String?): Boolean =
    extension != null && extension.lowercase() in setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
