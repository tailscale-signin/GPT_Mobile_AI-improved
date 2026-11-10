package dev.chungjungsoo.gptmobile.data.voice

/** A microphone belongs to one capture engine, including across screen/engine changes. */
internal object VoiceMicrophoneLease {
    private var owner: String? = null

    @Synchronized fun acquire(candidate: String): Boolean {
        if (owner != null) return false
        owner = candidate
        return true
    }

    @Synchronized fun release(candidate: String) {
        if (owner == candidate) owner = null
    }
}
