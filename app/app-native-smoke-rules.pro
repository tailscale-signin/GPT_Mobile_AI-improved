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
# The separate test APK reflects and calls these caption-verification types.
-keep class dev.chungjungsoo.gptmobile.data.voice.VoiceCaptionWordCoverage { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.VoiceCaptionWordCoverageResult { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBackend { *; }
# The minified test APK opens the real streaming output and observes its Android playback clock.
-keep class dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechModelStore { *; }
-keepclassmembers class dev.chungjungsoo.gptmobile.data.voice.LocalVoiceModelStore {
    java.lang.Object install(kotlin.coroutines.Continuation);
}
-keep class dev.chungjungsoo.gptmobile.data.voice.SpeechVoiceEvidenceKt {
    public static java.lang.String speechAcceleratorEnvironment(android.content.Context);
}
-keep class dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechNpuKt {
    public static void prepareMediaPipeSpeechNpu(android.content.Context);
    public static boolean isSm8750SpeechDevice();
}
-keep class dev.chungjungsoo.gptmobile.data.voice.VoicePcmSignalMeasurement { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.VoicePcmSignal { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.MediaPipeVoicePhraseEngineFactory { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.KokoroVoiceOutput { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.KokoroModelPack { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.KokoroVoice { *; }
-keep interface dev.chungjungsoo.gptmobile.data.voice.VoiceReplyPlayback { *; }
-keep interface dev.chungjungsoo.gptmobile.data.voice.VoicePlaybackMeasurementObserver { *; }
-keep interface dev.chungjungsoo.gptmobile.data.voice.LocalVoiceAssetSource { *; }
-keep class dev.chungjungsoo.gptmobile.data.voice.LocalVoiceAssetStream { *; }
