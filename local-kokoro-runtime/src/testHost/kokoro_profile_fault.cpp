#include "kokoro_profile_fault.h"

#include <jni.h>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <stdexcept>

namespace {
std::mutex lock;
std::condition_variable changed;
bool armed = false, entered = false, released = false;
std::mutex context_lock;
std::condition_variable context_changed;
bool context_armed = false, context_entered = false, context_released = false;
int context_compilations = 0, context_fallbacks = 0;
}

void KokoroHostProfileFaultBoundary() {
  std::unique_lock<std::mutex> guard(lock);
  if (!armed) return;
  armed = false;
  entered = true;
  changed.notify_all();
  if (!changed.wait_for(guard, std::chrono::seconds(120), [] { return released; }))
    throw std::runtime_error("Host profile barrier timed out");
  throw std::runtime_error("Host interrupted inference after real kernel execution");
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_kokoro_KokoroNativeProfileLifecycleIntegration_armFault(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> guard(lock);
  armed = true;
  entered = released = false;
}

extern "C" JNIEXPORT jboolean JNICALL Java_dev_melo_kokoro_KokoroNativeProfileLifecycleIntegration_awaitFault(
    JNIEnv *, jclass) {
  std::unique_lock<std::mutex> guard(lock);
  return changed.wait_for(guard, std::chrono::seconds(120), [] { return entered; });
}

extern "C" JNIEXPORT void JNICALL Java_dev_melo_kokoro_KokoroNativeProfileLifecycleIntegration_releaseFault(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> guard(lock);
  released = true;
  changed.notify_all();
}

void KokoroHostContextPreparedBoundary() {
  std::unique_lock<std::mutex> guard(context_lock);
  if (!context_armed) return;
  context_armed = false;
  context_entered = true;
  context_changed.notify_all();
  if (!context_changed.wait_for(guard, std::chrono::seconds(120), [] { return context_released; }))
    throw std::runtime_error("Host context preparation barrier timed out");
}
void KokoroHostContextCompileStarted() {
  std::lock_guard<std::mutex> guard(context_lock);
  ++context_compilations;
}
void KokoroHostContextUncachedStarted() {
  std::lock_guard<std::mutex> guard(context_lock);
  ++context_fallbacks;
}
extern "C" JNIEXPORT void JNICALL Java_dev_melo_kokoro_KokoroNativeContextIntegration_armPreparation(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> guard(context_lock);
  context_armed = true;
  context_entered = context_released = false;
  context_compilations = context_fallbacks = 0;
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_melo_kokoro_KokoroNativeContextIntegration_awaitPreparation(
    JNIEnv *, jclass) {
  std::unique_lock<std::mutex> guard(context_lock);
  return context_changed.wait_for(guard, std::chrono::seconds(120), [] { return context_entered; });
}
extern "C" JNIEXPORT void JNICALL Java_dev_melo_kokoro_KokoroNativeContextIntegration_releasePreparation(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> guard(context_lock);
  context_released = true;
  context_changed.notify_all();
}
extern "C" JNIEXPORT jint JNICALL Java_dev_melo_kokoro_KokoroNativeContextIntegration_contextCompilations(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> guard(context_lock);
  return context_compilations;
}
extern "C" JNIEXPORT jint JNICALL Java_dev_melo_kokoro_KokoroNativeContextIntegration_contextFallbacks(
    JNIEnv *, jclass) {
  std::lock_guard<std::mutex> guard(context_lock);
  return context_fallbacks;
}
