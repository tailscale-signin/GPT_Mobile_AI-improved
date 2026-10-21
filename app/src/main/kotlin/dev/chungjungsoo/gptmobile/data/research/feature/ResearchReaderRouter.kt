package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.pageText
import dev.chungjungsoo.gptmobile.data.agent.tool.researchPayload
import dev.chungjungsoo.gptmobile.data.research.ResearchDocumentResult
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.SourceContentValidator
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicEvidence
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper
import dev.chungjungsoo.gptmobile.data.research.researchDomainAllowed
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.data.research.strings
import dev.chungjungsoo.gptmobile.data.research.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Tries only already-authorized alternate URLs; access failure never becomes a reliability grade. */
internal object ResearchReaderRouter {
    suspend fun fallback(paper: AcademicPaper, initial: AcademicEvidence, readers: List<ResolvedAgentTool>, sessions: ResearchSessionStore, included: List<String>, excluded: List<String>, permitted: suspend () -> Boolean): AcademicEvidence {
        if (initial.retrievalStatus in setOf("read", "permission_denied", "waiting_capacity")) return initial
        val reader = readers.firstOrNull { it.realToolName == "read_url" && it.connectionUid == null } ?: return initial
        var retained = initial
        fun keepFailure(failed: AcademicEvidence) {
            retained = if (retained.text.isNotBlank() && SourceContentValidator.validate(retained.text, abstractOnly = retained.retrievalStatus == "abstract_only").usable) {
                retained.copy(failureCode = failed.failureCode, failureReason = failed.failureReason, retryAtMs = failed.retryAtMs, redirectChain = failed.redirectChain)
            } else failed
        }
        val avoided = initial.redirectChain.toSet() + if (initial.retrievalStatus in setOf("blocked", "authentication_required", "redirect_failed")) setOf(initial.sourceUrl) else emptySet()
        val candidates = listOfNotNull(paper.openAccessUrl, paper.canonicalUrl).filter { it.isNotBlank() }.distinct().filterNot { it in avoided }
        for (url in candidates) {
            if (!permitted()) return retained.copy(retrievalStatus = "permission_denied", failureCode = "PERMISSION_REVOKED")
            if (!ResearchArchiveIngestor.publicUrl(url) || !researchDomainAllowed(url, included, excluded)) continue
            val result = try {
                reader.tool.execute(
                    "examine-document:" + researchHash(paper.paperId + url),
                    buildJsonObject {
                        put("url", url)
                        put("extractDocument", true)
                        put("includeDomains", JsonArray(included.map(::JsonPrimitive)))
                        put("excludeDomains", JsonArray(excluded.map(::JsonPrimitive)))
                    }
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                dev.chungjungsoo.gptmobile.data.research.academic.academicFailureEvidence(paper, failure)?.let(::keepFailure)
                if (retained.retrievalStatus == "waiting_capacity") return retained
                continue
            }
            if (!permitted()) return retained.copy(retrievalStatus = "permission_denied", failureCode = "PERMISSION_REVOKED")
            val payload = (result.retainedContent ?: result.content).researchPayload() as? JsonObject ?: continue
            val document = ResearchDocumentResult.fromJson(payload)
            if (result.isError) {
                if (document != null) keepFailure(retained.copy(text = "", textHash = researchHash(""), retrievalStatus = document.status, failureCode = document.failureCode ?: result.errorCode, failureReason = document.failureReason, sourceUrl = url))
                if (document?.status == "waiting_capacity") return retained
                continue
            }
            val finalUrl = payload.text("url").ifBlank { url }
            if (!ResearchArchiveIngestor.publicUrl(finalUrl) || !researchDomainAllowed(finalUrl, included, excluded)) continue
            val chunks = payload.strings("bodyChunks")
            val captured = (if (chunks.isEmpty()) pageText(payload) else sessions.readFeatureBody(chunks)) ?: continue
            val admission = SourceContentValidator.validate(captured, url = finalUrl)
            if (!admission.usable) {
                keepFailure(retained.copy(text = "", textHash = researchHash(""), retrievalStatus = "blocked", failureCode = "SOURCE_ACCESS_BLOCKED", failureReason = admission.reasons.joinToString(), sourceUrl = finalUrl))
                continue
            }
            if (retained.text.length > captured.length) continue
            val text = captured.take(240000)
            val status = if (admission.kind == SourceContentValidator.Kind.ABSTRACT) {
                "abstract_only"
            } else if (text.length < captured.length || payload.text("truncated") == "true") {
                "partially_read"
            } else {
                "read"
            }
            return AcademicEvidence(paper.paperId, researchHash(text), finalUrl, System.currentTimeMillis(), researchHash(text), text, status, section = "Native document extraction; tables, figures and supplements uninspected", providerReceipts = paper.providerReceipts + "native-read-url:" + researchHash(finalUrl), canonicalUrl = paper.canonicalUrl)
        }
        return retained
    }
}
