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
# The separate instrumentation APK exercises these actual app adapters and their
# state/cue contracts. These roots apply only to nativeSmoke, never to release.
-keep class dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechCatalog { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechEngine { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechModel { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechTaskConfiguration { *; }
-keep interface dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechTask { *; }
-keep interface dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechStream { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.AndroidMediaPipeSpeechTask { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.MediaPipeVoicePhraseEngine { *; }
-keep interface dev.chungjungsoo.gptmobile.data.voice.VoicePhraseEngine { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.VoicePcmChunk { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.VoiceStreamFrames { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.VoiceStreamCue { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.VoiceWordCueProvenance { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend { *; }
