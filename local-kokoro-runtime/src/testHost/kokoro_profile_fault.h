#ifndef KOKORO_HOST_PROFILE_FAULT_H_
#define KOKORO_HOST_PROFILE_FAULT_H_

// Test-only barrier injected after real ORT execution and before Sherpa ends
// profiling. Production sources and Android builds never include this file.
void KokoroHostProfileFaultBoundary();

#endif
