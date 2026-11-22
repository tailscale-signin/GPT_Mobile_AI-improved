package dev.chungjungsoo.gptmobile.data.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import dev.chungjungsoo.gptmobile.data.privacy.UnverifiedServiceTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GatewayVoiceTransportTest {
    @Test fun `speech transport uses the originating conversation policy independently of availability and permits cancellation`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PrivacyModeStore.initialize(app)
        val preferences = app.getSharedPreferences("device_privacy", Context.MODE_PRIVATE)
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests++
                respond("{\"ok\":true}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val transport = GatewayVoiceTransport(client, "https://chosen-pc.example", "test-credential")
            preferences.edit().putBoolean("privacy_mode_enabled", false).commit()
            withContext(ConversationPrivacyContext(1, false)) { transport.exchange("POST", "/gateway/voice/synthesize", JSONObject(), 1024) }
            assertEquals(1, requests)
            preferences.edit().putBoolean("privacy_mode_enabled", true).commit()
            withContext(ConversationPrivacyContext(1, false)) { transport.exchange("POST", "/gateway/voice/synthesize", JSONObject(), 1024) }
            assertEquals(2, requests)
            preferences.edit().putBoolean("privacy_mode_enabled", false).commit()
            val blocked = withContext(ConversationPrivacyContext(2, true)) {
                runCatching { transport.exchange("POST", "/gateway/voice/synthesize", JSONObject(), 1024) }.exceptionOrNull()
            }
            assertTrue(blocked is UnverifiedServiceTransport)
            assertEquals(2, requests)
            withContext(ConversationPrivacyContext(2, true)) {
                transport.exchange("POST", "/gateway/voice/cancel", JSONObject().put("request_id", "existing"), 1024)
            }
            assertEquals(3, requests)
        } finally {
            preferences.edit().putBoolean("privacy_mode_enabled", false).commit()
            client.close()
        }
    }
}
