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
        assertEquals("binary_tombstone_not_text", nativeCrashTraceEvidence(ByteArrayInputStream(byteArrayOf(0, 1, 2, 3))).reason)
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
}
