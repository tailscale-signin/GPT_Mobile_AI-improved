package dev.melo.kokoro;

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
    public TimedKokoro(String model, String voices, String tokens, String lexicon, int threads) {
        handle = open(model, voices, tokens, lexicon, threads);
    }

    public Audio generate(String phrase, int speaker, float speed) {
        if (handle == 0) throw new IllegalStateException("Kokoro is closed");
        return generatePhrase(handle, phrase, speaker, speed);
    }

    @Override public void close() {
        release(handle);
        handle = 0;
    }

    private static native long open(String model, String voices, String tokens, String lexicon, int threads);
    private static native Audio generatePhrase(long handle, String phrase, int speaker, float speed);
    private static native void release(long handle);
}
