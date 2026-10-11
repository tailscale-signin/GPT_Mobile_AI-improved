package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.health.HealthConnectTools
import dev.chungjungsoo.gptmobile.data.health.HealthDataReader
import dev.chungjungsoo.gptmobile.data.health.HealthQuery
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Phone-owned tools. Never execute on a gateway, cache, or share raw results between profiles. */
class HealthConnectTool(
    private val operation: String,
    private val reader: HealthDataReader,
    private val accessAllowed: suspend () -> Boolean,
    private val aiConsent: suspend () -> Boolean,
    private val clock: Clock = Clock.systemDefaultZone()
) : OwnedAgentTool {
    init {
        require(operation in HealthConnectTools.operations)
    }
    override val executionOwner = AgentToolExecutionOwner.CLIENT
    override val definition = AgentToolDefinition(
        name = HealthConnectTools.PREFIX + operation,
        description = "Read-only Android Health Connect $operation. Synced smartwatch wellness history, not live sensors or medical diagnosis. ${if (operation == "status") "Check availability and permissions without reading measurements." else "Requires device-local AI-sharing consent and per-profile opt-in. Missing/null values are not zero. Do not infer unrecorded metrics. Default: today in the phone time zone. Use source_package to isolate a data source; no routes, notes, writes or device control."}",
        inputSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    if (operation != "status") {
                        put(
                            "days",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                                put("maximum", 30)
                                put("default", 1)
                                put("description", "Calendar days including today, in the phone's time zone. Do not combine with explicit timestamps.")
                            }
                        )
                        listOf("start_time", "end_time").forEach { key ->
                            put(
                                key,
                                buildJsonObject {
                                    put("type", "string")
                                    put("format", "date-time")
                                    put("description", "Supply both timestamps with Z or a UTC offset. End is exclusive; last 30 days only.")
                                }
                            )
                        }
                        put(
                            "source_package",
                            buildJsonObject {
                                put("type", "string")
                                put("maxLength", 200)
                                put("description", "Optional Android package name of the app that wrote the records.")
                            }
                        )
                        put(
                            "limit",
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 1)
                                put("maximum", 100)
                                put("default", 50)
                                put("description", "Maximum recent sessions or scalar samples per record type. Aggregated totals are independent of this limit.")
                            }
                        )
                    }
                }
            )
            put("additionalProperties", false)
        }
    )

    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
        fun failure(code: String, message: String, retryable: Boolean = false) = AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("status", code)
                    put("message", message)
                    put("retryable", retryable)
                    put("operation", operation)
                }
            ),
            true,
            traceContent = ToolResultContent.Text("Health Connect: $code"),
            errorCode = code
        )
        try {
            if (!accessAllowed()) return failure("plugin_disabled", "Enable Smartwatch Health Connect globally and for this AI profile. Local tools must be enabled.")
            if (operation != "status" && !aiConsent()) return failure("ai_consent_required", "Open Smartwatch Health Connect settings and explicitly allow health results in AI conversations.")
            if (operation == "status" && arguments.isNotEmpty()) return failure("invalid_arguments", "The status tool accepts no arguments.")
            val now = clock.instant()
            val query = if (operation == "status") {
                HealthQuery(now.minusSeconds(1), now, clock.zone)
            } else {
                try {
                    HealthQuery.parse(arguments, now, clock.zone)
                } catch (invalid: IllegalArgumentException) {
                    return failure("invalid_arguments", invalid.message ?: "Invalid query arguments.")
                }
            }
            val result = reader.read(operation, query)
            if (!accessAllowed() || operation != "status" && !aiConsent()) return failure("consent_revoked", "Access changed during this read. No health measurements were returned.")
            val status = (result["status"] as? JsonPrimitive)?.content
            val failed = status !in setOf("available", "partial", "no_data")
            return AgentToolResult(
                callId,
                ToolResultContent.Json(result),
                failed,
                traceContent = ToolResultContent.Text(HealthConnectTools.REDACTED),
                errorCode = status.takeIf { failed }
            )
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            return failure("health_connect_timeout", "Health Connect did not respond in time. Keep the app open and retry.", retryable = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val classified = dev.chungjungsoo.gptmobile.data.health.HealthReadFailure.classify(error)
            return failure(classified.code, classified.action, classified.retryable)
        }
    }
}
