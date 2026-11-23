package dev.chungjungsoo.gptmobile.data.database

import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the production default-method ownership rules; actual Room atomicity is instrumented separately. */
class ToolConnectionBindingContractTest {
    @Test
    fun replacementAndDisableRetainOtherProfilesToolsAndRemoteNames() = runBlocking {
        val original = binding("old")
        val otherProfile = original.copy(bindingUid = "profile", profileUid = "other")
        val otherTool = original.copy(bindingUid = "tool", toolName = BuiltInAgentTool.CURRENT_DATE)
        val remote = original.copy(bindingUid = "remote", connectionUid = "mcp")
        val dao = Bindings(original, original.copy(bindingUid = "duplicate"), otherProfile, otherTool, remote)
        val replacement = original.copy(bindingUid = "replacement")

        dao.setBuiltInBinding(original.profileUid, original.toolName, replacement)

        assertEquals(setOf(replacement, otherProfile, otherTool, remote), dao.rows.values.toSet())
        dao.setBuiltInBinding(original.profileUid, original.toolName, null)
        assertEquals(setOf(otherProfile, otherTool, remote), dao.rows.values.toSet())
    }

    @Test
    fun malformedExplicitRequestsCannotRemoveAnExistingGrant() = runBlocking {
        val original = binding("retained")
        val dao = Bindings(original)
        val invalid = listOf(
            original.copy(profileUid = "other"),
            original.copy(toolName = BuiltInAgentTool.CURRENT_DATE),
            original.copy(connectionUid = "mcp"),
            original.copy(bindingUid = "")
        )
        invalid.forEach { incoming ->
            assertTrue(runCatching { dao.setBuiltInBinding(original.profileUid, original.toolName, incoming) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(listOf(original), dao.rows.values.toList())
        }
        assertTrue(runCatching { dao.setBuiltInBinding("", original.toolName, null) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { dao.setBuiltInBinding(original.profileUid, "legacy_name", null) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(original), dao.rows.values.toList())
    }

    @Test
    fun anImportedBindingIdCannotMoveBetweenProfilesConnectionsOrTools() = runBlocking {
        val owned = binding("owned")
        val target = binding("target").copy(profileUid = "target-profile")
        for (incoming in listOf(owned.copy(profileUid = target.profileUid), owned.copy(connectionUid = "mcp"), owned.copy(toolName = BuiltInAgentTool.CURRENT_DATE))) {
            val dao = Bindings(owned, target)
            assertTrue(runCatching { dao.insertBinding(incoming) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(setOf(owned, target), dao.rows.values.toSet())
        }
    }

    @Test
    fun genericImportsCollapseLegacyNamesAndPermitOwnedMetadataUpdates() = runBlocking {
        val legacy = binding("legacy").copy(toolName = "retired_builtin")
        val remote = legacy.copy(bindingUid = "remote", connectionUid = "mcp")
        val dao = Bindings(legacy, remote)
        val imported = legacy.copy(bindingUid = "imported", createdAt = 10)
        dao.insertBinding(imported)
        dao.insertBinding(imported.copy(createdAt = 11))
        assertEquals(setOf(imported.copy(createdAt = 11), remote), dao.rows.values.toSet())
    }

    @Test
    fun legacyReadUrlHelpersRequireBuiltInOwnershipAndShareReplacementRules() = runBlocking {
        val original = binding("old")
        val dao = Bindings(original, original.copy(bindingUid = "duplicate"))
        assertTrue(runCatching { dao.replaceReadUrlBinding(original.copy(connectionUid = "mcp")) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(2, dao.rows.size)
        val replacement = original.copy(bindingUid = "new")
        dao.replaceReadUrlBinding(replacement)
        assertEquals(listOf(replacement), dao.rows.values.toList())
        dao.removeReadUrlBinding(original.profileUid)
        assertTrue(dao.rows.isEmpty())
    }

    private fun binding(uid: String) = AgentToolBinding(uid, "profile", null, BuiltInAgentTool.READ_URL, 1)

    private class Bindings(vararg initial: AgentToolBinding) : ToolConnectionDao {
        val rows = initial.associateBy { it.bindingUid }.toMutableMap()

        override suspend fun rawBindingByUid(bindingUid: String): AgentToolBinding? = rows[bindingUid]
        override suspend fun insertBindingRow(binding: AgentToolBinding) {
            rows[binding.bindingUid] = binding
        }
        override suspend fun deleteBuiltInToolBinding(profileUid: String, toolName: String) {
            rows.values.removeAll { it.profileUid == profileUid && it.toolName == toolName && it.connectionUid == null }
        }
        override suspend fun listBindingsByProfile(profileUid: String): List<AgentToolBinding> = rows.values.filter { it.profileUid == profileUid }
        override fun observeConnections(): Flow<List<ToolConnection>> = error("Not used")
        override suspend fun listConnections(): List<ToolConnection> = error("Not used")
        override suspend fun getAllConnections(): List<ToolConnection> = error("Not used")
        override suspend fun getConnection(connectionUid: String): ToolConnection? = error("Not used")
        override suspend fun getConnectionsByUids(connectionUids: List<String>): List<ToolConnection> = error("Not used")
        override suspend fun upsertConnection(connection: ToolConnection): Unit = error("Not used")
        override suspend fun deleteConnectionByUid(connectionUid: String): Unit = error("Not used")
        override suspend fun deleteConnectionToolBindingsForTypes(profileUid: String, toolName: String, connectionTypes: List<String>): Unit = error("Not used")
        override suspend fun deleteConnectionBindingsForType(profileUid: String, connectionType: String): Unit = error("Not used")
    }
}
