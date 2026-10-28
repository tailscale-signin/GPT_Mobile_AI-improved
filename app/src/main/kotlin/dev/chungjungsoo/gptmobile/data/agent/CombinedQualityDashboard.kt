package dev.chungjungsoo.gptmobile.data.agent

/** Profile text-link percentages overlap for shared claims; they are not factual or semantic scores. */
internal data class CombinedProfileCoverage(val profileUid: String, val claims: Int, val linked: Int, val partial: Int, val unresolved: Int, val evaluated: Boolean = true) {
    val linkedPercent: Int? get() = if (!evaluated || claims == 0) null else (linked.toLong() * 100 / claims).toInt()
}

internal data class CombinedQualityDashboard(
    val words: Int,
    val claims: Int,
    val linked: Int,
    val shared: Int,
    val partial: Int,
    val unresolved: Int,
    val evaluated: Boolean,
    val duplicatesRemoved: Int,
    val completion: String,
    val profiles: List<CombinedProfileCoverage>,
    val usage: CombinedReportedUsage?
) {
    companion object {
        fun from(report: CombinedQualityReport, displayedDigest: String): CombinedQualityDashboard? {
            if (!report.finalAssessment || report.answerDigest.isBlank() || report.answerDigest != displayedDigest) return null
            val mapping = report.claimMapping?.takeIf { it.answerDigest == displayedDigest }
            val claims = mapping?.claims.orEmpty().distinctBy { it.canonicalId }
            val profiles = (report.includedProfileUids + report.consideredProfileUids + claims.flatMap { it.profileUids }).sorted().map { uid ->
                val own = claims.filter { uid in it.profileUids }
                CombinedProfileCoverage(uid, own.size, own.count { it.fullyLinked }, own.count { it.status == "PARTIAL" }, own.count { !it.fullyLinked && it.status != "PARTIAL" }, mapping?.evaluated == true)
            }
            return CombinedQualityDashboard(report.wordCount, claims.size, claims.count { it.fullyLinked }, claims.count { it.fullyLinked && it.profileUids.size > 1 }, claims.count { it.status == "PARTIAL" }, claims.count { !it.fullyLinked && it.status != "PARTIAL" }, mapping?.evaluated == true, report.duplicatePassagesRemoved, report.deliveredScopeStatus, profiles, report.writerUsage)
        }
    }
}
