package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import dev.chungjungsoo.gptmobile.R

/** Prepared short sounds. Capture continues immediately when a cue is unavailable. */
internal class VoiceButtonFeedback(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder().setMaxStreams(1)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val loaded = mutableSetOf<Int>()
    private val loadResults = mutableMapOf<Int, Boolean>()
    private val startReadiness = VoiceMicrophoneCueReadiness()
    private var closed = false
    private var start = 0
    private val stop: Int
    private val click: Int

    init {
        pool.setOnLoadCompleteListener { _, sample, status ->
            synchronized(this) {
                if (!closed) {
                    loadResults[sample] = status == 0
                    if (status == 0) loaded.add(sample)
                    if (sample == start) startReadiness.loaded(status == 0)
                }
            }
        }
        start = pool.load(context, R.raw.voice_mic_start, 1)
        synchronized(this) {
            if (start == 0) startReadiness.loaded(false) else loadResults[start]?.let(startReadiness::loaded)
        }
        stop = pool.load(context, R.raw.voice_mic_stop, 1)
        click = pool.load(context, R.raw.voice_button_click, 1)
    }

    suspend fun prepareStart(): Boolean = startReadiness.prepare()
    fun start(): Boolean = play(start)
    fun stop() {
        play(stop)
    }
    fun click() {
        play(click)
    }

    @Synchronized
    private fun play(sample: Int): Boolean =
        !closed &&
            sample in loaded &&
            audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            pool.play(sample, 0.26f, 0.26f, 1, 0, 1f) != 0

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        startReadiness.loaded(false)
        pool.release()
    }
}
