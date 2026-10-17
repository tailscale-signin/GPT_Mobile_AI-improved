package dev.melo.gguf;

/** The sole JNI boundary. Model bytes and prompts never leave the inference process. */
public final class GgufNative {
    public static final String REVISION = "4413a192410fad1f054e9216114087a3e01527a0";
    static { System.loadLibrary("gptmobile_gguf"); }
    private GgufNative() {}
    public interface Callback { boolean onBytes(byte[] utf8); }
    /** True when the packaged backend discovers a compatible GPU on this device. */
    public static native boolean supportsGpuOffload();
    /** gpuLayers is zero for CPU or positive for explicit GPU layer offload. */
    public static native long load(byte[] path, int contextTokens, int threads, int gpuLayers);
    /** Returns input tokens, output tokens, prefill ms, decode ms, stop code. */
    public static native long[] generate(long handle, String[] roles, byte[][] contents, int outputTokens, int topK, float topP, float temperature, Callback callback);
    public static native void cancel(long handle);
    public static native void unload(long handle);
}
