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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onSizeChanged
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
    val completed = status == CombinedResponseStatus.COMPLETED
    val opacity by animateFloatAsState(if (completed) 1f else 0.4f, tween(500), label = "Profile completion opacity")
    val background by animateColorAsState(if (completed) colors.surfaceContainerHigh else Color(0xFF808080), tween(500), label = "Profile completion background")
    val foreground by animateColorAsState(if (completed) (if (selected) colors.onPrimaryContainer else colors.onSurface) else Color(0xFFBDBDBD), tween(500), label = "Profile completion text")
    val primaryTint by animateColorAsState(if (completed) colors.primaryContainer else Color(0xFF808080), tween(500), label = "Profile primary tint")
    val secondaryTint by animateColorAsState(if (completed) colors.secondaryContainer else Color(0xFF808080), tween(500), label = "Profile secondary tint")
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
        color = background.copy(alpha = opacity),
        contentColor = foreground.copy(alpha = opacity),
        border = BorderStroke(1.dp, (if (completed) colors.primary else Color.Gray).copy(alpha = (0.22f + selection * 0.5f) * opacity)),
        shadowElevation = if (completed) (2f + selection * 4f).dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            primaryTint.copy(alpha = if (completed) 0.2f + selection * 0.65f else 0f),
                            secondaryTint.copy(alpha = if (completed) 0.15f + selection * 0.35f else 0f)
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
    // Keep the input's footprint during profile inspection. Fading the input
    // must not change list padding or move the response under the user's eyes.
    var measuredHeight by remember { mutableIntStateOf(0) }
    val minimumHeight = with(LocalDensity.current) { measuredHeight.toDp() }
    Box(modifier.heightIn(min = minimumHeight)) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(500)),
            exit = fadeOut(tween(2000))
        ) {
            Box(Modifier.onSizeChanged { measuredHeight = it.height }) { content() }
        }
    }
}
