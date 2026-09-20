package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.runtime.Immutable

/**
 * Standardized transparency and alpha tokens for chat UI elements.
 */
@Immutable
data class ChatAlphaTokens(
    val chatBubbleBackground: Float = 0.06f,
    val toolTraceBackground: Float = 0.07f,
    val toolTraceCard: Float = 0.07f,
    val dimmedMetadata: Float = 0.20f,
    val timestampOverlay: Float = 0.30f,
    val detailsText: Float = 0.50f,
    val favoriteTint: Float = 0.12f
)

val LocalChatAlpha = ChatAlphaTokens()
