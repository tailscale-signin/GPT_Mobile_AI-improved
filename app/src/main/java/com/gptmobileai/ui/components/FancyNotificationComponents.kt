package com.gptmobileai.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gptmobileai.R

/**
 * Fancy animated notification icon with pulse effect and gradient background.
 * Clicking navigates to the relevant conversation.
 */
@Composable
fun FancyNotificationIcon(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    unreadCount: Int = 0,
    showBadge: Boolean = true,
    iconResId: Int = R.drawable.ic_notification,
    label: String? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "notification_pulse")
    
    // Pulse animation for the icon background
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    
    // Opacity animation for the badge
    val badgeOpacity by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "badge_opacity"
    )
    
    // Gradient colors for the fancy background
    val gradientColors = listOf(
        Color(0xFF6366F1), // Indigo
        Color(0xFF8B5CF6), // Violet
        Color(0xFFEC4899), // Pink
        Color(0xFFF472B6)  // Light pink
    )
    
    Column(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(gradientColors),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Animated icon with pulse effect
        Icon(
            painter = painterResource(id = iconResId),
            contentDescription = "Notifications",
            tint = Color.White,
            modifier = Modifier
                .size(32.dp)
                .scale(pulseScale)
        )
        
        // Unread count badge with pulse animation
        if (showBadge && unreadCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-12).dp, y = (-12).dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Red.copy(alpha = badgeOpacity))
                    .clickable(onClick = onClick)
            ) {
                Text(
                    text = unreadCount.toString(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
        
        // Label text below icon
        if (label != null) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = Ellipsis
            )
        }
    }
}

/**
 * Notification row with fancy icon and conversation preview.
 */
@Composable
fun FancyNotificationRow(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    unreadCount: Int = 0,
    title: String,
    subtitle: String? = null,
    timestamp: String? = null,
    iconResId: Int = R.drawable.ic_notification
) {
    val infiniteTransition = rememberInfiniteTransition(label = "notification_row_pulse")
    
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "row_pulse"
    )
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
    ) {
        // Fancy notification icon
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF6366F1), Color(0xFFEC4899))
                    )
                )
        ) {
            Icon(
                painter = painterResource(id = iconResId),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Notification content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                if (timestamp != null) {
                    Text(
                        text = timestamp,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = Ellipsis
                )
            }
        }
        
        // Unread badge
        if (unreadCount > 0) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Red.copy(alpha = 0.9f))
            ) {
                Text(
                    text = unreadCount.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
    }
}
