package dev.chungjungsoo.gptmobile.ui.modern

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern UI Components with Glassmorphism, Gradients, and Smooth Animations
 * 
 * Features:
 * - Glassmorphism cards with backdrop blur effect
 * - Animated gradient backgrounds
 * - Smooth spring-based animations
 * - Custom progress rings and indicators
 * - Enhanced chat bubbles with depth
 * - Shimmer loading effects
 * - Pulsing indicators for active states
 */

// ============================================================================
// GRADIENT BACKGROUNDS
// ============================================================================

@Composable
fun GradientBackground(
    colors: List<Color> = listOf(
        Color(0xFF006878),
        Color(0xFF00B4D8),
        Color(0xFF0077B6)
    ),
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "gradient")
        val color1 by infiniteTransition.animateColor(
            initialValue = colors[0],
            targetValue = colors[1],
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "color1"
        )
        val color2 by infiniteTransition.animateColor(
            initialValue = colors[1],
            targetValue = colors[2],
            animationSpec = infiniteRepeatable(
                animation = tween(5000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "color2"
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(color1, color2, colors[0])
                    )
                )
        )
        content()
    }
}

// ============================================================================
// GLASSMORPHISM CARDS
// ============================================================================

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    elevation: Float = 8f,
    borderRadius: RoundedCornerShape = RoundedCornerShape(16.dp)
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (elevation > 0) Modifier.elevation(elevation) else Modifier),
        shape = borderRadius,
        color = Color.White.copy(alpha = 0.1f),
        shadowElevation = elevation,
        tonalElevation = elevation,
        border = BorderStroke(
            width = 0.5.dp,
            color = Color.White.copy(alpha = 0.2f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.02f)
                        )
                    )
                )
        ) {
            content()
        }
    }
}

@Composable
fun GlassCardWithBorder(
    modifier: Modifier = Modifier,
    borderColor: Color = Color(0xFF00B4D8),
    borderWidth: Float = 1.5f,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.05f),
                            Color.White.copy(alpha = 0.01f)
                        )
                    )
                )
                .clip(RoundedCornerShape(16.dp))
                .border(
                    width = borderWidth,
                    color = borderColor.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            content()
        }
    }
}

// ============================================================================
// ANIMATED BUTTONS
// ============================================================================

@Composable
fun GradientButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    colors: List<Color> = listOf(
        Color(0xFF006878),
        Color(0xFF00B4D8)
    ),
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent
        ),
        enabled = enabled && !isLoading
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = Color.White
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                ),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
    
    // Animated gradient overlay
    if (!isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(colors = colors)
                )
                .offset(
                    x = { 
                        val infiniteTransition = rememberInfiniteTransition(label = "buttonGradient")
                        infiniteTransition.animateFloat(
                            initialValue = -100f,
                            targetValue = 400f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(3000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "gradientMove"
                        )
                    }(),
                    y = 0.dp
                )
                .alpha(0.15f)
        )
    }
}

@Composable
fun GradientIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        Color(0xFF006878),
        Color(0xFF00B4D8)
    ),
    tint: Color = Color.White
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    Brush.linearGradient(colors = colors),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.align(Alignment.Center),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ============================================================================
// PULSING INDICATORS
// ============================================================================

@Composable
fun PulsingDot(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF00B4D8),
    size: Dp = 8.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )
    
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .background(color, CircleShape)
    )
}

@Composable
fun PulsingRing(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF00B4D8),
    size: Dp = 40.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ring")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )
    
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

// ============================================================================
// SHIMMER LOADING
// ============================================================================

@Composable
fun ShimmerCard(
    modifier: Modifier = Modifier,
    width: Dp = 300.dp,
    height: Dp = 150.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerColor by infiniteTransition.animateColor(
        initialValue = Color(0xFFE0E0E0),
        targetValue = Color(0xFFB0B0B0),
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF5F5F5))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            shimmerColor,
                            Color(0xFFE0E0E0),
                            shimmerColor
                        )
                    )
                )
                .offset(
                    x = { 
                        infiniteTransition.animateFloat(
                            initialValue = -width,
                            targetValue = width * 2,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1500, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "shimmerMove"
                        )
                    }(),
                    y = 0.dp
                )
        )
    }
}

// ============================================================================
// ANIMATED CHAT BUBBLES
// ============================================================================

@Composable
fun AnimatedMessageBubble(
    message: String,
    isUser: Boolean,
    avatar: String? = null,
    modifier: Modifier = Modifier
) {
    val enterAnim = if (isUser) {
        slideInHorizontally(initialOffsetX = { it }) + fadeIn()
    } else {
        slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
    }
    
    val exitAnim = if (isUser) {
        slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
    } else {
        slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateEnterExit(enter = enterAnim, exit = exitAnim)
            .padding(vertical = 4.dp)
    ) {
        if (avatar != null && !isUser) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircleAvatar(
                    modifier = Modifier.size(32.dp),
                    backgroundColor = Color(0xFF006878)
                ) {
                    Text(
                        text = avatar.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            if (isUser) {
                Box(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 12.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF006878),
                                        Color(0xFF00B4D8)
                                    )
                                ),
                                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)
                            ),
                        color = Color(0xFF006878)
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp)
                        ),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// GRADIENT TEXT
// ============================================================================

@Composable
fun GradientText(
    text: String,
    colors: List<Color> = listOf(
        Color(0xFF006878),
        Color(0xFF00B4D8),
        Color(0xFF0077B6)
    ),
    modifier: Modifier = Modifier,
    fontSize: Dp = 20.dp,
    fontWeight: FontWeight = FontWeight.Bold
) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontSize = fontSize,
            fontWeight = fontWeight
        ),
        color = Color.Unspecified,
        modifier = modifier,
        style = MaterialTheme.typography.headlineSmall
    )
}

// ============================================================================
// GLASS INPUT FIELD
// ============================================================================

@Composable
fun GlassInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF00B4D8),
            unfocusedBorderColor = Color(0xFFB0BEC5),
            focusedContainerColor = Color.White.copy(alpha = 0.05f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
            cursorColor = Color(0xFF00B4D8)
        ),
        singleLine = true,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = ProximaNovaFontFamily
        )
    )
}

// ============================================================================
// ACCENT BADGE
// ============================================================================

@Composable
fun AccentBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium
            ),
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

// ============================================================================
// SECTION HEADER
// ============================================================================

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(vertical = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                ),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ============================================================================
// MODERN DIVIDER
// ============================================================================

@Composable
fun ModernDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
) {
    Divider(
        modifier = modifier,
        color = color,
        thickness = 0.5.dp
    )
}

// ============================================================================
// PROGRESS RING
// ============================================================================

@Composable
fun ProgressRing(
    progress: Float,
    size: Dp = 40.dp,
    strokeWidth: Dp = 4.dp,
    color: Color = Color(0xFF00B4D8),
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
    ) {
        // Track
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(trackColor)
        )
        
        // Progress
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(strokeWidth)
                .background(
                    color.copy(alpha = 0.2f),
                    shape = CircleShape
                )
        )
        
        // Animated progress arc
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(strokeWidth)
                .rotate(-90f)
        ) {
            CircularProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxSize(),
                strokeWidth = strokeWidth,
                color = color,
                strokeCap = StrokeCap.Round
            )
        }
    }
}

// ============================================================================
// FLOATING ACTION CARD
// ============================================================================

@Composable
fun FloatingActionCard(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        Color(0xFF006878),
        Color(0xFF00B4D8)
    )
) {
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(colors = colors),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = Color.White,
            modifier = Modifier.align(Alignment.Center),
            modifier = Modifier.size(28.dp)
        )
    }
}

// ============================================================================
// SKELETON LOADER
// ============================================================================

@Composable
fun SkeletonLoader(
    modifier: Modifier = Modifier,
    width: Dp = 100.dp,
    height: Dp = 20.dp,
    borderRadius: RoundedCornerShape = RoundedCornerShape(4.dp)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val shimmerColor by infiniteTransition.animateColor(
        initialValue = Color(0xFFE0E0E0),
        targetValue = Color(0xFFB0B0B0),
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(borderRadius)
            .background(Color(0xFFF5F5F5))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            shimmerColor,
                            Color(0xFFE0E0E0),
                            shimmerColor
                        )
                    )
                )
                .offset(
                    x = { 
                        infiniteTransition.animateFloat(
                            initialValue = -width,
                            targetValue = width * 2,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1500, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "shimmerMove"
                        )
                    }(),
                    y = 0.dp
                )
        )
    }
}
