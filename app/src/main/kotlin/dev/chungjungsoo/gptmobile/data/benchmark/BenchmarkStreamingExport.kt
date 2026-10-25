package dev.chungjungsoo.gptmobile.data.benchmark

import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToStream

internal class BenchmarkExportRecords(private val write: (emit: (JsonElement) -> Unit) -> Unit) {
    fun writeTo(emit: (JsonElement) -> Unit) = write(emit)
}

internal class BenchmarkExportRecordEmitter(private val emit: (JsonElement) -> Unit) {
    fun add(value: JsonElement) = emit(value)
}

internal fun benchmarkExportRecords(values: Sequence<JsonElement>) = BenchmarkExportRecords { emit -> values.forEach(emit) }
internal fun benchmarkExportRecords(write: BenchmarkExportRecordEmitter.() -> Unit) = BenchmarkExportRecords { emit -> BenchmarkExportRecordEmitter(emit).write() }

internal interface BenchmarkExportOutput {
    fun put(name: String, value: JsonElement)
    fun put(name: String, records: BenchmarkExportRecords)
    fun put(name: String, value: String) = put(name, JsonPrimitive(value))
    fun put(name: String, value: Int) = put(name, JsonPrimitive(value))
    fun put(name: String, value: Long) = put(name, JsonPrimitive(value))
}

/** Never owns the stream and never accumulates a complete document or history array. */
@OptIn(ExperimentalSerializationApi::class)
internal class BenchmarkStreamingExport(private val output: OutputStream) : BenchmarkExportOutput {
    private var firstField = true
    private var finished = false

    init {
        output.write('{'.code)
    }

    override fun put(name: String, value: JsonElement) {
        field(name)
        element(value)
    }

    override fun put(name: String, records: BenchmarkExportRecords) {
        field(name)
        output.write('['.code)
        var firstRecord = true
        records.writeTo { value ->
            if (!firstRecord) output.write(','.code)
            output.write('\n'.code)
            firstRecord = false
            element(value)
        }
        output.write(']'.code)
    }

    private fun field(name: String) {
        check(!finished) { "Benchmark export is already finished." }
        if (!firstField) output.write(','.code)
        output.write('\n'.code)
        firstField = false
        benchmarkExportJson.encodeToStream(JsonElement.serializer(), JsonPrimitive(name), output)
        output.write(':'.code)
    }

    private fun element(value: JsonElement) {
        benchmarkExportJson.encodeToStream(JsonElement.serializer(), redactBenchmarkExport(value), output)
    }

    fun finish() {
        check(!finished) { "Benchmark export is already finished." }
        output.write('\n'.code)
        output.write('}'.code)
        output.flush()
        finished = true
    }
}
