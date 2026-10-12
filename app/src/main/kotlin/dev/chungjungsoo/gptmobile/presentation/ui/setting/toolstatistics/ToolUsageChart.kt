package dev.chungjungsoo.gptmobile.presentation.ui.setting.toolstatistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository

@Composable
internal fun ToolUsageChart(plugin: String, repository: ToolStatisticsRepository, scope: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsScope = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsScope()) {
    val nowHour = remember(scope) { System.currentTimeMillis() / 3600000L }
    val until = if (scope.untilHour == Long.MAX_VALUE) nowHour + 1 else scope.untilHour
    val since = if (scope.fromHour < 0) until - 90 * 24 else scope.fromHour
    val days = ((until - since + 23) / 24).toInt().coerceIn(1, 90)
    var table by rememberSaveable { mutableStateOf(false) }
    val today = (until - 1) / 24
    val records by remember(plugin, days, scope) { repository.chart(plugin, since, scope) }.collectAsStateWithLifecycle(emptyList())
    val values = (since / 24..today).map { day -> day to records.filter { it.bucket / 24 == day }.sumOf { it.total } }
    val total = values.sumOf { it.second }
    val color = MaterialTheme.colorScheme.primary
    Column {
        Text(if (scope.fromHour < 0) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_5cd2e3edb3) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_a8ff4ad4e1))
        Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_8eeb79c14c, total))
        TextButton(onClick = { table = !table }) { Text(if (table) androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_a58524835e) else androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_text_8132a5f6d5)) }
        if (table) {
            // Independent bounded scroll keeps 30 data rows out of the parent composition.
            LazyColumn(Modifier.height(180.dp)) { values.forEach { (day, count) -> item { Text("${java.time.LocalDate.ofEpochDay(day)}: $count", Modifier.padding(4.dp)) } } }
        } else if (total > 0) {
            Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "$total recorded uses in $days UTC days. Use Show data table for daily counts." }) {
                val maximum = values.maxOf { it.second }.coerceAtLeast(1)
                val width = size.width / values.size
                values.forEachIndexed { index, (_, count) ->
                    val height = size.height * count.toFloat() / maximum
                    drawRect(color, Offset(index * width, size.height - height), Size(width * 0.8f, height))
                }
            }
        } else {
            Text(androidx.compose.ui.res.stringResource(dev.chungjungsoo.gptmobile.R.string.v0950_no_uses_recorded_in_this_period_collection_ga_b5ff3))
        }
    }
}
