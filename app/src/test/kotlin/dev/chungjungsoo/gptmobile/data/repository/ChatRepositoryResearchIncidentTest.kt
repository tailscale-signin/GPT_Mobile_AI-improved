package dev.chungjungsoo.gptmobile.data.repository

import android.app.Application
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keep the long evidence-recovery fixture on the actual Android AtomicFile implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChatRepositoryResearchIncidentTest {
    private val fixture = ChatRepositoryImplTest()

    @get:Rule val testFiles = fixture.testFiles

    @Test fun incidentReplayRetainsEvidenceAcrossTwentyFourHelperRequestsWriter403AndContinue() {
        fixture.incidentReplayRetainsEvidenceAcrossTwentyFourHelperRequestsWriter403AndContinue()
    }
}
