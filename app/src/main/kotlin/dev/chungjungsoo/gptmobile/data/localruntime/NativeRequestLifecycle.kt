package dev.chungjungsoo.gptmobile.data.localruntime

/** One terminal winner, independent from the proof that native handles may be reused. */
internal class NativeRequestLifecycle {
    enum class Stage { PREPARING, ENTERING_NATIVE, ACTIVE, CANCELLING, SETTLING }
    enum class Outcome { COMPLETED, FAILED, CANCELED }

    @Volatile var stage = Stage.PREPARING
        private set

    @Volatile var outcome: Outcome? = null
        private set

    @Volatile var failure: Throwable? = null
        private set

    @Volatile var quiescent = false
        private set

    @Synchronized fun enteringNative() {
        check(stage == Stage.PREPARING && outcome == null)
        stage = Stage.ENTERING_NATIVE
    }

    @Synchronized fun active() {
        if (outcome == null) stage = Stage.ACTIVE
    }

    @Synchronized fun terminal(value: Outcome, cause: Throwable? = null, acknowledged: Boolean = false): Boolean {
        if (acknowledged) quiescent = true
        if (outcome != null) return false
        outcome = value
        failure = cause
        stage = Stage.SETTLING
        return true
    }

    @Synchronized fun cancelling() {
        if (!quiescent) stage = Stage.CANCELLING
    }
}
