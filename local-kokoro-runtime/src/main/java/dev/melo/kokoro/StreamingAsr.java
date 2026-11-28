package dev.melo.kokoro;

/** Streaming Sherpa recognition. Token timestamps are emission positions, not word ends. */
public final class StreamingAsr implements AutoCloseable {
    public static final String RUNTIME_ID = "sherpa-38016c6-ort-1.31-qairt-2.52-asr-1";
    public static final int SAMPLE_RATE = 16000;
    static { System.loadLibrary("timed_kokoro"); }

    public static final class Result {
        public final String text;
        public final String[] tokens;
        public final float[] timestampsSeconds;
        public final float segmentStartSeconds;
        public final int segment;
        public final boolean endpoint;
        public final long acceptedAudioFrames;
        public Result(String text, String[] tokens, float[] timestampsSeconds,
                      float segmentStartSeconds, int segment, boolean endpoint, long acceptedAudioFrames) {
            this.text = text;
            this.tokens = tokens;
            this.timestampsSeconds = timestampsSeconds;
            this.segmentStartSeconds = segmentStartSeconds;
            this.segment = segment;
            this.endpoint = endpoint;
            this.acceptedAudioFrames = acceptedAudioFrames;
        }
    }

    public static final class Evidence {
        public final String requestedProvider;
        public final String actualProvider;
        public final long completedChunks;
        public final long acceptedAudioFrames;
        /** Encoder, decoder, joiner counts for the first completed profiled invocation. */
        public final long[] sampledAcceleratorKernels;
        public final long[] sampledCpuKernels;
        public final long[] completedComponentRuns;
        public final boolean executionVerified;
        public final String discovery;
        public Evidence(String requestedProvider, String actualProvider, long completedChunks,
                        long acceptedAudioFrames, long[] accelerator, long[] cpu, long[] runs,
                        boolean executionVerified, String discovery) {
            this.requestedProvider = requestedProvider;
            this.actualProvider = actualProvider;
            this.completedChunks = completedChunks;
            this.acceptedAudioFrames = acceptedAudioFrames;
            this.sampledAcceleratorKernels = accelerator;
            this.sampledCpuKernels = cpu;
            this.completedComponentRuns = runs;
            this.executionVerified = executionVerified;
            this.discovery = discovery;
        }
        @Override public String toString() {
            return "requested=" + requestedProvider + ";actual=" + actualProvider
                + ";executed=" + executionVerified + ";chunks=" + completedChunks
                + ";realAudioFrames=" + acceptedAudioFrames
                + ";sampledGpuKernels=" + java.util.Arrays.toString(sampledAcceleratorKernels)
                + ";sampledCpuKernels=" + java.util.Arrays.toString(sampledCpuKernels)
                + ";componentRuns=" + java.util.Arrays.toString(completedComponentRuns)
                + ";discovery=" + discovery;
        }
    }

    public static final class Failure extends RuntimeException {
        public final String code;
        public Failure(String code) { super(code); this.code = code; }
    }

    private volatile long handle;
    public StreamingAsr(String encoder, String decoder, String joiner, String tokens,
                        String modelType, String provider, String language, String nativeLibraryDir,
                        String workDirectory, int threads, int chunkMillis, float endpointSilenceSeconds) {
        handle = nativeCreate(encoder, decoder, joiner, tokens, modelType, provider, language,
            nativeLibraryDir, workDirectory, threads, chunkMillis, endpointSilenceSeconds);
        if (handle == 0) throw new Failure("ASR_INITIALIZATION_FAILED");
    }

    public synchronized Result accept(float[] audio) { return nativeAccept(checkedHandle(), audio); }
    public synchronized Result finish() { return nativeFinish(checkedHandle()); }
    /** Start an independent capture while retaining the prepared model and graph contexts. */
    public synchronized void reset(String language) { nativeReset(checkedHandle(), language); }
    public synchronized Evidence evidence() { return nativeEvidence(checkedHandle()); }
    /** Cancellation must not wait for the inference monitor. */
    public void cancel() {
        long current = handle;
        if (current != 0) nativeCancel(current);
    }
    @Override public void close() {
        cancel();
        synchronized (this) {
            long current = handle;
            handle = 0;
            if (current != 0) nativeDelete(current);
        }
    }
    private long checkedHandle() {
        if (handle == 0) throw new IllegalStateException("Recognition engine is closed");
        return handle;
    }

    private static native long nativeCreate(String encoder, String decoder, String joiner, String tokens,
        String modelType, String provider, String language, String libraryDir, String workDir, int threads, int chunkMillis,
        float endpointSilenceSeconds);
    private static native Result nativeAccept(long handle, float[] audio);
    private static native Result nativeFinish(long handle);
    private static native void nativeReset(long handle, String language);
    private static native Evidence nativeEvidence(long handle);
    private static native void nativeCancel(long handle);
    private static native void nativeDelete(long handle);
}
