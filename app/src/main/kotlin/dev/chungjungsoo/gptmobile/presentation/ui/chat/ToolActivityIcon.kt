package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

internal fun toolActivityIcon(name: String): ImageVector {
    val value = name.lowercase()
    return when {
        value.contains("delegate_to_model") || value.contains("delegate") -> Icons.Rounded.Dns
        value.contains("location") || value.contains("map") -> Icons.Rounded.LocationOn
        value.contains("memory") || value.contains("recall") || value.contains("nodes") || value.contains("observations") -> Icons.Rounded.Memory
        value.contains("search") || value.contains("exa") || value.contains("brave") -> Icons.Rounded.Search
        value.contains("github") || value.contains("commit") || value.contains("repository") -> Icons.Rounded.Code
        value.contains("shell") || value.contains("terminal") -> Icons.Rounded.Terminal
        value.contains("image") -> Icons.Rounded.Image
        value.contains("calculate") -> Icons.Rounded.Calculate
        value.contains("directory") || value.contains("folder") -> Icons.Rounded.Folder
        value.contains("document") || value.contains("file") -> Icons.Rounded.Description
        value.contains("web") || value.contains("url") || value.contains("fetch") -> Icons.Rounded.Language
        else -> Icons.Rounded.Build
    }
}
