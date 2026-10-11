package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.LocalReducedMotion

@Composable
internal fun CombinedProfileBubbles(
    profiles: List<CombinedResponseProfile>,
    selectedUid: String?,
    combinedStatus: CombinedResponseStatus,
    animateArrival: Boolean,
    onSelectProfile: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    // Contributions are immediately inspectable, including failed or still-running work.
    // Never delay an actionable tab behind a decorative stagger animation.
    val transitionMillis = if (animateArrival && !LocalReducedMotion.current) 180 else 0
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CombinedProfileBubble(
            name = stringResource(R.string.chat_mode_combined),
            status = combinedStatus,
            selected = selectedUid == null,
            transitionMillis = transitionMillis,
            modifier = Modifier.testTag("combined-response-tab"),
            onClick = { onSelectProfile(null) }
        )
        profiles.forEach { profile ->
            key(profile.uid) {
                CombinedProfileBubble(
                    name = profile.name,
                    status = profile.status,
                    selected = selectedUid == profile.uid,
                    transitionMillis = transitionMillis,
                    modifier = Modifier.testTag("combined-profile-${profile.uid}"),
                    onClick = { onSelectProfile(profile.uid) }
                )
            }
        }
    }
}

@Composable
private fun CombinedProfileBubble(
    name: String,
    status: CombinedResponseStatus,
    selected: Boolean,
    transitionMillis: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(if (selected) colors.primaryContainer else colors.surfaceContainerLow, tween(transitionMillis), label = "Profile selection")
    val foreground = if (selected) colors.onPrimaryContainer else colors.onSurface
    val statusText = stringResource(
        when (status) {
            CombinedResponseStatus.GENERATING -> R.string.combined_profile_generating
            CombinedResponseStatus.COMPLETED -> R.string.combined_profile_completed
            CombinedResponseStatus.FAILED -> R.string.combined_profile_failed
        }
    )
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            this.selected = selected
            stateDescription = statusText
        },
        shape = MaterialTheme.shapes.large,
        color = background,
        contentColor = foreground,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant)
    ) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (status) {
                    CombinedResponseStatus.GENERATING -> Icons.Rounded.HourglassTop
                    CombinedResponseStatus.COMPLETED -> Icons.Rounded.CheckCircle
                    CombinedResponseStatus.FAILED -> Icons.Rounded.ErrorOutline
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                Text(statusText, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun CombinedChatComposer(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Keep the input's footprint during profile inspection. Fading the input
    // must not change list padding or move the response under the user's eyes.
    val reducedMotion = LocalReducedMotion.current
    var measuredHeight by remember { mutableIntStateOf(0) }
    val minimumHeight = with(LocalDensity.current) { measuredHeight.toDp() }
    Box(modifier.heightIn(min = minimumHeight)) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(if (reducedMotion) 0 else 180)),
            exit = fadeOut(tween(if (reducedMotion) 0 else 140))
        ) {
            Box(Modifier.onSizeChanged { measuredHeight = it.height }) { content() }
        }
    }
}
