package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.pricing.PriceFreshness
import dev.chungjungsoo.gptmobile.data.pricing.PriceSource
import dev.chungjungsoo.gptmobile.data.pricing.formatRate
import dev.chungjungsoo.gptmobile.presentation.common.FadingAlertDialog as AlertDialog
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ProfileInsightDetailsDialog(insight: ProfileInsight, refresh: () -> Unit, onFavorite: () -> Unit, onDismiss: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, locale)
    val local = insight.profile.compatibleType == ClientType.LITERT_LM
    val score = insight.score
    val price = insight.price
    val green = MaterialTheme.colorScheme.primary
    val benchmarkText = when {
        local -> stringResource(R.string.insights_device_benchmark)
        score != null -> stringResource(R.string.insights_score, String.format(locale, "%.0f", score.score), if (insight.community) stringResource(R.string.insights_community) else stringResource(R.string.insights_my_best), score.submission.plan.label)
        else -> stringResource(R.string.insights_not_benchmarked)
    }
    val freshness = price?.let {
        val date = it.reviewedOn ?: dateFormat.format(Date(it.validatedAt))
        val source = when (it.source) {
            PriceSource.PROVIDER_API -> stringResource(R.string.insights_api)
            PriceSource.REVIEWED -> stringResource(R.string.insights_reviewed)
            PriceSource.ACCOUNT_OVERRIDE -> stringResource(R.string.insights_manual)
        }
        "${if (it.freshness(insight.now) == PriceFreshness.STALE) stringResource(R.string.insights_stale) + " · " else ""}$source · $date"
    }
    val priceText = when {
        local -> stringResource(R.string.insights_on_device_price)
        price == null -> stringResource(R.string.insights_price_unknown)
        else -> stringResource(R.string.insights_rates, price.currency, formatRate(price.inputPerMillion), formatRate(price.outputPerMillion))
    }
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(insight.profile.name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(insight.profile.model)
            if (insight.settings.newChatScores) {
                Text(benchmarkText, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.insights_benchmark_scope))
                if (insight.community) Text(stringResource(R.string.insights_community_scope))
                score?.let { result ->
                    Text("${result.submission.plan.label} · Suite ${result.submission.suiteVersion} · ${dateFormat.format(Date(result.submission.startedAt))}")
                    result.metrics.forEach { Text("${it.label}: ${it.value?.let { number -> String.format(locale, "%.1f", number) } ?: "—"} ${it.unit}") }
                }
                insight.localRun?.let { run ->
                    Text(stringResource(R.string.insights_local_recorded))
                    Text("${run.device} · ${run.backend.orEmpty()} · ${run.accelerator.orEmpty()}")
                    Text("${run.mode.label} · ${dateFormat.format(Date(run.startedAt))}")
                    val metrics = run.samples.mapNotNull { it.nativeMetrics?.takeIf { metric -> metric.isValid } }
                    metrics.map { it.decodeTokensPerSecond }.sorted().let { speeds -> if (speeds.isNotEmpty()) Text(stringResource(R.string.insights_local_speed, String.format(locale, "%.1f", speeds[speeds.size / 2]))) }
                    run.peakClientPssKb?.let { Text(stringResource(R.string.insights_local_memory, it / 1024)) }
                    run.samples.mapNotNull { it.firstTextMs }.sorted().let { times -> if (times.isNotEmpty()) Text(stringResource(R.string.insights_local_latency, times[times.size / 2])) }
                }
                if (score == null && insight.localRun == null) Text(stringResource(R.string.insights_benchmark_missing))
            }
            if (insight.settings.newChatPrices) {
                Text(stringResource(R.string.insights_price_details), style = MaterialTheme.typography.titleMedium)
                if (price?.routingReference == true) Text(stringResource(R.string.insights_route_reference))
                Text(priceText, color = if (price != null || local) green else MaterialTheme.colorScheme.onSurfaceVariant)
                freshness?.let { Text(it) }
                Text(stringResource(R.string.insights_list_scope))
                price?.cachedInputPerMillion?.let { Text(stringResource(R.string.insights_cached_rate, price.currency, formatRate(it))) }
                price?.extras?.forEach { (dimension, value) -> Text("$dimension: ${price.currency} $value") }
                if (price?.conditionalPricing == true) Text(stringResource(R.string.insights_conditional))
                if (price?.sourceUrl?.startsWith("https://") == true) TextButton(onClick = { uri.openUri(price.sourceUrl) }) { Text(stringResource(R.string.insights_source)) }
                TextButton(onClick = refresh, enabled = !insight.priceRefreshing && insight.profile.compatibleType == ClientType.OPENROUTER) { Text(stringResource(if (insight.priceRefreshing) R.string.insights_refreshing else R.string.insights_refresh)) }
                insight.priceMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = onFavorite) { Text(stringResource(if (insight.profile.isFavorite) R.string.picker_remove_favorite else R.string.picker_add_favorite)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
