package dev.melo.kokoro;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** One synthesis worker owns this model and closes it after its final phrase. */
public final class TimedKokoro implements AutoCloseable {
    public static final int SAMPLE_RATE = 24000;
    public static final String RUNTIME_ID = "ort1.28.2-qnn2.7-qairt2.51-convex2-profile5-cache3-progress1-parser2-validation1";
    public static final int MAX_INPUT_BYTES = 128;
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
        public final int inputBytes, inputByteLimit, tokenLimit;
        public final String discoverySummary;
        /** Immutable evidence from this failed attempt only; absent before a model is opened. */
        public final Evidence currentEvidence;

        private Failure(String stage, String code, int nativeCode, int tokenCount, String provider,
                int inputBytes, int inputByteLimit, int tokenLimit, String discoverySummary, long[] currentEvidence) {
            super("Speech " + stage + " failed: " + code);
            this.stage = stage;
            this.code = code;
            this.nativeCode = nativeCode;
            this.tokenCount = tokenCount;
            this.provider = provider;
            this.inputBytes = inputBytes;
            this.inputByteLimit = inputByteLimit;
            this.tokenLimit = tokenLimit;
            this.discoverySummary = discoverySummary;
            this.currentEvidence = currentEvidence == null ? null : new Evidence(currentEvidence, discoverySummary);
        }
    }

    /** Profile counts/times describe the first inference of this exact graph shape. */
    public static final class Evidence {
        public final int acceleratorKernels, cpuKernels, tokenCount, deviceType;
        public final long acceleratorMicros, cpuMicros, prepareMs, synthesisMs, vendorId, deviceId;
        public final boolean validatedAudio;
        public final boolean profileAvailable, cacheHit, profileReused, graphPreparedThisCall;
        public final long profileId, currentPrepareMs, cacheEstimatedBytes, clippedSamples, nonSilentSamples;
        public final int cachedShapeCount, cacheLimit;
        public final double audioRms, audioPeak;
        public final String discoverySummary;
        /** Current-call phase costs; null when reading the historical v2 snapshot. */
        public final Long frontendMs, validationMs, profileFinalizeMs;
        public final Integer cacheEvictions;
        /** Nullable for historical snapshots; retention estimates are not allocator limits. */
        public final Long cacheBudgetBytes, activeShapeEstimatedBytes, availableMemoryBytes;
        public final String cacheAdmission;
        /** Legacy complete receipts prove a scan; legacy failures leave availability unknown. */
        public final Boolean audioMeasurementAvailable;
        public final Boolean audioFinite;
        /** Fixed reason code and bounded scalar metadata; never PCM, phrase text or driver detail. */
        public final String validationReason;
        public final Long outputTensorCount, outputFrames, outputElementType, outputRank;
        public final Long durationCount, durationElementType, durationRank, durationSum, durationMin, durationMax;
        public final Boolean durationStatsAvailable, durationSumOverflow;

        private Evidence(long[] data, String discoverySummary) {
            if (data == null || !((data.length == 25 && data[0] == 2) || (data.length == 29 && data[0] == 3) ||
                    (data.length == 33 && data[0] == 4) || (data.length == 48 && data[0] == 5)))
                throw new IllegalStateException("Invalid speech execution evidence");
            if (data[0] == 5) validateCurrentEvidence(data);
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
            profileAvailable = data[12] == 1;
            profileId = data[13];
            cacheHit = data[14] == 1;
            currentPrepareMs = data[15];
            cachedShapeCount = (int) data[16];
            cacheLimit = (int) data[17];
            cacheEstimatedBytes = data[18];
            audioRms = Double.longBitsToDouble(data[19]);
            audioPeak = Double.longBitsToDouble(data[20]);
            clippedSamples = data[21];
            nonSilentSamples = data[22];
            profileReused = data[23] == 1;
            graphPreparedThisCall = data[24] == 1;
            this.discoverySummary = discoverySummary;
            frontendMs = data.length >= 29 ? data[25] : null;
            validationMs = data.length >= 29 ? data[26] : null;
            cacheEvictions = data.length >= 29 ? (int) data[27] : null;
            profileFinalizeMs = data.length >= 29 ? data[28] : null;
            cacheBudgetBytes = data.length >= 33 ? data[29] : null;
            activeShapeEstimatedBytes = data.length >= 33 && data[30] >= 0 ? data[30] : null;
            availableMemoryBytes = data.length >= 33 && data[31] > 0 ? data[31] : null;
            cacheAdmission = data.length >= 33 && data[32] >= 0 && data[32] <= 4 ?
                    new String[] {"CPU_SINGLE", "MEMORY_UNKNOWN", "MEMORY_PRESSURE", "BOUNDED_TWO_SHAPES", "BOUNDED_THREE_SHAPES"}[(int) data[32]] : null;
            final boolean current = data[0] == 5;
            audioMeasurementAvailable = current ? Boolean.valueOf(data[34] == 1) : validatedAudio ? Boolean.TRUE : null;
            audioFinite = current && data[34] == 1 ? Boolean.valueOf(data[47] == 1) : validatedAudio ? Boolean.TRUE : null;
            validationReason = current && data[33] != 0 ? new String[] {
                    "NONE", "OUTPUT_COUNT", "AUDIO_TENSOR_TYPE", "AUDIO_FRAME_COUNT", "AUDIO_TOO_LONG",
                    "TIMING_TENSOR_TYPE", "TIMING_TOKEN_COUNT", "TOKEN_DURATION_INVALID", "TIMING_PCM_MISMATCH",
                    "NONFINITE_PCM", "SILENT_PCM", "TENSOR_METADATA_UNAVAILABLE"
            }[(int) data[33]] : null;
            outputTensorCount = current && data[35] >= 0 ? data[35] : null;
            outputFrames = current && data[36] >= 0 ? data[36] : null;
            outputElementType = current && data[37] >= 0 ? data[37] : null;
            outputRank = current && data[38] >= 0 ? data[38] : null;
            durationCount = current && data[39] >= 0 ? data[39] : null;
            durationElementType = current && data[40] >= 0 ? data[40] : null;
            durationRank = current && data[41] >= 0 ? data[41] : null;
            durationStatsAvailable = current ? data[42] == 1 : null;
            durationSumOverflow = current && data[42] == 1 ? data[46] == 1 : null;
            durationSum = current && data[42] == 1 && data[46] == 0 ? data[43] : null;
            durationMin = current && data[42] == 1 ? data[44] : null;
            durationMax = current && data[42] == 1 ? data[45] : null;
        }

        private static void validateCurrentEvidence(long[] data) {
            boolean valid = data[33] >= 0 && data[33] <= 11 && data[32] >= 0 && data[32] <= 4;
            for (int index : new int[] {11, 12, 14, 23, 24, 34, 42, 46, 47}) valid &= data[index] == 0 || data[index] == 1;
            for (int index = 35; index <= 41; index++) valid &= data[index] >= -1;
            valid &= data[16] >= 0 && data[16] <= 3 && data[17] >= 1 && data[17] <= 3;
            valid &= data[16] <= data[17];
            if (data[42] == 1) valid &= data[39] > 0 && data[39] <= 511 && data[40] == 7 && data[44] <= data[45];
            if (data[46] == 1) valid &= data[42] == 1;
            if (data[34] == 1) {
                valid &= data[35] == 2 && data[36] > 0 && data[36] <= 30L * SAMPLE_RATE && data[37] == 1 && data[38] >= 0 &&
                        data[39] > 0 && data[39] <= 511 && data[40] == 7 && data[41] >= 0 && data[42] == 1 && data[46] == 0 &&
                        data[43] > 0 && data[43] <= 30L * SAMPLE_RATE / 600 && data[43] * 600 == data[36] && data[44] > 0;
            } else valid &= data[47] == 0;
            if (data[11] == 1) valid &= data[34] == 1 && data[47] == 1 && data[33] == 0;
            if (data[33] == 9 || data[33] == 10) valid &= data[34] == 1;
            if (!valid) throw new IllegalStateException("Invalid speech execution evidence");
        }
    }

    /** Atomic current-attempt progress; contains no text, paths or mutable session evidence. */
    public static final class Progress {
        public final String stage;
        public final long attemptId, stageSequence, stageElapsedMs, attemptElapsedMs;
        public final boolean cancellationRequested, active;

        private Progress(long[] data) {
            if (data.length != 8 || data[0] != 1 || data[2] < 0 || data[2] > 4)
                throw new IllegalStateException("Invalid speech execution progress");
            stage = new String[] {"frontend", "preparing", "synthesis", "validating", "ready"}[(int) data[2]];
            attemptId = data[1];
            stageSequence = data[3];
            stageElapsedMs = data[4];
            attemptElapsedMs = data[5];
            cancellationRequested = data[6] == 1;
            active = data[7] == 1;
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
            return new Evidence(executionEvidence(handle), executionDiscovery(handle));
        } finally { lifetime.readLock().unlock(); }
    }

    /** Never blocks the watchdog behind close; the native read only accesses atomics. */
    public Progress progress() {
        if (!lifetime.readLock().tryLock()) return null;
        try {
            if (handle == 0) return null;
            long[] snapshot = executionProgress(handle);
            return snapshot == null ? null : new Progress(snapshot);
        } finally { lifetime.readLock().unlock(); }
    }

    public void cancel() {
        // A waiting close writer must not prevent cancellation of the reader
        // it is waiting for. If close already owns the handle, it is retiring.
        if (!lifetime.readLock().tryLock()) return;
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
    private static native long[] executionProgress(long handle);
    private static native String executionDiscovery(long handle);
    private static native Audio generatePhrase(long handle, String phrase, int speaker, float speed);
    private static native void cancelGeneration(long handle);
    private static native void resetGeneration(long handle);
    private static native void release(long handle);
}
