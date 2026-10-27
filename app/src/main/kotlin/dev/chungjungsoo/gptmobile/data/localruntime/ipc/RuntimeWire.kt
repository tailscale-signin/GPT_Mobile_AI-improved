package dev.chungjungsoo.gptmobile.data.localruntime.ipc

import android.content.Context
import android.os.Bundle
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import dev.chungjungsoo.gptmobile.data.localruntime.LocalConversationConfig
import dev.chungjungsoo.gptmobile.data.localruntime.LocalEngineSpec
import dev.chungjungsoo.gptmobile.data.localruntime.LocalHistoryMessage
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics
import dev.chungjungsoo.gptmobile.data.localruntime.LocalModelCapabilities
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeState
import dev.chungjungsoo.gptmobile.data.localruntime.LocalSamplerConfig
import dev.chungjungsoo.gptmobile.data.localruntime.LocalToolDescriptor
import dev.chungjungsoo.gptmobile.data.localruntime.LocalToolExecutor
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class WireConversation(
    val sampler: LocalSamplerConfig,
    val systemPrompt: String?,
    val history: List<LocalHistoryMessage>,
    val tools: List<LocalToolDescriptor>,
    val constrained: Boolean,
    val outputTokens: Int?,
    val thinking: Boolean?,
    val speculative: Boolean?,
    val finalResponseSchemaJson: String? = null,
    val allowAutomaticRecovery: Boolean = true,
    val structuredOutputMode: dev.chungjungsoo.gptmobile.data.localruntime.LocalStructuredOutputMode = dev.chungjungsoo.gptmobile.data.localruntime.LocalStructuredOutputMode.NATIVE_JSON_SAFE_V1,
    val structuredOutputCompatibilityKey: String? = null,
    val structuredOutputProbe: Boolean = false
) {
    fun config(executor: LocalToolExecutor?) =
        LocalConversationConfig(
            sampler,
            systemPrompt,
            history,
            tools,
            constrained,
            executor,
            outputTokens,
            thinking,
            speculative,
            finalResponseSchemaJson,
            allowAutomaticRecovery,
            structuredOutputMode,
            structuredOutputCompatibilityKey,
            structuredOutputProbe
        )

    companion object {
        fun from(config: LocalConversationConfig) =
            WireConversation(
                config.sampler,
                config.systemPrompt,
                config.initialMessages,
                config.tools,
                config.isConstrainedDecodingEnabled,
                config.maxOutputTokens,
                config.thinkingEnabled,
                config.speculativeDecoding,
                config.finalResponseSchemaJson,
                config.allowAutomaticRecovery,
                config.structuredOutputMode,
                config.structuredOutputCompatibilityKey,
                config.structuredOutputProbe
            )
    }
}

@Serializable
internal data class RuntimeWire(
    val operation: String,
    val requestId: String,
    val sessionId: String = "",
    val generation: String = "",
    val sequence: Long = 0,
    val version: Int = 1,
    val text: String = "",
    val images: List<ByteArray> = emptyList(),
    val spec: LocalEngineSpec? = null,
    val settings: AppFeatureSettings? = null,
    val preferredBackend: LocalRuntimeBackend? = null,
    val conversation: WireConversation? = null,
    val metrics: LocalInferenceMetrics? = null,
    val state: LocalRuntimeState? = null,
    val capabilities: LocalModelCapabilities? = null,
    val callId: String = "",
    val toolName: String = "",
    val errorCode: String? = null,
    val loadAttempts: List<dev.chungjungsoo.gptmobile.data.localruntime.LocalLoadAttempt> = emptyList(),
    val processPssBytes: Long? = null,
    val nativeProgress: dev.chungjungsoo.gptmobile.data.localruntime.GgufNativeProgress? = null,
    val safetyTokens: Int = 0,
    val physicalAttemptId: String? = null,
    val attemptedContextTokens: Int? = null,
    val suggestedContextTokens: Int? = null,
    val experiment: dev.chungjungsoo.gptmobile.data.localruntime.CpuExperimentTrial? = null,
    val executionReceipt: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeExecutionReceipt? = null,
    val cleanupReceipt: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeCleanupReceipt? = null,
    val inspectionReceipt: dev.chungjungsoo.gptmobile.data.localruntime.RuntimeInspectionReceipt? = null
)

/** Large input travels through an unlinked app-private descriptor, never a Binder byte array. */
internal object RuntimeWireTransport {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    const val MAX_PAYLOAD_BYTES = 32 * 1024 * 1024

    fun send(
        context: Context,
        target: Messenger,
        packet: RuntimeWire,
        replyTo: Messenger? = null
    ) {
        RuntimeWireEnvelope.validate(
            packet.version, packet.requestId, packet.sessionId,
            packet.generation, packet.callId, packet.sequence
        )
        val content = json.encodeToString(packet)
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        require(contentBytes.size <= MAX_PAYLOAD_BYTES) {
            "Local request exceeds the IPC payload limit; reduce attached images or history."
        }
        var descriptor: ParcelFileDescriptor? = null
        try {
            val data = Bundle()
            if (contentBytes.size < 16 * 1024) {
                data.putString("json", content)
            } else {
                val directory = File(context.noBackupFilesDir, "runtime-ipc-${android.os.Process.myPid()}").apply { mkdirs() }
                val file = File.createTempFile("request-", ".json", directory)
                try {
                    file.writeBytes(contentBytes)
                    descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    data.putParcelable("fd", descriptor)
                } finally {
                    file.delete()
                }
            }
            target.send(
                Message.obtain().apply {
                    what = 1
                    this.data = data
                    this.replyTo = replyTo
                }
            )
        } finally {
            descriptor?.close()
        }
    }

    @Suppress("DEPRECATION")
    fun read(message: Message): RuntimeWire {
        val data = message.data
        val descriptor = data.getParcelable<ParcelFileDescriptor>("fd")
        val content =
            if (descriptor != null) {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_PAYLOAD_BYTES) { "Local IPC input is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
            } else {
                data.getString("json").orEmpty().also { inline ->
                    require(inline.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
                        "Local IPC inline input is too large"
                    }
                }
            }
        return json.decodeFromString<RuntimeWire>(content).also {
            RuntimeWireEnvelope.validate(
                it.version, it.requestId, it.sessionId,
                it.generation, it.callId, it.sequence
            )
        }
    }
}
