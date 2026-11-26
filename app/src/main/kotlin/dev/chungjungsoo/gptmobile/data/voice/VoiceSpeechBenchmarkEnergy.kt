package dev.chungjungsoo.gptmobile.data.voice

internal enum class VoiceSpeechEnergyChargeState { UNKNOWN, DISCHARGING, CHARGING, FULL, NOT_CHARGING }

/** Android's remaining-energy counter is whole-device evidence, not energy attributable to speech. */
internal data class VoiceSpeechEnergyEndpoint(
    val remainingNanowattHours: Long? = null,
    val chargeState: VoiceSpeechEnergyChargeState = VoiceSpeechEnergyChargeState.UNKNOWN,
    val plugged: Boolean? = null
) {
    init {
        require(remainingNanowattHours == null || remainingNanowattHours >= 0)
    }

    val unpluggedDischarge: Boolean get() = chargeState == VoiceSpeechEnergyChargeState.DISCHARGING && plugged == false
    val powerStateKnown: Boolean get() = chargeState != VoiceSpeechEnergyChargeState.UNKNOWN && plugged != null
}

internal enum class VoiceSpeechEnergyStatus {
    IN_PROGRESS,
    INTERRUPTED,
    COUNTER_UNAVAILABLE,
    POWER_STATE_UNKNOWN,
    EXTERNAL_POWER_OBSERVED,
    MONITOR_UNCONFIRMED,
    TIMING_UNAVAILABLE,
    COUNTER_ZERO_OR_RESET,
    COUNTER_INCREASED,
    NO_COUNTER_CHANGE,
    COUNTER_DECREASE_OBSERVED
}

/** Only endpoints and bounded monitoring totals are retained; no battery broadcasts or device IDs. */
internal data class VoiceSpeechBenchmarkEnergyObservation(
    val requestedBackend: VoiceSpeechBackend,
    val start: VoiceSpeechEnergyEndpoint,
    val end: VoiceSpeechEnergyEndpoint? = null,
    val elapsedMs: Long? = null,
    val monitorRegistered: Boolean = false,
    val monitoringConfirmed: Boolean = false,
    val observedPowerEvents: Int = 0,
    val externalPowerObserved: Boolean = false,
    val unknownPowerStateObserved: Boolean = false,
    val interrupted: Boolean = false,
    val window: Int = 1
) {
    init {
        require(elapsedMs == null || elapsedMs in 0..MAX_WINDOW_MS)
        require(observedPowerEvents in 0..MAX_POWER_EVENTS)
        require(window in 1..2)
        require(!monitoringConfirmed || monitorRegistered && end != null)
        require(!interrupted || end == null && elapsedMs == null && !monitoringConfirmed)
    }

    val status: VoiceSpeechEnergyStatus
        get() {
            val last = end ?: return if (interrupted) VoiceSpeechEnergyStatus.INTERRUPTED else VoiceSpeechEnergyStatus.IN_PROGRESS
            val before = start.remainingNanowattHours ?: return VoiceSpeechEnergyStatus.COUNTER_UNAVAILABLE
            val after = last.remainingNanowattHours ?: return VoiceSpeechEnergyStatus.COUNTER_UNAVAILABLE
            return when {
                externalPowerObserved ||
                    start.plugged == true ||
                    last.plugged == true ||
                    start.chargeState in POWERED_STATES ||
                    last.chargeState in POWERED_STATES -> VoiceSpeechEnergyStatus.EXTERNAL_POWER_OBSERVED
                unknownPowerStateObserved || !start.powerStateKnown || !last.powerStateKnown -> VoiceSpeechEnergyStatus.POWER_STATE_UNKNOWN
                !start.unpluggedDischarge || !last.unpluggedDischarge -> VoiceSpeechEnergyStatus.POWER_STATE_UNKNOWN
                !monitoringConfirmed -> VoiceSpeechEnergyStatus.MONITOR_UNCONFIRMED
                elapsedMs == null || elapsedMs <= 0 -> VoiceSpeechEnergyStatus.TIMING_UNAVAILABLE
                before == 0L || after == 0L -> VoiceSpeechEnergyStatus.COUNTER_ZERO_OR_RESET
                after > before -> VoiceSpeechEnergyStatus.COUNTER_INCREASED
                before == after -> VoiceSpeechEnergyStatus.NO_COUNTER_CHANGE
                else -> VoiceSpeechEnergyStatus.COUNTER_DECREASE_OBSERVED
            }
        }

    /** An unchanged/coarse counter is not evidence of zero consumption. Never used for ranking. */
    val observedCounterDecreaseNanowattHours: Long?
        get() = if (status == VoiceSpeechEnergyStatus.COUNTER_DECREASE_OBSERVED) checkNotNull(start.remainingNanowattHours) - checkNotNull(end?.remainingNanowattHours) else null

    companion object {
        const val MAX_WINDOW_MS = 86_400_000L
        const val MAX_POWER_EVENTS = 1_000_000
        private val POWERED_STATES = setOf(VoiceSpeechEnergyChargeState.CHARGING, VoiceSpeechEnergyChargeState.FULL)
    }
}

internal interface VoiceSpeechBenchmarkEnergyWindow {
    fun current(): VoiceSpeechBenchmarkEnergyObservation
    fun finish(): VoiceSpeechBenchmarkEnergyObservation
}

/** Product goals are exported separately from observations and never confer qualification. */
internal object VoiceSpeechBenchmarkTargets {
    const val PREPARE_TO_FIRST_PCM_MS = 800L
    const val WARM_FIRST_PCM_MS = 400L
    const val LONG_SPEECH_RTF = .5
    const val ACCELERATOR_KERNEL_TIME_SHARE = .8
    const val CLIPPED_SAMPLES = 0L
}
