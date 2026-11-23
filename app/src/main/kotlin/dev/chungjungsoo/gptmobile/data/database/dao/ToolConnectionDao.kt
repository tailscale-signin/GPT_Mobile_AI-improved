package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.database.entity.AgentToolBinding
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType

data class AgentToolBindingWithConnection(
    val binding: AgentToolBinding,
    val connection: ToolConnection?
)

@Dao
interface ToolConnectionDao {
    @Query("SELECT * FROM tool_connections ORDER BY name, alias, connection_uid")
    fun observeConnections(): kotlinx.coroutines.flow.Flow<List<ToolConnection>>

    @Query("SELECT * FROM tool_connections ORDER BY name, alias, connection_uid")
    suspend fun listConnections(): List<ToolConnection>

    @Query("SELECT * FROM tool_connections ORDER BY name, alias, connection_uid")
    suspend fun getAllConnections(): List<ToolConnection>

    @Query("SELECT * FROM tool_connections WHERE connection_uid = :connectionUid")
    suspend fun getConnection(connectionUid: String): ToolConnection?

    @Query("SELECT * FROM tool_connections WHERE connection_uid IN (:connectionUids)")
    suspend fun getConnectionsByUids(connectionUids: List<String>): List<ToolConnection>

    @Upsert
    suspend fun upsertConnection(connection: ToolConnection)

    @Query("DELETE FROM tool_connections WHERE connection_uid = :connectionUid")
    suspend fun deleteConnectionByUid(connectionUid: String)

    @Query("SELECT * FROM agent_tool_bindings WHERE profile_uid = :profileUid ORDER BY tool_name, connection_uid, binding_uid")
    suspend fun listBindingsByProfile(profileUid: String): List<AgentToolBinding>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBindingRow(binding: AgentToolBinding)

    @Query("SELECT * FROM agent_tool_bindings WHERE binding_uid = :bindingUid")
    suspend fun rawBindingByUid(bindingUid: String): AgentToolBinding?

    /** Imports may retain legacy names, but cannot replace a different binding owner. */
    @Transaction
    suspend fun insertBinding(binding: AgentToolBinding) {
        val existing = rawBindingByUid(binding.bindingUid)
        require(existing == null || existing.profileUid == binding.profileUid && existing.connectionUid == binding.connectionUid && existing.toolName == binding.toolName) {
            "The binding ID belongs to another profile, connection or tool."
        }
        // SQLite's composite unique index does not collide on a NULL connection.
        // Keep logical replacement and the insert in one transaction for UI and imports.
        if (binding.connectionUid == null) deleteBuiltInToolBinding(binding.profileUid, binding.toolName)
        insertBindingRow(binding)
    }

    @Transaction
    suspend fun setBuiltInBinding(profileUid: String, toolName: String, binding: AgentToolBinding?) {
        require(profileUid.isNotBlank()) { "A built-in tool requires a profile." }
        require(toolName in BUILT_IN_TOOLS) { "Unknown built-in tool: $toolName" }
        require(binding == null || binding.profileUid == profileUid && binding.toolName == toolName && binding.connectionUid == null && binding.bindingUid.isNotBlank()) {
            "The built-in binding does not match its profile and tool."
        }
        if (binding == null) deleteBuiltInToolBinding(profileUid, toolName) else insertBinding(binding)
    }

    @Query(
        """
        DELETE FROM agent_tool_bindings
        WHERE profile_uid = :profileUid
            AND tool_name = :toolName
            AND connection_uid IN (
                SELECT connection_uid FROM tool_connections
                WHERE type IN (:connectionTypes)
            )
        """
    )
    suspend fun deleteConnectionToolBindingsForTypes(
        profileUid: String,
        toolName: String,
        connectionTypes: List<String>
    )

    @Query(
        """
        DELETE FROM agent_tool_bindings
        WHERE profile_uid = :profileUid
            AND tool_name = :toolName
            AND connection_uid IS NULL
        """
    )
    suspend fun deleteBuiltInToolBinding(profileUid: String, toolName: String)

    @Query(
        """
        DELETE FROM agent_tool_bindings
        WHERE profile_uid = :profileUid
            AND connection_uid IN (
                SELECT connection_uid FROM tool_connections
                WHERE type = :connectionType
            )
        """
    )
    suspend fun deleteConnectionBindingsForType(profileUid: String, connectionType: String)

    @Transaction
    suspend fun replaceWebSearchBinding(binding: AgentToolBinding) {
        require(binding.toolName == WEB_SEARCH_TOOL)
        deleteConnectionToolBindingsForTypes(binding.profileUid, WEB_SEARCH_TOOL, WEB_SEARCH_TYPES)
        insertBinding(binding)
    }

    @Transaction
    suspend fun replaceWebSearchBindings(profileUid: String, bindings: List<AgentToolBinding>) {
        require(bindings.all { it.profileUid == profileUid && it.toolName == WEB_SEARCH_TOOL })
        deleteConnectionToolBindingsForTypes(profileUid, WEB_SEARCH_TOOL, WEB_SEARCH_TYPES)
        bindings.forEach { insertBinding(it) }
    }

    @Transaction
    suspend fun removeWebSearchBinding(profileUid: String) {
        deleteConnectionToolBindingsForTypes(profileUid, WEB_SEARCH_TOOL, WEB_SEARCH_TYPES)
    }

    @Transaction
    suspend fun replaceReadUrlBinding(binding: AgentToolBinding) {
        require(binding.toolName == BuiltInAgentTool.READ_URL)
        setBuiltInBinding(binding.profileUid, BuiltInAgentTool.READ_URL, binding)
    }

    @Transaction
    suspend fun removeReadUrlBinding(profileUid: String) {
        setBuiltInBinding(profileUid, BuiltInAgentTool.READ_URL, null)
    }

    @Transaction
    suspend fun replaceMcpBindings(
        profileUid: String,
        bindings: List<AgentToolBinding>
    ) {
        deleteConnectionBindingsForType(profileUid, ToolConnectionType.MCP)
        bindings.forEach { insertBinding(it) }
    }

    @Transaction
    suspend fun listBindingsWithConnections(profileUid: String): List<AgentToolBindingWithConnection> {
        val bindings = listBindingsByProfile(profileUid)
        val connections = bindings
            .mapNotNull { it.connectionUid }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { getConnectionsByUids(it) }
            ?.associateBy { it.connectionUid }
            .orEmpty()
        return bindings.map { binding ->
            AgentToolBindingWithConnection(binding, binding.connectionUid?.let(connections::get))
        }
    }

    companion object {
        private val BUILT_IN_TOOLS = setOf(
            BuiltInAgentTool.READ_URL,
            BuiltInAgentTool.READ_FILE_SLICE,
            BuiltInAgentTool.DEVICE_LOCATION,
            BuiltInAgentTool.CALCULATE_EXPRESSION,
            BuiltInAgentTool.GITHUB,
            BuiltInAgentTool.CURRENT_DATE
        )
        private const val WEB_SEARCH_TOOL = "web_search"
        private val WEB_SEARCH_TYPES = ToolConnectionType.WEB_SEARCH_TYPES.toList()
    }
}
