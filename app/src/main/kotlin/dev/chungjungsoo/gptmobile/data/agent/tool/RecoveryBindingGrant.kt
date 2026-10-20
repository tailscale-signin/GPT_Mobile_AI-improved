package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.database.dao.AgentToolBindingWithConnection

/** Labels and edit timestamps are not a change to the source account or tool grant. */
internal fun recoveryBindingGrants(bindings: List<AgentToolBindingWithConnection>): Set<AgentToolBindingWithConnection> = bindings.map { row ->
    row.copy(binding = row.binding.copy(createdAt = 0), connection = row.connection?.copy(name = "", alias = "", createdAt = 0, updatedAt = 0))
}.toSet()
