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
