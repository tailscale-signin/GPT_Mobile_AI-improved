package dev.chungjungsoo.gptmobile.data.health

/** Known external memory writes. This is not a claim to detect arbitrary tools' side effects. */
object HealthExternalStoragePolicy {
    private val writes = setOf("context_store", "create_entities", "add_observations", "create_relations", "store_memory", "save_memory", "add_memory", "update_memory", "upsert_memory", "remember", "memory_store", "memory_save", "memory_add", "memory_upsert")
    fun requiresConsent(toolName: String): Boolean = toolName.lowercase(java.util.Locale.ROOT).substringBefore("__") in writes
}
