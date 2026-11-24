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
        long[] overflow = tooLong.clone(); overflow[33] = 7; overflow[46] = 1; overflow[43] = 0; overflow[45] = Long.MAX_VALUE;
        require(parse(overflow).durationSum == null && Boolean.TRUE.equals(parse(overflow).durationSumOverflow), "Duration overflow became a valid sum");
        rejected(Arrays.copyOf(complete, 47));
        long[] unknownVersion = complete.clone(); unknownVersion[0] = 6; rejected(unknownVersion);
        for (int index : new int[] {11, 12, 14, 23, 24, 34, 42, 46, 47}) {
            long[] badFlag = complete.clone(); badFlag[index] = 2; rejected(badFlag);
        }
        long[] badReason = complete.clone(); badReason[33] = 12; rejected(badReason);
        long[] falseMeasurement = empty(); falseMeasurement[11] = 1; rejected(falseMeasurement);
        long[] falseSilence = empty(); falseSilence[33] = 10; rejected(falseSilence);
        long[] badTiming = complete.clone(); badTiming[43] = Long.MAX_VALUE; rejected(badTiming);
        long[] wrongDtype = complete.clone(); wrongDtype[40] = 1; rejected(wrongDtype);
        long[] badCache = complete.clone(); badCache[16] = 4; rejected(badCache);
        long[] badCount = complete.clone(); badCount[39] = 512; rejected(badCount);
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        verify();
        System.out.println("Native evidence v2-v5 availability, validation reasons and malformed-input controls passed");
    }
}
