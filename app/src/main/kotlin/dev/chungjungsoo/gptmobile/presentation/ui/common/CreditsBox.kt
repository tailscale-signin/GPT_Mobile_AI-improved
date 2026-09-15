package dev.chungjungsoo.gptmobile.presentation.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsData

/**
 * Fancy animated credits display box for OpenRouter
 * Only visible when OpenRouter is configured as the active AI platform
 */
@Composable
fun CreditsBox(
    creditsData: OpenRouterCreditsData?,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isError: Boolean = false
) {
    if (creditsData == null) {
        if (isLoading) {
            LoadingCreditsBox(modifier = modifier)
        } else if (isError) {
            ErrorCreditsBox(modifier = modifier)
        } else {
            EmptyCreditsBox(modifier = modifier)
        }
        return
    }

    val totalCredits = creditsData.totalCredits
    val totalUsage = creditsData.totalUsage
    val remaining = creditsData.remaining
    val usagePercentage = creditsData.usagePercentage

    val status = when {
        remaining <= 0 -> CreditStatus.Low("No credits")
        remaining < totalCredits * 0.1 -> CreditStatus.Low("Low credits")
        else -> CreditStatus.Ok("Available")
    }

    val gradientColors = when (status) {
        CreditStatus.Ok -> listOf(
            Color(0xFF667eea),
            Color(0xFF764ba2)
        )
        CreditStatus.Low -> listOf(
            Color(0xFFFF6B6B),
            Color(0xFFFF8E53)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (status == CreditStatus.Ok) {
                    gradientColors.toStatelessScope().createGradient()
                } else {
                    Color(0xFFFF6B6B)
                }
            )
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "💰",
                        fontSize = 24.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Column {
                        Text(
                            text = "OpenRouter Credits",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = status.text,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Remaining Credits
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Remaining",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                    Text(
                        text = "$${String.format("%.2f", remaining)}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                Column {
                    Text(
                        text = "Used",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                    Text(
                        text = "$${String.format("%.2f", totalUsage)}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                )
                Box(
                    modifier = Modifier
                        .width((usagePercentage / 100f) * Modifier.size.width)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (usagePercentage > 90f) {
                                Color.Red
                            } else if (usagePercentage > 70f) {
                                Color(0xFFFFB74D)
                            } else {
                                Color(0xFF4CAF50)
                            }
                        )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${usagePercentage.toInt()}% used",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun LoadingCreditsBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF667eea))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 3.dp
        )
    }
}

@Composable
private fun ErrorCreditsBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFF6B6B))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "⚠️",
                fontSize = 32.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Credits not available",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Configure OpenRouter as your AI platform",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun EmptyCreditsBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF333333))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "⚠️",
                fontSize = 32.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "OpenRouter credits not available",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Configure OpenRouter as your AI platform",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp
            )
        }
    }
}

enum class CreditStatus(val text: String) {
    Ok("Available"),
    Low("Low credits")
}
