package dev.melo.kokoro;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Actual JNI/model/EPContext compile, publication, fresh-session load and corrupt-cache recovery. */
public final class KokoroNativeContextIntegration {
    private static final String IDENTITY = "4dbe0c0b4e8a0c724ff164a986e3e94b8cd625751c49b31fb17253d9ff45bb68";
    private static native void armPreparation();
    private static native boolean awaitPreparation();
    private static native void releasePreparation();
    private static native int contextCompilations();
    private static native int contextFallbacks();
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static TimedKokoro open(Path pack, Path libraries, Path cache) {
        return new TimedKokoro(pack.resolve("model.int8.onnx").toString(), pack.resolve("voices.bin").toString(),
                pack.resolve("tokens.txt").toString(), pack.resolve("lexicon-us-en.txt").toString(), 2,
                "qnn-htp", libraries.toString(), libraries.resolve("context-profile").toString(),
                cache == null ? "" : cache.toString(), cache == null ? "" : IDENTITY, "balanced");
    }

    private static void observe(String phase, TimedKokoro.Evidence value) {
        System.out.println("{\"phase\":\"" + phase + "\",\"graphPreparationSource\":\"" + value.graphPreparationSource +
                "\",\"contextCacheStatus\":\"" + value.contextCacheStatus + "\",\"contextFallback\":" + value.contextFallback +
                ",\"contextBytes\":" + value.contextBytes + ",\"contextLoadMs\":" + value.contextLoadMs +
                ",\"contextStoreMs\":" + value.contextStoreMs + ",\"profileAvailable\":" + value.profileAvailable +
                ",\"currentPrepareMs\":" + value.currentPrepareMs + ",\"graphPreparedThisCall\":" + value.graphPreparedThisCall +
                ",\"acceleratorKernels\":" + value.acceleratorKernels + ",\"cpuKernels\":" + value.cpuKernels +
                ",\"cacheHit\":" + value.cacheHit + ",\"profileReused\":" + value.profileReused +
                ",\"validatedAudio\":" + value.validatedAudio + "}");
    }

    private static int[] verifyPreparationCancellation(TimedKokoro model, Path cache, String phrase) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        armPreparation();
        var pending = worker.submit(() -> model.generate(phrase, 0, 1f));
        try {
            require(awaitPreparation(), "Actual CPU source save did not reach the preparation barrier");
            TimedKokoro.Progress preparing = model.progress();
            require(preparing != null && preparing.active && "preparing".equals(preparing.stage),
                    "Context preparation lost its active native progress");
            model.cancel();
            releasePreparation();
            try {
                pending.get(120, TimeUnit.SECONDS);
                throw new AssertionError("Cancelled context preparation returned audio");
            } catch (ExecutionException expected) {
                require(expected.getCause() instanceof CancellationException, "Preparation cancellation identity changed");
            }
            TimedKokoro.Evidence interrupted = model.evidence();
            observe("preparation_cancelled", interrupted);
            require(!interrupted.validatedAudio && !interrupted.profileAvailable && !interrupted.graphPreparedThisCall &&
                    interrupted.currentPrepareMs > 0, "Cancelled preparation claimed a completed graph or lost its measured cost");
            int compilations = contextCompilations(), fallbacks = contextFallbacks();
            require(compilations == 0 && fallbacks == 0,
                    "Cancelled preparation continued into QNN compilation or optional-cache fallback");
            TimedKokoro.Progress stopped = model.progress();
            require(stopped != null && !stopped.active && stopped.cancellationRequested && !"ready".equals(stopped.stage),
                    "Cancelled preparation published Ready or left an active owner");
            try (var files = Files.list(cache)) {
                require(files.allMatch(p -> p.getFileName().toString().equals(".lock")),
                        "Cancelled preparation retained an intermediate or published a context");
            }
            model.resetCancellation();
            return new int[] {compilations, fallbacks};
        } finally {
            releasePreparation();
            worker.shutdownNow();
            require(worker.awaitTermination(120, TimeUnit.SECONDS), "Context preparation worker did not retire");
        }
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
        TimedKokoro.Audio uncachedAudio;
        TimedKokoro.Evidence uncached;
        try (TimedKokoro model = open(pack, libraries, null)) {
            uncachedAudio = model.generate(phrase, 0, 1f);
            uncached = model.evidence();
            observe("uncached", uncached);
            require(uncached.validatedAudio && uncached.profileAvailable && uncached.acceleratorKernels > 0 &&
                    "DISABLED".equals(uncached.contextCacheStatus), "Original-model QNN reference did not execute uncached");
        }
        TimedKokoro.Audio original;
        TimedKokoro.Evidence compiled;
        SignalComparison originalSignal;
        int[] preparationAttempts;
        try (TimedKokoro model = open(pack, libraries, cache)) {
            require("NONE".equals(model.evidence().graphPreparationSource), "Engine open claimed an exact graph was prepared");
            preparationAttempts = verifyPreparationCancellation(model, cache, phrase);
            try {
                model.generate(phrase, 1000, 1f);
                throw new AssertionError("Invalid voice produced audio");
            } catch (TimedKokoro.Failure expected) {
                require("FRONTEND_INVALID_VOICE".equals(expected.code), "Invalid voice changed failure identity");
                observe("invalid_voice", model.evidence());
            }
            try (var files = Files.list(cache)) {
                require(files.noneMatch(p -> p.getFileName().toString().matches("[0-9a-f]{64}")),
                        "Unexecuted graph was published as a reusable context");
            }
            original = model.generate(phrase, 0, 1f);
            compiled = model.evidence();
            observe("compiled", compiled);
            require(compiled.validatedAudio && compiled.acceleratorKernels > 0 &&
                    "COMPILED".equals(compiled.graphPreparationSource) && "STORED".equals(compiled.contextCacheStatus) &&
                    compiled.contextBytes > 0, "Actual HTP context was not generated and atomically stored after valid PCM");
            require(Arrays.equals(uncachedAudio.durations, original.durations) &&
                    Arrays.equals(uncachedAudio.wordTokens, original.wordTokens), "Context preparation changed original-model timing");
            originalSignal = compareSignal(uncachedAudio.samples, original.samples);
            require(compiled.acceleratorKernels == uncached.acceleratorKernels && compiled.cpuKernels == uncached.cpuKernels,
                    "Context preparation changed the measured full-model kernel counts");
            model.generate(phrase, 0, 1f);
            TimedKokoro.Evidence resident = model.evidence();
            observe("resident", resident);
            require("RESIDENT".equals(resident.graphPreparationSource) && "NOT_ACCESSED".equals(resident.contextCacheStatus) &&
                    resident.cacheHit && resident.profileReused, "Resident graph was confused with a disk read or fresh execution profile");
        }
        TimedKokoro.Evidence loaded;
        SignalComparison loadedSignal;
        try (TimedKokoro model = open(pack, libraries, cache)) {
            TimedKokoro.Audio audio = model.generate(phrase, 0, 1f);
            loaded = model.evidence();
            observe("loaded", loaded);
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
            observe("recovered", recovered);
            require("COMPILED".equals(recovered.graphPreparationSource) && "STORED".equals(recovered.contextCacheStatus) &&
                    Boolean.TRUE.equals(recovered.contextFallback) && recovered.validatedAudio && recovered.acceleratorKernels > 0,
                    "Corrupt compiled context did not fall back to the original graph and repair its cache");
            require(Arrays.equals(original.durations, audio.durations) && Arrays.equals(original.wordTokens, audio.wordTokens),
                    "Corrupt-cache recovery changed timing or frontend outputs");
            recoveredSignal = compareSignal(original.samples, audio.samples);
        }
        long unfinishedProfiles, retainedSources;
        try (var files = Files.list(libraries)) {
            unfinishedProfiles = files.filter(p -> p.getFileName().toString().startsWith("context-profile")).count();
            require(unfinishedProfiles == 0, "Context session left an unfinished ORT profile");
        }
        try (var files = Files.walk(cache)) {
            retainedSources = files.filter(p -> p.getFileName().toString().equals(".source.onnx") ||
                    p.getFileName().toString().startsWith("pending-")).count();
            require(retainedSources == 0, "Context preparation retained transient artifacts");
        }
        String receipt = "{\"scope\":\"actual_native_htp_context_emulator\",\"physical_device_qualified\":false," +
                "\"preparationCancellationPassed\":true,\"preparationCancellationCompileAttempts\":" + preparationAttempts[0] +
                ",\"preparationCancellationFallbackAttempts\":" + preparationAttempts[1] +
                ",\"unfinishedProfiles\":" + unfinishedProfiles + ",\"retainedSourceSnapshots\":" + retainedSources + "," +
                "\"uncachedQnnKernels\":" + uncached.acceleratorKernels + ",\"uncachedCpuKernels\":" + uncached.cpuKernels + "," +
                "\"compiledContextBytes\":" + compiled.contextBytes + ",\"compiledQnnKernels\":" + compiled.acceleratorKernels +
                ",\"compiledCpuKernels\":" + compiled.cpuKernels + ",\"compiledPrepareMs\":" + compiled.currentPrepareMs +
                ",\"loadedQnnKernels\":" + loaded.acceleratorKernels + ",\"loadedCpuKernels\":" + loaded.cpuKernels +
                ",\"contextLoadMs\":" + loaded.contextLoadMs + ",\"corruptionRecovered\":" + recovered.contextFallback +
                ",\"originalModelPcm\":" + originalSignal.json() +
                ",\"loadedPcm\":" + loadedSignal.json() + ",\"recoveredPcm\":" + recoveredSignal.json() + "}\n";
        Files.writeString(Path.of(args[2]), receipt);
        System.out.print(receipt);
    }
}
