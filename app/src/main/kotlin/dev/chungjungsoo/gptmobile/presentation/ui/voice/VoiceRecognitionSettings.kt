package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionCompute
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionModel
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionModelCatalog
import dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionTimingCapability

@Composable
internal fun VoiceRecognitionModelChoices(
    model: VoiceRecognitionModel,
    compute: VoiceRecognitionCompute,
    threads: Int,
    language: String,
    selectModel: (VoiceRecognitionModel) -> Unit,
    selectCompute: (VoiceRecognitionCompute) -> Unit,
    selectThreads: (Int) -> Unit
) {
    VoiceChoice("Local recognition model", model.id, VoiceRecognitionModel.entries.map { it.id to it.title }, { selectModel(VoiceRecognitionModel.fromSaved(it)) })
    val routes = VoiceRecognitionModelCatalog.computes(model)
    if (routes.size > 1) {
        VoiceChoice("Recognition processor", compute.name, routes.map { it.name to it.title }, { selectCompute(VoiceRecognitionCompute.fromSaved(it)) })
    }
    val explanation = when (model) {
        VoiceRecognitionModel.MOONSHINE_TINY -> "Small English model for a quick start and low storage use."
        VoiceRecognitionModel.MOONSHINE_SMALL -> "Balanced English recognition with word timestamps."
        VoiceRecognitionModel.MOONSHINE_MEDIUM -> "Larger English recognition model with word timestamps. Uses more memory."
        VoiceRecognitionModel.ZIPFORMER_EN -> "Compact streaming English recognition with token start times."
        VoiceRecognitionModel.ZIPFORMER_FR -> "Streaming French recognition with token start times."
        VoiceRecognitionModel.NEMOTRON_3_5 -> "Multilingual streaming recognition, including English, French and Portuguese. Uses more memory than the smaller models."
    }
    Text(explanation, style = MaterialTheme.typography.bodySmall)
    if (model.engine == dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionEngine.MOONSHINE) {
        Text("Dictionary words can guide recognition. Their pronunciations still apply only to spoken replies.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("The pronunciation dictionary applies to spoken replies. Recognition vocabulary hints are unavailable for this model pack.", style = MaterialTheme.typography.bodySmall)
    }
    if (!model.supportsLanguage(language)) {
        Text("Choose a recognition language supported by this model, or use Android’s offline recognizer.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    when (compute) {
        VoiceRecognitionCompute.CPU -> Unit
        VoiceRecognitionCompute.GPU -> Text("Requires a compatible Qualcomm Adreno GPU. Some operations still run on CPU. Compare this route on your phone before choosing it for everyday use.", style = MaterialTheme.typography.bodySmall)
        VoiceRecognitionCompute.NPU -> Text("This NPU pack targets Snapdragon 8 Elite (SM8750). It uses precompiled graphs; other chips require a matching model pack.", style = MaterialTheme.typography.bodySmall)
    }
    if (model.engine == dev.chungjungsoo.gptmobile.data.voice.VoiceRecognitionEngine.SHERPA && compute != VoiceRecognitionCompute.NPU) {
        VoiceChoice("Recognition CPU threads", threads.toString(), listOf("0" to "Automatic") + listOf(1, 2, 3, 4, 6, 8).map { it.toString() to it.toString() }, { selectThreads(it.toInt()) })
        if (threads == 0) {
            val automatic = if (compute == VoiceRecognitionCompute.CPU) {
                "Automatic uses a completed recognition benchmark for this model and phone when available. Until then it uses up to two threads."
            } else {
                "Automatic uses up to two CPU threads for operations not assigned to the GPU. CPU-model calibration does not qualify this different model export."
            }
            Text(automatic, style = MaterialTheme.typography.bodySmall)
        }
    }
    val pack = VoiceRecognitionModelCatalog.pack(model, compute)
    pack?.let {
        Text("Download ${it.downloadBytes / 1_000_000} MB · Installed ${it.installedBytes / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
    }
    if (model.timingCapability == VoiceRecognitionTimingCapability.TOKEN) {
        Text("Live captions retain token start times. Word endings are not supplied by this recognizer.", style = MaterialTheme.typography.bodySmall)
    }
}
