package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind
import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsentHttpAttemptTest {
    @Test fun onlyObservedDnsFailureBeforeConnectionOrHeadersProvesNoDispatch() {
        val unsent = HttpAttemptTrace { _, _ -> }
        unsent.mark(RunDiagnosticKind.HTTP_CALL_START)
        unsent.failed(UnknownHostException("unresolved.example"))
        assertTrue(unsent.provenUnsent)
        val unknown = HttpAttemptTrace { _, _ -> }
        unknown.failed(UnknownHostException("unresolved.example"))
        assertFalse(unknown.provenUnsent)
        val ambiguous = HttpAttemptTrace { _, _ -> }
        ambiguous.mark(RunDiagnosticKind.HTTP_CALL_START)
        ambiguous.failed(IOException("Connection reset"))
        assertFalse(ambiguous.provenUnsent)
        val connected = HttpAttemptTrace { _, _ -> }
        connected.mark(RunDiagnosticKind.HTTP_CALL_START)
        connected.connectionOrRequestStarted()
        connected.failed(UnknownHostException("redirect.example"))
        assertFalse(connected.provenUnsent)
        val responded = HttpAttemptTrace { _, _ -> }
        responded.mark(RunDiagnosticKind.HTTP_CALL_START)
        responded.mark(RunDiagnosticKind.RESPONSE_HEADERS)
        responded.failed(UnknownHostException("redirect.example"))
        assertFalse(responded.provenUnsent)
    }
}
