package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApi
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApiFactory
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubLiveProcess
import dev.chungjungsoo.gptmobile.data.github.GitHubLiveStatus
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.github.githubElapsedTime
import dev.chungjungsoo.gptmobile.data.github.liveProcess
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@HiltViewModel
internal class GitHubLiveStatusViewModel @Inject constructor(private val auth: GitHubPublicAuthCoordinator, private val factory: GitHubEngineeringApiFactory, private val connections: dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository) : ViewModel() {
    private val mutable = MutableStateFlow(GitHubLiveStatus())
    val state = mutable.asStateFlow()
    suspend fun observe(connection: ToolConnection, selection: GitHubRepositoryContext, refreshPulls: () -> Unit) {
        mutable.value = GitHubLiveStatus()
        var cycle = 0
        while (true) {
            try {
                val api = factory.create { auth.accessToken(requireNotNull(connections.getConnection(connection.connectionUid))) }
                val root = GitHubEngineeringApi.root(selection.owner, selection.repo)
                val payload = Json.parseToJsonElement(api.request("$root/actions/runs?branch=${GitHubWorkspaceClient.segment(selection.ref)}&per_page=10")) as JsonObject
                val runs = (payload["workflow_runs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter {
                    (it["head_branch"] as? JsonPrimitive)?.content == selection.ref &&
                        ((it["head_repository"] as? JsonObject)?.get("full_name") as? JsonPrimitive)?.contentOrNull?.equals(selection.fullName, true) == true
                }
                val rows = mutableListOf<GitHubLiveProcess>()
                for (run in runs) {
                    val item = liveProcess(run)
                    rows += item
                    if (item.status in setOf("in_progress", "queued", "waiting", "pending", "requested") && rows.size < 60) {
                        val jobs = Json.parseToJsonElement(api.request("$root/actions/runs/${item.id}/jobs?per_page=100")) as JsonObject
                        rows += (jobs["jobs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.take(20).map { liveProcess(it, "job:") }
                    }
                }
                mutable.value = GitHubLiveStatus(rows, checkedAt = System.currentTimeMillis())
                if (cycle++ % 3 == 0) refreshPulls()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = "Live results could not refresh. Showing the last observed results.")
            }
            delay(if (mutable.value.error == null) 15_000 else 30_000)
        }
    }
}

@Composable
internal fun GitHubStatusLight(status: String, modifier: Modifier = Modifier) {
    val color = when (status.uppercase()) {
        "SUCCESS", "PASSED", "MERGED" -> Color(0xFF55C99A)
        "FAILURE", "FAILED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE", "BLOCKED", "CONFLICTS" -> MaterialTheme.colorScheme.error
        "RUNNING", "IN_PROGRESS", "QUEUED", "WAITING", "PENDING", "REQUESTED", "RETRYING", "DRAFT" -> Color(0xFFE8BC58)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Canvas(modifier.size(12.dp)) {
        drawCircle(color.copy(alpha = 0.18f), radius = size.minDimension / 2)
        drawCircle(color, radius = size.minDimension * 0.30f)
    }
}

@Composable
internal fun GitHubRepositoryStatusPanel(connection: ToolConnection, selection: GitHubRepositoryContext, pulls: List<JsonObject>, refreshPulls: () -> Unit, model: GitHubLiveStatusViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connection, selection, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observe(connection, selection, refreshPulls) }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Workflows · ${selection.ref}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("Live refresh every 15 seconds · elapsed time from GitHub · remaining time unavailable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.rows.isEmpty()) Text(if (state.checkedAt == null) "Loading workflow results…" else "No workflow runs found for this branch.")
        state.rows.forEach { row ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .18f)), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GitHubStatusLight(row.status)
                    Column(Modifier.weight(1f)) {
                        Text(row.title, style = MaterialTheme.typography.titleSmall)
                        Text(row.status.replace('_', ' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (row.steps.isNotBlank()) Text(row.steps, style = MaterialTheme.typography.bodySmall)
                    }
                    row.started?.let { start ->
                        // Completed run updated_at is not a trustworthy completion time.
                        Text(
                            if (row.completed != null) {
                                "Total ${githubElapsedTime(start, row.completed)}"
                            } else if (row.status in setOf("in_progress", "queued", "waiting", "pending", "requested")) {
                                githubElapsedTime(start, now)
                            } else {
                                "Total unavailable"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        state.checkedAt?.let { Text("Updated ${githubElapsedTime(it, now)} ago · ${pulls.size} PRs loaded", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
