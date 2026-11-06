package dev.chungjungsoo.gptmobile.presentation.ui.benchmark

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardObservation
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardPhase
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardState
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardTarget
import dev.chungjungsoo.gptmobile.data.network.gateway.parseGatewayDashboard
import dev.chungjungsoo.gptmobile.presentation.ui.setting.GitHubGatewayStatusContent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Production dashboard rendering with scripted states; this fixture never opens a socket. */
@Composable
internal fun GatewayReconnectBenchmarkFixture() {
    val snapshot = remember {
        parseGatewayDashboard(
            Json.parseToJsonElement(
                """{"schema":"gptmobile.gateway.dashboard.v1","schemaVersion":1,"scope":"admitted_identity",
                    "instanceId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","capturedAt":100,
                    "availability":{"usage":true,"service":true,"openJobs":true,"activities":true},
                    "usage":{"available":true,"outputTokens":12345,"tokensPerSecond":18.2,"days":[]},
                    "service":{"activeWorkers":1,"maxWorkers":2,"draining":false},"openJobs":1,
                    "activities":[{"id":"job-fixture","kind":"engineering","title":"Verify repository change","status":"running","updated":100}]}"""
            ) as JsonObject
        )
    }
    val target = remember { GatewayDashboardTarget("benchmark", "Gateway fixture", "127.0.0.1:8090", true, null) }
    var cycle by remember { mutableIntStateOf(0) }
    var completed by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(GatewayDashboardPhase.LIVE) }
    LaunchedEffect(cycle) {
        if (cycle == 0) return@LaunchedEffect
        phase = GatewayDashboardPhase.OFFLINE
        delay(350)
        phase = GatewayDashboardPhase.RECONNECTING
        delay(350)
        phase = GatewayDashboardPhase.DEGRADED
        delay(350)
        phase = GatewayDashboardPhase.LIVE
        completed = cycle
    }
    Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        Button(onClick = { cycle++ }, enabled = cycle == completed, modifier = Modifier.fillMaxWidth().testTag("benchmark_gateway_cycle")) { Text("Cycle gateway state") }
        Text("Cycles: $completed", Modifier.testTag("benchmark_gateway_done"))
        LazyColumn(state = rememberLazyListState(), modifier = Modifier.weight(1f).testTag("benchmark_gateway_dashboard")) {
            item(key = "gateway") {
                GitHubGatewayStatusContent(GatewayDashboardState(target, phase, GatewayDashboardObservation(snapshot, 100_000)), 100_000) {}
            }
        }
    }
}
