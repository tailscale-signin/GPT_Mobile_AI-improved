package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import kotlinx.coroutines.delay

private const val PROFILE_SLIDE_MS = 1000
private const val PROFILE_STAGGER_MS = 500L

@Composable
internal fun CombinedProfileBubbles(
    profiles: List<CombinedResponseProfile>,
    selectedUid: String?,
    combinedStatus: CombinedResponseStatus,
    animateArrival: Boolean,
    onSelectProfile: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GPTMobileIcon(loading = combinedStatus == CombinedResponseStatus.GENERATING)
        CombinedProfileBubble(
            name = stringResource(R.string.chat_mode_combined),
            status = combinedStatus,
            selected = selectedUid == null,
            modifier = Modifier.testTag("combined-response-tab"),
            onClick = { onSelectProfile(null) }
        )
        profiles.forEachIndexed { index, profile ->
            key(profile.uid) {
                var arrived by rememberSaveable { mutableStateOf(!animateArrival) }
                val entrance = remember { Animatable(if (arrived) 1f else 0f) }
                var targetX by remember { mutableFloatStateOf(0f) }
                val iconCenter = with(LocalDensity.current) { 24.dp.toPx() }
                LaunchedEffect(Unit) {
                    if (!arrived) {
                        delay(index * PROFILE_STAGGER_MS)
                        entrance.animateTo(1f, tween(PROFILE_SLIDE_MS, easing = FastOutSlowInEasing))
                        arrived = true
                    }
                }
                CombinedProfileBubble(
                    name = profile.name,
                    status = profile.status,
                    selected = selectedUid == profile.uid,
                    modifier = Modifier
                        .testTag("combined-profile-${profile.uid}")
                        .onPlaced { targetX = it.positionInParent().x }
                        .graphicsLayer {
                            val progress = entrance.value
                            // Every pill begins at the app icon's centre and travels to its own array slot.
                            translationX = (iconCenter - targetX - size.width / 2f) * (1f - progress)
                            alpha = progress
                            scaleX = 0.65f + progress * 0.35f
                            scaleY = 0.65f + progress * 0.35f
                        },
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val selection by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "Profile selection")
    val dotColor by animateColorAsState(
        when (status) {
            CombinedResponseStatus.GENERATING -> Color(0xFFFFD54F)
            CombinedResponseStatus.COMPLETED -> Color(0xFF39FF88)
            CombinedResponseStatus.FAILED -> Color(0xFFFF5252)
        },
        tween(280),
        label = "Profile status"
    )
    val statusText = stringResource(
        when (status) {
            CombinedResponseStatus.GENERATING -> R.string.combined_profile_generating
            CombinedResponseStatus.COMPLETED -> R.string.combined_profile_completed
            CombinedResponseStatus.FAILED -> R.string.combined_profile_failed
        }
    )
    val pulse: State<Float> = if (status == CombinedResponseStatus.GENERATING) {
        rememberInfiniteTransition(label = "Generating profile").animateFloat(
            initialValue = 0.4f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "Status halo"
        )
    } else {
        remember { mutableFloatStateOf(0.55f) }
    }
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            this.selected = selected
            stateDescription = statusText
        },
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerHigh,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.22f + selection * 0.5f)),
        shadowElevation = (2f + selection * 4f).dp
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            colors.primaryContainer.copy(alpha = 0.2f + selection * 0.65f),
                            colors.secondaryContainer.copy(alpha = 0.15f + selection * 0.35f)
                        )
                    )
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(18.dp)
                        .graphicsLayer { alpha = pulse.value * 0.3f }
                        .background(dotColor, CircleShape)
                )
                Box(Modifier.size(9.dp).background(dotColor, CircleShape))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun CombinedChatComposer(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Keep a measured wrapper after the exit so the list's padding and edge fade also reach zero.
    Box(modifier) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(500)) + expandVertically(tween(500), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(2000)) + shrinkVertically(tween(2000), shrinkTowards = Alignment.Bottom)
        ) {
            content()
        }
    }
}
