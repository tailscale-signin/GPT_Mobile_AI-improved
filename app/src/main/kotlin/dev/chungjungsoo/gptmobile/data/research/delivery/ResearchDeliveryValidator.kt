package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail

/** Deterministic delivery checks. This is not an entailment model or scientific truth score. */
internal object ResearchDeliveryValidator {
    data class Result(val issues: List<String>, val citedIds: Set<String>, val blocks: List<ResearchReportBlock> = emptyList(), val repairHints: List<String> = emptyList()) {
        val accepted: Boolean get() = issues.isEmpty()
    }
    private val citations = Regex("\\[(S[0-9a-f]+)\\]")
    private val numbers = Regex("(?<![\\p{L}\\p{N}])[-+]?\\d+(?:[.,]\\d+)*(?:%)?")

    fun validate(text: String, packet: ResearchDeliveryPacket): Result {
        val issues = mutableListOf<String>()
        val repairHints = mutableListOf<String>()
        val allIds = ResearchReportReferences.cited(text, packet)
        val body = text.split(Regex("(?im)^#{1,6}\\s+references\\s*$"), limit = 2).first()
        val ids = ResearchReportReferences.cited(body, packet)
        val known = (packet.studies + packet.unread).associateBy { it.id }
        if (text.isBlank()) issues += "EMPTY_REPORT"
        if (allIds.any { it !in known }) issues += "UNKNOWN_CITATION"
        if (!packet.snapshot.complete && !Regex("(?i)partial|incomplete|coverage.*limited|limited.*coverage").containsMatchIn(text.take(1200))) issues += "PARTIAL_COVERAGE_NOT_DISCLOSED"
        if (packet.claims.none { it.independentReview } && !Regex("(?i)independent.{0,40}(unavailable|not|unverified)|not.{0,25}independently|unreviewed").containsMatchIn(text)) issues += "REVIEW_STATUS_NOT_DISCLOSED"
        if (packet.detail != ResearchReportDetail.BRIEF) {
            packet.sections.filterNot { text.contains(it, ignoreCase = true) }.forEach { issues += "MISSING_SECTION:$it" }
        }
        packet.studies.take(3).filter { it.id !in ids }.forEach { issues += "OMITTED_LEADING_STUDY:${it.id}" }
        packet.unread.take(if (packet.detail == ResearchReportDetail.BRIEF) 1 else 3).filter { it.id !in ids }.forEach { issues += "OMITTED_UNREAD_LEAD:${it.id}" }
        val paragraphs = body.split(Regex("\n\\s*\n"))
        for ((index, rawParagraph) in paragraphs.withIndex()) {
            // A Markdown heading need not have a blank line before the following prose.
            // Its title is navigation, never a scientific claim or a supporting citation.
            val paragraph = rawParagraph.lines().filterNot { Regex("^ {0,3}#{1,6}(?:\\s|$)").containsMatchIn(it) }.joinToString("\n")
            val cited = ResearchReportReferences.cited(paragraph, packet)
            if (paragraph.isBlank()) continue
            val scientific = Regex("(?i)trial|participants|patients|randomi[sz]|significant|associated|improv|reduc|increase|efficacy|adverse|observed|researchers|study|studies").containsMatchIn(paragraph)
            if (scientific && cited.isEmpty() && !Regex("(?i)unknown|unavailable|could not|not established|no verified|not verified").containsMatchIn(paragraph)) {
                issues += "UNSOURCED_SCIENTIFIC_PARAGRAPH"
                repairHints += "Paragraph ${index + 1} needs supporting source IDs from the packet, or removal of unsupported findings: ${paragraph.take(500)}"
            }
            val readable = cited.mapNotNull { id -> packet.studies.firstOrNull { it.id == id } }
            if (cited.any { id -> packet.unread.any { it.id == id } } && !Regex("(?i)unread|not read|metadata.only|retrieval failed").containsMatchIn(paragraph)) issues += "METADATA_PRESENTED_AS_READ"
            if (readable.isNotEmpty()) {
                val support = readable.joinToString("\n") { it.passage + "\n" + it.publishedAt + " " + it.publicationYear?.toString().orEmpty() + "\n" + ResearchDeliveryPacket.bibliography(it) + "\n" + packet.studyFields[it.id].orEmpty() }
                val allowed = numbers.findAll(support).map { it.value.replace(",", "") }.toSet()
                val withoutLinks = paragraph.replace(Regex("https?://[^\\s)]+|\\[S[0-9a-f]+\\]|10\\.\\d{4,9}/[^\\s]+"), "")
                issues += ResearchNumericalGrounding.issues(withoutLinks, readable.map { it.passage })
                if (numbers.findAll(withoutLinks).any { it.value.replace(",", "") !in allowed }) issues += "NUMERICAL_CLAIM_NOT_IN_CITED_PASSAGE"
            }
        }
        val urls = Regex("https?://[^\\s)<>]+").findAll(text).map { it.value.trimEnd('.', ',', ';') }.toSet()
        val allowedUrls = known.values.flatMap { listOf(it.url, it.canonicalUrl, it.doi.takeIf(String::isNotBlank)?.let { doi -> "https://doi.org/$doi" }.orEmpty()) }.toSet()
        if (urls.any { ResearchReportReferences.canonical(it) !in allowedUrls.filter(String::isNotBlank).map(ResearchReportReferences::canonical) }) issues += "UNREGISTERED_REFERENCE_URL"
        ids.mapNotNull(known::get).filter { source -> source.url !in urls && source.canonicalUrl !in urls && (source.doi.isBlank() || "https://doi.org/${source.doi}" !in urls) }.forEach { issues += "DIRECT_REFERENCE_MISSING:${it.id}" }

        val blocks = ResearchReportBlocks.parse(text, packet)
        packet.snapshot.questionPlan.forEach { question ->
            val supporting = packet.claims.filter { it.questionId == question.id }.map { it.sourceId }.toSet()
            if (supporting.isNotEmpty() && supporting.none { it in ids }) issues += "QUESTION_EVIDENCE_NOT_CITED:${question.id}"
        }
        if (packet.claims.any { it.verdict == "Contradicted" } && !Regex("(?i)contradict|disagree|mixed|negative|uncertain|no (?:significant|benefit|effect)|not support").containsMatchIn(body)) issues += "COUNTEREVIDENCE_NOT_DISCLOSED"
        return Result(issues.distinct(), ids, blocks, repairHints.take(12))
    }

    /** A failed repair delivers captured evidence and honest gaps, never an unchecked scientific answer. */
    fun fallback(packet: ResearchDeliveryPacket): String = buildString {
        append("## Executive findings\n\n")
        append(if (packet.snapshot.complete) "The research collection finished. " else "Partial coverage: the research run did not complete. ")
        append("The generated report did not pass delivery checks. The captured evidence below remains available; scientific interpretation and independent verification are not established.\n\n")
        append("## Scientific background\n\nA verified background synthesis is unavailable in this delivery.\n\n")
        append("## Leading studies\n\n")
        if (packet.studies.isEmpty()) append("No readable, relevant study was retained.\n\n")
        packet.studies.forEach { source ->
            append("### [${source.id}] ${source.title}\n\n")
            append("Source coverage: ${source.retrievalQuality.lowercase().replace('_', ' ')}. ")
            val claims = packet.claims.filter { it.sourceId == source.id }
            append(if (claims.any { it.independentReview }) "Independent review is recorded for some claims; this does not establish scientific truth.\n\n" else "Independent review is not established.\n\n")
            claims.take(3).forEach { claim -> append("Captured passage [${source.id}] (${claim.verdict.lowercase()}):\n\n> ${claim.quote.replace("\n", "\n> ")}\n\n") }
            if (claims.isEmpty()) append("Unreviewed captured excerpt [${source.id}]:\n\n> ${source.passage.take(1400).replace("\n", "\n> ")}\n\n")
        }
        append("## Latest papers\n\nPublication chronology is limited to captured bibliographic metadata; issue year alone does not establish a new finding.\n\n")
        packet.unread.forEach { append("- [${it.id}] ${it.title}: unread; ${it.failureReason.ifBlank { it.status }}. Results were not verified.\n") }
        append("\n## Comparison of evidence\n\nA verified comparison is unavailable; quotations alone cannot establish agreement, causality or generalizability.\n\n")
        append("## Safety and limitations\n\nThe retained excerpts may omit methods, adverse events or other limitations. Absence of reported harm does not establish safety.\n\n")
        append("## Research gaps and outlook\n\nComplete the unread sources and independent review before drawing stronger conclusions.\n\n")
        append("## References\n\n")
        (packet.studies + packet.unread).forEach { append("- [${it.id}] [${it.title.replace('[', '(').replace(']', ')')}](${it.url})${if (it.doi.isBlank()) "" else " · DOI: ${it.doi}"}\n") }
        append("\nSearch date: ${packet.searchDate}. Delivery checks are structural and numerical; semantic coverage is unverified.")
    }.let { report ->
        if (packet.contract == ResearchReportContract.SCIENTIFIC) {
            report
        } else {
            ResearchDeliveryPacket.SECTIONS.zip(packet.sections).fold(report) { text, (old, new) -> text.replace("## $old", "## $new") }
                .replace("scientific interpretation", "interpretation")
                .replace("scientific truth", "factual accuracy")
                .replace("relevant study", "relevant source")
                .replace("bibliographic metadata; issue year alone does not establish a new finding", "source metadata; publication date alone does not establish a new finding")
                .replace("methods, adverse events or other limitations. Absence of reported harm does not establish safety", "context, tradeoffs or other limitations. Missing observations do not establish reliability")
        }
    }
}
