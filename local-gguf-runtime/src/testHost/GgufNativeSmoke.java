import dev.melo.gguf.GgufNative;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Uses real JNI and CPU inference, with synthetic text and no application secrets. */
public final class GgufNativeSmoke {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(GgufNative.buildInfo().contains("operationControl=1"), "Packaged build identity missing operation control");
        GgufNative.validateChatTemplate("{% for m in messages %}{{ m.role|upper }}: {{ m.content }}{% endfor %}".getBytes(StandardCharsets.UTF_8));
        try {
            GgufNative.validateChatTemplate("{% if true %}unclosed".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("Malformed Jinja passed metadata-only preflight");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("syntax error"), "Wrong template failure");
        }
        boolean gpu = GgufNative.supportsGpuOffload();
        if (gpu) throw new AssertionError("CPU host smoke unexpectedly enabled GPU offload");
        try {
            GgufNative.load(args[0].getBytes(StandardCharsets.UTF_8), 512, 2, 1);
            throw new AssertionError("GPU request silently fell back to the CPU-only host runtime");
        } catch (IllegalStateException expected) {
            if (!(expected instanceof dev.melo.gguf.GgufNativeException) || !((dev.melo.gguf.GgufNativeException) expected).category.equals("DEVICE_UNAVAILABLE")) throw expected;
        }
        long engine = GgufNative.load(args[0].getBytes(StandardCharsets.UTF_8), 512, 2, 0);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                var output = new java.io.ByteArrayOutputStream();
                long[] usage = GgufNative.generate(engine, new String[]{"user"}, new byte[][]{"Once upon a time".getBytes(StandardCharsets.UTF_8)}, 16, 1, 1f, 0f, bytes -> {
                    output.writeBytes(bytes); return true;
                });
                check(usage.length == 7 && usage[0] > 0 && usage[1] > 0 && usage[1] <= 16, "Invalid native token receipt");
                check(usage[5] >= 0 && usage[6] > 0 && usage[5] + usage[6] == usage[0], "Invalid prefix reuse accounting");
                if (attempt > 0) check(usage[5] > 0, "Repeated prompt did not reuse its exact token prefix");
                check(usage[4] == 0 || usage[4] == 1, "Unexpected terminal reason");
                check(output.size() > 0, "No visible output");
                System.out.println("warm attempt " + attempt + ": " + Arrays.toString(usage));
            }
            var continued = new java.io.ByteArrayOutputStream();
            long[] historyUsage = GgufNative.generate(engine,
                new String[]{"system", "user", "assistant", "user"},
                new byte[][]{
                    "Tell a short story.".getBytes(StandardCharsets.UTF_8),
                    "Once upon a time".getBytes(StandardCharsets.UTF_8),
                    "there was a little bird.".getBytes(StandardCharsets.UTF_8),
                    "What happened next?".getBytes(StandardCharsets.UTF_8)
                }, 16, 1, 1f, 0f, bytes -> { continued.writeBytes(bytes); return true; });
            check(historyUsage[0] > 0 && historyUsage[1] > 0 && continued.size() > 0, "Multi-turn generation failed");
            check(historyUsage[4] == 0 || historyUsage[4] == 1, "Multi-turn generation returned an error");
            long[] stopped = GgufNative.generate(engine, new String[]{"user"}, new byte[][]{"Once upon a time".getBytes(StandardCharsets.UTF_8)}, 64, 1, 1f, 0f, bytes -> false);
            check(stopped[4] == 2 && stopped[0] > 0 && stopped[1] == 1, "Cancellation lost partial counters");
            long cancelledBeforeEntry = GgufNative.nextOperationId();
            GgufNative.prepareOperation(engine, cancelledBeforeEntry);
            GgufNative.cancelOperation(engine, cancelledBeforeEntry);
            long[] notEntered = GgufNative.generatePrepared(engine, new String[]{"user"}, new byte[][]{"Hello".getBytes(StandardCharsets.UTF_8)}, 16, 1, 1f, 0f, bytes -> { throw new AssertionError("Canceled operation emitted text"); }, null, cancelledBeforeEntry);
            check(notEntered[4] == 2 && notEntered[0] == 0, "Cancellation before entry was cleared");
            long adaptiveOperation = GgufNative.nextOperationId();
            GgufNative.prepareOperation(engine, adaptiveOperation);
            var processedBatches = new java.util.ArrayList<Long>();
            long[] adaptive = GgufNative.generatePrepared(engine, new String[]{"user"},
                new byte[][]{"A completely different prompt to invalidate prefix reuse. Tell a story about the ocean and a boat.".getBytes(StandardCharsets.UTF_8)},
                16, 1, 1f, 0f, bytes -> true,
                (phase,total,processed,sampled,elapsed,code) -> {
                    if (phase.equals("PREFILL_BATCH_END") && code == 0) processedBatches.add(processed);
                }, adaptiveOperation);
            check(adaptive[1] > 0 && processedBatches.size() > 1, "Adaptive prefill did not complete multiple batches");
            long batchCapacity = GgufNative.allocationObservation(engine)[1];
            check(processedBatches.get(0) - adaptive[5] == Math.min(8, adaptive[0] - adaptive[5]), "CPU prefill must start with the governor's bounded eight-token probe");
            for (int i = 1; i < processedBatches.size(); i++) {
                long delta = processedBatches.get(i) - processedBatches.get(i - 1);
                check(delta > 0 && delta <= batchCapacity, "CPU prefill batch exceeded its configured capacity");
            }
            check(processedBatches.get(processedBatches.size() - 1) == adaptive[0], "Prefill progress did not cover the complete input");
            long duringPrefill = GgufNative.nextOperationId();
            GgufNative.prepareOperation(engine, duringPrefill);
            var stages = new java.util.ArrayList<String>();
            long[] prefillStop = GgufNative.generatePrepared(engine, new String[]{"user"}, new byte[][]{"Tell a new short story about a bird".getBytes(StandardCharsets.UTF_8)}, 16, 1, 1f, 0f, bytes -> { throw new AssertionError("Prefill cancellation emitted text"); }, (phase,total,processed,sampled,elapsed,code) -> {
                stages.add(phase);
                if (phase.equals("PREFILL_BATCH_BEGIN")) GgufNative.cancelOperation(engine, duringPrefill);
            }, duringPrefill);
            check(prefillStop[4] == 2 && prefillStop[1] == 0, "Prefill cancellation did not retain partial receipt");
            check(stages.contains("RENDER_BEGIN") && stages.contains("TOKENIZE_END") && stages.contains("NATIVE_RETURN"), "Native stage evidence missing");
            long fresh = GgufNative.nextOperationId();
            GgufNative.prepareOperation(engine, fresh);
            GgufNative.cancelOperation(engine, duringPrefill); // Delayed cancel must not hit the new operation.
            long[] freshResult = GgufNative.generatePrepared(engine, new String[]{"user"}, new byte[][]{"Once upon a time".getBytes(StandardCharsets.UTF_8)}, 16, 1, 1f, 0f, bytes -> true, null, fresh);
            check(freshResult[1] > 0 && freshResult[4] != 2, "Stale cancellation stopped a fresh operation");
            long budgetOperation = GgufNative.nextOperationId();
            GgufNative.prepareOperation(engine, budgetOperation);
            var actualBudget = new java.util.concurrent.atomic.AtomicInteger();
            long[] boundedReply = GgufNative.generatePrepared(engine, new String[]{"user"},
                new byte[][]{"Once upon a time".getBytes(StandardCharsets.UTF_8)},
                8192, 1, 1f, 0f, bytes -> false,
                (phase,total,processed,sampled,elapsed,code) -> {
                    if (phase.equals("OUTPUT_BUDGET_ADJUSTED")) actualBudget.set(code);
                }, budgetOperation);
            check(actualBudget.get() > 0 && actualBudget.get() < 512, "Exact tokenizer did not reserve reply headroom");
            check(boundedReply[0] + actualBudget.get() == GgufNative.allocationObservation(engine)[0], "Native reply allocation exceeds context");
            check(boundedReply[1] == 1 && boundedReply[4] == 2, "Fitting input was rejected because of an oversized output preference");
            try {
                GgufNative.generate(engine, new String[]{"user"}, new byte[][]{"x ".repeat(1000).getBytes(StandardCharsets.UTF_8)}, 64, 1, 1f, 0f, bytes -> true);
                throw new AssertionError("Oversized prompt admitted");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("context limit"), "Wrong bound failure");
            }
        } finally { GgufNative.unload(engine); }
        GgufNative.cancel(engine);
        GgufNative.unload(engine);
        try {
            GgufNative.generate(engine, new String[]{"user"}, new byte[][]{{65}}, 16, 1, 1f, 0f, bytes -> true);
            throw new AssertionError("Stale handle admitted");
        } catch (IllegalStateException expected) { }
        long reopened = GgufNative.load(args[0].getBytes(StandardCharsets.UTF_8), 512, 2, 0);
        GgufNative.unload(reopened);
        for (int batch : new int[]{64, 128, 256}) {
            long operation = GgufNative.nextOperationId();
            GgufNative.prepareOperation(0, operation);
            long configured = GgufNative.loadConfigured(args[0].getBytes(StandardCharsets.UTF_8), 512, 1, 0, operation, 2, batch, Math.min(128, batch));
            try {
                long[] result = GgufNative.generate(configured, new String[]{"user"}, new byte[][]{"Once upon a time".getBytes(StandardCharsets.UTF_8)}, 16, 1, 1f, 0f, bytes -> true);
                check(result[1] > 0 && result[4] != 3, "Configured batch failed real inference");
            } finally { GgufNative.unload(configured); }
        }
        long observedOperation = GgufNative.nextOperationId();
        GgufNative.prepareOperation(0, observedOperation);
        var loadStages = new java.util.ArrayList<String>();
        var fractions = new java.util.ArrayList<Float>();
        long observed = GgufNative.loadConfiguredProgress(args[0].getBytes(StandardCharsets.UTF_8), 513, 2, 0,
            observedOperation, 2, 128, 64, (phase, fraction, elapsed) -> {
                check(elapsed >= 0 && fraction >= 0 && fraction <= 1, "Invalid load progress");
                loadStages.add(phase); fractions.add(fraction);
            });
        try {
            long[] allocation = GgufNative.allocationObservation(observed);
            check(allocation.length == 10, "Missing allocation components");
            check(allocation[0] >= 513 && allocation[0] % 256 == 0, "Effective padded context missing");
            check(allocation[1] == 128 && allocation[2] == 64, "Effective batches differ");
            check(allocation[3] > 0 && allocation[4] > 0 && allocation[5] > 0 && allocation[9] > 0, "Unmeasured native buffers");
            check(allocation[6] == 0 && allocation[7] == 0 && allocation[8] == 0, "CPU buffers reported as device allocations");
            check(loadStages.contains("NATIVE_READY") && fractions.contains(1f), "Load progress never completed");
            System.out.println("native allocation components: " + Arrays.toString(allocation));
        } finally { GgufNative.unload(observed); }
        System.out.println("GGUF JNI smoke passed: repeated sessions, native counts, cancellation, context rejection, stale handles, reopening");
    }
}
