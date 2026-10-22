package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.free.FreeModelCatalog
import dev.chungjungsoo.gptmobile.data.free.FreeServiceStatus
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w400dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FreeModelsRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerDetailsRemainReadableAndStatusDoesNotClaimInference() {
        val provider = FreeAiProvider.VIREONIX
        val profile = PlatformV2(uid = "test-vireonix", name = "Vireonix", compatibleType = ClientType.FREE, apiUrl = provider.apiUrl, model = provider.model)
        var opened = ""
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                GPTMobileTheme(themeMode = ThemeMode.DARK) {
                    Surface {
                        Column(Modifier.width(360.dp).testTag("free-card")) {
                            FreeServiceCard(provider, FreeServiceStatus(reachable = true, checkedAt = System.currentTimeMillis(), message = "Catalog reachable · generation not tested"), listOf(profile), FreeModelCatalog.defaults(provider), { opened = it }, {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Online: Vireonix").assertIsDisplayed()
        compose.onNodeWithText("Online · Catalog reachable · generation not tested").assertIsDisplayed()
        compose.onNodeWithText("Profile options").performClick()
        compose.runOnIdle { assertEquals(profile.uid, opened) }
        compose.onNodeWithText("Models & details").performClick()
        compose.onNodeWithText(FreeModelCatalog.details(provider).privacy).assertIsDisplayed()
        val bitmap = compose.onNodeWithTag("free-card", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val file = File("build/reports/reliability-ui/free-models-dark-large-text.png").apply { parentFile.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun neverCheckedServicesUseUnknownStatus() {
        val provider = FreeAiProvider.AI_HORDE
        val profile = PlatformV2(name = "AI Horde", compatibleType = ClientType.FREE, apiUrl = provider.apiUrl, model = provider.model)
        compose.setContent { GPTMobileTheme(themeMode = ThemeMode.LIGHT) { Surface { Column(Modifier.width(360.dp).testTag("free-card")) { FreeServiceCard(provider, FreeServiceStatus(), listOf(profile), FreeModelCatalog.defaults(provider), {}, {}) } } } }
        compose.onNodeWithContentDescription("Not checked recently: AI Horde").assertIsDisplayed()
        compose.onNodeWithText("Online", substring = true).assertDoesNotExist()
        val bitmap = compose.onNodeWithTag("free-card", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val file = File("build/reports/reliability-ui/free-models-light-unknown.png").apply { parentFile.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
