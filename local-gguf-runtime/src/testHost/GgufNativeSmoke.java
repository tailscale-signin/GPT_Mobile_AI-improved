import dev.melo.gguf.GgufNative;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Uses real JNI and CPU inference, with synthetic text and no application secrets. */
public final class GgufNativeSmoke {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        boolean gpu = GgufNative.supportsGpuOffload();
        if (gpu) throw new AssertionError("CPU host smoke unexpectedly enabled GPU offload");
        try {
            GgufNative.load(args[0].getBytes(StandardCharsets.UTF_8), 512, 2, 1);
            throw new AssertionError("GPU request silently fell back to the CPU-only host runtime");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("GPU offload is unavailable")) throw expected;
        }
        long engine = GgufNative.load(args[0].getBytes(StandardCharsets.UTF_8), 512, 2, 0);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                var output = new java.io.ByteArrayOutputStream();
                long[] usage = GgufNative.generate(engine, new String[]{"user"}, new byte[][]{"Once upon a time".getBytes(StandardCharsets.UTF_8)}, 16, 1, 1f, 0f, bytes -> {
                    output.writeBytes(bytes); return true;
                });
                check(usage.length == 5 && usage[0] > 0 && usage[1] > 0 && usage[1] <= 16, "Invalid native token receipt");
                check(usage[4] == 0 || usage[4] == 1, "Unexpected terminal reason");
                check(output.size() > 0, "No visible output");
                System.out.println("warm attempt " + attempt + ": " + Arrays.toString(usage));
            }
            long[] stopped = GgufNative.generate(engine, new String[]{"user"}, new byte[][]{"Once upon a time".getBytes(StandardCharsets.UTF_8)}, 64, 1, 1f, 0f, bytes -> false);
            check(stopped[4] == 2 && stopped[0] > 0 && stopped[1] == 1, "Cancellation lost partial counters");
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
        System.out.println("GGUF JNI smoke passed: repeated sessions, native counts, cancellation, context rejection, stale handles, reopening");
    }
}
