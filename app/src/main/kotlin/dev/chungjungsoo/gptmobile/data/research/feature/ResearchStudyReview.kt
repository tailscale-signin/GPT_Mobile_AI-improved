package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReviewDomain(val domain: String, val grade: Int?, val rationale: String, val passageIds: List<String>)

@Serializable
data class StudyReviewArtifact(
    val conclusion: String,
    val limitation: String,
    val outcomeKey: String,
    val passageIds: List<String>,
    val reliability: List<ReviewDomain>,
    val accuracy: List<ReviewDomain>,
    val criticalConcern: Boolean = false,
    val summaryContradiction: Boolean = false,
    val integrityConcern: Boolean = false
)

@Serializable
@Entity(tableName = "research_study_reviews", foreignKeys = [ForeignKey(entity = ResearchWorkVersion::class, parentColumns = ["versionId"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("versionId"), Index("workId")])
data class ResearchStudyReview(
    @PrimaryKey val reviewId: String,
    val workId: String,
    val versionId: String,
    val jobId: String,
    val jobLease: Long,
    val profileUid: String,
    val model: String,
    val configHash: String,
    val sourceHash: String,
    val summaryHash: String,
    val artifactJson: String,
    val reliabilityScore: Int?,
    val accuracyScore: Int?,
    val overallScore: Int?,
    val status: String,
    val createdAt: Long,
    val rubricVersion: Int = 1,
    val coverageJson: String = "{}",
    val design: StudyDesign = StudyDesign.UNKNOWN
)

@Serializable
@Entity(tableName = "research_work_studies", primaryKeys = ["workId", "studyId"], foreignKeys = [ForeignKey(entity = ResearchWork::class, parentColumns = ["workId"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = ResearchStudyFamily::class, parentColumns = ["familyId"], childColumns = ["studyId"], onDelete = ForeignKey.CASCADE)], indices = [Index("studyId")])
data class WorkStudyRelationship(val workId: String, val studyId: String, val provenance: String, val revisedAt: Long)

/** Versioned app rubric. Unknown domains never earn points; a score is not a probability. */
internal object ResearchReviewScoreCalculator {
    val reliabilityDomains = listOf("design_comparison", "selection_confounding", "measurement_conduct", "analysis_missing_data", "reporting_consistency")
    val accuracyDomains = listOf("population_design", "exposure_comparison", "results_timepoint", "direction_uncertainty", "qualification")
    data class Scores(val reliability: Int?, val accuracy: Int?, val overall: Int?, val status: String)
    fun calculate(artifact: StudyReviewArtifact, fullScope: Boolean, design: StudyDesign, retracted: Boolean, rubricVersion: Int = ResearchReviewRubric.VERSION): Scores {
        require(rubricVersion in ResearchReviewRubric.supportedVersions)
        fun component(values: List<ReviewDomain>, expected: List<String>): Int? {
            if (values.map { it.domain }.toSet() != expected.toSet() || values.size != expected.size) return null
            if (values.any { it.grade == null || it.grade !in 0..4 || it.passageIds.isEmpty() || it.rationale.isBlank() }) return null
            return values.sumOf { requireNotNull(it.grade) } * 5
        }
        val reliability = if (fullScope && ResearchReviewRubric.supports(design)) component(artifact.reliability, reliabilityDomains) else null
        val accuracy = component(artifact.accuracy, accuracyDomains)
        val status = when {
            retracted || artifact.integrityConcern -> "Integrity concern"
            artifact.criticalConcern -> "Critical concern"
            artifact.summaryContradiction -> "Needs correction"
            reliability == null || accuracy == null -> "Limited review"
            else -> "Reviewed"
        }
        return Scores(reliability, accuracy, if (status == "Reviewed") minOf(requireNotNull(reliability), requireNotNull(accuracy)) else null, status)
    }
    fun validate(reply: String, outcome: ResearchOutcome, passages: List<EvidencePassage>, rubricVersion: Int = ResearchReviewRubric.VERSION): StudyReviewArtifact {
        require(rubricVersion in ResearchReviewRubric.supportedVersions)
        require(reply.toByteArray().size <= 48000)
        val artifact = Json.decodeFromString<StudyReviewArtifact>(reply.trim())
        require(artifact.outcomeKey == outcome.outcomeKey && artifact.conclusion.length in 1..1200 && artifact.limitation.length in 1..1200)
        val byId = passages.filter { it.versionId == outcome.versionId }.associateBy { it.passageId }
        if (rubricVersion >= 2) require(byId.values.all { it.start >= 0 && it.end - it.start == it.quote.length && researchHash(it.quote) == it.textHash }) { "Review evidence locator or content hash changed" }
        fun valid(ids: List<String>) = ids.isNotEmpty() && ids.distinct().size == ids.size && ids.all { it in byId }
        require(valid(artifact.passageIds))
        val quotes = artifact.passageIds.map { byId.getValue(it).quote }
        require(dev.chungjungsoo.gptmobile.data.research.delivery.ResearchNumericalGrounding.issues(artifact.conclusion + "\n" + artifact.limitation, quotes).isEmpty()) { "Conclusion numbers, units or comparator order do not match the cited evidence" }
        require(artifact.reliability.size <= 5 && artifact.accuracy.size <= 5)
        (artifact.reliability + artifact.accuracy).forEach {
            require(it.grade == null || it.grade in 0..4)
            require(it.rationale.length in 1..1600 && (it.grade == null || valid(it.passageIds)))
            require(it.passageIds.all { id -> id in byId })
            if (rubricVersion >= 2 && it.grade != null) {
                require(dev.chungjungsoo.gptmobile.data.research.delivery.ResearchNumericalGrounding.issues(it.rationale, it.passageIds.map { id -> byId.getValue(id).quote }).isEmpty()) { "Domain judgment numbers or comparisons do not match its cited evidence" }
            }
        }
        // Empty evidence and invented locators cannot produce a favorable score.
        require(artifact.reliability.map { it.domain }.distinct().size == artifact.reliability.size)
        require(artifact.accuracy.map { it.domain }.distinct().size == artifact.accuracy.size)
        require(artifact.reliability.all { it.domain in reliabilityDomains } && artifact.accuracy.all { it.domain in accuracyDomains })
        return artifact
    }
    fun summaryHash(artifact: StudyReviewArtifact) = researchHash(artifact.conclusion + "\n" + artifact.limitation)
}

/** A deterministic coverage receipt, never an invented null finding or a scientific score. */
internal fun limitedEvidenceAssessment(passageIds: List<String>, coverage: ArticleCoverage): StudyReviewArtifact {
    require(passageIds.isNotEmpty())
    val limitation = if (coverage == ArticleCoverage.ABSTRACT_ONLY) "Only the abstract was available. Methods and results were not fully assessed." else "No supported outcome/effect pair was extracted from the inspected material. This does not establish absence of benefit or harm."
    return StudyReviewArtifact(
        conclusion = "Source passages were inspected, but the available extraction does not establish an empirical outcome.",
        limitation = limitation,
        outcomeKey = "evidence_coverage_only",
        passageIds = passageIds.distinct().take(12),
        reliability = ResearchReviewScoreCalculator.reliabilityDomains.map { ReviewDomain(it, null, "Insufficient outcome evidence; unscored", emptyList()) },
        accuracy = ResearchReviewScoreCalculator.accuracyDomains.map { ReviewDomain(it, null, "No empirical conclusion assessed; unscored", emptyList()) }
    )
}
