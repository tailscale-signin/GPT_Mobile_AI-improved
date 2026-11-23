package dev.melo.kokoro;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Explicit host integration gate: uses the published model and real JNI/ORT, without Android stubs. */
public final class KokoroNativeIntegration {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static String file(File pack, String name) { return new File(pack, name).getAbsolutePath(); }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Pass the verified Kokoro pack directory");
        File pack = new File(args[0]);
        try (KokoroFrontend frontend = new KokoroFrontend(file(pack, "tokens.txt"), file(pack, "lexicon-us-en.txt"));
             TimedKokoro model = new TimedKokoro(file(pack, "model.int8.onnx"), file(pack, "voices.bin"),
                     file(pack, "tokens.txt"), file(pack, "lexicon-us-en.txt"), 2)) {
            String phrase = "melo enjoys a clear and natural voice.";
            KokoroFrontend.EncodedPhrase encoded = frontend.encode(phrase);
            KokoroFrontend.EncodedPhrase again = frontend.encode(phrase);
            require(Arrays.equals(encoded.tokenIds, again.tokenIds), "Frontend cache changed pronunciation");
            require(frontend.encode("a ".repeat(64)).tokenIds.length > 0, "Exact 128-byte frontend boundary was rejected");
            expectFrontendFailure(frontend, "a ".repeat(64) + "a", "FRONTEND_INPUT_TOO_LONG", 129);
            expectFrontendFailure(frontend, "a ".repeat(134), "FRONTEND_INPUT_TOO_LONG", 268);
            expectFrontendFailure(frontend, "", "FRONTEND_INPUT_EMPTY", 0);
            expectFrontendFailure(frontend, "hello/world", "FRONTEND_INVALID_SEPARATOR", 11);
            expectFrontendFailure(frontend, "...", "FRONTEND_NO_WORDS", 3);
            TimedKokoro.Audio audio = model.generate(phrase, 0, 1f);
            validate(audio, encoded);
            require(model.acceleratedNodes() == 0, "CPU cannot claim acceleration");
            TimedKokoro.Evidence evidence = model.evidence();
            require(evidence.validatedAudio && evidence.tokenCount == encoded.tokenIds.length, "JNI evidence does not match audio");
            require(evidence.acceleratorKernels == 0 && evidence.deviceType == -1, "CPU evidence invented accelerator execution");
            require(evidence.prepareMs >= 0 && evidence.synthesisMs >= 0, "Invalid native stage measurements");
            require(!evidence.profileAvailable && evidence.profileId == 0 && !evidence.profileReused, "Unprofiled CPU claimed kernel measurements");
            require(evidence.cacheHit && !evidence.graphPreparedThisCall && evidence.currentPrepareMs == 0, "CPU reported cached compilation as current work");
            require(evidence.cachedShapeCount == 1 && evidence.cacheLimit == 1 && evidence.cacheEstimatedBytes > 0, "CPU cache was not bounded");
            require(evidence.audioRms > 0 && Double.isFinite(evidence.audioRms) && evidence.audioPeak > 0 &&
                    evidence.nonSilentSamples > 0 && evidence.clippedSamples >= 0, "Missing audio validation metrics");
            require(evidence.discoverySummary.matches("[A-Za-z0-9_.=,:;-]{1,512}"), "Unsafe discovery summary");
            try {
                model.generate(phrase, 0, 0f);
                throw new AssertionError("Invalid speed was accepted");
            } catch (TimedKokoro.Failure expected) {
                require("frontend".equals(expected.stage) && "cpu".equals(expected.provider), "Native failure provenance was lost");
                require("FRONTEND_INVALID_SPEED".equals(expected.code) && expected.tokenCount == 0 && expected.nativeCode == -1, "Invalid speed was not rejected before inference");
                require(expected.inputBytes == phrase.length() && expected.inputByteLimit == 128 && expected.tokenLimit == 511, "Native input limits were lost");
                require(expected.currentEvidence == null && "stage=frontend".equals(expected.discoverySummary), "Frontend failure reused previous inference evidence");
                require(!expected.getMessage().contains(phrase), "Native failure exposed phrase text");
            }
            try {
                model.generate("a ".repeat(134), 0, 1f);
                throw new AssertionError("Overlong phrase was accepted");
            } catch (TimedKokoro.Failure expected) {
                require("FRONTEND_INPUT_TOO_LONG".equals(expected.code) && expected.inputBytes == 268 &&
                        expected.inputByteLimit == 128 && "cpu".equals(expected.provider), "Overlong failure provenance was lost");
                require(!model.evidence().validatedAudio && !model.evidence().profileAvailable, "Failed frontend reused a successful audio receipt");
            }
            model.cancel();
            try {
                model.generate(phrase, 0, 1f);
                throw new AssertionError("Cancelled model generated audio");
            } catch (java.util.concurrent.CancellationException expected) { }
            model.resetCancellation();
            validate(model.generate(phrase, 0, 1f), encoded);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                CountDownLatch entered = new CountDownLatch(1);
                Future<TimedKokoro.Audio> pending = worker.submit(() -> {
                    entered.countDown();
                    return model.generate("this longer spoken passage checks that an interruption stops active synthesis before the next reply begins.", 0, .5f);
                });
                require(entered.await(2, TimeUnit.SECONDS), "Synthesis worker never started");
                Thread.sleep(10);
                long start = System.nanoTime();
                model.cancel();
                long cancellationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                try {
                    pending.get(10, TimeUnit.SECONDS);
                    throw new AssertionError("In-flight synthesis ignored cancellation");
                } catch (ExecutionException expected) {
                    require(expected.getCause() instanceof java.util.concurrent.CancellationException, "Wrong native cancellation error");
                }
                model.resetCancellation();
                validate(model.generate(phrase, 0, 1.25f), encoded);
                System.out.println("Native CPU audio/timing, frontend parity, reset and in-flight cancellation passed; cancel call " + cancellationMs + " ms on this host");
            } finally {
                worker.shutdownNow();
                require(worker.awaitTermination(10, TimeUnit.SECONDS), "Synthesis worker did not terminate");
            }
        }
    }

    private static void expectFrontendFailure(KokoroFrontend frontend, String input, String code, int bytes) {
        try {
            frontend.encode(input);
            throw new AssertionError("Invalid frontend input was accepted");
        } catch (TimedKokoro.Failure failure) {
            require(code.equals(failure.code) && failure.inputBytes == bytes && failure.inputByteLimit == 128,
                    "Frontend did not retain typed failure and input bounds");
            require("frontend".equals(failure.stage) && "frontend".equals(failure.provider), "Wrong frontend failure origin");
            require(failure.currentEvidence == null, "Frontend-only failure invented inference evidence");
            require(!failure.getMessage().contains("hello/world"), "Frontend failure exposed text");
        }
    }

    private static void validate(TimedKokoro.Audio audio, KokoroFrontend.EncodedPhrase encoded) {
        require(audio.samples.length > 0 && audio.samples.length <= 30 * TimedKokoro.SAMPLE_RATE, "Invalid audio size");
        require(audio.durations.length == encoded.tokenIds.length, "Duration count differs from frontend tokens");
        require(Arrays.equals(audio.wordTokens, encoded.wordTokens), "Gateway frontend and local captions disagree");
        long units = 0;
        for (long duration : audio.durations) { require(duration > 0, "Nonpositive duration"); units += duration; }
        require(units * 600 == audio.samples.length, "Audio and captions differ");
        for (float sample : audio.samples) require(Float.isFinite(sample), "Nonfinite audio");
    }
}
