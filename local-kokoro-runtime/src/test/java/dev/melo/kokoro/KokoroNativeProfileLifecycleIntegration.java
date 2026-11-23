package dev.melo.kokoro;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Actual Model/JNI/Sherpa/ORT regression, with an explicit x86 QNN host port. */
public final class KokoroNativeProfileLifecycleIntegration {
    private static native void armFault();
    private static native boolean awaitFault();
    private static native void releaseFault();

    private static void require(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 4, "Pass model pack, provider directory, case and receipt path");
        Path pack = Path.of(args[0]);
        Path libraries = Path.of(args[1]);
        String kind = args[2];
        boolean qualified = kind.equals("qualified_cancel");
        boolean cancel = !kind.equals("unqualified_failure");
        require(qualified || kind.equals("unqualified_cancel") || kind.equals("unqualified_failure"), "Unknown profile case");
        String phrase = "melo enjoys a clear voice.";
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (TimedKokoro model = new TimedKokoro(
                pack.resolve("model.int8.onnx").toString(), pack.resolve("voices.bin").toString(),
                pack.resolve("tokens.txt").toString(), pack.resolve("lexicon-us-en.txt").toString(), 2,
                "qnn-htp", libraries.toString(), libraries.resolve("lifecycle-profile").toString())) {
            TimedKokoro.Evidence original = null;
            if (qualified) {
                model.generate(phrase, 0, 1f);
                original = model.evidence();
                require(original.validatedAudio && original.profileAvailable && original.acceleratorKernels > 0,
                        "Positive control did not execute QNN kernels");
            }
            armFault();
            Future<TimedKokoro.Audio> pending = worker.submit(() -> model.generate(phrase, 0, 1f));
            TimedKokoro.Failure failure = null;
            try {
                require(awaitFault(), "Actual ORT run did not reach the post-kernel barrier");
                if (cancel) model.cancel();
                releaseFault();
                try {
                    pending.get(120, TimeUnit.SECONDS);
                    throw new AssertionError("Injected interruption returned audio");
                } catch (ExecutionException expected) {
                    if (cancel) {
                        require(expected.getCause() instanceof CancellationException, "Native cancellation identity changed");
                    } else {
                        require(expected.getCause() instanceof TimedKokoro.Failure, "Native failure receipt was lost");
                        failure = (TimedKokoro.Failure) expected.getCause();
                        require(failure.currentEvidence != null && "synthesis".equals(failure.stage), "Missing current inference evidence");
                        require(!failure.currentEvidence.validatedAudio && !failure.currentEvidence.profileAvailable,
                                "Unfinished profile was reported as measured");
                    }
                }
            } finally {
                releaseFault();
            }
            TimedKokoro.Evidence interrupted = model.evidence();
            require(!interrupted.validatedAudio, "Failed inference borrowed valid audio");
            model.resetCancellation();
            model.generate(phrase, 0, 1f);
            TimedKokoro.Evidence resumed = model.evidence();
            String receipt = "{\"case\":\"" + kind + "\",\"physical_device_qualified\":false," +
                    "\"interruptedProfileAvailable\":" + interrupted.profileAvailable +
                    ",\"interruptedProfileId\":" + interrupted.profileId +
                    ",\"resumedProfileId\":" + resumed.profileId +
                    ",\"resumedQnnKernels\":" + resumed.acceleratorKernels +
                    ",\"resumedCpuKernels\":" + resumed.cpuKernels +
                    ",\"cacheHit\":" + resumed.cacheHit +
                    ",\"profileReused\":" + resumed.profileReused +
                    ",\"graphPreparedThisCall\":" + resumed.graphPreparedThisCall +
                    ",\"validatedAudio\":" + resumed.validatedAudio + "}\n";
            Files.writeString(Path.of(args[3]), receipt);
            System.out.print(receipt);
            require(resumed.validatedAudio && resumed.profileAvailable && resumed.acceleratorKernels > 0,
                    "Resumed inference did not execute and validate the actual model");
            if (qualified) {
                require(resumed.cacheHit && resumed.profileReused && !resumed.graphPreparedThisCall,
                        "Previously qualified session was unnecessarily retired");
                require(resumed.profileId == original.profileId && resumed.acceleratorKernels == original.acceleratorKernels &&
                        resumed.cpuKernels == original.cpuKernels, "Previously completed profile changed after cancellation");
            } else {
                require(!resumed.cacheHit && !resumed.profileReused && resumed.graphPreparedThisCall,
                        "Interrupted first profile was reused instead of rebuilt");
                require(resumed.acceleratorKernels == 39, "Resumed profile includes kernels from an earlier attempt");
                if (failure != null) {
                    require(!failure.currentEvidence.profileAvailable && !failure.currentEvidence.validatedAudio &&
                            failure.currentEvidence.profileId == interrupted.profileId &&
                            failure.currentEvidence.synthesisMs == interrupted.synthesisMs,
                            "Later success mutated the immutable failed-attempt receipt");
                }
            }
        } finally {
            releaseFault();
            worker.shutdownNow();
            require(worker.awaitTermination(120, TimeUnit.SECONDS), "Native worker did not terminate");
        }
        try (var files = Files.list(libraries)) {
            require(files.noneMatch(p -> p.getFileName().toString().startsWith("lifecycle-profile")),
                    "Closing retired/current sessions left a profiling file behind");
        }
    }
}
