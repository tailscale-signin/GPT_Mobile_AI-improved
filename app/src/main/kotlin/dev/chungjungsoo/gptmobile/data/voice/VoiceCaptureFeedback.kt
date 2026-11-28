package dev.chungjungsoo.gptmobile.data.voice

/** One cue belongs to one capture, including asynchronous backend admission and local recorder recovery. */
internal class VoiceCaptureFeedback(private val play: () -> Boolean) {
    private var active = true
    private var consumed = false

    @Synchronized
    fun ready(): Boolean {
        if (!active || consumed) return false
        consumed = true
        return play()
    }

    @Synchronized
    fun cancel() {
        active = false
    }
}

internal fun VoiceAudio.Listener.withCaptureFeedback(feedback: (() -> Boolean)?): VoiceAudio.Listener {
    if (feedback == null) return this
    val owner = this
    val cue = VoiceCaptureFeedback(feedback)
    return object : VoiceAudio.Listener by owner {
        override fun ready() {
            cue.ready()
            owner.ready()
        }
    }
}
