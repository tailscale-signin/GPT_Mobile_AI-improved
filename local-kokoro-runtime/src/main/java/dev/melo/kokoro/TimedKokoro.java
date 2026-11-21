package dev.melo.kokoro;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** One synthesis worker owns this model and closes it after its final phrase. */
public final class TimedKokoro implements AutoCloseable {
    public static final int SAMPLE_RATE = 24000;
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
    private static native Audio generatePhrase(long handle, String phrase, int speaker, float speed);
    private static native void cancelGeneration(long handle);
    private static native void resetGeneration(long handle);
    private static native void release(long handle);
}
