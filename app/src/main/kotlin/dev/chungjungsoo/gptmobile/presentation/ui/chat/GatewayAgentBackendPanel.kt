package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.chungjungsoo.gptmobile.data.github.GatewayAgentWorkspace
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.workspaceFlag
import dev.chungjungsoo.gptmobile.data.github.workspaceStrings
import dev.chungjungsoo.gptmobile.data.github.workspaceText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
internal fun GatewayAgentBackendPanel(board: GatewayAgentWorkspace, binding: GatewayEngineeringBinding, enabled: Boolean, model: GatewayEngineeringViewModel) {
    var compared by remember(board.jobId, board.headSha) { mutableStateOf(emptySet<String>()) }
    var consented by remember(board.jobId, board.headSha) { mutableStateOf(emptySet<String>()) }
    Text("Agent backends", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("gateway_agent_backends"))
    Text("The Gateway lists configured runtimes and their actual capabilities. The existing job owns source, budgets, review and publication.", style = MaterialTheme.typography.bodySmall)
    if (board.agents.isEmpty()) Text("No external agent runtime is configured on this Gateway. Its current selected-model workflow remains available.")
    board.agents.forEach { agent ->
        val id = agent.workspaceText("id").orEmpty()
        val capabilities = agent.workspaceStrings("capabilities")
        val remote = agent.workspaceFlag("requires_remote_consent")
        val consent = !remote || id in consented
        GatewayWorkspaceItem(agent.workspaceText("label") ?: id) {
            Text(agent.workspaceText("state") ?: "Availability unknown")
            agent.workspaceText("reason")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("${agent.workspaceText("execution_location") ?: "Execution location not reported"} · ${agent.workspaceText("model_id") ?: "Model not configured"}", style = MaterialTheme.typography.bodySmall)
            if (capabilities.isNotEmpty()) Text(capabilities.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            if (remote && agent.workspaceFlag("ready")) {
                Row {
                    Checkbox(checked = consent, onCheckedChange = { consented = if (it) consented + id else consented - id }, enabled = enabled)
                    Text("Allow this configured agent to receive the accepted task and repository source.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (board.canRun("agent_qualify")) TextButton(onClick = { model.workspaceCommand(binding, "agent_qualify", buildJsonObject { put("backend", id) }) }, enabled = enabled && id.isNotBlank() && agent.workspaceFlag("configured")) { Text("Check runtime") }
                if (board.canRun("agent_start")) {
                    TextButton(
                        enabled = enabled && id.isNotBlank() && agent.workspaceFlag("ready") && "start" in capabilities && (!remote || consent),
                        onClick = {
                            model.workspaceCommand(
                                binding,
                                "agent_start",
                                buildJsonObject {
                                    put("backend", id)
                                    put("objective", binding.request.objective)
                                    agent.workspaceText("model_id")?.let { put("model_id", it) }
                                    put("consent", consent)
                                }
                            )
                        }
                    ) { Text("Run accepted task") }
                }
            }
            if (board.canRun("agent_compare") && agent.workspaceFlag("ready") && "start" in capabilities) {
                Row {
                    Checkbox(checked = id in compared, onCheckedChange = { compared = if (it) compared + id else compared - id }, enabled = enabled && (id in compared || compared.size < 4))
                    Text("Include in agent comparison", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (board.canRun("agent_compare")) {
        val selected = board.agents.filter { it.workspaceText("id") in compared }
        Text("Agent-system comparison uses the same accepted task and source with separate runtimes and candidates. Model-only benchmarks remain a separate comparison.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = enabled && selected.size in 2..4 && selected.all { it.workspaceFlag("ready") && (!it.workspaceFlag("requires_remote_consent") || it.workspaceText("id") in consented) }, onClick = {
            model.workspaceCommand(
                binding,
                "agent_compare",
                buildJsonObject {
                    put("backends", JsonArray(selected.map { JsonPrimitive(requireNotNull(it.workspaceText("id"))) }))
                    put("objective", binding.request.objective)
                    put("consent", true)
                }
            )
        }) { Text("Compare ${compared.size} configured agents") }
    }
    Text("Saved agent runs", style = MaterialTheme.typography.titleMedium)
    if (board.agentRuns.isEmpty()) Text("No external agent run has been started for this job.")
    board.agentRuns.forEach { run -> GatewayAgentRun(board, binding, run, enabled, model) }
    if (board.productReferences.isNotEmpty()) {
        Text("Product and connector references", style = MaterialTheme.typography.titleMedium)
        board.productReferences.filter { it["executable"] == JsonPrimitive(false) }.forEach { reference ->
            GatewayWorkspaceItem(reference.workspaceText("name") ?: reference.workspaceText("id").orEmpty()) {
                Text(if (reference.workspaceText("state") == "CONFIGURED_CONNECTOR") "Configured connector reference" else "Workflow reference", style = MaterialTheme.typography.labelSmall)
                reference.workspaceText("feature")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun GatewayAgentRun(board: GatewayAgentWorkspace, binding: GatewayEngineeringBinding, run: JsonObject, enabled: Boolean, model: GatewayEngineeringViewModel) {
    val id = run.workspaceText("run_id").orEmpty()
    val source = run.workspaceText("source_sha")
    val capabilities = run.workspaceStrings("capabilities")
    val sameSource = source == board.headSha
    var message by remember(board.jobId, id, source) { mutableStateOf("") }
    GatewayWorkspaceItem("${run.workspaceText("backend").orEmpty()} · ${run.workspaceText("state") ?: "Unknown"}") {
        Text("$id · ${source?.take(12) ?: "Source not reported"}", style = MaterialTheme.typography.labelSmall)
        listOf("model_id", "operation_state", "candidate_status", "session_id").forEach { key -> run.workspaceText(key)?.let { Text("${key.replace('_', ' ')} · $it", style = MaterialTheme.typography.bodySmall) } }
        if (!sameSource) Text("This run belongs to an earlier source. Its evidence remains historical.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("agent_status" to "Check run", "agent_events" to "Read events", "agent_artifacts" to "Read artifacts", "agent_import" to "Import candidate").forEach { (action, label) ->
                val capability = action.removePrefix("agent_")
                if (board.canRun(action) && capability in capabilities) {
                    TextButton(
                        onClick = { model.workspaceCommand(binding, action, buildJsonObject { put("run_id", id) }) },
                        enabled = enabled && id.isNotBlank() && sameSource
                    ) { Text(label) }
                }
            }
            if (board.canRun("agent_cancel") && "cancel" in capabilities) TextButton(onClick = { model.workspaceCommand(binding, "agent_cancel", buildJsonObject { put("run_id", id) }) }, enabled = enabled && id.isNotBlank() && sameSource) { Text("Stop agent") }
        }
        if (board.canRun("agent_approve_plan") && "approve_plan" in capabilities) TextButton(onClick = { model.workspaceCommand(binding, "agent_approve_plan", buildJsonObject { put("run_id", id) }) }, enabled = enabled && id.isNotBlank() && sameSource) { Text("Approve agent plan") }
        if (board.canRun("agent_steer") && "steer" in capabilities && sameSource) {
            OutlinedTextField(message, { message = it.take(2000) }, label = { Text("Continue this agent within the accepted task") }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = enabled && message.isNotBlank(), onClick = {
                model.workspaceCommand(
                    binding,
                    "agent_steer",
                    buildJsonObject {
                        put("run_id", id)
                        put("message", message.trim())
                        run.workspaceText("remote_run_id")?.let { put("expected_remote_run_id", it) }
                    }
                )
            }) { Text("Send instruction") }
        }
        GatewayWorkspaceReceipt("Agent execution identity", run.toString())
    }
}
