package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Registered, bounded evidence access; model-supplied URLs and user-origin flags are rejected. */
internal class ResearchEvidenceTool(private val operation: String, private val archive: ResearchArchiveRepository, private val coordinator: () -> ArticleExaminationCoordinator, private val profileUid: String, private val chatId: Int, private val authority: String, private val tools: ChatMcpToolConfig, private val permitted: suspend () -> Boolean, private val comparisonLimit: Int, private val rootWorkId: String? = null) : AgentTool {
    override val definition = AgentToolDefinition(
        "research_$operation",
        when (operation) {
            "find_evidence" -> "Find registered article metadata. Archive matches are historical leads and require source revalidation before current factual reuse."
            "get_evidence" -> "Read version-linked exact passages and study fields; preserves historical coverage and independent-review limits."
            "examine_article" -> "Request an authorized research examination of a registered work. Returns a durable job ID. Cannot impersonate a user Examine click or change permission."
            "get_examination" -> "Inspect an authorized examination checkpoint, coverage and model receipts. Does not start or resume work."
            else -> "Compare registered versions without pooling dependent studies or turning AI appraisal into scientific truth."
        },
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put(
                "properties",
                buildJsonObject {
                    when (operation) {
                        "find_evidence" -> put(
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put("minLength", 2)
                                put("maxLength", 300)
                            }
                        )
                        "get_evidence" -> {
                            put("versionId", idSchema())
                            put(
                                "offset",
                                buildJsonObject {
                                    put("type", "integer")
                                    put("minimum", 0)
                                    put("maximum", 10000)
                                }
                            )
                        }
                        "examine_article" -> put("workId", idSchema())
                        "get_examination" -> put(
                            "jobId",
                            buildJsonObject {
                                put("type", "string")
                                put("maxLength", 80)
                            }
                        )
                        else -> put(
                            "versionIds",
                            buildJsonObject {
                                put("type", "array")
                                put("minItems", 2)
                                put("maxItems", comparisonLimit)
                                put("uniqueItems", true)
                                put("items", idSchema())
                            }
                        )
                    }
                }
            )
            put(
                "required",
                JsonArray(
                    listOf(
                        JsonPrimitive(
                            when (operation) {
                                "find_evidence" -> "query"
                                "get_evidence" -> "versionId"
                                "examine_article" -> "workId"
                                "get_examination" -> "jobId"
                                else -> "versionIds"
                            }
                        )
                    )
                )
            )
        }
    )
    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = try {
        check(permitted()) { "Research evidence permission revoked" }
        val keys = when (operation) {
            "find_evidence" -> setOf("query")
            "get_evidence" -> setOf("versionId", "offset")
            "examine_article" -> setOf("workId")
            "get_examination" -> setOf("jobId")
            else -> setOf("versionIds")
        }
        require(arguments.keys.all { it in keys }) { "Unsupported research arguments" }
        fun string(key: String): String = (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing $key")
        suspend fun version(id: String): ResearchWorkVersion {
            require(id.matches(Regex("[a-f0-9]{64}")))
            val value = requireNotNull(archive.dao.version(id)) { "Unknown evidence version" }
            check(value.publicSource || value.authority == authority) { "Evidence belongs to another authority" }
            return value
        }
        val result = when (operation) {
            "find_evidence" -> {
                val query = string("query")
                require(query.length in 2..300)
                val rows = archive.page(query).take(12)
                buildJsonObject {
                    put("status", "historical_metadata_leads")
                    put("requiresRevalidation", true)
                    put(
                        "works",
                        JsonArray(
                            rows.map { row ->
                                buildJsonObject {
                                    put("workId", row.workId)
                                    put("versionId", row.latestVersionId)
                                    put("title", row.title.take(500))
                                    put("coverage", row.coverage.name)
                                    put("peerReview", row.peerReview.name)
                                }
                            }
                        )
                    )
                }
            }
            "get_evidence" -> {
                val value = version(string("versionId"))
                val offset = (arguments["offset"] as? JsonPrimitive)?.intOrNull ?: 0
                require(offset in 0..10000)
                val body = archive.text(value, authority) ?: error("Saved evidence is unavailable or failed its integrity check")
                val passages = archive.dao.passages(value.versionId).filter { passage ->
                    passage.start >= 0 && passage.end in passage.start..body.length && body.substring(passage.start, passage.end) == passage.quote && dev.chungjungsoo.gptmobile.data.research.researchHash(passage.quote) == passage.textHash
                }
                val page = passages.drop(offset).take(8)
                buildJsonObject {
                    put("versionId", value.versionId)
                    put("coverage", value.coverage.name)
                    put("contentHash", value.contentHash)
                    put("freshness", "historical_snapshot")
                    put("passages", Json.encodeToJsonElement(page))
                    put("fields", Json.encodeToJsonElement(archive.dao.fields(value.versionId).filter { field -> page.any { it.passageId in field.passageIdsJson } }.take(24)))
                    put("nextOffset", if (offset + page.size < passages.size) offset + page.size else -1)
                    put("omittedPassages", (passages.size - offset - page.size).coerceAtLeast(0))
                    put("appraisals", Json.encodeToJsonElement(archive.dao.appraisals(value.versionId).map { it.copy(judgmentsJson = "[]") }.take(12)))
                }
            }
            "examine_article" -> {
                val workId = string("workId")
                require(workId.matches(Regex("[a-f0-9]{64}")))
                val value = requireNotNull(archive.dao.latestVersion(workId))
                version(value.versionId)
                val jobId = coordinator().automaticExamine(ExaminationRequest(workId, "", chatId, value.versionId, authority, ExaminationOrigin.AUTHORIZED_RESEARCH, rootWorkId = rootWorkId, parentRunId = kotlin.coroutines.coroutineContext[dev.chungjungsoo.gptmobile.data.agent.ToolRunContext]?.runId), { tools })
                buildJsonObject {
                    put("jobId", jobId)
                    put("status", "admitted")
                    put("note", "Inspect job state; admission is not completed examination")
                }
            }
            "get_examination" -> {
                val id = string("jobId")
                require(id.length in 1..80)
                val job = requireNotNull(archive.dao.job(id))
                check(job.authority == authority && job.chatId == chatId)
                Json.encodeToJsonElement(job)
            }
            else -> {
                val ids = (arguments["versionIds"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: error("Version ID required") } ?: error("Version IDs required")
                require(ids.size in 2..comparisonLimit && ids.distinct().size == ids.size)
                val values = ids.map { version(it) }
                buildJsonObject {
                    put(
                        "comparison",
                        JsonArray(
                            values.map { value ->
                                val work = requireNotNull(archive.dao.work(value.workId))
                                buildJsonObject {
                                    put("workId", work.workId)
                                    put("versionId", value.versionId)
                                    put("title", work.title.take(500))
                                    put("studyFamily", work.studyFamilyId ?: "unknown")
                                    put("coverage", value.coverage.name)
                                    put("peerReview", work.peerReview.name)
                                    put("fields", Json.encodeToJsonElement(archive.dao.fields(value.versionId).take(24)))
                                    put("independentReview", JsonArray(archive.dao.appraisals(value.versionId).map { JsonPrimitive(it.independentReview) }.distinct()))
                                }
                            }
                        )
                    )
                    put("limitations", "Historical version comparison. Shared or unknown study families must not be counted as independent studies. No pooled effect or overall truth score is computed.")
                }
            }
        }
        check(permitted()) { "Research evidence permission revoked" }
        require(result.toString().toByteArray().size <= 64 * 1024) { "Evidence page exceeds output allowance; request fewer versions" }
        AgentToolResult(callId, ToolResultContent.Json(result), false)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("status", "unavailable")
                    put("reason", dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(error.message.orEmpty()).take(300))
                }
            ),
            true
        )
    }
    companion object {
        val OPERATIONS = listOf("find_evidence", "get_evidence", "examine_article", "get_examination", "compare_evidence")
        private fun idSchema() = buildJsonObject {
            put("type", "string")
            put("pattern", "^[a-f0-9]{64}$")
            put("maxLength", 64)
        }
    }
}
