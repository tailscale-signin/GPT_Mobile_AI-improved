package com.example.gptmobileai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pure domain representation of supported AI platforms.
 */
enum class AIPlatform(val displayName: String, val identifier: String) {
    OPENAI("OpenAI", "openai"),
    ANTHROPIC("Anthropic", "anthropic"),
    LOCAL_LLM("Local LLM", "local"),
    OLLAMA("Ollama", "ollama"),
    CUSTOM("Custom / External", "custom");

    companion object {
        fun fromIdentifier(id: String?): AIPlatform =
            entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) } ?: CUSTOM
    }
}

/**
 * UI presentation icon mapping for [AIPlatform].
 */
val AIPlatform.icon: ImageVector
    get() = when (this) {
        AIPlatform.OPENAI -> Icons.Default.Cloud
        AIPlatform.ANTHROPIC -> Icons.Default.Security
        AIPlatform.LOCAL_LLM -> Icons.Default.PhoneAndroid
        AIPlatform.OLLAMA -> Icons.Default.Computer
        AIPlatform.CUSTOM -> Icons.Default.Devices
    }

/**
 * UI presentation badge color mapping for [AIPlatform].
 */
val AIPlatform.badgeColor: Color
    get() = when (this) {
        AIPlatform.OPENAI -> Color(0xFF10A37F)
        AIPlatform.ANTHROPIC -> Color(0xFFD97706)
        AIPlatform.LOCAL_LLM -> Color(0xFF6366F1)
        AIPlatform.OLLAMA -> Color(0xFF3B82F6)
        AIPlatform.CUSTOM -> Color(0xFF8B5CF6)
    }

/**
 * Badge label displaying the platform identity, icon, and optional model name.
 */
@Composable
fun PlatformLabel(
    platform: AIPlatform,
    modifier: Modifier = Modifier,
    modelName: String? = null,
    compact: Boolean = false
) {
    val shape = RoundedCornerShape(12.dp)
    val color = platform.badgeColor

    Box(
        modifier = modifier
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(width = 1.dp, color = color.copy(alpha = 0.40f), shape = shape)
            .padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 2.dp else 4.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = platform.icon,
                contentDescription = platform.displayName,
                tint = color,
                modifier = Modifier.size(if (compact) 12.dp else 14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (modelName.isNullOrBlank()) platform.displayName else "${platform.displayName} • $modelName",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = if (compact) 10.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
