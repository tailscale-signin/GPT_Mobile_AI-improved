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
            TimedKokoro.Audio audio = model.generate(phrase, 0, 1f);
            validate(audio, encoded);
            require(model.acceleratedNodes() == 0, "CPU cannot claim acceleration");
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
