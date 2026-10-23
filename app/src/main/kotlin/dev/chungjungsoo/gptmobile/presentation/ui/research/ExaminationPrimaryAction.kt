package dev.chungjungsoo.gptmobile.presentation.ui.research

import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationJob
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationOrigin
import dev.chungjungsoo.gptmobile.data.research.feature.ExaminationStage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class ExaminationPrimaryAction { EXAMINE, RESUME, EXAMINE_AGAIN }
internal data class ExaminationPrimarySelection(val action: ExaminationPrimaryAction, val resumeJobId: String? = null)

@Serializable
private data class ExaminationVersionReference(val versionId: String? = null)
private val versionReferenceJson = Json { ignoreUnknownKeys = true }

/** Callers scope jobs to the displayed work, chat and authority before resolving this action. */
internal fun examinationPrimarySelection(jobs: List<ExaminationJob>, profileUid: String, versionId: String): ExaminationPrimarySelection {
    val latest = jobs.filter { job ->
        job.profileUid == profileUid &&
            (job.requestedVersionId == versionId || runCatching { versionReferenceJson.decodeFromString<ExaminationVersionReference>(job.checkpointJson).versionId }.getOrNull() == versionId)
    }.maxWithOrNull(
        compareBy<ExaminationJob> { it.queuedAt.takeIf { time -> time > 0 } ?: it.startedAt.takeIf { time -> time > 0 } ?: it.updatedAt }
            .thenBy { it.updatedAt }.thenBy { it.jobId }
    ) ?: return ExaminationPrimarySelection(ExaminationPrimaryAction.EXAMINE)
    return when {
        latest.stage in setOf(ExaminationStage.COMPLETE_FULL, ExaminationStage.COMPLETE_PARTIAL, ExaminationStage.ABSTRACT_ONLY) -> ExaminationPrimarySelection(ExaminationPrimaryAction.EXAMINE_AGAIN)
        latest.origin == ExaminationOrigin.USER_EXAMINE -> ExaminationPrimarySelection(ExaminationPrimaryAction.RESUME, latest.jobId)
        else -> ExaminationPrimarySelection(ExaminationPrimaryAction.EXAMINE)
    }
}
