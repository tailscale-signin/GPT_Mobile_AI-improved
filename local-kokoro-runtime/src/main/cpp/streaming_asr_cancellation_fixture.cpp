// Host-only JNI scheduling fixture. It does not load a model or emulate QNN.
#include <jni.h>
#include <condition_variable>
#include <mutex>

namespace {
std::mutex mutex;
std::condition_variable changed;
int options = 0;
int accepts = 0;
int cancels = 0;
int active_cancels = 0;
int deletes = 0;
int overlapping_delete = 0;
int overlapping_cancel = 0;
bool cancelled = false;
bool cancel_released = false;
bool delete_released = false;
bool deleting = false;
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_melo_kokoro_StreamingAsrCancellationIntegration_resetFixture(JNIEnv *, jclass, jint flags) {
  std::lock_guard<std::mutex> lock(mutex);
  options = flags;
  accepts = cancels = active_cancels = deletes = overlapping_delete = overlapping_cancel = 0;
  cancelled = cancel_released = delete_released = deleting = false;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_melo_kokoro_StreamingAsrCancellationIntegration_counter(JNIEnv *, jclass, jint field) {
  std::lock_guard<std::mutex> lock(mutex);
  switch (field) {
    case 0: return accepts;
    case 1: return cancels;
    case 2: return active_cancels;
    case 3: return deletes;
    case 4: return overlapping_delete;
    case 5: return overlapping_cancel;
    default: return -1;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_melo_kokoro_StreamingAsrCancellationIntegration_releaseAll(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(mutex);
  cancelled = cancel_released = delete_released = true;
  changed.notify_all();
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_melo_kokoro_StreamingAsr_nativeCreate(JNIEnv *, jclass, jstring, jstring, jstring, jstring,
    jstring, jstring, jstring, jstring, jstring, jint, jint, jfloat) {
  return 1;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_melo_kokoro_StreamingAsr_nativeAccept(JNIEnv *, jclass, jlong, jfloatArray) {
  std::unique_lock<std::mutex> lock(mutex);
  ++accepts;
  changed.notify_all();
  if (options & 1) changed.wait(lock, [] { return cancelled; });
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_melo_kokoro_StreamingAsr_nativeCancel(JNIEnv *, jclass, jlong) {
  std::unique_lock<std::mutex> lock(mutex);
  const int invocation = ++cancels;
  ++active_cancels;
  if (deleting) ++overlapping_cancel;
  cancelled = true;
  changed.notify_all();
  if ((options & 2) && invocation == 1) changed.wait(lock, [] { return cancel_released; });
  --active_cancels;
  changed.notify_all();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_melo_kokoro_StreamingAsr_nativeDelete(JNIEnv *env, jclass, jlong) {
  std::unique_lock<std::mutex> lock(mutex);
  ++deletes;
  if (active_cancels != 0) ++overlapping_delete;
  deleting = true;
  changed.notify_all();
  if (options & 4) changed.wait(lock, [] { return delete_released; });
  deleting = false;
  if (options & 8) {
    auto failure = env->FindClass("dev/melo/kokoro/StreamingAsr$Failure");
    if (failure) env->ThrowNew(failure, "ASR_RETIREMENT_UNCONFIRMED");
  }
}
