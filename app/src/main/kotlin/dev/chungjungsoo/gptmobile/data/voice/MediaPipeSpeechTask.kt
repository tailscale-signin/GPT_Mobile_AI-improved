package dev.chungjungsoo.gptmobile.data.voice

import com.google.mediapipe.tasks.audio.texttospeech.AudioChunk
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.audio.texttospeech.TtsStream
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.File

internal data class MediaPipeSpeechTaskConfiguration(
    val modelFile: File,
    val cacheDirectory: File,
    val requestGpu: Boolean,
    val speakingRate: Float,
    val voiceId: String
)

/** The production implementation calls the pinned SDK directly; tests replace only this boundary. */
internal interface MediaPipeSpeechTask : AutoCloseable {
    fun voiceIds(): Set<String>
    fun openStream(text: String, voiceId: String): MediaPipeSpeechStream
    fun cancel()
}

internal interface MediaPipeSpeechStream : AutoCloseable {
    fun readNext(): AudioChunk?
    fun cancel()
}

internal class AndroidMediaPipeSpeechTask(configuration: MediaPipeSpeechTaskConfiguration) : MediaPipeSpeechTask {
    private val task = TextToSpeech.create(
        TextToSpeechOptions(
            baseOptions = BaseOptions.builder()
                .setModelAssetPath(configuration.modelFile.absolutePath)
                .setDelegate(if (configuration.requestGpu) Delegate.GPU else Delegate.CPU)
                .build(),
            defaultVoiceId = configuration.voiceId,
            speakingRate = configuration.speakingRate,
            targetBucket = 0,
            cacheDir = configuration.cacheDirectory.absolutePath
        )
    )

    override fun voiceIds(): Set<String> = task.listVoices().mapTo(mutableSetOf()) { it.voiceId }

    override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream = AndroidMediaPipeSpeechStream(
        task.openStream(initialText = text, options = SynthesisOptions(voiceId = voiceId), finishInput = true)
    )

    override fun cancel() = task.cancel()

    override fun close() = task.close()

    private class AndroidMediaPipeSpeechStream(private val stream: TtsStream) : MediaPipeSpeechStream {
        override fun readNext(): AudioChunk? = stream.readNext()
        override fun cancel() = stream.cancel()
        override fun close() = stream.close()
    }
}
