package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

/** A separate, steady marker so working and unread states never obscure privacy. */
@Composable
internal fun ConversationPrivacyBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(18.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .testTag("conversation_privacy_badge"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Lock,
            "Privacy Mode",
            Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
