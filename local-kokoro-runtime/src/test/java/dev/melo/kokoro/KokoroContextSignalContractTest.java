package dev.melo.kokoro;

import java.util.Arrays;

/** Numerical guards only; the separate required context job executes JNI and the real model. */
public final class KokoroContextSignalContractTest {
    private static void rejected(float[] reference, float[] candidate) {
        try {
            KokoroNativeContextIntegration.compareSignal(reference, candidate);
        } catch (AssertionError expected) { return; }
        throw new AssertionError("Invalid context PCM comparison passed");
    }
    private static float[] signal() {
        float[] samples = new float[100];
        Arrays.fill(samples, .1f);
        return samples;
    }
    public static void main(String[] args) {
        float[] reference = signal();
        KokoroNativeContextIntegration.SignalComparison same = KokoroNativeContextIntegration.compareSignal(reference, reference);
        if (same.maximumSampleDifference != 0 || same.normalizedRmsError != 0) throw new AssertionError("Identical PCM error changed");
        float[] noisy = signal();
        for (int i = 0; i < noisy.length; ++i) noisy[i] += i % 2 == 0 ? .005f : -.005f;
        KokoroNativeContextIntegration.SignalComparison noise = KokoroNativeContextIntegration.compareSignal(reference, noisy);
        if (noise.maximumSampleDifference <= 0 || noise.normalizedRmsError <= 0) throw new AssertionError("Actual waveform differences were omitted");
        rejected(new float[0], new float[0]);
        rejected(reference, new float[99]);
        rejected(reference, new float[100]);
        rejected(new float[100], reference);
        for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, 1f, -1f}) {
            float[] corrupt = signal(); corrupt[51] = value;
            rejected(reference, corrupt);
            rejected(corrupt, reference);
        }
        float[] loud = signal(); Arrays.fill(loud, .4f); rejected(reference, loud);
        float[] inactive = signal(); Arrays.fill(inactive, 0, 50, 0f);
        Arrays.fill(inactive, 50, 100, (float) Math.sqrt(.02));
        rejected(reference, inactive);
        System.out.println("Context PCM finite, clipping, silence, level, activity and measured-error controls passed");
    }
}
