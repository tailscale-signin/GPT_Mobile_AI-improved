package dev.chungjungsoo.gptmobile.data.voice

/** Admission is representative-workload consistency; it does not certify arbitrary spoken text. */
internal data class VoiceBackendPlaybackAdmission(
    val selected: VoiceSpeechBackend,
    val denied: VoiceSpeechBackend? = null,
    val comparison: VoiceSpeechAudioComparison? = null
)

internal object VoiceBackendAudioAdmission {
    private val accelerators = setOf(VoiceSpeechBackend.QNN_GPU, VoiceSpeechBackend.QNN_HTP)

    /** Unknown thermals prevent speed ranking, but cannot erase a complete numerical observation. */
    private fun complete(record: VoiceBackendCapability?, now: Long): Boolean = record != null &&
        record.current(now) &&
        record.failure == null &&
        record.protocol == VoiceMeasuredBackendPolicy.COMPARISON_PROTOCOL &&
        record.comparisonId?.matches(Regex("[0-9a-f]{32}")) == true &&
        record.comparisonScope?.matches(Regex("[0-9a-f]{64}")) == true &&
        record.speakerId?.let { it in 0..255 } == true &&
        record.speechRate?.let { it.isFinite() && it in .5f..2f } == true &&
        VoiceMeasuredBackendPolicy.validAudioObservations(record.audioObservations)

    fun compare(candidate: VoiceBackendCapability?, cpu: VoiceBackendCapability?, now: Long): VoiceSpeechAudioComparison {
        if (!complete(cpu, now) || cpu?.backend != VoiceSpeechBackend.CPU) {
            return VoiceSpeechAudioComparison(VoiceSpeechAudioReferenceStatus.UNOBSERVED, VoiceSpeechAudioReferenceReason.CPU_REFERENCE_MISSING)
        }
        if (!complete(candidate, now) || candidate?.backend !in accelerators || candidate?.verifiedAccelerator != true) {
            return VoiceSpeechAudioComparison(VoiceSpeechAudioReferenceStatus.UNOBSERVED, VoiceSpeechAudioReferenceReason.MEASUREMENT_MISSING)
        }
        checkNotNull(candidate)
        checkNotNull(cpu)
        if (candidate.comparisonId != cpu.comparisonId ||
            candidate.comparisonScope != cpu.comparisonScope ||
            candidate.speakerId != cpu.speakerId ||
            candidate.speechRate != cpu.speechRate
        ) {
            return VoiceSpeechAudioComparison(VoiceSpeechAudioReferenceStatus.UNOBSERVED, VoiceSpeechAudioReferenceReason.CPU_REFERENCE_MISSING)
        }
        return candidate.audioObservations.zip(cpu.audioObservations)
            .map { (actual, reference) -> VoiceSpeechAudioReference.compare(reference, actual) }
            .firstOrNull { it.status != VoiceSpeechAudioReferenceStatus.CONSISTENT }
            ?: VoiceSpeechAudioComparison(VoiceSpeechAudioReferenceStatus.CONSISTENT)
    }

    /** Production only: the explicit benchmark factory deliberately does not call this gate. */
    fun select(requested: VoiceSpeechBackend, records: List<VoiceBackendCapability>, now: Long): VoiceBackendPlaybackAdmission {
        require(requested == VoiceSpeechBackend.AUTO || requested == VoiceSpeechBackend.CPU || requested in accelerators)
        val chosen = if (requested == VoiceSpeechBackend.AUTO) VoiceMeasuredBackendPolicy.choose(records, now) else requested
        if (chosen == VoiceSpeechBackend.CPU) return VoiceBackendPlaybackAdmission(chosen)
        val comparison = compare(records.singleOrNull { it.backend == chosen }, records.singleOrNull { it.backend == VoiceSpeechBackend.CPU }, now)
        return if (comparison.status == VoiceSpeechAudioReferenceStatus.CONSISTENT) {
            VoiceBackendPlaybackAdmission(chosen, comparison = comparison)
        } else {
            VoiceBackendPlaybackAdmission(VoiceSpeechBackend.CPU, chosen, comparison)
        }
    }

    fun recheckOrder(requested: VoiceSpeechBackend, revision: Long): List<VoiceSpeechBackend> {
        require(requested == VoiceSpeechBackend.AUTO || requested in accelerators)
        return VoiceMeasuredBackendPolicy.comparisonOrder(revision).filter {
            requested == VoiceSpeechBackend.AUTO || it == VoiceSpeechBackend.CPU || it == requested
        }
    }

    /** Pair only after all candidates finish: CPU can be first, middle or last in the rotation. */
    fun finalized(records: List<VoiceBackendCapability>, now: Long): List<VoiceBackendCapability> {
        require(records.size <= 3 && records.map { it.backend }.distinct().size == records.size)
        val cpu = records.singleOrNull { it.backend == VoiceSpeechBackend.CPU }
        return records.map { record ->
            if (record.backend in accelerators && compare(record, cpu, now).status == VoiceSpeechAudioReferenceStatus.MISMATCH) {
                record.copy(failure = VoiceSpeechFailureCode.OUTPUT_INVALID, failureStage = VoiceSpeechStage.VALIDATING)
            } else {
                record
            }
        }
    }

    /** At most three fixed-workload receipts; no arbitrary phrase or waveform cache is retained. */
    fun recheck(
        requested: VoiceSpeechBackend,
        revision: Long,
        checkActive: () -> Unit,
        now: () -> Long,
        measure: (VoiceSpeechBackend) -> VoiceBackendCapability?,
        publish: (List<VoiceBackendCapability>) -> Unit
    ) {
        val completed = mutableListOf<VoiceBackendCapability>()
        for (backend in recheckOrder(requested, revision)) {
            checkActive()
            measure(backend)?.let {
                require(it.backend == backend)
                completed += it
            }
        }
        checkActive()
        publish(finalized(completed, now()))
    }
}
