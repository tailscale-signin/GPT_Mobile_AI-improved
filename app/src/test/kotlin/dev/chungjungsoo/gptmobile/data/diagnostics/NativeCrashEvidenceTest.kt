package dev.chungjungsoo.gptmobile.data.diagnostics

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCrashEvidenceTest {
    @Test fun nativeTombstoneKeepsOnlySignalLibraryAndSafeSymbol() {
        val trace = """
            signal 6 (SIGABRT), code -1 (SI_QUEUE)
            Abort message: 'private prompt token=secret-value'
              #00 pc 000000000004 lib64/libc.so (abort+160)
              #01 pc 000000000105 /data/app/private/base.apk!/lib/arm64-v8a/libmoonshine.so (moonshine_transcribe+28)
              #02 pc 000000000106 /data/user/0/private/libunsafe.so (function with private text+4)
        """.trimIndent()

        val result = nativeCrashTraceEvidence(ByteArrayInputStream(trace.encodeToByteArray()))

        assertEquals("sanitized", result.coverage)
        assertEquals("SIGABRT", result.signal)
        assertEquals(listOf("libc.so!abort", "libmoonshine.so!moonshine_transcribe", "libunsafe.so"), result.frames)
        assertFalse(result.fields().contains("secret-value"))
        assertFalse(result.fields().contains("/data/"))
    }

    @Test fun missingBinaryAndUnrecognizedTracesHaveExplicitCoverageReasons() {
        assertEquals("no_trace_stream", nativeCrashTraceEvidence(null).reason)
        assertEquals("unrecognized_binary_tombstone", nativeCrashTraceEvidence(ByteArrayInputStream(byteArrayOf(0, 1, 2, 3))).reason)
        val unknown = nativeCrashTraceEvidence(ByteArrayInputStream("private arbitrary text".encodeToByteArray()))
        assertEquals("no_recognized_tombstone_fields", unknown.reason)
        assertTrue(unknown.frames.isEmpty())
        assertNull(unknown.signal)
    }

    @Test fun voiceBreadcrumbRequiresSameProcessAndARecentEarlierStage() {
        val index = NativeVoiceBreadcrumbIndex()
        index.observe("2026-11-11T17:20:00Z I/VoiceEngine: eventId=e1 · processId=42 · moonshine_model_load_start · stage=model_load_start · nativeOperationId=local-voice:abc")
        index.observe("2026-11-11T17:20:02Z I/VoiceEngine: eventId=e2 · processId=43 · moonshine_decoder_close · stage=decoder_close · nativeOperationId=local-voice:def")

        val matched = index.correlate(42, java.time.Instant.parse("2026-11-11T17:20:03Z").toEpochMilli())
        assertEquals("model_load_start", matched?.stage)
        assertTrue(requireNotNull(matched).fields(java.time.Instant.parse("2026-11-11T17:20:03Z").toEpochMilli()).contains("voiceNativeAttribution=temporal_only"))
        assertNull(index.correlate(99, java.time.Instant.parse("2026-11-11T17:20:03Z").toEpochMilli()))
        assertNull(index.correlate(42, java.time.Instant.parse("2026-11-11T17:30:03Z").toEpochMilli()))
    }

    @Test fun androidProtobufSelectsTheCrashedThreadAndNeverExportsSensitiveFields() {
        val other = protoThread(19, protoFrame("/private/libother.so", "other_engine"))
        val crashed = protoThread(42, protoFrame("/data/app/private/base.apk!/lib/arm64-v8a/libvoice.so", "voice_decode", 512, "aabbccddeeff0011"))
        val trace = protoNumber(5, 42) + protoNumber(6, 42) +
            protoBytes(9, "secret command line".encodeToByteArray()) +
            protoBytes(10, protoNumber(1, 6) + protoBytes(2, "SIGABRT".encodeToByteArray())) +
            protoBytes(14, "secret abort message".encodeToByteArray()) +
            protoBytes(16, other) + protoBytes(16, crashed) +
            protoBytes(18, "secret logs and memory".encodeToByteArray())
        val result = nativeCrashTraceEvidence(ByteArrayInputStream(trace), expectedProcessId = 42)
        assertEquals("sanitized", result.coverage)
        assertEquals("protobuf", result.format)
        assertEquals("SIGABRT", result.signal)
        assertEquals(listOf("libvoice.so!voice_decode"), result.frames)
        assertEquals(listOf("libvoice.so:200"), result.relativePcs)
        assertEquals(listOf("libvoice.so:aabbccddeeff0011"), result.buildIds)
        assertEquals(trace.size, result.bytesRead)
        assertFalse(result.truncated)
        assertFalse(result.fields().contains("secret"))
        assertFalse(result.fields().contains("/data"))
        assertFalse(result.fields().contains("libother"))
    }

    @Test fun protobufFieldOrderAndUnknownOemFieldsDoNotChangeTheProjection() {
        val trace = protoBytes(16, protoThread(21, protoFrame("/lib/libc.so", "abort"))) +
            protoBytes(1001, "opaque private vendor payload".encodeToByteArray()) +
            protoBytes(10, protoBytes(2, "SIGABRT".encodeToByteArray())) + protoNumber(6, 21) + protoNumber(5, 21)
        val result = nativeCrashTraceEvidence(ByteArrayInputStream(trace), expectedProcessId = 21)
        assertEquals(listOf("libc.so!abort"), result.frames)
        assertEquals("protobuf_crashed_thread", result.reason)
        assertFalse(result.fields().contains("private"))
    }

    @Test fun aTraceForAnotherProcessCannotAttributeItsLibrariesToThisExit() {
        val trace = protoNumber(5, 123) + protoNumber(6, 123) + protoBytes(16, protoThread(123, protoFrame("/lib/libwrong.so", "decode")))
        val result = nativeCrashTraceEvidence(ByteArrayInputStream(trace), expectedProcessId = 456)
        assertEquals("trace_process_mismatch", result.reason)
        assertTrue(result.frames.isEmpty())
    }

    @Test fun malformedLengthAndVarintAreBoundedFailuresWithoutInventedFrames() {
        val invalid = protoNumber(5, 42) + protoBytes(10, protoBytes(2, "SIGABRT".encodeToByteArray())) +
            byteArrayOf(0x82.toByte(), 0x01, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x7f)
        assertEquals("malformed_protobuf", nativeCrashTraceEvidence(ByteArrayInputStream(invalid)).reason)
        val overflow = protoNumber(5, 42) + ByteArray(11) { 0xff.toByte() }
        assertEquals("malformed_protobuf", nativeCrashTraceEvidence(ByteArrayInputStream(overflow)).reason)
    }

    @Test fun oversizedTraceReportsPartialCoverageAndStopsAtTheInputLimit() {
        val prefix = protoNumber(5, 42) + protoNumber(6, 42) + protoBytes(10, protoBytes(2, "SIGABRT".encodeToByteArray()))
        val trace = prefix + protoBytes(18, ByteArray(200_000) { 'x'.code.toByte() })
        val result = nativeCrashTraceEvidence(ByteArrayInputStream(trace), expectedProcessId = 42)
        assertEquals("sanitized_partial", result.coverage)
        assertEquals("protobuf_prefix_limit", result.reason)
        assertEquals(128 * 1024, result.bytesRead)
        assertTrue(result.truncated)
        assertEquals("SIGABRT", result.signal)
    }

    @Test fun outOfOrderBreadcrumbSourcesChooseTheNearestEarlierOperation() {
        val index = NativeVoiceBreadcrumbIndex()
        index.observe("2026-11-11T17:20:02Z I/VoiceEngine: processId=42 · stage=decode_start · nativeOperationId=recent")
        index.observe("2026-11-11T17:20:01Z I/VoiceEngine: processId=42 · stage=model_load · nativeOperationId=older")
        index.observe("2026-11-11T17:20:04Z I/VoiceEngine: processId=42 · stage=model_load · nativeOperationId=future")
        val result = index.correlate(42, java.time.Instant.parse("2026-11-11T17:20:03Z").toEpochMilli())
        assertEquals("recent", result?.operationId)
    }

    private fun protoFrame(library: String, symbol: String, pc: Long = 0, build: String = ""): ByteArray =
        protoNumber(1, pc) + protoBytes(4, symbol.encodeToByteArray()) + protoBytes(6, library.encodeToByteArray()) + protoBytes(8, build.encodeToByteArray())

    private fun protoThread(id: Long, frame: ByteArray): ByteArray =
        protoNumber(1, id) + protoBytes(2, protoNumber(1, id) + protoBytes(4, frame))

    private fun protoNumber(number: Int, value: Long): ByteArray = varint(number.toLong() shl 3) + varint(value)
    private fun protoBytes(number: Int, value: ByteArray): ByteArray = varint((number.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    private fun varint(value: Long): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        var remaining = value
        while (remaining > 127) {
            bytes.write(((remaining and 127) or 128).toInt())
            remaining = remaining ushr 7
        }
        bytes.write(remaining.toInt())
        return bytes.toByteArray()
    }
}
