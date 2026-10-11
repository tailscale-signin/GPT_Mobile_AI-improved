package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogRedactionTest {
    @org.junit.Test fun defaultDiagnosticsRemovePrivatePathsAndTailnetAddresses() {
        val safe = redactLogMessage("model=/data/user/0/app/private.litertlm host=100.64.8.2 url=http://192.168.1.2:8101/mcp?key=secret")
        org.junit.Assert.assertFalse(safe.contains("/data/user"))
        org.junit.Assert.assertFalse(safe.contains("100.64.8.2"))
        org.junit.Assert.assertFalse(safe.contains("192.168.1.2"))
        org.junit.Assert.assertFalse(safe.contains("secret"))
    }

    @Test fun `serialized chat profile and message objects are removed from diagnostics`() {
        val raw = "D/chats: [ChatRoomV2(id=14, title=Private Project, enabledPlatform=[uuid-secret], updatedAt=123), MessageV2(content=private text, id=88), PlatformV2(uid=profile-secret, model=test)]"
        val safe = redactLogMessage(raw)
        listOf("Private Project", "uuid-secret", "private text", "profile-secret").forEach {
            assertFalse(it, safe.contains(it))
        }
        assertTrue(safe.contains("D/chats: [redacted chat collection]"))
    }

    @Test fun `headers json credentials key prefixes and URL secrets are redacted`() {
        val raw = "Authorization: Bearer dangerous-token\nCookie: session=private\nX-Subscription-Token: brave-secret\n{\"api_key\":\"secret-value\"}\nhttps://example.com/search?token=url-secret\nhf_abcdefghijklmnop\nHTTP 500 request failed"
        val safe = redactLogMessage(raw)
        listOf("dangerous-token", "session=private", "brave-secret", "secret-value", "url-secret", "hf_abcdefghijklmnop").forEach { assertFalse(it, safe.contains(it)) }
        assertTrue(safe.contains("HTTP 500 request failed"))
    }
}
