package dev.chungjungsoo.gptmobile.data.assist

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Future/unknown modes disable Assist without resetting unrelated restored preferences. */
object AssistModeSerializer : KSerializer<AssistMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AssistMode", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: AssistMode) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): AssistMode {
        val name = decoder.decodeString()
        return AssistMode.entries.firstOrNull { it.name == name } ?: AssistMode.OFF
    }
}
