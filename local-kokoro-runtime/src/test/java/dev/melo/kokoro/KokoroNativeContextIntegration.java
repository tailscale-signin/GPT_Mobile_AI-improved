package dev.melo.kokoro;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Actual JNI/model/EPContext compile, publication, fresh-session load and corrupt-cache recovery. */
public final class KokoroNativeContextIntegration {
    private static final String IDENTITY = "4dbe0c0b4e8a0c724ff164a986e3e94b8cd625751c49b31fb17253d9ff45bb68";
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static TimedKokoro open(Path pack, Path libraries, Path cache) {
        return new TimedKokoro(pack.resolve("model.int8.onnx").toString(), pack.resolve("voices.bin").toString(),
                pack.resolve("tokens.txt").toString(), pack.resolve("lexicon-us-en.txt").toString(), 2,
                "qnn-htp", libraries.toString(), libraries.resolve("context-profile").toString(),
                cache.toString(), IDENTITY, "balanced");
    }

    // The pinned model contains unseeded RandomUniformLike/RandomNormalLike vocoder
    // nodes. Preserve exact timing and compare the actual PCM using the app's
    // cpu-reference-duration-timing-signal-v2 limits; bitwise equality is not valid.
    // This is signal consistency, not perceptual or samplewise waveform equivalence.
    static SignalComparison compareSignal(float[] reference, float[] candidate) {
        require(reference.length > 0 && reference.length == candidate.length, "Context PCM sample count changed");
        double firstSquares = 0, secondSquares = 0, errorSquares = 0;
        double firstPeak = 0, secondPeak = 0, maximumError = 0;
        long firstActive = 0, secondActive = 0;
        for (int i = 0; i < reference.length; ++i) {
            double first = reference[i], second = candidate[i];
            require(Double.isFinite(first) && Double.isFinite(second), "Context PCM is nonfinite");
            require(Math.abs(first) < 1 && Math.abs(second) < 1, "Context PCM is clipped");
            firstSquares += first * first;
            secondSquares += second * second;
            errorSquares += (first - second) * (first - second);
            firstPeak = Math.max(firstPeak, Math.abs(first));
            secondPeak = Math.max(secondPeak, Math.abs(second));
            maximumError = Math.max(maximumError, Math.abs(first - second));
            if (Math.abs(first) >= 1e-5) firstActive++;
            if (Math.abs(second) >= 1e-5) secondActive++;
        }
        double firstRms = Math.sqrt(firstSquares / reference.length);
        double secondRms = Math.sqrt(secondSquares / reference.length);
        require(firstRms >= 1e-6 && secondRms >= 1e-6 && firstActive > 0 && secondActive > 0,
                "Context PCM is silent or below the reference measurement floor");
        double rmsRatio = secondRms / firstRms;
        double activityDifference = Math.abs(firstActive - secondActive) / (double) reference.length;
        require(rmsRatio >= .5 && rmsRatio <= 2.0 && secondPeak <= Math.max(firstPeak * 2.0, firstPeak + .1),
                "Context PCM signal level differs from the reference");
        require(activityDifference <= .15, "Context PCM activity differs from the reference");
        return new SignalComparison(rmsRatio, secondPeak / firstPeak, activityDifference,
                maximumError, Math.sqrt(errorSquares / reference.length) / firstRms);
    }

    static final class SignalComparison {
        final double rmsRatio, peakRatio, activityDifference, maximumSampleDifference, normalizedRmsError;
        SignalComparison(double rmsRatio, double peakRatio, double activityDifference, double maximumSampleDifference, double normalizedRmsError) {
            this.rmsRatio = rmsRatio; this.peakRatio = peakRatio; this.activityDifference = activityDifference;
            this.maximumSampleDifference = maximumSampleDifference; this.normalizedRmsError = normalizedRmsError;
        }
        String json() {
            return "{\"status\":\"CONSISTENT\",\"scope\":\"SIGNAL_CONSISTENCY_ONLY_NOT_WAVEFORM_EQUIVALENCE\"," +
                    "\"protocol\":\"cpu-reference-duration-timing-signal-v2\",\"bitwiseEqualityRequired\":false," +
                    "\"rmsRatio\":" + rmsRatio + ",\"peakRatio\":" + peakRatio +
                    ",\"activityFractionDifference\":" + activityDifference +
                    ",\"maximumSampleDifference\":" + maximumSampleDifference +
                    ",\"normalizedRmsError\":" + normalizedRmsError + "}";
        }
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 3, "Pass model pack, provider directory and receipt path");
        Path pack = Path.of(args[0]), libraries = Path.of(args[1]);
        Path cache = Files.createDirectory(libraries.resolve("compiled-contexts"));
        String phrase = "melo enjoys a clear voice.";
        TimedKokoro.Audio original;
        TimedKokoro.Evidence compiled;
        try (TimedKokoro model = open(pack, libraries, cache)) {
            require("NONE".equals(model.evidence().graphPreparationSource), "Engine open claimed an exact graph was prepared");
            try {
                model.generate(phrase, 1000, 1f);
                throw new AssertionError("Invalid voice produced audio");
            } catch (TimedKokoro.Failure expected) {
                require("FRONTEND_INVALID_VOICE".equals(expected.code), "Invalid voice changed failure identity");
            }
            try (var files = Files.list(cache)) {
                require(files.noneMatch(p -> p.getFileName().toString().matches("[0-9a-f]{64}")),
                        "Unexecuted graph was published as a reusable context");
            }
            original = model.generate(phrase, 0, 1f);
            compiled = model.evidence();
            require(compiled.validatedAudio && compiled.acceleratorKernels > 0 &&
                    "COMPILED".equals(compiled.graphPreparationSource) && "STORED".equals(compiled.contextCacheStatus) &&
                    compiled.contextBytes > 0, "Actual HTP context was not generated and atomically stored after valid PCM");
            model.generate(phrase, 0, 1f);
            TimedKokoro.Evidence resident = model.evidence();
            require("RESIDENT".equals(resident.graphPreparationSource) && "NOT_ACCESSED".equals(resident.contextCacheStatus) &&
                    resident.cacheHit && resident.profileReused, "Resident graph was confused with a disk read or fresh execution profile");
        }
        TimedKokoro.Evidence loaded;
        SignalComparison loadedSignal;
        try (TimedKokoro model = open(pack, libraries, cache)) {
            TimedKokoro.Audio audio = model.generate(phrase, 0, 1f);
            loaded = model.evidence();
            require("DISK_CONTEXT".equals(loaded.graphPreparationSource) && "HIT".equals(loaded.contextCacheStatus) &&
                    !loaded.cacheHit && !loaded.profileReused && loaded.profileAvailable && loaded.acceleratorKernels > 0 && loaded.validatedAudio,
                    "A fresh model failed to load, execute and qualify the actual cached context");
            require(Arrays.equals(original.durations, audio.durations) && Arrays.equals(original.wordTokens, audio.wordTokens) &&
                    original.samples.length == audio.samples.length, "Cached context changed timing or frontend outputs");
            loadedSignal = compareSignal(original.samples, audio.samples);
        }
        Path context;
        try (var entries = Files.list(cache)) {
            Path entry = entries.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}")).findFirst().orElseThrow();
            context = entry.resolve("context.onnx");
        }
        Files.write(context, new byte[] {0}, StandardOpenOption.TRUNCATE_EXISTING);
        TimedKokoro.Evidence recovered;
        SignalComparison recoveredSignal;
        try (TimedKokoro model = open(pack, libraries, cache)) {
            TimedKokoro.Audio audio = model.generate(phrase, 0, 1f);
            recovered = model.evidence();
            require("COMPILED".equals(recovered.graphPreparationSource) && "STORED".equals(recovered.contextCacheStatus) &&
                    Boolean.TRUE.equals(recovered.contextFallback) && recovered.validatedAudio && recovered.acceleratorKernels > 0,
                    "Corrupt compiled context did not fall back to the original graph and repair its cache");
            require(Arrays.equals(original.durations, audio.durations) && Arrays.equals(original.wordTokens, audio.wordTokens),
                    "Corrupt-cache recovery changed timing or frontend outputs");
            recoveredSignal = compareSignal(original.samples, audio.samples);
        }
        String receipt = "{\"scope\":\"actual_native_htp_context_emulator\",\"physical_device_qualified\":false," +
                "\"compiledContextBytes\":" + compiled.contextBytes + ",\"compiledQnnKernels\":" + compiled.acceleratorKernels +
                ",\"loadedQnnKernels\":" + loaded.acceleratorKernels + ",\"loadedCpuKernels\":" + loaded.cpuKernels +
                ",\"contextLoadMs\":" + loaded.contextLoadMs + ",\"corruptionRecovered\":" + recovered.contextFallback +
                ",\"loadedPcm\":" + loadedSignal.json() + ",\"recoveredPcm\":" + recoveredSignal.json() + "}\n";
        Files.writeString(Path.of(args[2]), receipt);
        System.out.print(receipt);
        try (var files = Files.list(libraries)) {
            require(files.noneMatch(p -> p.getFileName().toString().startsWith("context-profile")), "Context session left an unfinished ORT profile");
        }
    }
}
