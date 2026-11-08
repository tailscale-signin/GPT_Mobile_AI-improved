package dev.chungjungsoo.gptmobile.data.database

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationSnapshot
import dev.chungjungsoo.gptmobile.data.github.gatewayIdentityKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GatewayJobPersistenceTest {
    @Test fun cachedConversationsFilterTheExactProfileEndpointGatewayAndPrincipal() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        try {
            val dao = database.gatewayJobDao()
            val endpoint = "http://192.168.1.9:8090"
            fun record(id: String, profile: String = "profile", address: String = endpoint, gateway: String = "installation", principal: String = "account", syncedAt: Long = 1000) = GatewayConversationRecord(
                gatewayIdentityKey(gateway, principal, id), gateway, principal, id, profile, address, profile, null,
                Json.encodeToString(GatewayConversationSnapshot(gatewayId = gateway, principalId = principal, conversationId = id, profileId = profile, model = "original-model")),
                syncedAtMillis = syncedAt
            )
            val older = record("original-older")
            val newer = record("original-newer", syncedAt = 2000)
            for (row in listOf(
                older,
                newer,
                record("another-gateway", gateway = "other-installation"),
                record("another-principal", principal = "other-account"),
                record("another-profile", profile = "other-profile"),
                record("another-endpoint", address = "http://192.168.1.10:8090")
            )) {
                dao.saveConversation(row)
            }

            assertEquals(listOf(newer, older), dao.cachedConversations("profile", endpoint, "installation", "account"))
            assertTrue(dao.cachedConversations("missing-profile", endpoint, "installation", "account").isEmpty())
        } finally {
            database.close()
        }
    }
}
