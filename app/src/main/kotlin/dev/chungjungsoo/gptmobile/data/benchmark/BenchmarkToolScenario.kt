package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.CalculatorTool
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Host-owned offline tools. Their data and execution state never leave this test's session. */
internal class BenchmarkToolScenario(private val test: BenchmarkCase, private val code: String) {
    private val reads = AtomicInteger()
    private val searched = java.util.concurrent.atomic.AtomicBoolean()
    private val recovery = test.workload == "tool_error_recovery"
    private val research = test.workload == "research_fixture"
    private val parcelArguments = buildJsonObject { put("key", "parcel") }
    private val typedArguments = buildJsonObject {
        put("key", "parcel")
        put("count", 2)
        put("includeArchived", false)
    }
    private fun schema(properties: Map<String, String>, required: Set<String> = properties.keys) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { properties.forEach { (key, type) -> put(key, buildJsonObject { put("type", type) }) } })
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
    private fun tool(name: String, description: String, schema: JsonObject, action: (String, JsonObject) -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition(name, description, schema)
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = action(callId, arguments)
    }
    private fun result(id: String, body: String, error: Boolean = false, code: String? = null) = AgentToolResult(id, ToolResultContent.Text(body), error, errorCode = code)

    val tools: List<AgentTool> = when {
        test.calculatorExpression != null -> listOf(CalculatorTool())
        research -> listOf(
            tool("benchmark_search", "Search the offline parcel records. Returns a fixture URL to read.", schema(mapOf("query" to "string"))) { id, args ->
                if (args != buildJsonObject { put("query", "parcel") }) {
                    result(id, "Expected query=parcel", true)
                } else {
                    searched.set(true)
                    result(id, "Read fixture://parcel-record with benchmark_read. The code is only in that record.")
                }
            },
            tool("benchmark_read", "Read the offline URL returned by benchmark_search.", schema(mapOf("url" to "string"))) { id, args ->
                if (!searched.get() || args != buildJsonObject { put("url", "fixture://parcel-record") }) result(id, "Search first and use its exact fixture URL.", true) else result(id, "Parcel evidence: FINAL_PARCEL_CODE=$code")
            }
        )
        else -> listOf(
            tool("benchmark_lookup", "Look up the parcel code in the temporary offline fixture.", if (test.workload == "typed_arguments") schema(mapOf("key" to "string", "count" to "integer", "includeArchived" to "boolean"), setOf("key", "count")) else schema(mapOf("key" to "string"))) { id, args ->
                when {
                    args != if (test.workload == "typed_arguments") typedArguments else parcelArguments -> result(id, "Invalid fixture arguments", true)
                    recovery && reads.incrementAndGet() == 1 -> result(id, "Temporary fixture failure. Retry once with the same arguments and a new call ID.", true, "BENCHMARK_RETRYABLE")
                    test.workload == "tool_result_reading" -> result(id, "Unrelated record. ".repeat(120) + "FINAL_PARCEL_CODE=$code\n" + "Other shipment records. ".repeat(40))
                    else -> result(id, code)
                }
            }
        ) + if (test.workload == "tool_selection") {
            listOf("weather", "calendar").map { name ->
                tool("benchmark_$name", "Returns $name information, never parcel codes.", schema(mapOf("key" to "string"))) { id, _ -> result(id, "No parcel information", true) }
            }
        } else {
            emptyList()
        }
    }

    fun valid(call: ProviderEvent.ToolCall): Boolean = when {
        test.calculatorExpression != null -> call.name == "calculate_expression" && call.arguments == buildJsonObject { put("expression", test.calculatorExpression) }
        research && call.name == "benchmark_search" -> call.arguments == buildJsonObject { put("query", "parcel") }
        research && call.name == "benchmark_read" -> call.arguments == buildJsonObject { put("url", "fixture://parcel-record") }
        research -> false
        else -> call.name == "benchmark_lookup" && call.arguments == if (test.workload == "typed_arguments") typedArguments else parcelArguments
    }

    fun expectedError(call: ProviderEvent.ToolCall, result: AgentToolResult): Boolean = recovery && valid(call) && result.isError && result.errorCode == "BENCHMARK_RETRYABLE"

    fun passed(calls: List<ProviderEvent.ToolCall>, successful: Set<String>, expectedErrors: Set<String>): Boolean = calls.isNotEmpty() &&
        calls.all(::valid) &&
        successful.size + expectedErrors.size == calls.size &&
        (!recovery || expectedErrors.size == 1 && successful.size == 1) &&
        (!research || calls.map { it.name } == listOf("benchmark_search", "benchmark_read"))
}
