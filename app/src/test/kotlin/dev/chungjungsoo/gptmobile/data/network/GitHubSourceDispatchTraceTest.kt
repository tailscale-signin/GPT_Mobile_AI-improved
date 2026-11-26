package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubSourceDispatchTraceTest {
    private val commit = "a".repeat(40)
    private val blob = "b".repeat(40)
    private val body = "private first\nprivate second\n"
    private val call = "private-call"

    private fun page(text: String = body, line: Int = 11, offset: Int = 0, sha: String = blob) = buildJsonObject {
        put("schema", "github.operation.v1")
        put("operation", "github_read")
        put("source_page", true)
        put("commit_sha", commit)
        put(
            "items",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("path", "private/secret.kt")
                        put("sha", sha)
                        put("start_line", line)
                        put("range_offset", offset)
                        put("range_end", text == body)
                        put("content", text)
                    }
                )
            )
        )
    }

    private fun exchange() = AgentToolExchange(
        listOf(ProviderEvent.ToolCall(call, "github_read__github_api", JsonObject(emptyMap()))),
        listOf(AgentToolResult(call, ToolResultContent.Json(page()), false))
    )

    private fun wire(content: JsonObject, role: String = "tool", id: String = call) = buildJsonObject {
        put(
            "messages",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("role", role)
                        put("tool_call_id", id)
                        put("content", content.toString())
                    }
                )
            )
        )
    }

    private fun result(request: JsonObject): JsonObject = requireNotNull(GitHubSourceDispatchTrace.capture(listOf(exchange())))
        .compare(request).getValue("ranges").jsonArray.single().jsonObject

    @Test fun exactOrTrimmedRangesUseActualContentAndExposeOnlyHashesAndCoordinates() {
        val full = result(wire(page()))
        assertEquals(JsonPrimitive("MATCHED"), full["state"])
        assertEquals(JsonPrimitive(12), full.getValue("serialized").jsonArray.single().jsonObject["end_line"])
        val partial = result(wire(page("private sec", 12, body.indexOf("private second"))))
        assertEquals(JsonPrimitive("MATCHED_FRAGMENTS"), partial["state"])
        assertEquals(JsonPrimitive(12), partial.getValue("serialized").jsonArray.single().jsonObject["start_line"])
        assertFalse(partial.toString().contains("private"))
    }

    @Test fun payloadsCannotBorrowSourceFromUsersOtherCallsChangedBytesOrOtherRevisions() {
        assertEquals(JsonPrimitive("TOOL_OUTPUT_OMITTED"), result(wire(page(), role = "user"))["state"])
        assertEquals(JsonPrimitive("TOOL_OUTPUT_OMITTED"), result(wire(page(), id = "different"))["state"])
        assertEquals(JsonPrimitive("NO_MATCHING_SOURCE_RANGE"), result(wire(page("changed")))["state"])
        assertEquals(JsonPrimitive("NO_MATCHING_SOURCE_RANGE"), result(wire(page(sha = "c".repeat(40))))["state"])
        assertEquals(JsonPrimitive("NO_MATCHING_SOURCE_RANGE"), result(wire(page("private", 30)))["state"])
        val emptyTool = buildJsonObject {
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", call)
                            put("content", JsonArray(emptyList()))
                        }
                    )
                )
            )
        }
        assertEquals(JsonPrimitive("NO_MATCHING_SOURCE_RANGE"), result(emptyTool)["state"])
        val enormous = buildJsonObject {
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", call)
                            put("content", "x".repeat(265_000))
                        }
                    )
                )
            )
        }
        assertEquals(JsonPrimitive("UNKNOWN"), result(enormous)["state"])
        assertNull(GitHubSourceDispatchTrace.capture(emptyList()))
    }

    @Test fun nativeRetainedReadIdentifiesAPartialFirstPageInsteadOfTreatingItAsTheCompleteObservation() {
        val raw = JsonObject(page().filterKeys { it in setOf("commit_sha", "items") })
        val partial = page("private first")
        val original = exchange()
        val result = original.results.single().copy(content = ToolResultContent.Json(partial), retainedContent = ToolResultContent.Json(raw))
        val trace = requireNotNull(GitHubSourceDispatchTrace.capture(listOf(original.copy(results = listOf(result)))))
        val observed = trace.compare(wire(partial)).getValue("ranges").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("MATCHED_FRAGMENTS"), observed["state"])
        assertEquals(JsonPrimitive(body.length), observed.getValue("prepared").jsonObject["length_utf16"])
        assertNull(GitHubSourceDispatchTrace.capture(listOf(original.copy(results = listOf(result.copy(dispatched = false))))))
    }

    @Test fun eachPhysicalAttemptKeepsItsOwnTransportAndSignalsBoundedHistory() {
        val trace = requireNotNull(GitHubSourceDispatchTrace.capture(listOf(exchange())))
        trace.record("first", wire(page()))
        trace.record("second", wire(page(), role = "user"))
        val first = HttpAttemptTrace { _, _ -> }
        val second = HttpAttemptTrace { _, _ -> }
        val transfers = mapOf("first" to first, "second" to second)
        fun attempts() = requireNotNull(trace.evidence(transfers::get)).getValue("attempts").jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive("TRANSMISSION_UNVERIFIED"), attempts().first()["transport"])
        first.mark(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.REQUEST_BODY_END)
        second.mark(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.HTTP_CALL_START)
        second.failed(java.net.UnknownHostException("example.invalid"))
        assertEquals(listOf(JsonPrimitive("REQUEST_BODY_SENT"), JsonPrimitive("PROVEN_UNSENT")), attempts().map { it["transport"] })
        assertEquals(JsonPrimitive("TOOL_OUTPUT_OMITTED"), attempts().last().getValue("ranges").jsonArray.single().jsonObject["state"])
        repeat(5) { trace.record("retry-$it", wire(page())) }
        assertEquals(listOf("first", "second", "retry-0", "retry-4").map(::JsonPrimitive), attempts().map { it["physical_attempt_id"] })
        assertEquals(JsonPrimitive(3), requireNotNull(trace.evidence(transfers::get))["attempts_omitted"])
        assertFalse(trace.evidence(transfers::get).toString().contains("private"))
    }

    @Test fun onlyVerifiedPinnedExcerptClaimsGetAContentFreeJoinToTheMatchingToolCall() = kotlinx.coroutines.runBlocking {
        val raw = JsonObject(page().filterKeys { it in setOf("commit_sha", "items") })
        val digest = dev.chungjungsoo.gptmobile.data.diagnostics.DiagnosticRecordChunks.sha256(raw.toString())
        val receipt = buildJsonObject {
            put("receipt_id", "ev-fixture")
            put("tool", "github_read__github_api")
            put("call_id", call)
            put("result", raw)
            put("result_sha256", digest)
            put("success", true)
            put("trusted", true)
            put("immutable", true)
        }
        val excerpt = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceExcerpts.collect(receipt).entries.single()
        val checked = buildJsonObject {
            put("status", "VERIFIED")
            put("kind", "excerpt")
            put("receipt_id", receipt.getValue("receipt_id"))
            put("pointer", excerpt.key)
            put("observed", excerpt.value.getValue("value"))
        }
        fun binding(source: JsonObject = receipt, claim: JsonObject = checked) =
            dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceExcerpts.sourceClaimBinding(source, excerpt.key, claim, excerpt.value)
        val link = requireNotNull(binding())
        assertEquals(result(wire(page())).getValue("call_id_sha256"), link["call_id_sha256"])
        assertEquals(JsonPrimitive(blob), link["blob_sha"])
        assertFalse(link.toString().contains("private"))
        assertNull(binding(claim = JsonObject(checked + ("status" to JsonPrimitive("NOT_MEASURED")))))
        assertNull(binding(claim = JsonObject(checked + ("observed" to JsonPrimitive("other")))))
        assertNull(binding(source = JsonObject(receipt + ("result_sha256" to JsonPrimitive("corrupt")))))
        assertNull(binding(source = JsonObject(receipt + ("trusted" to JsonPrimitive(false)))))
        val trace = requireNotNull(GitHubSourceDispatchTrace.capture(listOf(exchange())))
        trace.record("full", wire(page()))
        trace.record("partial", wire(page("private sec", 12, body.indexOf("private second"))))
        trace.record("omitted", wire(page(), role = "user"))
        trace.record("unsent", wire(page()))
        val sent = HttpAttemptTrace { _, _ -> }.also { it.mark(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.REQUEST_BODY_END) }
        val transports = mapOf("full" to sent, "partial" to sent, "omitted" to sent)
        val records = listOf(buildJsonObject { put("github_source_delivery", requireNotNull(trace.evidence(transports::get))) }.toString())
        val boundClaim = JsonObject(checked + ("source_binding" to link))
        val otherClaim = JsonObject(checked + ("source_binding" to JsonObject(link + ("source_row_sha256" to JsonPrimitive("other digest")))))
        val report = buildJsonObject { put("claims", JsonArray(listOf(boundClaim, otherClaim, checked))) }
        var loads = 0
        val (text, attached) = dev.chungjungsoo.gptmobile.data.github.GitHubClaimSourceDelivery.attach("Answer" to report) {
            loads++
            records
        }
        assertEquals(1, loads)
        val claims = attached.getValue("claims").jsonArray
        fun spans(index: Int) = claims[index].jsonObject.getValue("source_delivery").jsonObject.getValue("attempts").jsonArray.map { it.jsonObject["serialized_coverage"] }
        assertEquals(listOf("COMPLETE", "PARTIAL", "OMITTED", "COMPLETE").map(::JsonPrimitive), spans(0))
        assertEquals(JsonPrimitive("UNKNOWN"), spans(1).first())
        assertEquals(JsonPrimitive("TRANSMISSION_UNVERIFIED"), claims[0].jsonObject.getValue("source_delivery").jsonObject.getValue("attempts").jsonArray.last().jsonObject["transport"])
        assertEquals(checked, claims[2])
        org.junit.Assert.assertTrue(text.contains("full excerpt sent (1)"))
        org.junit.Assert.assertTrue(text.contains("transmission unverified (1)"))
        assertFalse(text.contains("private"))
        assertNull(binding(source = JsonObject(receipt + ("call_id" to JsonPrimitive("")))))
    }

    @Test fun unknownClaimsDoNotLoadRequestHistoryAndUnavailableHistoryDoesNotBlockPublication() = kotlinx.coroutines.runBlocking {
        fun answer(claim: JsonObject) = "Answer" to buildJsonObject { put("claims", JsonArray(listOf(claim))) }
        val source = buildJsonObject {
            put("call_id_sha256", "f".repeat(64))
            put("start_line", 1)
            put("end_line", 2)
        }
        val unsupported = answer(
            buildJsonObject {
                put("source_binding", source)
                put("status", "NOT_MEASURED")
            }
        )
        assertEquals(unsupported, dev.chungjungsoo.gptmobile.data.github.GitHubClaimSourceDelivery.attach(unsupported) { error("Unexpected lookup") })
        val verified = answer(
            buildJsonObject {
                put("source_binding", source)
                put("kind", "excerpt")
                put("status", "VERIFIED")
            }
        )
        val attached = dev.chungjungsoo.gptmobile.data.github.GitHubClaimSourceDelivery.attach(verified) { throw java.io.IOException("private") }
        org.junit.Assert.assertTrue(attached.first.contains("source delivery unknown"))
        assertFalse(attached.first.contains("private"))
    }

    @Test fun responseFormatsPreserveCallIdentityAndUnattributableGoogleCallsRemainUnknown() {
        val payload = page()
        val block = buildJsonObject {
            put("type", "text")
            put("text", payload.toString())
        }
        val outputs = buildJsonObject {
            put(
                "input",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", call)
                            put("output", JsonArray(listOf(block)))
                        }
                    )
                )
            )
        }
        val anthropic = buildJsonObject {
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("type", "tool_result")
                                            put("tool_use_id", call)
                                            put("content", JsonArray(listOf(block)))
                                        }
                                    )
                                )
                            )
                        }
                    )
                )
            )
        }
        fun google(id: String?) = buildJsonObject {
            put(
                "contents",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "parts",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put(
                                                "functionResponse",
                                                buildJsonObject {
                                                    id?.let { put("id", it) }
                                                    put(
                                                        "response",
                                                        buildJsonObject {
                                                            put("data", payload)
                                                            put("saved_page_reading", true)
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    )
                                )
                            )
                        }
                    )
                )
            )
        }
        for (request in listOf(outputs, anthropic, google(call))) assertEquals(JsonPrimitive("MATCHED"), result(request)["state"])
        assertEquals(JsonPrimitive("UNKNOWN_CALL_ID"), result(google(null))["state"])
        assertEquals(JsonPrimitive("UNKNOWN"), result(JsonObject(emptyMap()))["state"])
    }
}
