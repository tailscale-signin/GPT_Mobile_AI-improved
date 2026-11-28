package dev.melo.kokoro;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;

/** Executes the production JNI bridge with verified public CPU model/audio fixtures. */
public final class StreamingAsrNativeIntegration {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static float[] wave(Path path) throws Exception {
        try (var source = AudioSystem.getAudioInputStream(path.toFile())) {
            var format = source.getFormat();
            require(format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED && format.getChannels() == 1
                && format.getSampleSizeInBits() == 16 && format.getSampleRate() == 16000 && !format.isBigEndian(),
                "The pinned fixture must be 16 kHz mono signed 16-bit PCM");
            var bytes = source.readAllBytes();
            require(bytes.length > 0 && bytes.length % 2 == 0, "Invalid PCM fixture");
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            var samples = new float[bytes.length / 2];
            for (int i = 0; i < samples.length; ++i) samples[i] = buffer.getShort() / 32768f;
            return samples;
        }
    }

    private static StreamingAsr.Result run(StreamingAsr nativeModel, float[] audio, int chunk, int[] updates) {
        String previous = "";
        for (int offset = 0; offset < audio.length; offset += chunk) {
            int end = Math.min(offset + chunk, audio.length);
            var result = nativeModel.accept(Arrays.copyOfRange(audio, offset, end));
            require(result.acceptedAudioFrames == end, "Native input clock must equal accepted real PCM");
            if (!result.text.equals(previous)) updates[0]++;
            previous = result.text;
        }
        return nativeModel.finish();
    }

    private static void timings(StreamingAsr.Result result, int realFrames) {
        require(result.acceptedAudioFrames == realFrames, "Flush padding leaked into captured audio frames");
        require(result.tokens.length > 0 && result.tokens.length == result.timestampsSeconds.length,
            "Expected native token timestamps for the recognized fixture");
        float previous = -1;
        for (float start : result.timestampsSeconds) {
            require(Float.isFinite(start) && start >= previous && start >= 0, "Invalid or decreasing native token onset");
            require(Math.round((start + result.segmentStartSeconds) * 16000.0) <= realFrames,
                "Token onset cannot be presented outside the actual input timeline");
            previous = start;
        }
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 11, "Expected pack, encoder, decoder, joiner, tokens, type, language, chunk, wav, prefix, receipt");
        Path pack = Path.of(args[0]), receipt = Path.of(args[10]);
        Path work = receipt.toAbsolutePath().getParent().resolve("native-work");
        Files.createDirectories(work);
        float[] audio = wave(pack.resolve(args[8]));
        StreamingAsr model = new StreamingAsr(pack.resolve(args[1]).toString(), pack.resolve(args[2]).toString(),
            pack.resolve(args[3]).toString(), pack.resolve(args[4]).toString(), args[5], "cpu", args[6],
            work.toString(), work.toString(), 2, Integer.parseInt(args[7]), .65f);
        StreamingAsr.Result streamed;
        StreamingAsr.Evidence execution;
        int[] updates = {0};
        try {
            require(!model.evidence().executionVerified, "Model loading cannot qualify execution");
            streamed = run(model, audio, 640, updates);
            timings(streamed, audio.length);
            require(streamed.text.toLowerCase(Locale.ROOT).startsWith(args[9].toLowerCase(Locale.ROOT)),
                "The real fixture did not produce its expected phrase");
            require(updates[0] > 1, "Expected incremental recognition before finalization");
            execution = model.evidence();
            require(execution.executionVerified && execution.actualProvider.equals("cpu") && execution.completedChunks > 0,
                "CPU execution evidence is missing or mislabeled");
            if (args[5].equals("nemo_transducer")) {
                for (long count : execution.completedComponentRuns) require(count > 0, "Missing completed Nemotron component runs");
            }
            require(Arrays.stream(execution.sampledAcceleratorKernels).sum() == 0, "CPU test cannot claim GPU kernels");
            require(model.finish().text.equals(streamed.text), "Finalization must be idempotent");

            model.reset(args[6]);
            require(!model.evidence().executionVerified && model.evidence().acceptedAudioFrames == 0,
                "A new capture cannot inherit the preceding capture's execution status");
            var batched = run(model, audio, 32000, new int[]{0});
            timings(batched, audio.length);
            require(batched.text.equals(streamed.text) && Arrays.equals(batched.tokens, streamed.tokens)
                && Arrays.equals(batched.timestampsSeconds, streamed.timestampsSeconds),
                "Capture chunk size changed recognized text or native token timing");

            model.reset(args[6]);
            model.cancel();
            try {
                model.accept(new float[640]);
                throw new AssertionError("Cancellation did not reach native inference");
            } catch (StreamingAsr.Failure failure) {
                require(failure.code.equals("ASR_CANCELLED"), "Cancellation was mislabeled as another runtime failure");
            }
            require(!model.evidence().executionVerified, "A canceled stream cannot qualify execution");
            model.reset(args[6]);
            var empty = model.finish();
            require(empty.text.isEmpty() && empty.acceptedAudioFrames == 0 && !model.evidence().executionVerified,
                "Empty finalization fabricated captured speech or an execution result");

            // Exercise cancellation/close ownership while another thread is
            // entering/running JNI. Both native holders and the Java monitor
            // must retire within the bound; no pointer is freed mid-call.
            model.reset(args[6]);
            var entered = new CountDownLatch(1);
            var workerFailure = new AtomicReference<Throwable>();
            Thread worker = new Thread(() -> {
                entered.countDown();
                try {
                    for (int i = 0; i < 20; ++i) model.accept(Arrays.copyOf(audio, 32000));
                } catch (Throwable failure) { workerFailure.set(failure); }
            });
            worker.setDaemon(true);
            worker.start();
            require(entered.await(2, TimeUnit.SECONDS), "Capture worker did not start");
            Thread closing = new Thread(model::close);
            closing.setDaemon(true);
            closing.start();
            closing.join(10000);
            worker.join(10000);
            require(!closing.isAlive() && !worker.isAlive(), "Concurrent native cancellation/close did not retire");
            Throwable stopped = workerFailure.get();
            require(stopped instanceof IllegalStateException ||
                (stopped instanceof StreamingAsr.Failure && ((StreamingAsr.Failure) stopped).code.equals("ASR_CANCELLED")),
                "Concurrent close did not produce a controlled closed/canceled result");
        } finally {
            model.close();
            model.close();
        }
        String record = "{\n  \"scope\": \"CPU_HOST_PRODUCTION_JNI_REGRESSION_ONLY\",\n"
            + "  \"physicalDeviceQualified\": false,\n  \"gpuQualified\": false,\n  \"npuQualified\": false,\n"
            + "  \"runtime\": " + json(StreamingAsr.RUNTIME_ID) + ",\n  \"text\": " + json(streamed.text)
            + ",\n  \"acceptedRealFrames\": " + streamed.acceptedAudioFrames
            + ",\n  \"partialUpdates\": " + updates[0] + ",\n  \"tokenOnsetsSeconds\": " + Arrays.toString(streamed.timestampsSeconds)
            + ",\n  \"timingProvenance\": \"TOKEN_EMISSION\",\n  \"execution\": " + json(execution.toString())
            + ",\n  \"chunkInvariant\": true,\n  \"residentReuse\": true,\n  \"cancellationAndClose\": true\n}\n";
        Files.writeString(receipt, record);
        System.out.println("Production JNI CPU lifecycle passed: " + args[5] + "; tokens=" + streamed.tokens.length);
    }
}
