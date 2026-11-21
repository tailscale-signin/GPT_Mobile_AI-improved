package dev.melo.kokoro;

/** Reuses the exact offline English pronunciation frontend without loading an inference model. */
public final class KokoroFrontend implements AutoCloseable {
    static { System.loadLibrary("timed_kokoro"); }
    public static final class EncodedPhrase {
        public final long[] tokenIds;
        public final int[] wordTokens;
        private EncodedPhrase(long[] tokenIds, int[] wordTokens) {
            this.tokenIds = tokenIds;
            this.wordTokens = wordTokens;
        }
    }
    private long handle;
    public KokoroFrontend(String tokens, String lexicon) { handle = open(tokens, lexicon); }
    public synchronized EncodedPhrase encode(String phrase) {
        if (handle == 0) throw new IllegalStateException("Kokoro frontend is closed");
        return encodePhrase(handle, phrase);
    }
    @Override public synchronized void close() {
        if (handle != 0) release(handle);
        handle = 0;
    }
    private static native long open(String tokens, String lexicon);
    private static native EncodedPhrase encodePhrase(long handle, String phrase);
    private static native void release(long handle);
}
