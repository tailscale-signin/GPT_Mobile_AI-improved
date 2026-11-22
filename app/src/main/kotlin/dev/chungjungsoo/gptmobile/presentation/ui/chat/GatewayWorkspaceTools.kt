package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
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
import dev.chungjungsoo.gptmobile.data.github.workspaceCount
import dev.chungjungsoo.gptmobile.data.github.workspaceFlag
import dev.chungjungsoo.gptmobile.data.github.workspaceStrings
import dev.chungjungsoo.gptmobile.data.github.workspaceText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
internal fun GatewayWorkspaceEnvironment(board: GatewayAgentWorkspace, binding: GatewayEngineeringBinding, enabled: Boolean, model: GatewayEngineeringViewModel) {
    Text("Environment qualification", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("gateway_environment_qualification"))
    Text("Runtime setup, source and executed checks are recorded separately from agent quality.", style = MaterialTheme.typography.bodySmall)
    if (board.environment.isEmpty()) {
        Text("No environment qualification receipt is retained.")
    } else {
        listOf("state", "status", "source_sha", "validation_policy_state", "runtime", "message").forEach { key -> board.environment.workspaceText(key)?.let { Text("${key.replace('_', ' ')} · $it") } }
        GatewayWorkspaceReceipt("Environment checks and coverage", board.environment.toString())
    }
    if (board.canRun("environment_qualify")) TextButton(onClick = { model.workspaceCommand(binding, "environment_qualify") }, enabled = enabled) { Text("Qualify configured environment") }
    Text("Requirements and evidence", style = MaterialTheme.typography.titleMedium)
    if (board.requirements.isEmpty()) Text("No requirement evidence is included in this observation.")
    board.requirements.forEach { requirement ->
        GatewayWorkspaceItem(requirement.workspaceText("id") ?: requirement.workspaceText("requirement_id") ?: "Accepted requirement") {
            requirement.workspaceText("status")?.let { Text(it) }
            requirement.workspaceText("state")?.let { Text(it) }
            val checks = requirement.workspaceStrings("checks")
            if (checks.isNotEmpty()) Text(checks.joinToString(" · "))
            GatewayWorkspaceReceipt("Requirement mapping and receipts", requirement.toString())
        }
    }
    val accepted = binding.job?.acceptance?.takeIf { it.isNotEmpty() } ?: binding.request.acceptance
    if (board.taskPlan.workspaceText("state") in setOf(null, "NOT_PLANNED") && accepted.isNotEmpty() && board.canRun("task_plan")) {
        TextButton(
            enabled = enabled,
            onClick = {
                model.workspaceCommand(
                    binding,
                    "task_plan",
                    buildJsonObject {
                        put(
                            "items",
                            JsonArray(
                                accepted.chunked((accepted.size + 79) / 80).mapIndexed { index, requirements ->
                                    buildJsonObject {
                                        put("id", "requirement-${index + 1}")
                                        put("requirement_ids", JsonArray(requirements.map { JsonPrimitive(it.id) }))
                                        put("depends_on", JsonArray(emptyList()))
                                        put("role", "editor")
                                    }
                                }
                            )
                        )
                    }
                )
            }
        ) { Text("Build task plan from accepted requirements") }
    }
    if (board.taskPlan.isNotEmpty()) GatewayWorkspaceReceipt("Executable task plan", board.taskPlan.toString())
    (board.taskPlan["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { item ->
        val id = item.workspaceText("id").orEmpty()
        GatewayWorkspaceItem("$id · ${item.workspaceText("state") ?: "Unknown"}") {
            Text("Requirements · ${item.workspaceStrings("requirement_ids").joinToString()}", style = MaterialTheme.typography.bodySmall)
            val dependencies = item.workspaceStrings("depends_on")
            if (dependencies.isNotEmpty()) Text("Depends on · ${dependencies.joinToString()}", style = MaterialTheme.typography.bodySmall)
            if (item.workspaceFlag("ready") && board.canRun("task_plan_complete")) {
                var hashes by remember(board.jobId, board.headSha, id) { mutableStateOf("") }
                val receipts = hashes.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()
                OutlinedTextField(hashes, { hashes = it.take(1040) }, label = { Text("Passing receipt hashes, one per line") }, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = enabled && receipts.size in 1..16 && receipts.all { it.matches(Regex("[a-f0-9]{64}")) }, onClick = {
                    model.workspaceCommand(
                        binding,
                        "task_plan_complete",
                        buildJsonObject {
                            put("id", id)
                            put("receipt_sha256", JsonArray(receipts.map(::JsonPrimitive)))
                        }
                    )
                }) { Text("Verify mapped task evidence") }
                Text("The Gateway verifies named behavior cases against this source, candidate and approved policy. Overall completion still requires the final checks.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (board.canRun("task_plan_status")) TextButton(onClick = { model.workspaceCommand(binding, "task_plan_status") }, enabled = enabled) { Text("Check task evidence") }
    Text("Active instructions", style = MaterialTheme.typography.titleMedium)
    if (board.instructions.isEmpty()) Text("No active instruction files reported.")
    board.instructions.forEach { instruction ->
        Text((instruction as? JsonPrimitive)?.content ?: instruction.toString(), style = MaterialTheme.typography.bodySmall)
    }
    Text("Retained summaries", style = MaterialTheme.typography.titleMedium)
    if (board.summaries.isEmpty()) Text("No compact evidence summary has been recorded.")
    board.summaries.forEachIndexed { index, summary -> GatewayWorkspaceReceipt("Summary ${index + 1} and source receipts", summary.toString()) }
    if (board.canRun("context_summary")) TextButton(onClick = { model.workspaceCommand(binding, "context_summary") }, enabled = enabled) { Text("Save compact evidence summary") }
}

@Composable
internal fun GatewayWorkspaceTools(board: GatewayAgentWorkspace, binding: GatewayEngineeringBinding, enabled: Boolean, model: GatewayEngineeringViewModel) {
    Text("Tools for this task", style = MaterialTheme.typography.titleMedium)
    Text("Tool profiles narrow the current job's admitted tools. Selecting a profile does not grant new access.", style = MaterialTheme.typography.bodySmall)
    if (board.canRun("tool_profile")) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("plan", "edit", "review", "test", "retrieve").forEach { profile ->
                FilterChip(selected = board.toolProfile.workspaceText("profile") == profile, enabled = enabled, onClick = { model.workspaceCommand(binding, "tool_profile", buildJsonObject { put("profile", profile) }) }, label = { Text(profile.replaceFirstChar { it.uppercase() }) })
            }
        }
    }
    if (board.toolProfile.isNotEmpty()) GatewayWorkspaceReceipt("Available tools and guidance", board.toolProfile.toString())
    GatewayWorkspaceContextPack(board, binding, enabled, model)
    GatewayWorkspaceIntegrations(board, binding, enabled, model)
    if (board.nextEditReadiness.isNotEmpty()) GatewayWorkspaceReceipt("Local next-edit availability", board.nextEditReadiness.toString())
    if (board.canRun("next_edit")) {
        var path by remember(board.jobId, board.headSha) { mutableStateOf("") }
        var instruction by remember(board.jobId, board.headSha) { mutableStateOf("") }
        Text("Local next edit", style = MaterialTheme.typography.titleMedium)
        Text("The configured local route suggests a source-bound candidate. Inspect its patch and tests before applying it.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(path, { path = it.take(400) }, label = { Text("Observed source path") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(instruction, { instruction = it.take(2000) }, label = { Text("Edit instruction") }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = enabled && board.nextEditReadiness.workspaceText("state") == "AVAILABLE" && path.isNotBlank() && instruction.isNotBlank(), onClick = {
            model.workspaceCommand(
                binding,
                "next_edit",
                buildJsonObject {
                    put("path", path.trim())
                    put("instruction", instruction.trim())
                }
            )
        }) { Text("Suggest next edit") }
    }
    GatewayWorkspaceNextEdits(board, binding, enabled, model)
}

@Composable
private fun GatewayWorkspaceContextPack(board: GatewayAgentWorkspace, binding: GatewayEngineeringBinding, enabled: Boolean, model: GatewayEngineeringViewModel) {
    if (!board.canRun("context_pack")) return
    var paths by remember(board.jobId, board.headSha) { mutableStateOf(binding.job?.changedFiles.orEmpty().take(60).joinToString("\n")) }
    var format by remember(board.jobId) { mutableStateOf("markdown") }
    var engine by remember(board.jobId) { mutableStateOf("builtin") }
    val selected = paths.lineSequence().map { it.trim() }.filter(String::isNotEmpty).distinct().toList()
    Text("Bounded repository context", style = MaterialTheme.typography.titleMedium)
    Text("Package observed files at this commit, with blob identities and explicit omissions. The complete pack remains a private job artifact.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(paths, { paths = it.take(24000) }, label = { Text("Observed paths, one per line (up to 60)") }, modifier = Modifier.fillMaxWidth())
    Row { listOf("markdown", "json", "xml").forEach { value -> FilterChip(selected = value == format, onClick = { format = value }, label = { Text(value) }) } }
    Row { listOf("builtin" to "Built-in", "repomix" to "Configured Repomix").forEach { (value, label) -> FilterChip(selected = value == engine, onClick = { engine = value }, label = { Text(label) }) } }
    TextButton(enabled = enabled && selected.size in 1..60, onClick = {
        model.workspaceCommand(
            binding,
            "context_pack",
            buildJsonObject {
                put("paths", JsonArray(selected.map(::JsonPrimitive)))
                put("format", format)
                put("engine", engine)
                put("max_bytes", 65536)
            }
        )
    }) { Text("Create context pack") }
    board.contextPack?.let { GatewayWorkspaceReceipt("Retained context pack manifest", it.toString()) }
}

@Composable
private fun GatewayWorkspaceIntegrations(board: GatewayAgentWorkspace, binding: GatewayEngineeringBinding, enabled: Boolean, model: GatewayEngineeringViewModel) {
    Text("Documentation, browser and GitHub tools", style = MaterialTheme.typography.titleMedium)
    if (board.canRun("integration_status")) TextButton(onClick = { model.workspaceCommand(binding, "integration_status") }, enabled = enabled) { Text("Discover configured tools") }
    val integrations = (board.integrations["integrations"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    if (integrations.isEmpty()) Text("No configured tool catalog has been reported. Configure approved Context7 or Playwright tools on the Gateway to use them here.", style = MaterialTheme.typography.bodySmall)
    integrations.forEach { integration ->
        val id = integration.workspaceText("id") ?: integration.workspaceText("integration").orEmpty()
        val tools = (integration["tools"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        GatewayWorkspaceItem(integration.workspaceText("label") ?: id) {
            Text(integration.workspaceText("state") ?: "Availability unknown")
            integration.workspaceText("reason")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            tools.forEach { tool ->
                val name = tool.workspaceText("name") ?: tool.workspaceText("id").orEmpty()
                var show by remember(board.jobId, id, name) { mutableStateOf(false) }
                var arguments by remember(board.jobId, id, name) { mutableStateOf("{}") }
                TextButton(onClick = { show = !show }) { Text(name) }
                if (show) {
                    tool.workspaceText("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    (tool["inputSchema"] ?: tool["input_schema"])?.let { GatewayWorkspaceReceipt("Tool arguments schema", it.toString()) }
                    OutlinedTextField(arguments, { arguments = it.take(16000) }, label = { Text("Tool arguments (JSON object)") }, modifier = Modifier.fillMaxWidth())
                    val parsed = remember(arguments) { runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull() }
                    if (parsed == null) Text("Enter a valid JSON object.", color = MaterialTheme.colorScheme.error)
                    TextButton(enabled = enabled && parsed != null && name.isNotBlank() && id in setOf("context7", "playwright", "github") && integration.workspaceText("state") == "READY_FOR_DISPATCH" && tool.workspaceFlag("advertised") && board.canRun("integration_call"), onClick = {
                        model.workspaceCommand(
                            binding,
                            "integration_call",
                            buildJsonObject {
                                put("integration", id)
                                put("tool", name)
                                put("arguments", requireNotNull(parsed))
                                put("invocation_id", java.util.UUID.randomUUID().toString())
                            }
                        )
                    }) { Text("Run configured tool") }
                }
            }
        }
    }
    if (board.integrations.isNotEmpty()) GatewayWorkspaceReceipt("Integration availability evidence", board.integrations.toString())
}

@Composable
private fun GatewayWorkspaceNextEdits(board: GatewayAgentWorkspace, binding: GatewayEngineeringBinding, enabled: Boolean, model: GatewayEngineeringViewModel) {
    val checks = (board.environment["checks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.workspaceCount("index") != null }
    board.nextEdits.forEach { edit ->
        val id = edit.workspaceText("next_edit_id").orEmpty()
        var check by remember(board.jobId, board.headSha, id) { mutableStateOf<Int?>(null) }
        val current = edit.workspaceText("source_sha") == board.headSha
        GatewayWorkspaceItem("${edit.workspaceText("path").orEmpty()} · ${edit.workspaceText("state") ?: "Unknown"}") {
            edit.workspaceText("diff")?.let { dev.chungjungsoo.gptmobile.presentation.ui.workspace.GitHubCodeDiff(it) }
            if (!edit.workspaceFlag("diff_complete")) Text("The visible diff may be partial. Full source-bound evidence is retained with the job.", style = MaterialTheme.typography.bodySmall)
            Text("Tests · ${edit.workspaceText("test_status") ?: "Not run"}", style = MaterialTheme.typography.bodySmall)
            if (current && edit.workspaceText("state") == "PROPOSED" && board.canRun("next_edit_validate")) {
                checks.forEach { candidate ->
                    val index = requireNotNull(candidate.workspaceCount("index"))
                    FilterChip(selected = check == index, onClick = { check = index }, label = { Text(candidate.workspaceText("name") ?: "Approved check $index") }, enabled = enabled)
                }
                if (checks.isEmpty()) Text("Configure and qualify an approved validation check before testing this candidate.", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = enabled && check != null && id.isNotBlank(), onClick = {
                    model.workspaceCommand(
                        binding,
                        "next_edit_validate",
                        buildJsonObject {
                            put("next_edit_id", id)
                            put("check_index", requireNotNull(check))
                        }
                    )
                }) { Text("Validate suggested edit") }
            }
            GatewayWorkspaceReceipt("Next-edit source and artifact evidence", edit.toString())
        }
    }
}
