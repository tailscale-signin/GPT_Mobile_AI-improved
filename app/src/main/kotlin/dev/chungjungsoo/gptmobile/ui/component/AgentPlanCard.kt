package dev.chungjungsoo.gptmobile.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chungjungsoo.gptmobile.data.model.AgentPlan
import dev.chungjungsoo.gptmobile.data.model.AgentPlanStatus
import dev.chungjungsoo.gptmobile.data.model.AgentStepStatus
import dev.chungjungsoo.gptmobile.data.model.AgentTaskStep

/**
 * AgentPlanCard - Enhanced agent workflow execution display featuring:
 * - Color-coded progress bar with dynamic animations
 * - Clear visual indicators for step completion states
 * - Animated expand/collapse transitions with state descriptions
 * - Detailed information display for expanded steps (tool names, snippets, durations)
 * - Full accessibility and screen reader support
 */
@Composable
fun AgentPlanCard(
    plan: AgentPlan,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    val completedSteps = plan.steps.count { it.status == AgentStepStatus.SUCCESS }
    val failedSteps = plan.steps.count { it.status == AgentStepStatus.FAILED }
    val totalSteps = plan.steps.size
    val progress = if (totalSteps > 0) completedSteps.toFloat() / totalSteps.toFloat() else 0f

    val progressColor = when {
        failedSteps > 0 -> MaterialTheme.colorScheme.error
        completedSteps == totalSteps && totalSteps > 0 -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.primary
    }

    val expandStateDesc = if (isExpanded) "Expanded, showing $totalSteps steps" else "Collapsed, showing $completedSteps of $totalSteps completed"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics {
                contentDescription = "Agent plan: ${plan.title}"
                stateDescription = expandStateDesc
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { isExpanded = !isExpanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    when (plan.status) {
                        AgentPlanStatus.IN_PROGRESS -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary,
                                strokeCap = StrokeCap.Round
                            )
                        }
                        AgentPlanStatus.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Status: Completed",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        AgentPlanStatus.FAILED -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Status: Failed",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        AgentPlanStatus.WAITING_USER_INPUT -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Status: Waiting for input",
                                tint = Color(0xFFFF9800),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        AgentPlanStatus.NOT_STARTED -> {
                            Icon(
                                imageVector = Icons.Default.PendingActions,
                                contentDescription = "Status: Not started",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = plan.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = progressColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "$completedSteps/$totalSteps",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = progressColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse plan steps" else "Expand plan steps",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Visual Color-Coded Progress Bar
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                strokeCap = StrokeCap.Round
            )

            // Steps List with expand/collapse animation
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(250, easing = LinearOutSlowInEasing)) + fadeIn(),
                exit = shrinkVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    plan.steps.forEach { step ->
                        AgentTaskStepRow(step = step)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentTaskStepRow(step: AgentTaskStep) {
    var isStepExpanded by remember { mutableStateOf(false) }
    val hasDetails = !step.resultSnippet.isNullOrBlank() || !step.toolName.isNullOrBlank()

    val stepBorderColor = when (step.status) {
        AgentStepStatus.RUNNING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        AgentStepStatus.FAILED -> MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
        AgentStepStatus.SUCCESS -> Color(0xFF4CAF50).copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    val stepBackgroundColor = when (step.status) {
        AgentStepStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        AgentStepStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        AgentStepStatus.SUCCESS -> Color(0xFF4CAF50).copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    }

    val statusDescription = when (step.status) {
        AgentStepStatus.RUNNING -> "In progress"
        AgentStepStatus.SUCCESS -> "Completed successfully"
        AgentStepStatus.FAILED -> "Failed"
        AgentStepStatus.PENDING -> "Pending"
        AgentStepStatus.SKIPPED -> "Skipped"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(stepBackgroundColor)
            .then(
                if (stepBorderColor != Color.Transparent) {
                    Modifier.border(1.dp, stepBorderColor, RoundedCornerShape(10.dp))
                } else Modifier
            )
            .clickable(enabled = hasDetails, role = Role.Button) {
                isStepExpanded = !isStepExpanded
            }
            .padding(10.dp)
            .semantics {
                contentDescription = "Step ${step.stepNumber}: ${step.description}. Status: $statusDescription"
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Icon Indicator
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                when (step.status) {
                    AgentStepStatus.RUNNING -> {
                        val infiniteTransition = rememberInfiniteTransition(label = "runningStep")
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = LinearOutSlowInEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "runningRotation"
                        )
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(rotation),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            strokeCap = StrokeCap.Round
                        )
                    }
                    AgentStepStatus.SUCCESS -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Step Succeeded",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    AgentStepStatus.FAILED -> {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Step Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    AgentStepStatus.PENDING -> {
                        Icon(
                            imageVector = Icons.Default.HourglassEmpty,
                            contentDescription = "Step Pending",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    AgentStepStatus.SKIPPED -> {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Step Skipped",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${step.stepNumber}. ${step.description}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                    fontWeight = if (step.status == AgentStepStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
                    color = when (step.status) {
                        AgentStepStatus.FAILED -> MaterialTheme.colorScheme.error
                        AgentStepStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )

                if (step.toolName != null && !isStepExpanded) {
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = step.toolName,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            if (hasDetails) {
                Icon(
                    imageVector = if (isStepExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isStepExpanded) "Collapse step details" else "Expand step details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Detailed Information Display on Step Expansion
        AnimatedVisibility(
            visible = isStepExpanded && hasDetails,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 30.dp)
            ) {
                step.toolName?.let { tool ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = "Tool name",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = tool,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                step.resultSnippet?.let { snippet ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        border = borderStepSnippet(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = snippet,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun borderStepSnippet() = androidx.compose.foundation.BorderStroke(
    width = 0.5.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
)
