package dev.chungjungsoo.gptmobile.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class AvailableChatTool(
    val id: String,
    val name: String,
    val description: String = "",
    val source: String = "",
    val isEnabled: Boolean = true
)
