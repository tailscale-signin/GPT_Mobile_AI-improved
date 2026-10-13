package dev.chungjungsoo.gptmobile.presentation.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.pricing.PriceFreshness
import dev.chungjungsoo.gptmobile.data.pricing.PriceSource
import dev.chungjungsoo.gptmobile.data.pricing.formatRate
import dev.chungjungsoo.gptmobile.presentation.common.ThemeIcon as Icon
import java.text.DateFormat
import java.util.Date

@Composable
internal fun NewChatProfileInsights(insight: ProfileInsight, refresh: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, locale)
    var detail by remember(insight.profile.model, insight.profile.apiUrl) { mutableStateOf<String?>(null) }
    val local = insight.profile.compatibleType == ClientType.LITERT_LM
    val score = insight.score
    val price = insight.price
    val green = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF256B2A)
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
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (insight.settings.newChatScores) {
            TextButton(onClick = { detail = "score" }) {
                Icon(Icons.Rounded.QueryStats, null)
                Text(benchmarkText, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (insight.settings.newChatPrices) {
            TextButton(onClick = { detail = "price" }) {
                Icon(Icons.Rounded.Payments, null, tint = if (price != null || local) green else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(priceText, Modifier.padding(start = 8.dp), color = if (price != null || local) green else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
            if (price?.routingReference == true) Text(stringResource(R.string.insights_route_reference), style = MaterialTheme.typography.bodySmall)
            if (price?.conditionalPricing == true) Text(stringResource(R.string.insights_conditional), style = MaterialTheme.typography.bodySmall)
            freshness?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    detail?.let { selected ->
        val uri = LocalUriHandler.current
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(if (selected == "score") benchmarkText else stringResource(R.string.insights_price_details)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(insight.profile.model)
                if (selected == "score") {
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
                } else {
                    Text(priceText, color = if (price != null || local) green else MaterialTheme.colorScheme.onSurfaceVariant)
                    freshness?.let { Text(it) }
                    Text(stringResource(R.string.insights_list_scope))
                    price?.cachedInputPerMillion?.let { Text(stringResource(R.string.insights_cached_rate, price.currency, formatRate(it))) }
                    price?.extras?.forEach { (dimension, value) -> Text("$dimension: ${price.currency} $value") }
                    if (price?.conditionalPricing == true) Text(stringResource(R.string.insights_conditional))
                    if (price?.sourceUrl?.startsWith("https://") == true) TextButton(onClick = { uri.openUri(price.sourceUrl) }) { Text(stringResource(R.string.insights_source)) }
                    TextButton(onClick = refresh, enabled = !insight.priceRefreshing) { Text(stringResource(if (insight.priceRefreshing) R.string.insights_refreshing else R.string.insights_refresh)) }
                    insight.priceMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text(stringResource(R.string.close)) } })
    }
}
