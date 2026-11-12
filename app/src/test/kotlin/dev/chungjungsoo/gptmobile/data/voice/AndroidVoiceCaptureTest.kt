package dev.chungjungsoo.gptmobile.data.voice

import android.Manifest
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidVoiceCaptureTest {
    @Test fun deniedOutputFocusDoesNotBlockPermittedRecordingOrPcm() {
        val fixture = Fixture()
        try {
            fixture.capture.start()
            assertEquals(1, fixture.recorderCreations)
            verify(fixture.record).startRecording()
            assertArrayEquals(FloatArray(1600) { .5f }, fixture.capture.read(), 0f)
            val request = ArgumentCaptor.forClass(AudioFocusRequest::class.java)
            verify(fixture.manager).requestAudioFocus(request.capture())
            verify(fixture.manager).abandonAudioFocusRequest(request.value)
        } finally {
            fixture.capture.close()
        }
        verify(fixture.record).release()
    }

    @Test fun microphonePermissionStillPreventsFocusAndRecorderAdmission() {
        val fixture = Fixture()
        shadowOf(fixture.context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        try {
            assertThrows(SecurityException::class.java) { fixture.capture.start() }
            assertEquals(0, fixture.recorderCreations)
            verify(fixture.manager, never()).requestAudioFocus(any(AudioFocusRequest::class.java))
            verify(fixture.record, never()).startRecording()
        } finally {
            fixture.capture.close()
        }
    }

    @Test fun permissionRevokedDuringConfigurationBlocksTheDefaultRecorderFactory() {
        val context = RuntimeEnvironment.getApplication()
        val manager = mock(AudioManager::class.java)
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        `when`(manager.requestAudioFocus(any(AudioFocusRequest::class.java))).thenReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val capture = AndroidVoiceCapture(
            context = context,
            manager = manager,
            minimumBufferSize = {
                shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
                3200
            }
        )
        val request = ArgumentCaptor.forClass(AudioFocusRequest::class.java)
        try {
            val failure = assertThrows(SecurityException::class.java) { capture.start() }
            assertEquals("Microphone permission was revoked before recorder creation.", failure.message)
            assertTrue(capture.read().isEmpty())
            verify(manager).requestAudioFocus(request.capture())
            verify(manager, never()).abandonAudioFocusRequest(any(AudioFocusRequest::class.java))
        } finally {
            capture.close()
        }
        verify(manager).abandonAudioFocusRequest(request.value)
    }

    @Test fun permissionRegrantAllowsANewCaptureAfterPermissionRejection() {
        val rejected = Fixture()
        shadowOf(rejected.context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        try {
            assertThrows(SecurityException::class.java) { rejected.capture.start() }
            assertEquals(0, rejected.recorderCreations)
            verify(rejected.manager, never()).requestAudioFocus(any(AudioFocusRequest::class.java))
        } finally {
            rejected.capture.close()
        }
        val restored = Fixture()
        try {
            restored.capture.start()
            assertEquals(1, restored.recorderCreations)
            verify(restored.record).startRecording()
            assertArrayEquals(FloatArray(1600) { .5f }, restored.capture.read(), 0f)
        } finally {
            restored.capture.close()
        }
        verify(restored.record).release()
        verify(rejected.record, never()).startRecording()
    }

    @Test fun focusLossDuringTheFocusRequestCannotStartARecorder() {
        val fixture = Fixture(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        `when`(fixture.manager.requestAudioFocus(any(AudioFocusRequest::class.java))).thenAnswer {
            val request = it.getArgument<AudioFocusRequest>(0)
            val listener = ReflectionHelpers.callInstanceMethod<AudioManager.OnAudioFocusChangeListener>(request, "getOnAudioFocusChangeListener")
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        try {
            val failure = assertThrows(LocalVoiceCaptureException::class.java) { fixture.capture.start() }
            assertEquals("MICROPHONE_FOCUS_LOST", failure.code)
            assertEquals(0, fixture.recorderCreations)
            verify(fixture.record, never()).startRecording()
        } finally {
            fixture.capture.close()
        }
    }

    @Test fun cancellationDuringTheFocusRequestCannotStartARecorder() {
        val fixture = Fixture(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        `when`(fixture.manager.requestAudioFocus(any(AudioFocusRequest::class.java))).thenAnswer {
            fixture.capture.stop()
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        try {
            fixture.capture.start()
            assertEquals(0, fixture.recorderCreations)
            assertTrue(fixture.capture.read().isEmpty())
            verify(fixture.record, never()).startRecording()
        } finally {
            fixture.capture.close()
        }
    }

    @Test fun deniedOutputFocusDoesNotHideAnUninitializedRecorder() {
        val fixture = Fixture()
        `when`(fixture.record.state).thenReturn(AudioRecord.STATE_UNINITIALIZED)
        try {
            val failure = assertThrows(LocalVoiceCaptureException::class.java) { fixture.capture.start() }
            assertEquals("MICROPHONE_UNINITIALIZED", failure.code)
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_FAILED, failure.audioFocusResult)
            verify(fixture.record, never()).startRecording()
        } finally {
            fixture.capture.close()
        }
        verify(fixture.record).release()
    }

    @Test fun deniedOutputFocusDoesNotHideARecorderStartFailure() {
        val fixture = Fixture()
        doThrow(IllegalStateException("unavailable")).`when`(fixture.record).startRecording()
        try {
            val failure = assertThrows(LocalVoiceCaptureException::class.java) { fixture.capture.start() }
            assertEquals("MICROPHONE_START_FAILED", failure.code)
            assertEquals("capture_start", failure.phase)
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_FAILED, failure.audioFocusResult)
        } finally {
            fixture.capture.close()
        }
        verify(fixture.record).release()
    }

    @Test fun aRecorderThatDidNotEnterRecordingCannotProducePcm() {
        val fixture = Fixture()
        `when`(fixture.record.recordingState).thenReturn(AudioRecord.RECORDSTATE_STOPPED)
        try {
            val failure = assertThrows(LocalVoiceCaptureException::class.java) { fixture.capture.start() }
            assertEquals("MICROPHONE_NOT_RECORDING", failure.code)
            verify(fixture.record, never()).read(any(ShortArray::class.java), anyInt(), anyInt(), anyInt())
        } finally {
            fixture.capture.close()
        }
    }

    @Test fun systemSilencedInputStopsBeforeAnyPcmIsRead() {
        val fixture = Fixture()
        fixture.silenced = true
        try {
            val failure = assertThrows(LocalVoiceCaptureException::class.java) { fixture.capture.start() }
            assertEquals("MICROPHONE_CAPTURE_SILENCED", failure.code)
            assertEquals("capture_start", failure.phase)
            assertFalse(failure.retryable)
            assertEquals(0L, failure.capturedSamples)
            assertTrue(fixture.capture.read().isEmpty())
            verify(fixture.record, never()).read(any(ShortArray::class.java), anyInt(), anyInt(), anyInt())
            verify(fixture.record).stop()
        } finally {
            fixture.capture.close()
        }
        verify(fixture.record).release()
    }

    @Test fun inputSilencedDuringReadNeverReachesTheSpeechDecoder() {
        val fixture = Fixture()
        fixture.beforeRead = { fixture.silenced = true }
        try {
            fixture.capture.start()
            val failure = assertThrows(LocalVoiceCaptureException::class.java) { fixture.capture.read() }
            assertEquals("MICROPHONE_CAPTURE_SILENCED", failure.code)
            assertEquals("capture_read", failure.phase)
            assertEquals(0L, failure.capturedSamples)
            assertTrue(fixture.capture.read().isEmpty())
            verify(fixture.record).stop()
        } finally {
            fixture.capture.close()
        }
    }

    @Test fun cancellationDuringReadDiscardsPcmWithoutReportingAPolicyFailure() {
        val fixture = Fixture()
        fixture.beforeRead = {
            fixture.silenced = true
            fixture.capture.stop()
        }
        try {
            fixture.capture.start()
            assertTrue(fixture.capture.read().isEmpty())
            verify(fixture.record).stop()
        } finally {
            fixture.capture.close()
        }
    }

    @Test fun missingRecordingConfigurationDoesNotInventAnInputPolicyDenial() {
        val fixture = Fixture()
        `when`(fixture.record.activeRecordingConfiguration).thenReturn(null)
        try {
            fixture.capture.start()
            assertArrayEquals(FloatArray(1600) { .5f }, fixture.capture.read(), 0f)
        } finally {
            fixture.capture.close()
        }
    }

    @Test fun grantedOutputFocusIsAbandonedWhenCaptureCloses() {
        val fixture = Fixture(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        fixture.capture.start()
        val request = ArgumentCaptor.forClass(AudioFocusRequest::class.java)
        verify(fixture.manager).requestAudioFocus(request.capture())
        verify(fixture.manager, never()).abandonAudioFocusRequest(any(AudioFocusRequest::class.java))
        fixture.capture.close()
        verify(fixture.manager).abandonAudioFocusRequest(request.value)
        verify(fixture.record).release()
    }

    private class Fixture(focusResult: Int = AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
        val context = RuntimeEnvironment.getApplication()
        val manager = mock(AudioManager::class.java)
        val record = mock(AudioRecord::class.java)
        private val configuration = mock(AudioRecordingConfiguration::class.java)
        var recorderCreations = 0
        var silenced = false
        var beforeRead: (() -> Unit)? = null
        private var recording = false
        val capture = AndroidVoiceCapture(context, manager, { 3200 }) {
            recorderCreations++
            assertEquals(16_000, it)
            record
        }

        init {
            shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
            `when`(manager.requestAudioFocus(any(AudioFocusRequest::class.java))).thenReturn(focusResult)
            `when`(record.state).thenReturn(AudioRecord.STATE_INITIALIZED)
            `when`(record.recordingState).thenAnswer { if (recording) AudioRecord.RECORDSTATE_RECORDING else AudioRecord.RECORDSTATE_STOPPED }
            `when`(record.activeRecordingConfiguration).thenReturn(configuration)
            `when`(configuration.isClientSilenced).thenAnswer { silenced }
            doAnswer {
                recording = true
                null
            }.`when`(record).startRecording()
            doAnswer {
                recording = false
                null
            }.`when`(record).stop()
            `when`(record.read(any(ShortArray::class.java), anyInt(), anyInt(), eq(AudioRecord.READ_NON_BLOCKING))).thenAnswer {
                beforeRead?.invoke()
                val buffer = it.getArgument<ShortArray>(0)
                val offset = it.getArgument<Int>(1)
                val count = it.getArgument<Int>(2)
                buffer.fill(16_384, offset, offset + count)
                count
            }
        }
    }
}
