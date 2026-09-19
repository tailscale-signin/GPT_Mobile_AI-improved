package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern UI Components with glassmorphism, gradients, and smooth animations
 */

// ==================== Glassmorphism Card ====================

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
    alpha: Float = 0.7f,
    borderRadius: RoundedCornerShape = RoundedCornerShape(24.dp),
    shadowElevation: Float = 8f
) {
    Surface(
        modifier = modifier,
        shape = borderRadius,
        color = if (isSystemInDarkTheme()) {
            glassBackgroundDark.copy(alpha = alpha)
        } else {
            glassBackgroundLight.copy(alpha = alpha)
        },
        shadowElevation = shadowElevation,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .background(
                    brush = Brush.linearGradient(
                        colors = if (isSystemInDarkTheme()) {
                            listOf(
                                glassBackgroundDark.copy(alpha = 0.9f),
                                glassBackgroundDark.copy(alpha = 0.6f)
                            )
                        } else {
                            listOf(
                                glassBackgroundLight.copy(alpha = 0.95f),
                                glassBackgroundLight.copy(alpha = 0.7f)
                            )
                        },
                        startFraction = 0f,
                        endFraction = 1f
                    )
                )
                .clip(borderRadius)
                .drawShadow(
                    color = if (isSystemInDarkTheme()) {
                        Color(0xFF000000).copy(alpha = 0.3f)
                    } else {
                        Color(0xFF000000).copy(alpha = 0.1f)
                    },
                    shape = borderRadius,
                    elevation = shadowElevation.dp
                ),
            content = content
        )
    }
}

// ==================== Gradient Background ====================

@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    colors: List<Color> = if (isSystemInDarkTheme()) {
        listOf(gradientStartDark, gradientEndDark)
    } else {
        listOf(gradientStartLight, gradientEndLight)
    },
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = colors,
                        startFraction = 0f,
                        endFraction = 1f
                    )
                )
        )
        content()
    }
}

// ==================== Animated Gradient Button ====================

@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String = "",
    icon: (@Composable () -> Unit)? = null,
    isLoading: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gradient")
    val startColor by infiniteTransition.animateColor(
        initialValue = if (isSystemInDarkTheme()) gradientStartDark else gradientStartLight,
        targetValue = if (isSystemInDarkTheme()) gradientEndDark else gradientEndLight,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradientStart"
    )
    val endColor by infiniteTransition.animateColor(
        initialValue = if (isSystemInDarkTheme()) gradientEndDark else gradientEndLight,
        targetValue = if (isSystemInDarkTheme()) gradientStartDark else gradientStartLight,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradientEnd"
    )

    Button(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer {
                alpha = if (isLoading) 0.7f else 1f
            },
        enabled = enabled && !isLoading,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Unspecified
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    ) {
        if (icon != null) {
            icon()
            Spacer(modifier = Modifier.width(8.dp))
        }
        if (!isLoading) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = onPrimaryLight
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = onPrimaryLight,
                strokeWidth = 2.dp
            )
        }
    }
}

// ==================== Pulsing Dot Indicator ====================

@Composable
fun PulsingDot(
    modifier: Modifier = Modifier,
    color: Color = accentCyanLight,
    size: Dp = 8.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    )
}

// ==================== Floating Action Card ====================

@Composable
fun FloatingActionCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    text: String = "",
    elevation: Float = 12f
) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(16.dp),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSystemInDarkTheme()) {
                glassBackgroundDark.copy(alpha = 0.8f)
            } else {
                glassBackgroundLight.copy(alpha = 0.9f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            icon()
            if (text.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSystemInDarkTheme()) accentCyanDark else accentCyanLight,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ==================== Shimmer Loading Card ====================

@Composable
fun ShimmerCard(
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    borderRadius: RoundedCornerShape = RoundedCornerShape(16.dp)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerColor by infiniteTransition.animateColor(
        initialValue = if (isSystemInDarkTheme()) Color(0xFF1E292B) else Color(0xFFE0E0E0),
        targetValue = if (isSystemInDarkTheme()) Color(0xFF2C3E42) else Color(0xFFF5F5F5),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerColor"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(borderRadius)
            .background(shimmerColor)
    )
}

// ==================== Animated Message Bubble ====================

@Composable
fun AnimatedMessageBubble(
    modifier: Modifier = Modifier,
    text: String,
    isUser: Boolean = false,
    showAvatar: Boolean = true,
    avatarColor: Color = if (isUser) accentCyanLight else accentPurpleLight,
    onLongPress: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
    ) {
        if (showAvatar) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(avatarColor)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = onPrimaryLight,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Card(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    accentCyanLight.copy(alpha = 0.15f)
                } else {
                    accentPurpleLight.copy(alpha = 0.15f)
                }
            ),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 4.dp else 20.dp,
                bottomEnd = if (isUser) 20.dp else 4.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSystemInDarkTheme()) onSurfaceDark else onSurfaceLight
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Just now",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSystemInDarkTheme()) onSurfaceVariantDark.copy(alpha = 0.6f) else onSurfaceVariantLight.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// ==================== Gradient Text ====================

@Composable
fun GradientText(
    text: String,
    modifier: Modifier = Modifier,
    colors: List<Color> = if (isSystemInDarkTheme()) {
        listOf(gradientStartDark, gradientEndDark)
    } else {
        listOf(gradientStartLight, gradientEndLight)
    },
    fontWeight: FontWeight = FontWeight.Bold
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = fontWeight,
            fontFamily = ProximaNovaFontFamily
        ),
        color = Color.Unspecified,
        textDecoration = TextDecoration.Unspecified,
        textStyle = TextStyle(
            fontFamily = ProximaNovaFontFamily,
            fontWeight = fontWeight,
            fontSize = MaterialTheme.typography.headlineMedium.fontSize,
            letterSpacing = MaterialTheme.typography.headlineMedium.letterSpacing,
            lineHeight = MaterialTheme.typography.headlineMedium.lineHeight
        ),
        brush = Brush.horizontalGradient(colors)
    )
}

// ==================== Glass Input Field ====================

@Composable
fun GlassInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    enabled: Boolean = true
) {
    val density = LocalDensity.current
    val borderWidth = with(density) { 1.dp.toPx() }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Unspecified,
            unfocusedBorderColor = Color.Unspecified,
            focusedContainerColor = Color.Unspecified,
            unfocusedContainerColor = Color.Unspecified,
            cursorColor = accentCyanLight,
            disabledBorderColor = Color.Unspecified,
            disabledContainerColor = Color.Unspecified
        ),
        singleLine = true,
        label = {
            if (label.isNotEmpty()) {
                Text(label, color = if (isSystemInDarkTheme()) onSurfaceVariantDark else onSurfaceVariantLight)
            }
        },
        placeholder = {
            if (placeholder.isNotEmpty()) {
                Text(
                    placeholder,
                    color = if (isSystemInDarkTheme()) onSurfaceVariantDark.copy(alpha = 0.5f) else onSurfaceVariantLight.copy(alpha = 0.5f)
                )
            }
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = ProximaNovaFontFamily
        ),
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = if (isSystemInDarkTheme()) onSurfaceVariantDark.copy(alpha = 0.6f) else onSurfaceVariantLight.copy(alpha = 0.6f)
                    )
                }
            }
        },
        containerColor = if (isSystemInDarkTheme()) {
            glassBackgroundDark.copy(alpha = 0.6f)
        } else {
            glassBackgroundLight.copy(alpha = 0.7f)
        },
        border = BorderStroke(
            width = borderWidth,
            color = if (isSystemInDarkTheme()) glassBorderDark else glassBorderLight
        ),
        enabled = enabled
    )
}

// ==================== Accent Badge ====================

@Composable
fun AccentBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = accentCyanLight,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp)
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color.copy(alpha = 0.15f),
        contentColor = color
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

// ==================== Section Header ====================

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (isSystemInDarkTheme()) accentCyanDark else accentCyanLight,
            fontFamily = ProximaNovaFontFamily
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSystemInDarkTheme()) onSurfaceVariantDark.copy(alpha = 0.7f) else onSurfaceVariantLight.copy(alpha = 0.7f),
                fontFamily = ProximaNovaFontFamily
            )
        }
    }
}

// ==================== Icon Button with Ripple ====================

@Composable
fun ModernIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    tint: Color = if (isSystemInDarkTheme()) accentCyanDark else accentCyanLight,
    size: Dp = 48.dp
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size)
    ) {
        Icon(
            imageVector = null,
            contentDescription = null,
            modifier = icon,
            tint = tint
        )
    }
}

// ==================== Progress Ring ====================

@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    strokeWidth: Dp = 4.dp,
    color: Color = accentCyanLight
) {
    val radius = (size - strokeWidth) / 2
    val circumference = radius * 2 * Math.PI
    val offset = circumference - progress * circumference

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(size),
            progress = progress,
            strokeWidth = strokeWidth,
            color = color,
            trackColor = Color.Transparent
        )
    }
}

// ==================== Skeleton Loader ====================

@Composable
fun SkeletonLoader(
    modifier: Modifier = Modifier,
    height: Dp = 20.dp,
    borderRadius: RoundedCornerShape = RoundedCornerShape(4.dp)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val skeletonColor by infiniteTransition.animateColor(
        initialValue = if (isSystemInDarkTheme()) Color(0xFF1E292B) else Color(0xFFE0E0E0),
        targetValue = if (isSystemInDarkTheme()) Color(0xFF2C3E42) else Color(0xFFF5F5F5),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonColor"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(borderRadius)
            .background(skeletonColor)
    )
}

// ==================== Tooltip ====================

@Composable
fun ModernTooltip(
    text: String,
    modifier: Modifier = Modifier,
    position: TooltipPosition = TooltipPosition.Bottom
) {
    Tooltip(
        text = text,
        modifier = modifier,
        position = position
    )
}

// ==================== Divider ====================

@Composable
fun ModernDivider(
    modifier: Modifier = Modifier,
    color: Color = if (isSystemInDarkTheme()) glassBorderDark else glassBorderLight,
    thickness: Dp = 1.dp
) {
    HorizontalDivider(
        modifier = modifier,
        color = color,
        thickness = thickness
    )
}
