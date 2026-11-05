package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable

/** A private artifact is reusable only for the exact accepted stage, destination and input. */
@Serializable
data class ExaminationArtifactProgress(val requestKey: String, val checkpointId: String, val complete: Boolean = false)

internal object ExaminationArtifactBudget {
    const val MAX_ATTEMPTS = 6
    const val MAX_ARTIFACTS = 1024
    const val EXHAUSTED = "EXAMINATION_STAGE_ATTEMPTS_EXHAUSTED"

    fun key(profile: PlatformV2, kind: String, prompt: String): String = researchHash("$kind|${profile.uid}|${profile.compatibleType}|${profile.apiUrl}|${profile.model}|$prompt")

    fun storageStage(kind: String, key: String): String = "$kind:${key.take(40)}"

    /** Persist the returned claim before opening a model session; process loss never refunds it. */
    fun claim(attempts: Map<String, Int>, key: String): Map<String, Int> {
        val used = attempts[key] ?: 0
        check(used >= 0 && used < MAX_ATTEMPTS && (key in attempts || attempts.size < MAX_ARTIFACTS)) {
            "$EXHAUSTED: This exact examination artifact exhausted its saved attempt allowance. Captured source, completed stages and partial output remain available; change the source or supported Examiner configuration before retrying."
        }
        return attempts + (key to used + 1)
    }
}
