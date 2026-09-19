package com.example.gptmobileai.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Reusable loading indicator with progress bar and animated status text.
 * Supports both circular and linear progress modes.
 */
@Composable
fun LoadingIndicator(
    isLoading: Boolean,
    progress: Float = 0f,
    message: String = "Loading...",
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
    circular: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary
) {
    if (!isLoading) return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        // Circular progress indicator with animation
        if (circular) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp,
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        } else {
            // Linear progress bar with smooth animation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val animatedProgress by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = tween(durationMillis = 300),
                    label = "progress"
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width((animatedProgress * 100).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Animated status message with pulse effect
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Loading overlay that dims the background and shows a loading indicator.
 */
@Composable
fun LoadingOverlay(
    isLoading: Boolean,
    message: String = "Loading...",
    modifier: Modifier = Modifier
) {
    if (!isLoading) return

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    strokeWidth = 4.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Pulsing dot indicator for lightweight loading states.
 */
@Composable
fun LoadingPulse(
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isLoading) return

    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
    ) {
        val alpha by animateFloatAsState(
            targetValue = if (isLoading) 0.6f else 0.1f,
            animationSpec = repeatable(
                iterations = 3,
                animation = tween(durationMillis = 800),
                label = "pulse"
            ),
            finishedLabel = "pulse"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        )
    }
}
