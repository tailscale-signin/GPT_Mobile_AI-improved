package dev.chungjungsoo.gptmobile.data.knowledge

import dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot
import dev.chungjungsoo.gptmobile.data.rag.VaultFact

/** Privacy provenance survives memory edits, consolidation, and retained copies. */
internal object MemoryPrivacyPolicy {
    fun scope(chatId: Int): String {
        require(chatId > 0)
        return "private:chat:$chatId"
    }

    fun owner(scope: String): Int? = scope.takeIf { it.startsWith("private:chat:") }
        ?.removePrefix("private:chat:")?.toIntOrNull()?.takeIf { it > 0 }

    fun isPrivate(scope: String): Boolean = scope.startsWith("private:")

    fun validScope(scope: String): Boolean = scope == "personal" ||
        scope.startsWith("project:") ||
        scope.startsWith("personal:branch:") ||
        owner(scope) != null ||
        scope == "private:unshared"

    fun sourceChats(fact: VaultFact): Set<Int> = sourceChats(fact.evidenceSources + fact.lastSourceKey) + listOfNotNull(fact.sourceChatId.takeIf { it > 0 })

    fun sourceChats(messageKeys: Set<String>): Set<Int> = messageKeys.mapNotNull { it.substringBefore(':').toIntOrNull()?.takeIf { id -> id > 0 } }.toSet()

    fun protectedScope(scope: String, sources: Set<Int>, privateChats: Set<Int>): String {
        val owners = sources.intersect(privateChats) + listOfNotNull(owner(scope))
        if (owners.isEmpty() && !isPrivate(scope)) return scope
        // Mixed private provenance cannot become visible in either conversation.
        return owners.singleOrNull()?.let { MemoryPrivacyPolicy.scope(it) } ?: "private:unshared"
    }

    fun protect(fact: VaultFact, privateChats: Set<Int>): VaultFact {
        val protectedScope = protectedScope(fact.scope, sourceChats(fact), privateChats)
        if (!isPrivate(protectedScope) || fact.scope == protectedScope && fact.localOnly) return fact
        return fact.copy(scope = protectedScope, localOnly = true, revision = fact.revision + 1)
    }

    fun protect(snapshot: FactVaultSnapshot, privateChats: Set<Int>): FactVaultSnapshot = snapshot.copy(
        facts = snapshot.facts.map { protect(it, privateChats) },
        topics = snapshot.topics.map { topic -> topic.copy(scope = protectedScope(topic.scope, sourceChats(topic.messageKeys), privateChats)) }
    )
}
