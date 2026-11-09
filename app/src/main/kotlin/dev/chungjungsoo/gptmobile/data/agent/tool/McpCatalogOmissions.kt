package dev.chungjungsoo.gptmobile.data.agent.tool

/** Preserve catalog transitions without filling the retained log with the same missing names. */
internal class McpCatalogOmissions(private val capacity: Int = 128) {
    private val states = linkedMapOf<String, String>()

    @Synchronized fun changed(connection: String, names: Set<String>, reason: String): Boolean {
        require(capacity > 0)
        if (names.isEmpty()) {
            return states.remove(connection) != null
        }
        val signature = dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(reason + "\n" + names.sorted().joinToString("\n"))
        if (states[connection] == signature) return false
        states.remove(connection)
        states[connection] = signature
        if (states.size > capacity) states.remove(states.keys.first())
        return true
    }
}
