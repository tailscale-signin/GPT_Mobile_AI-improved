package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.chungjungsoo.gptmobile.data.audit.AppAuditEvidenceKind
import dev.chungjungsoo.gptmobile.data.audit.AppAuditGitHubCatalog
import dev.chungjungsoo.gptmobile.data.audit.AppAuditGitHubCategory
import dev.chungjungsoo.gptmobile.data.audit.AppAuditObservation
import dev.chungjungsoo.gptmobile.data.audit.AppAuditObservationSource
import dev.chungjungsoo.gptmobile.data.audit.AppAuditOutcome
import dev.chungjungsoo.gptmobile.data.audit.AppAuditPlanEntry
import dev.chungjungsoo.gptmobile.data.audit.AppAuditResult
import dev.chungjungsoo.gptmobile.data.audit.AppAuditRun
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppAuditGitHubContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localSuccessKeepsTheLiveScoreUnmeasuredAndShowsEveryGitHubCategory() {
        val plan = AppAuditGitHubCatalog.contracts.map { check ->
            AppAuditPlanEntry(
                check.id,
                AppAuditGitHubCatalog.CONTRACT_SOURCE,
                check.id,
                "github",
                check.label,
                evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT
            )
        }
        val run = AppAuditRun(
            "fixture", 1, "c".repeat(64), "a".repeat(40), "0.9.66.7", "debug", "d".repeat(64), 1, 1, plan,
            results = plan.map { entry ->
                AppAuditResult(
                    entry.id,
                    AppAuditObservation(AppAuditOutcome.PASS, "LOCAL_CONTRACT_PASSED", evidenceKind = AppAuditEvidenceKind.LOCAL_CONTRACT, observationSource = AppAuditObservationSource.FIXTURE),
                    1,
                    2,
                    1,
                    attempted = true
                )
            }
        )
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            compose.setContent { MaterialTheme { Column { AppAuditGitHubContent(run, showCategories = true) } } }
            compose.onNodeWithText("Live GitHub read score: Not measured").assertExists()
            compose.onNodeWithText("Local GitHub contract score: 100.0 / 100").assertExists()
            AppAuditGitHubCategory.entries.forEach { compose.onNodeWithText(it.title).assertExists() }
            compose.onNodeWithText("at most 36 GitHub requests", substring = true).assertExists()
        } finally {
            Locale.setDefault(locale)
        }
    }
}
