package dev.melo.kokoro;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** One synthesis worker owns this model and closes it after its final phrase. */
public final class TimedKokoro implements AutoCloseable {
    public static final int SAMPLE_RATE = 24000;
    public static final String RUNTIME_ID = "ort1.28.2-qnn2.7-qairt2.51-convex1";
    static { System.loadLibrary("timed_kokoro"); }

    public static final class Audio {
        public final float[] samples;
        public final long[] durations;
        /** Half-open token index pairs in spoken-word order, excluding spaces and punctuation. */
        public final int[] wordTokens;

        private Audio(float[] samples, long[] durations, int[] wordTokens) {
            this.samples = samples;
            this.durations = durations;
            this.wordTokens = wordTokens;
        }
    }

    /** Safe native failure fields; no spoken text, filesystem path or raw driver log. */
    public static final class Failure extends IllegalStateException {
        public final String stage;
        public final String code;
        public final int nativeCode;
        public final int tokenCount;
        public final String provider;

        private Failure(String stage, String code, int nativeCode, int tokenCount, String provider) {
            super("Speech " + stage + " failed: " + code);
            this.stage = stage;
            this.code = code;
            this.nativeCode = nativeCode;
            this.tokenCount = tokenCount;
            this.provider = provider;
        }
    }

    /** Profile counts/times describe the first inference of this exact graph shape. */
    public static final class Evidence {
        public final int acceleratorKernels, cpuKernels, tokenCount, deviceType;
        public final long acceleratorMicros, cpuMicros, prepareMs, synthesisMs, vendorId, deviceId;
        public final boolean validatedAudio;

        private Evidence(long[] data) {
            if (data == null || data.length != 12 || data[0] != 1) throw new IllegalStateException("Invalid speech execution evidence");
            acceleratorKernels = (int) data[1];
            cpuKernels = (int) data[2];
            acceleratorMicros = data[3];
            cpuMicros = data[4];
            prepareMs = data[5];
            synthesisMs = data[6];
            tokenCount = (int) data[7];
            deviceType = (int) data[8];
            vendorId = data[9];
            deviceId = data[10];
            validatedAudio = data[11] == 1;
        }
    }

    private long handle;
    // Cancellation may run beside generate; close waits for both without blocking cancellation.
    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock();
    public TimedKokoro(String model, String voices, String tokens, String lexicon, int threads) {
        this(model, voices, tokens, lexicon, threads, "cpu", "", "");
    }

    public TimedKokoro(String model, String voices, String tokens, String lexicon, int threads,
            String provider, String libraryDirectory, String profilePrefix) {
        handle = open(model, voices, tokens, lexicon, threads, provider, libraryDirectory, profilePrefix);
    }

    public Audio generate(String phrase, int speaker, float speed) {
        lifetime.readLock().lock();
        try {
            if (handle == 0) throw new IllegalStateException("Kokoro is closed");
            return generatePhrase(handle, phrase, speaker, speed);
        } finally { lifetime.readLock().unlock(); }
    }

    public int acceleratedNodes() {
        lifetime.readLock().lock();
        try { return handle == 0 ? 0 : executedAcceleratorNodes(handle); }
        finally { lifetime.readLock().unlock(); }
    }

    public Evidence evidence() {
        lifetime.readLock().lock();
        try {
            if (handle == 0) throw new IllegalStateException("Kokoro is closed");
            return new Evidence(executionEvidence(handle));
        } finally { lifetime.readLock().unlock(); }
    }

    public void cancel() {
        lifetime.readLock().lock();
        try { if (handle != 0) cancelGeneration(handle); }
        finally { lifetime.readLock().unlock(); }
    }

    public void resetCancellation() {
        lifetime.readLock().lock();
        try {
            if (handle == 0) throw new IllegalStateException("Kokoro is closed");
            resetGeneration(handle);
        } finally { lifetime.readLock().unlock(); }
    }

    @Override public void close() {
        lifetime.writeLock().lock();
        try {
            if (handle != 0) release(handle);
            handle = 0;
        } finally { lifetime.writeLock().unlock(); }
    }

    private static native long open(String model, String voices, String tokens, String lexicon, int threads,
            String provider, String libraryDirectory, String profilePrefix);
    private static native int executedAcceleratorNodes(long handle);
    private static native long[] executionEvidence(long handle);
    private static native Audio generatePhrase(long handle, String phrase, int speaker, float speed);
    private static native void cancelGeneration(long handle);
    private static native void resetGeneration(long handle);
    private static native void release(long handle);
}
