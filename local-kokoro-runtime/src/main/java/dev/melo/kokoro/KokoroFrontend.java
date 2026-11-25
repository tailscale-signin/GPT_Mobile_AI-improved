package dev.melo.kokoro;

/** Reuses the exact offline English pronunciation frontend without loading an inference model. */
public final class KokoroFrontend implements AutoCloseable {
    static { System.loadLibrary("timed_kokoro"); }
    public static final class EncodedPhrase {
        public final long[] tokenIds;
        public final int[] wordTokens;
        private String vocabulary;
        final int inputBytes;
        private long[] sealedTokens;
        private int[] sealedWords;

        private EncodedPhrase(long[] tokenIds, int[] wordTokens, int inputBytes) {
            this.tokenIds = tokenIds;
            this.wordTokens = wordTokens;
            this.inputBytes = inputBytes;
        }
        EncodedPhrase bind(String key) {
            vocabulary = key;
            sealedTokens = tokenIds.clone();
            sealedWords = wordTokens.clone();
            return this;
        }
        boolean matches(String key) { return key.equals(vocabulary); }
        long[] inferenceTokens() { return sealedTokens; }
        int[] inferenceWords() { return sealedWords; }
    }
    static String vocabularyKey(String tokens, String lexicon) { return tokens + (char) 0 + lexicon; }
    private final String vocabulary;
    private long handle;
    public KokoroFrontend(String tokens, String lexicon) {
        vocabulary = vocabularyKey(tokens, lexicon);
        handle = open(tokens, lexicon);
    }
    public synchronized EncodedPhrase encode(String phrase) {
        if (handle == 0) throw new IllegalStateException("Kokoro frontend is closed");
        return encodePhrase(handle, phrase).bind(vocabulary);
    }
    @Override public synchronized void close() {
        if (handle != 0) release(handle);
        handle = 0;
    }
    private static native long open(String tokens, String lexicon);
    private static native EncodedPhrase encodePhrase(long handle, String phrase);
    private static native void release(long handle);
}
