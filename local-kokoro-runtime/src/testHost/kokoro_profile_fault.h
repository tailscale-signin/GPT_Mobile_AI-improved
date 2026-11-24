#ifndef KOKORO_HOST_PROFILE_FAULT_H_
#define KOKORO_HOST_PROFILE_FAULT_H_

// Test-only barrier injected after real ORT execution and before Sherpa ends
// profiling. Production sources and Android builds never include this file.
void KokoroHostProfileFaultBoundary();

// Independent preparation barrier. It returns normally after release so the
// production cancellation check, rather than an injected exception, must stop.
void KokoroHostContextPreparedBoundary();
void KokoroHostContextCompileStarted();
void KokoroHostContextUncachedStarted();

#endif
