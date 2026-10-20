package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.looksLikeConsentOrScriptShell
import dev.chungjungsoo.gptmobile.data.agent.tool.pageText
import dev.chungjungsoo.gptmobile.data.agent.tool.researchPayload
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicEvidence
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper
import dev.chungjungsoo.gptmobile.data.research.researchDomainAllowed
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.data.research.strings
import dev.chungjungsoo.gptmobile.data.research.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The bounded native document reader is used only after native academic capability is unavailable. */
internal object ResearchReaderRouter {
    suspend fun fallback(paper: AcademicPaper, initial: AcademicEvidence, readers: List<ResolvedAgentTool>, sessions: ResearchSessionStore, included: List<String>, excluded: List<String>, permitted: suspend () -> Boolean): AcademicEvidence {
        if (initial.retrievalStatus in setOf("read", "blocked", "permission_denied", "authentication_required")) return initial
        fun retain(reason: String): AcademicEvidence {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ResearchExamine", "DOCUMENT_READER_RETAINED · paperId=${paper.paperId} · initialStatus=${initial.retrievalStatus} · reason=$reason")
            return initial
        }
        val reader = readers.firstOrNull { it.realToolName == "read_url" && it.connectionUid == null } ?: return retain("No authorized native document reader is available")
        val url = paper.openAccessUrl?.takeIf { it.isNotBlank() } ?: paper.canonicalUrl
        if (!ResearchArchiveIngestor.publicUrl(url) || !researchDomainAllowed(url, included, excluded) || !permitted()) return retain("URL or domain is restricted, or examination permission changed")
        val result = try {
            reader.tool.execute(
                "examine-document:" + researchHash(paper.paperId),
                buildJsonObject {
                    put("url", url)
                    put("extractDocument", true)
                    put("includeDomains", kotlinx.serialization.json.JsonArray(included.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    put("excludeDomains", kotlinx.serialization.json.JsonArray(excluded.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                }
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ResearchExamine", "DOCUMENT_READER_FAILED · paperId=${paper.paperId} · detail=${examinationFailureDetail(failure)}", "W")
            return initial
        }
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("ResearchExamine", "DOCUMENT_READER_RESULT · paperId=${paper.paperId} · tool=${reader.realToolName} · error=${result.isError} · errorCode=${result.errorCode}")
        if (result.isError || !permitted()) return retain("Reader failed or permission changed")
        val payload = (result.retainedContent ?: result.content).researchPayload() as? JsonObject ?: return retain("Reader response is not a document object")
        val finalUrl = payload.text("url").ifBlank { url }
        if (!ResearchArchiveIngestor.publicUrl(finalUrl) || !researchDomainAllowed(finalUrl, included, excluded)) return retain("Redirect destination is restricted")
        val chunks = payload.strings("bodyChunks")
        val captured = (if (chunks.isEmpty()) pageText(payload) else sessions.readFeatureBody(chunks)) ?: return retain("Retained document body is unavailable")
        if (captured.isBlank() || looksLikeConsentOrScriptShell(captured)) return if (initial.text.isNotBlank()) initial else initial.copy(retrievalStatus = "blocked", text = "", textHash = researchHash(""))
        val text = captured.take(240000)
        if (initial.text.length > text.length) return retain("Existing evidence has more readable text")
        val status = if (text.length < captured.length || payload.text("truncated") == "true") "partially_read" else "read"
        return AcademicEvidence(paper.paperId, researchHash(text), finalUrl, System.currentTimeMillis(), researchHash(text), text, status, section = "Bounded native document extraction; figures and supplements uninspected", providerReceipts = listOf("native-read-url:" + researchHash(finalUrl)))
    }
}
