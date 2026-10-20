package dev.melo.gguf;

/** The sole JNI boundary. Model bytes and prompts never leave the inference process. */
public final class GgufNative {
    public static final String REVISION = "4413a192410fad1f054e9216114087a3e01527a0";
    static { System.loadLibrary("gptmobile_gguf"); }
    private GgufNative() {}
    private static final java.util.concurrent.atomic.AtomicLong OPERATIONS = new java.util.concurrent.atomic.AtomicLong();
    public static long nextOperationId() { return OPERATIONS.incrementAndGet(); }
    /** Runs on the original JNI caller thread. Contains counters only, never source text. */
    public interface Progress { void onProgress(String phase, long promptTokens, long processedTokens, long sampledTokens, long elapsedMs, int nativeCode); }
    public static native void prepareOperation(long handle, long operationId);
    public static native void cancelOperation(long handle, long operationId);
    public interface LoadProgress { void onProgress(String phase, float fraction, long elapsedMs); }
    public static native long loadConfiguredProgress(byte[] path, int contextTokens, int threads, int gpuLayers, long operationId, int promptThreads, int batchTokens, int microBatchTokens, LoadProgress progress);
    /** Effective context/batch/microbatch, host model/context/compute, device model/context/compute, tensor bytes. */
    public static native long[] allocationObservation(long handle);
    public interface Callback { boolean onBytes(byte[] utf8); }
    /** True when the packaged backend discovers a compatible GPU on this device. */
    public static native boolean supportsGpuOffload();
    public static native String buildInfo();
    /** Parses only the embedded template; does not load model weights or allocate an engine. */
    public static native void validateChatTemplate(byte[] template);
    /** gpuLayers is zero for CPU or positive for explicit GPU layer offload. */
    public static long load(byte[] path, int contextTokens, int threads, int gpuLayers) {
        long operation = nextOperationId();
        prepareOperation(0, operation);
        return loadPrepared(path, contextTokens, threads, gpuLayers, operation);
    }
    public static long loadPrepared(byte[] path, int contextTokens, int threads, int gpuLayers, long operationId) {
        return loadConfigured(path, contextTokens, threads, gpuLayers, operationId, threads, 256, 128);
    }
    public static native long loadConfigured(byte[] path, int contextTokens, int threads, int gpuLayers, long operationId, int promptThreads, int batchTokens, int microBatchTokens);
    /** Returns input tokens, output tokens, prefill ms, decode ms, stop code. */
    public static long[] generate(long handle, String[] roles, byte[][] contents, int outputTokens, int topK, float topP, float temperature, Callback callback) {
        long operation = nextOperationId();
        prepareOperation(handle, operation);
        return generatePrepared(handle, roles, contents, outputTokens, topK, topP, temperature, callback, null, operation);
    }
    public static native long[] generatePrepared(long handle, String[] roles, byte[][] contents, int outputTokens, int topK, float topP, float temperature, Callback callback, Progress progress, long operationId);
    public static native void cancel(long handle);
    public static native void unload(long handle);
}
