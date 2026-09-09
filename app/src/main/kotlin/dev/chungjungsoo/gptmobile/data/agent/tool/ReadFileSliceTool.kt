package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Built-in tool that extracts a bounded slice of lines from text content.
 * Prefixes each line with its 1-indexed line number to allow precise context inspection
 * while conserving LLM token context.
 */
class ReadFileSliceTool : AgentTool {

    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = BuiltInAgentTool.READ_FILE_SLICE,
        description = "Extracts a bounded line slice (start_line to end_line, 1-indexed) from text content with prefixed line numbers.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "content",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "The raw file or text content to slice.")
                        }
                    )
                    put(
                        "start_line",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "The 1-indexed starting line number of the slice (inclusive).")
                        }
                    )
                    put(
                        "end_line",
                        buildJsonObject {
                            put("type", "integer")
                            put("description", "The 1-indexed ending line number of the slice (inclusive).")
                        }
                    )
                }
            )
            put(
                "required",
                buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("content"))
                    add(kotlinx.serialization.json.JsonPrimitive("start_line"))
                    add(kotlinx.serialization.json.JsonPrimitive("end_line"))
                }
            )
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        val content = arguments["content"]?.jsonPrimitive?.content
            ?: return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Text("Missing or invalid 'content' argument."),
                isError = true
            )

        if (content.isEmpty()) {
            return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Text("Provided content cannot be empty."),
                isError = true
            )
        }

        val startLine = arguments["start_line"]?.jsonPrimitive?.intOrNull
            ?: return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Text("Missing or invalid 'start_line' integer argument."),
                isError = true
            )

        val endLine = arguments["end_line"]?.jsonPrimitive?.intOrNull
            ?: return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Text("Missing or invalid 'end_line' integer argument."),
                isError = true
            )

        if (startLine < 1) {
            return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Text("start_line must be >= 1, received: $startLine"),
                isError = true
            )
        }

        if (startLine > endLine) {
            return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Text("start_line cannot be greater than end_line ($startLine > $endLine)."),
                isError = true
            )
        }

        val lines = content.lines()
        val totalLines = lines.size

        if (startLine > totalLines) {
            return AgentToolResult(
                callId = callId,
                content = ToolResultContent.Text("start_line ($startLine) exceeds total line count ($totalLines)."),
                isError = true
            )
        }

        val actualEndLine = minOf(endLine, totalLines)
        val sliced = (startLine..actualEndLine).map { lineNum ->
            "$lineNum: ${lines[lineNum - 1]}"
        }

        val header = if (startLine == actualEndLine) {
            "Line $startLine of $totalLines:"
        } else {
            "Lines $startLine-$actualEndLine of $totalLines:"
        }

        val resultText = buildString {
            appendLine(header)
            sliced.forEachIndexed { index, line ->
                append(line)
                if (index < sliced.lastIndex) {
                    appendLine()
                }
            }
        }

        return AgentToolResult(
            callId = callId,
            content = ToolResultContent.Text(resultText),
            isError = false
        )
    }
}
