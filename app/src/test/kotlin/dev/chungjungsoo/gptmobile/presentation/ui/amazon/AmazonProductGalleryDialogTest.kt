package dev.chungjungsoo.gptmobile.presentation.ui.amazon

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w400dp-h800dp")
class AmazonProductGalleryDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun swipingAndArrowNavigationKeepTheProductDialogOpen() {
        var dismissals = 0
        val products = (1..3).map { index ->
            buildJsonObject {
                put("asin", "B00000000$index")
                put("marketplace", "amazon.ca")
                put("title", "Headphones $index")
                put("price", "CAD 50")
            }
        }
        compose.setContent {
            MaterialTheme {
                var index by remember { mutableIntStateOf(0) }
                AmazonProductDetailContent(products[index], AmazonProductDetailState(product = products[index]), index, products.size, { index = it }) { dismissals++ }
            }
        }
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        compose.onNodeWithTag("amazon-product-gallery").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.onNodeWithText("Headphones 2").assertIsDisplayed()
        compose.onNodeWithTag("amazon-product-gallery").performTouchInput { swipeRight() }
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next Amazon product").performClick()
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        assertEquals(0, dismissals)
    }
}
