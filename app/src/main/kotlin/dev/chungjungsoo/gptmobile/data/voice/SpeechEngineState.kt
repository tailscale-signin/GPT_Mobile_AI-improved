package dev.chungjungsoo.gptmobile.data.voice

/** Binding and playback are independent from whether a voice screen is visible. */
internal enum class SpeechEngineState { UNINITIALIZED, INITIALIZING, READY, SPEAKING, CLOSED }
internal enum class SpeechVoiceCatalogState { UNINITIALIZED, CHECKING, AVAILABLE, EMPTY, UNAVAILABLE }
