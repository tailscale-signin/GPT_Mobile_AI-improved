package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.openrouter.OpenRouterCreditsData
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import java.text.NumberFormat
import java.util.Locale

sealed interface OpenRouterCreditsUiState {
    object Idle : OpenRouterCreditsUiState
    object Loading : OpenRouterCreditsUiState
    data class Success(
        val credits: OpenRouterCreditsData,
        val lastUpdatedTime: String
    ) : OpenRouterCreditsUiState
    data class Error(val message: String) : OpenRouterCreditsUiState
}

@Composable
fun FancyOpenRouterCreditsCard(
    uiState: OpenRouterCreditsUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currencyFormat = rememberCurrencyFormat()
    dev.chungjungsoo.gptmobile.presentation.designsystem.AppPanel(modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.openrouter_credits), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onRefresh, enabled = uiState !is OpenRouterCreditsUiState.Loading) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.refresh), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (uiState) {
            OpenRouterCreditsUiState.Idle -> Text("Refresh to check your credit balance.", style = MaterialTheme.typography.bodyMedium)
            OpenRouterCreditsUiState.Loading -> {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.openrouter_fetching_credits))
            }
            is OpenRouterCreditsUiState.Error -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Text(uiState.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                }
                androidx.compose.material3.TextButton(onClick = onRefresh) { Text(stringResource(R.string.tap_to_retry)) }
            }
            is OpenRouterCreditsUiState.Success -> {
                val credits = uiState.credits
                val isLow = credits.totalCredits > 0 && (credits.remaining < credits.totalCredits * 0.1 || credits.remaining <= 0.5)
                val status = when {
                    credits.remaining <= 0 -> R.string.openrouter_credits_empty
                    isLow -> R.string.openrouter_credits_low
                    else -> R.string.openrouter_credits_available
                }
                Text(stringResource(status), color = if (credits.remaining <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(currencyFormat.format(credits.remaining), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.openrouter_remaining_credits), style = MaterialTheme.typography.bodyMedium)
                MetricLine(stringResource(R.string.openrouter_used_credits), currencyFormat.format(credits.totalUsage))
                androidx.compose.material3.LinearProgressIndicator(progress = { (credits.usagePercentage / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.openrouter_usage_summary, String.format(Locale.US, "%.1f", credits.usagePercentage), currencyFormat.format(credits.totalCredits)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.openrouter_updated_at, uiState.lastUpdatedTime), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun rememberCurrencyFormat(): NumberFormat = androidx.compose.runtime.remember {
    NumberFormat.getCurrencyInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 4
    }
}
