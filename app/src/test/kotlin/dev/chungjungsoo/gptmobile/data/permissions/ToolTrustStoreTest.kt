package dev.chungjungsoo.gptmobile.data.permissions

import android.app.Application
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ToolTrustStoreTest {
    @Test fun `grant persists and is scoped to tool endpoint and account then revokes`() {
        val context = RuntimeEnvironment.getApplication()
        val store = ToolTrustStore(context)
        val connection = ToolConnection("test", "Name", "test", "MCP", "https://example.com/mcp", "OAUTH", null, "client-one")
        store.allow(connection, "write_file")
        val restored = ToolTrustStore(context)
        assertTrue(restored.allows(connection.copy(name = "Renamed"), "write_file"))
        assertFalse(restored.allows(connection, "delete_file"))
        assertFalse(restored.allows(connection.copy(endpointUrl = "https://other.example/mcp"), "write_file"))
        assertFalse(restored.allows(connection.copy(oauthClientId = "client-two"), "write_file"))
        restored.revoke("test")
        assertFalse(restored.allows(connection, "write_file"))
    }
}
