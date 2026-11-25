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
    val delegate: Delegate,
    val speakingRate: Float,
    val voiceId: String,
    val targetBucket: Int = 0
) {
    init {
        MediaPipeKokoroBucket.fromSdkValue(targetBucket)
    }
}

/** The production implementation calls the pinned SDK directly; tests replace only this boundary. */
internal interface MediaPipeSpeechTask : AutoCloseable {
    fun voiceIds(): Set<String>
    fun openStream(text: String, voiceId: String): MediaPipeSpeechStream
    fun openIncrementalStream(voiceId: String): MediaPipeSpeechStream = throw UnsupportedOperationException("Incremental speech input is unavailable")
    fun cancel()
}

internal interface MediaPipeSpeechStream : AutoCloseable {
    fun readNext(): AudioChunk?
    fun pushText(text: String): Unit = throw UnsupportedOperationException("This speech stream has sealed input")
    fun finishInput(): Unit = throw UnsupportedOperationException("This speech stream has sealed input")
    fun cancel()
}

internal class AndroidMediaPipeSpeechTask(configuration: MediaPipeSpeechTaskConfiguration) : MediaPipeSpeechTask {
    private val task = TextToSpeech.create(
        TextToSpeechOptions(
            baseOptions = BaseOptions.builder()
                .setModelAssetPath(configuration.modelFile.absolutePath)
                .setDelegate(configuration.delegate)
                .build(),
            defaultVoiceId = configuration.voiceId,
            speakingRate = configuration.speakingRate,
            targetBucket = configuration.targetBucket,
            cacheDir = configuration.cacheDirectory.absolutePath
        )
    )

    override fun voiceIds(): Set<String> = task.listVoices().mapTo(mutableSetOf()) { it.voiceId }

    override fun openStream(text: String, voiceId: String): MediaPipeSpeechStream = AndroidMediaPipeSpeechStream(
        task.openStream(initialText = text, options = SynthesisOptions(voiceId = voiceId), finishInput = true)
    )

    override fun openIncrementalStream(voiceId: String): MediaPipeSpeechStream = AndroidMediaPipeSpeechStream(
        task.openStream(initialText = "", options = SynthesisOptions(voiceId = voiceId), finishInput = false)
    )

    override fun cancel() = task.cancel()

    override fun close() = task.close()

    private class AndroidMediaPipeSpeechStream(private val stream: TtsStream) : MediaPipeSpeechStream {
        override fun readNext(): AudioChunk? = stream.readNext()
        override fun pushText(text: String) = stream.pushText(text)
        override fun finishInput() = stream.finishInput()
        override fun cancel() = stream.cancel()
        override fun close() = stream.close()
    }
}
