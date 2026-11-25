package dev.melo.kokoro;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/** Exercises the real Java JNI decoder without a model, Android stubs or native loading. */
public final class KokoroEvidenceContractTest {
    private static final Constructor<TimedKokoro.Evidence> CONSTRUCTOR;
    static {
        try {
            CONSTRUCTOR = TimedKokoro.Evidence.class.getDeclaredConstructor(long[].class, String.class);
            CONSTRUCTOR.setAccessible(true);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static TimedKokoro.Evidence parse(long[] data) throws ReflectiveOperationException {
        return CONSTRUCTOR.newInstance(data, "stage=selected");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static long[] empty() {
        long[] data = new long[48];
        data[0] = 5; data[17] = 3; data[32] = 4;
        Arrays.fill(data, 35, 42, -1);
        return data;
    }
    private static long[] measured() {
        long[] data = empty();
        data[11] = 1; data[34] = 1; data[35] = 2; data[36] = 1800;
        data[37] = 1; data[38] = 1; data[39] = 3; data[40] = 7; data[41] = 1;
        data[42] = 1; data[43] = 3; data[44] = 1; data[45] = 1; data[47] = 1;
        return data;
    }
    private static void rejected(long[] data) throws ReflectiveOperationException {
        try { parse(data); throw new AssertionError("Malformed current native evidence passed"); }
        catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof IllegalStateException, "Wrong decoder rejection");
        }
    }

    public static void verify() throws ReflectiveOperationException {
        for (int version = 2; version <= 4; version++) {
            long[] legacy = new long[version == 2 ? 25 : version == 3 ? 29 : 33];
            legacy[0] = version;
            TimedKokoro.Evidence failed = parse(legacy);
            require(failed.audioMeasurementAvailable == null && failed.audioFinite == null && failed.outputFrames == null &&
                    failed.validationReason == null && failed.durationStatsAvailable == null, "Legacy zero defaults became measurements");
            legacy[11] = 1;
            TimedKokoro.Evidence complete = parse(legacy);
            require(Boolean.TRUE.equals(complete.audioMeasurementAvailable) && Boolean.TRUE.equals(complete.audioFinite),
                    "Validated legacy audio lost its scan evidence");
        }
        TimedKokoro.Evidence notMeasured = parse(empty());
        require(Boolean.FALSE.equals(notMeasured.audioMeasurementAvailable) && notMeasured.audioFinite == null &&
                notMeasured.outputFrames == null && "BOUNDED_THREE_SHAPES".equals(notMeasured.cacheAdmission),
                "New evidence fabricated PCM or lost cache admission");
        require(notMeasured.graphPreparationSource == null && notMeasured.contextCacheStatus == null &&
                notMeasured.contextLoadMs == null && notMeasured.htpPerformanceMode == null,
                "Historical evidence fabricated compiled-context observations");
        long[] tooLong = empty();
        tooLong[33] = 4; tooLong[35] = 2; tooLong[36] = 93 * 45 * 600;
        tooLong[37] = 1; tooLong[38] = 1; tooLong[39] = 93; tooLong[40] = 7; tooLong[41] = 1;
        tooLong[42] = 1; tooLong[43] = 93 * 45; tooLong[44] = 45; tooLong[45] = 45;
        TimedKokoro.Evidence oversized = parse(tooLong);
        require("AUDIO_TOO_LONG".equals(oversized.validationReason) && Boolean.FALSE.equals(oversized.audioMeasurementAvailable) &&
                oversized.outputFrames == 2511000 && oversized.durationSum == 4185 && oversized.durationMin == 45 && oversized.durationMax == 45,
                "An unscanned oversized waveform lost safe duration evidence or became silent PCM");
        long[] complete = measured();
        require(Boolean.TRUE.equals(parse(complete).audioMeasurementAvailable), "Measured audio missing");
        long[] silent = measured(); silent[11] = 0; silent[33] = 10;
        require("SILENT_PCM".equals(parse(silent).validationReason) && Boolean.TRUE.equals(parse(silent).audioFinite), "Measured silence unavailable");
        long[] nonfinite = measured(); nonfinite[11] = 0; nonfinite[33] = 9; nonfinite[47] = 0;
        require(Boolean.FALSE.equals(parse(nonfinite).audioFinite), "Measured invalid PCM became unknown");
        long[] clipped = measured(); clipped[11] = 0; clipped[33] = 12; clipped[21] = 8;
        clipped[19] = Double.doubleToLongBits(.25); clipped[20] = Double.doubleToLongBits(1.2);
        TimedKokoro.Evidence clippedEvidence = parse(clipped);
        require("CLIPPED_PCM".equals(clippedEvidence.validationReason) && !clippedEvidence.validatedAudio &&
                Boolean.TRUE.equals(clippedEvidence.audioMeasurementAvailable) && Boolean.TRUE.equals(clippedEvidence.audioFinite) &&
                clippedEvidence.clippedSamples == 8 && clippedEvidence.audioPeak == 1.2 && clippedEvidence.audioRms == .25 &&
                clippedEvidence.outputFrames == 1800 && clippedEvidence.durationSum == 3,
                "Rejected clipping lost original waveform measurements or became valid audio");
        long[] exactFullScale = clipped.clone(); exactFullScale[20] = Double.doubleToLongBits(1);
        require("CLIPPED_PCM".equals(parse(exactFullScale).validationReason), "Exact full-scale clipping was not retained");
        long[] overflow = tooLong.clone(); overflow[33] = 7; overflow[46] = 1; overflow[43] = 0; overflow[45] = Long.MAX_VALUE;
        require(parse(overflow).durationSum == null && Boolean.TRUE.equals(parse(overflow).durationSumOverflow), "Duration overflow became a valid sum");
        rejected(Arrays.copyOf(complete, 47));
        long[] unknownVersion = complete.clone(); unknownVersion[0] = 6; rejected(unknownVersion);
        for (int index : new int[] {11, 12, 14, 23, 24, 34, 42, 46, 47}) {
            long[] badFlag = complete.clone(); badFlag[index] = 2; rejected(badFlag);
        }
        long[] badReason = complete.clone(); badReason[33] = 13; rejected(badReason);
        long[] falseClipping = empty(); falseClipping[33] = 12; rejected(falseClipping);
        long[] zeroClipping = clipped.clone(); zeroClipping[21] = 0; rejected(zeroClipping);
        long[] excessiveClipping = clipped.clone(); excessiveClipping[21] = excessiveClipping[36] + 1; rejected(excessiveClipping);
        long[] falsePeak = clipped.clone(); falsePeak[20] = Double.doubleToLongBits(.99); rejected(falsePeak);
        long[] nonfiniteClipping = clipped.clone(); nonfiniteClipping[47] = 0; rejected(nonfiniteClipping);
        long[] acceptedClipping = complete.clone(); acceptedClipping[21] = 1; rejected(acceptedClipping);
        long[] falseMeasurement = empty(); falseMeasurement[11] = 1; rejected(falseMeasurement);
        long[] falseSilence = empty(); falseSilence[33] = 10; rejected(falseSilence);
        long[] badTiming = complete.clone(); badTiming[43] = Long.MAX_VALUE; rejected(badTiming);
        long[] wrongDtype = complete.clone(); wrongDtype[40] = 1; rejected(wrongDtype);
        long[] badCache = complete.clone(); badCache[16] = 4; rejected(badCache);
        long[] badCount = complete.clone(); badCount[39] = 512; rejected(badCount);

        long[] pendingContext = Arrays.copyOf(empty(), 56);
        pendingContext[0] = 6; pendingContext[48] = 3; pendingContext[49] = 2;
        pendingContext[50] = 17; pendingContext[52] = 4096;
        TimedKokoro.Evidence prepared = parse(pendingContext);
        require("DISK_CONTEXT".equals(prepared.graphPreparationSource) && "HIT".equals(prepared.contextCacheStatus) &&
                prepared.contextLoadMs == 17 && "balanced".equals(prepared.htpPerformanceMode) &&
                !prepared.validatedAudio && !prepared.profileAvailable,
                "A loaded context fabricated actual execution or PCM qualification");
        long[] stored = Arrays.copyOf(measured(), 56);
        stored[0] = 6; stored[1] = 3; stored[12] = 1; stored[48] = 2; stored[49] = 3;
        stored[51] = 21; stored[52] = 4096; stored[53] = 1; stored[54] = 1; stored[55] = 2;
        TimedKokoro.Evidence committed = parse(stored);
        require("STORED".equals(committed.contextCacheStatus) && committed.contextStoreMs == 21 &&
                committed.contextCacheEvictions == 1 && Boolean.TRUE.equals(committed.contextFallback) &&
                "high_performance".equals(committed.htpPerformanceMode), "Committed context receipt lost actual fields");
        long[] resident = stored.clone(); resident[48] = 4; resident[49] = 10; resident[14] = 1;
        require("RESIDENT".equals(parse(resident).graphPreparationSource), "Resident graph became a disk load");
        long[] falseStored = stored.clone(); falseStored[12] = 0; rejected(falseStored);
        long[] falseResident = resident.clone(); falseResident[14] = 0; rejected(falseResident);
        long[] badPath = stored.clone(); badPath[48] = 5; rejected(badPath);
        long[] noStorage = stored.clone(); noStorage[49] = 18; noStorage[52] = 0;
        require("INSUFFICIENT_STORAGE".equals(parse(noStorage).contextCacheStatus), "Cache admission cause was unavailable");
        long[] restarted = stored.clone(); restarted[48] = 3; restarted[49] = 19;
        require("HIT_PREVIOUS_PROCESS".equals(parse(restarted).contextCacheStatus), "Cross-process disk hit was lost");
        restarted[48] = 2; rejected(restarted);
        for (int missing = 20; missing <= 21; missing++) {
            long[] absent = stored.clone(); absent[49] = missing; absent[52] = 0;
            require(parse(absent).contextCacheStatus.endsWith("FINGERPRINT_UNAVAILABLE"), "Missing partition identity");
        }
        long[] badState = stored.clone(); badState[49] = 22; rejected(badState);
        long[] badMode = stored.clone(); badMode[55] = 3; rejected(badMode);
        long[] tooLargeContext = stored.clone(); tooLargeContext[52] = 536870913; rejected(tooLargeContext);
        long[] badFallback = stored.clone(); badFallback[54] = 2; rejected(badFallback);
        for (int index = 50; index <= 53; index++) {
            long[] negative = stored.clone(); negative[index] = -1; rejected(negative);
        }
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        verify();
        System.out.println("Native evidence v2-v6 availability, context preparation, validation reasons and malformed-input controls passed");
    }
}
