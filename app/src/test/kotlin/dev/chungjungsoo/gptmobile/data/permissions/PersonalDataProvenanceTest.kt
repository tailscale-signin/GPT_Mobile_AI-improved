package dev.chungjungsoo.gptmobile.data.permissions

import android.app.Application
import android.content.Context
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PersonalDataProvenanceTest {
    private lateinit var store: FreeModelToolConsentStore
    private fun profile(uid: String = "reader", provider: FreeAiProvider = FreeAiProvider.OVHCLOUD) = PlatformV2(uid = uid, name = uid, apiUrl = provider.apiUrl, model = provider.model, compatibleType = ClientType.FREE)
    private fun memory() = PersonalDataProvenance(sourceToolId = "memory", sourceProfileUid = "reader", sourceRef = "saved-source", categories = setOf(PersonalDataCategory.MEMORY), retentionChatId = 7)

    @Before fun reset() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("free_model_tool_consent_v2", Context.MODE_PRIVATE).edit().clear().commit()
        store = FreeModelToolConsentStore(context)
    }

    @Test fun savedContributionRequiresAnIndependentWriterGrantAndRevocationStopsTheNextDispatch() {
        val reader = profile()
        val writer = profile("writer")
        store.grant(reader, "memory", chatId = 7)
        val context = PersonalDataContext(7, store, listOf(memory()))
        context.validateModel(reader)
        assertThrows(IllegalStateException::class.java) { context.validateModel(writer) }
        store.grant(writer, "memory", chatId = 7)
        context.validateModel(writer)
        assertThrows(IllegalStateException::class.java) { PersonalDataContext(8, store, listOf(memory())).validateModel(writer) }
        store.revokeProfile(writer.uid)
        assertThrows(IllegalStateException::class.java) { context.validateModel(writer) }
        assertThrows(IllegalStateException::class.java) { context.validateModel(profile("kilo", FreeAiProvider.KILO)) }
    }

    @Test fun publicSearchWorksUntilPersonalEvidenceCouldBeCopiedIntoItsDerivedArguments() = runTest {
        val profile = profile()
        store.grant(profile, "memory", chatId = 7)
        var searches = 0
        val search = object : AgentTool {
            override val definition = AgentToolDefinition("web_search", "Public search", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                searches++
                return AgentToolResult(callId, ToolResultContent.Text("Public result"), false)
            }
        }
        val guarded = ProvenanceGuardedTool(search, profile, "web_search", personal = false, external = true) { null }
        val context = PersonalDataContext(7, store)
        withContext(context) {
            guarded.execute("public", JsonObject(emptyMap()))
            context.add(listOf(memory()))
            val blocked = runCatching { guarded.execute("derived", JsonObject(emptyMap())) }.exceptionOrNull()
            assertTrue(blocked?.message.orEmpty().startsWith("PERSONAL_DATA_SERVICE_PERMISSION_REQUIRED"))
        }
        assertEquals(1, searches)
        // The same evidence remains usable with its explicitly approved model recipient.
        context.validateModel(profile)
    }

    @Test fun resultAndDurableCheckpointKeepLabelsAfterSummaryOrCacheCopies() = runTest {
        val profile = profile()
        store.grant(profile, "memory", chatId = 7)
        val memoryTool = object : AgentTool {
            override val definition = AgentToolDefinition("memory", "Read memory", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("Selected personal fact"), false)
        }
        val context = PersonalDataContext(7, store)
        val result = withContext(context) {
            ProvenanceGuardedTool(memoryTool, profile, "memory", personal = true, external = false) { store.grantFor(profile, "memory", 7) }.execute("read", JsonObject(emptyMap()))
        }
        val summary = result.copy(content = ToolResultContent.Text("A shorter summary"), sharedResult = true)
        assertEquals(result.personalData, summary.personalData)
        assertEquals(setOf(PersonalDataCategory.MEMORY), context.snapshot().single().categories)
        val event = ToolEvent("e", "r", 1, "read", null, null, "memory", "memory", "{}", ToolResultCheckpoint.encode("A shorter summary", ToolEventResultType.TEXT, "A shorter summary", summary.personalData), ToolEventResultType.CHECKPOINT, "COMPLETED")
        assertEquals(summary.personalData, ToolResultCheckpoint.personalData(event))
        assertThrows(IllegalStateException::class.java) { PersonalDataContext(7, store, ToolResultCheckpoint.personalData(event)).validateModel(profile("new-writer")) }
        val unrelatedPaidWriter = PlatformV2(uid = "paid-writer", name = "Paid writer", apiUrl = "https://api.example.org/v1", model = "model", compatibleType = ClientType.CUSTOM)
        assertThrows(IllegalStateException::class.java) { PersonalDataContext(7, store, ToolResultCheckpoint.personalData(event)).validateModel(unrelatedPaidWriter) }
    }

    @Test fun healthReplayMustStillFallInsideTheCurrentGrantRange() {
        val now = System.currentTimeMillis()
        val profile = profile()
        store.grant(profile, "health_connect_sleep", chatId = 7, healthRangeDays = 7)
        val recent = PersonalDataProvenance(sourceToolId = "health_connect_sleep", sourceProfileUid = profile.uid, sourceRef = "sleep", categories = setOf(PersonalDataCategory.HEALTH), rangeStart = now - 60_000, rangeEnd = now)
        PersonalDataContext(7, store, listOf(recent)).validateModel(profile)
        assertThrows(IllegalStateException::class.java) { PersonalDataContext(7, store, listOf(recent.copy(rangeStart = now - 14 * 24 * 60 * 60_000L))).validateModel(profile) }
        assertThrows(IllegalStateException::class.java) { PersonalDataContext(7, store, listOf(recent.copy(rangeStart = null, rangeEnd = null))).validateModel(profile) }
    }

    @Test fun aLaterPublicRouteLabelCannotEraseTheOriginalRecipients() {
        val restricted = memory().copy(sourceRecipients = setOf("OVHcloud"))
        val context = PersonalDataContext(7, store, listOf(restricted))
        context.add(listOf(memory()))
        val unrelated = PlatformV2(uid = "new-writer", name = "New writer", apiUrl = "https://api.example.org/v1", model = "model", compatibleType = ClientType.CUSTOM)
        assertThrows(IllegalStateException::class.java) { context.validateModel(unrelated) }
        assertEquals(2, context.snapshot().size)
    }

    @Test fun personalToolExceptionsRemainSensitiveBeforeADerivedQueryCanLeave() = runTest {
        val profile = profile()
        store.grant(profile, "memory", chatId = 7)
        val source = object : dev.chungjungsoo.gptmobile.data.agent.PreparableAgentTool {
            override val definition = AgentToolDefinition("memory", "Read memory", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Private execution detail")
            override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
                if (callId == "prepare") error("Private preparation detail")
                return { execute(callId, arguments) }
            }
        }
        for (callId in listOf("prepare", "execute")) {
            val context = PersonalDataContext(7, store)
            withContext(context) {
                val guarded = ProvenanceGuardedTool(source, profile, "memory", personal = true, external = false) { store.grantFor(profile, "memory", 7) }
                assertTrue(runCatching { guarded.execute(callId, JsonObject(emptyMap())) }.isFailure)
            }
            assertEquals(setOf(PersonalDataCategory.MEMORY), context.snapshot().single().categories)
            assertThrows(IllegalStateException::class.java) { context.validateExternalTool(profile, "web_search") }
            context.validateModel(profile)
        }
    }
}
