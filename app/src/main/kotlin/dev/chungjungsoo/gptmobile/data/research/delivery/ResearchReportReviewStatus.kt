package dev.chungjungsoo.gptmobile.data.research.delivery

internal object ResearchReportReviewStatus {
    fun status(packet: ResearchDeliveryPacket): ResearchReviewStatus {
        val claims = packet.claims.filter {
            it.exactQuoteVerified && it.relevance != "Irrelevant" && it.verdict in setOf("Supported", "Contradicted", "Insufficient")
        }
        val reviewed = claims.count { it.independentReview }
        return when {
            claims.isEmpty() || reviewed == 0 -> ResearchReviewStatus.UNREVIEWED
            reviewed == claims.size -> ResearchReviewStatus.REVIEWED
            else -> ResearchReviewStatus.PARTIALLY_REVIEWED
        }
    }

    /** Accept an explicit legacy unreviewed disclosure without rewriting retained report bytes. */
    fun hasDisclosure(text: String): Boolean =
        Regex("(?im)^Review status:\\s*(reviewed|partially reviewed|unreviewed)\\b").containsMatchIn(text) ||
            legacyUnreviewed.containsMatchIn(text)

    private val legacyUnreviewed = Regex("(?i)\\bindependent review (?:is |was |has )?(?:not (?:available|established|completed|complete)|unavailable|incomplete)\\b")

    fun disclose(text: String, packet: ResearchDeliveryPacket): String {
        val status = status(packet)
        if (status == ResearchReviewStatus.UNREVIEWED && legacyUnreviewed.containsMatchIn(text) && !Regex("(?im)^Review status:").containsMatchIn(text)) return text
        val disclosure = when (status) {
            ResearchReviewStatus.REVIEWED -> "Review status: Reviewed; all retained source-bound claims have independent review."
            ResearchReviewStatus.PARTIALLY_REVIEWED -> "Review status: Partially reviewed; some retained claims have independent review and others remain unreviewed."
            ResearchReviewStatus.UNREVIEWED -> "Review status: Unreviewed; independent review is not established for retained claims."
        }
        val prior = Regex("(?im)^Review status:.*(?:\\r?\\n|$)")
        return if (prior.containsMatchIn(text)) prior.replace(text, disclosure + "\n") else "$disclosure\n\n$text"
    }
}
