# The test APK is built after app shrinking. Its runner/tests can call shared
# Kotlin facades and app APIs that production code does not reference directly.
# Keep these extra roots only in nativeSmoke; release uses its original rules.
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-keep class dev.chungjungsoo.gptmobile.data.memory.LocalSemanticMemory { *; }
-keep class dev.chungjungsoo.gptmobile.data.memory.SemanticMemoryStatus { *; }
-keep class dev.chungjungsoo.gptmobile.data.rag.VaultFact { *; }
-keep class dev.chungjungsoo.gptmobile.data.rag.KnowledgeFact { *; }
-keep class dev.chungjungsoo.gptmobile.data.rag.KnowledgeEntity { *; }
-keep class dev.chungjungsoo.gptmobile.data.rag.KnowledgeRelation { *; }
