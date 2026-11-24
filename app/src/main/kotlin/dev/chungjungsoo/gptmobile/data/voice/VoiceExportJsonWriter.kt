package dev.chungjungsoo.gptmobile.data.voice

import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Encodes bounded records directly; no report-sized JSON string or byte array is retained. */
internal class VoiceExportJsonWriter(output: OutputStream) {
    private val writer = OutputStreamWriter(output, Charsets.UTF_8).buffered(8 * 1024)

    fun element(value: JsonElement) {
        when (value) {
            is JsonObject -> objectValue(value)
            is JsonArray -> array(value, ::element)
            else -> writer.write(Json.encodeToString(JsonElement.serializer(), value))
        }
    }

    fun objectValue(value: JsonObject, records: Map<String, () -> Unit> = emptyMap()) {
        writer.write("{")
        value.entries.forEachIndexed { index, (name, field) ->
            if (index != 0) writer.write(",")
            element(JsonPrimitive(name))
            writer.write(":")
            records[name]?.invoke() ?: element(field)
        }
        writer.write("}")
    }

    fun <T> array(values: Iterable<T>, write: (T) -> Unit) {
        writer.write("[")
        values.forEachIndexed { index, value ->
            if (index != 0) writer.write(",")
            write(value)
        }
        writer.write("]")
    }

    /** The caller owns the stream and observes its close/flush errors before marking success. */
    fun finish() = writer.flush()
}
