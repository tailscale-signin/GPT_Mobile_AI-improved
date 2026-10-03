package dev.chungjungsoo.gptmobile.data.permissions

import android.app.Application
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ScopedToolGrantTest {
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun scopedGrantsExpireAndDoNotCrossConversationsRepositoriesActionsOrSchemas() {
        val store = ToolTrustStore(RuntimeEnvironment.getApplication())
        val connection = ToolConnection("scope-test", "GitHub", "git", "MCP", "https://example.com/mcp", "OAUTH", null, "client")
        val scope = ScopedToolGrant.from(1, obj("""{"type":"object"}"""), obj("""{"owner":"Test","repo":"App","action":"commit"}"""))
        store.allowScoped(connection, "github", scope, now = 100)
        assertTrue(store.allowsScoped(connection, "github", scope, now = 101))
        assertFalse(store.allowsScoped(connection, "github", scope, now = 3_600_100))
        for (other in listOf(scope.copy(chatId = 2), scope.copy(resource = "test/other"), scope.copy(action = "delete"), scope.copy(schemaHash = "changed"))) assertFalse(store.allowsScoped(connection, "github", other, now = 101))
        assertFalse(store.allowsScoped(connection.copy(updatedAt = connection.updatedAt + 1), "github", scope, now = 101))
        val a = ScopedToolGrant.from(1, obj("{}"), obj("""{"owner":"test","repo":"app","branch":"work","path":"a.txt"}"""))
        val b = ScopedToolGrant.from(1, obj("{}"), obj("""{"owner":"test","repo":"app","branch":"main","path":"a.txt"}"""))
        assertNotEquals(a.key(), b.key())
        store.revoke(connection.connectionUid)
        assertFalse(store.allowsScoped(connection, "github", scope, now = 101))
    }

    @Test fun canonicalActionHashesIgnoreObjectOrderingButPreserveArrayOrder() {
        assertEquals(ScopedToolGrant.canonicalHash(obj("""{"a":1,"b":2}""")), ScopedToolGrant.canonicalHash(obj("""{"b":2,"a":1}""")))
        assertNotEquals(ScopedToolGrant.canonicalHash(obj("""{"files":[1,2]}""")), ScopedToolGrant.canonicalHash(obj("""{"files":[2,1]}""")))
    }

    @Test fun rememberedScopeDoesNotPersistSignedUrlArguments() {
        val context = RuntimeEnvironment.getApplication()
        val store = ToolTrustStore(context)
        val connection = ToolConnection("signed-url-test", "Reader", "web", "MCP", "https://example.com/mcp", "NONE", null, "")
        val scope = ScopedToolGrant.from(1, obj("{}"), obj("""{"url":"https://example.com/private?token=secret-value"}"""))
        store.allowScoped(connection, "read", scope)
        assertTrue(store.allowsScoped(connection, "read", scope))
        val saved = context.getSharedPreferences("tool_always_allow", android.content.Context.MODE_PRIVATE).all.toString()
        assertFalse(saved.contains("secret-value"))
        assertFalse(saved.contains("/private"))
        val other = ScopedToolGrant.from(1, obj("{}"), obj("""{"url":"https://example.com/private?token=other-value"}"""))
        assertFalse(store.allowsScoped(connection, "read", other))
    }
}
