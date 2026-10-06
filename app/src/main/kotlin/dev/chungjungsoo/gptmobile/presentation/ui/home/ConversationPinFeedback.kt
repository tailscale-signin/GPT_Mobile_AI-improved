package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon

@Composable
internal fun ConversationPinFeedback(visible: Boolean, isPinned: Boolean, dropTarget: Int?, modifier: Modifier = Modifier) {
    Box(modifier) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(160)) { -it } + fadeOut(tween(160))
        ) {
            PinDropHint(unpin = false, ready = dropTarget != null && dropTarget >= 0)
        }
        AnimatedVisibility(
            visible = visible && isPinned,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(160))
        ) {
            PinDropHint(unpin = true, ready = dropTarget == -1)
        }
    }
}

@Composable
private fun PinDropHint(unpin: Boolean, ready: Boolean) {
    val scale by animateFloatAsState(if (ready) 1.08f else 1f, tween(180), label = "pinDropHintScale")
    val rotation by animateFloatAsState(if (unpin) 45f else 0f, tween(220), label = "pinDropIconRotation")
    Surface(
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shape = RoundedCornerShape(24.dp),
        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
        shadowElevation = if (ready) 8.dp else 3.dp
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.PushPin, contentDescription = null, modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = rotation })
            Text(
                stringResource(
                    when {
                        ready && unpin -> R.string.release_to_unpin_chat
                        ready -> R.string.release_to_pin_chat
                        unpin -> R.string.drag_down_to_unpin_chat
                        else -> R.string.drag_up_to_pin_chat
                    }
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Icon(if (unpin) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}
