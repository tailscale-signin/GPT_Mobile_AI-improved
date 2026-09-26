package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.tool.McpInputRequest
import dev.chungjungsoo.gptmobile.data.agent.tool.schemaError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun McpInputDialog(request: McpInputRequest, respond: (String, JsonObject?) -> Unit) {
    var values by remember(request.id) { mutableStateOf((request.schema["properties"] as? JsonObject).orEmpty().filterValues { (it as? JsonObject)?.get("type") == JsonPrimitive("boolean") }.mapValues { "false" }) }
    val fields = request.schema["properties"]?.jsonObject.orEmpty()
    val required = (request.schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
    val parsed = JsonObject(
        values.mapNotNull { (key, text) ->
            val type = fields[key]?.jsonObject?.get("type")?.jsonPrimitive?.content
            val primitive = when (type) {
                "integer" -> text.toLongOrNull()?.let(::JsonPrimitive)
                "number" -> text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
                "boolean" -> text.toBooleanStrictOrNull()?.let(::JsonPrimitive)
                else -> JsonPrimitive(text)
            }
            primitive?.let { key to it }
        }.toMap()
    )
    val valid = schemaError(request.schema, parsed) == null && parsed.size == values.size
    AlertDialog(
        onDismissRequest = { respond(request.id, null) },
        title = { Text(stringResource(R.string.mcp_input_dialog_label_1)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(request.server)
                Text(request.message)
                Text(stringResource(R.string.mcp_input_dialog_label_2))
                fields.forEach { (key, value) ->
                    val field = value.jsonObject
                    val label = (field["title"]?.jsonPrimitive?.content ?: key) + if (key in required) " *" else ""
                    if (field["type"]?.jsonPrimitive?.content == "boolean") {
                        Row {
                            Checkbox(checked = values[key] == "true", onCheckedChange = { values = values + (key to it.toString()) })
                            Text(label, Modifier.weight(1f))
                        }
                    } else {
                        OutlinedTextField(value = values[key].orEmpty(), onValueChange = { text ->
                            values = if (text.isEmpty() && key !in required) values - key else values + (key to text.take(2000))
                        }, label = { Text(label) }, isError = values.containsKey(key) && schemaError(field, parsed[key] ?: JsonPrimitive(values[key])) != null)
                    }
                    (field["description"] as? JsonPrimitive)?.content?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    (field["enum"] as? JsonArray)?.let { options ->
                        options.forEach { option ->
                            TextButton(onClick = { values = values + (key to option.jsonPrimitive.content) }) { Text(option.jsonPrimitive.content) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { respond(request.id, parsed) }) { Text(stringResource(R.string.mcp_input_dialog_label_3)) } },
        dismissButton = { TextButton(onClick = { respond(request.id, null) }) { Text(stringResource(R.string.mcp_input_dialog_label_4)) } }
    )
}
