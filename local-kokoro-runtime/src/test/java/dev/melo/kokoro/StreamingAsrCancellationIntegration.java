package dev.melo.kokoro;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Actual Java ownership boundary with deterministic, host-only JNI scheduling. */
public final class StreamingAsrCancellationIntegration {
    static { System.loadLibrary("timed_kokoro"); }
    private static final int ACCEPTS = 0, CANCELS = 1, ACTIVE_CANCELS = 2, DELETES = 3;
    private static final int DELETE_OVERLAP = 4, CANCEL_OVERLAP = 5;
    private static final int BLOCK_ACCEPT = 1, BLOCK_FIRST_CANCEL = 2, BLOCK_DELETE = 4, FAIL_DELETE = 8;
    private static native void resetFixture(int flags);
    private static native int counter(int field);
    private static native void releaseAll();

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        require(condition.getAsBoolean(), message);
    }
    private static Thread start(String name, Runnable task, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try { task.run(); } catch (Throwable error) { failure.compareAndSet(null, error); }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
    private static void join(Thread thread) throws Exception {
        if (thread == null) return;
        thread.join(5000);
        require(!thread.isAlive(), "Ownership operation did not finish: " + thread.getName());
    }
    private static void healthy(AtomicReference<Throwable> failure) {
        if (failure.get() != null) throw new AssertionError("Worker failed", failure.get());
    }
    private static StreamingAsr model() {
        return new StreamingAsr("", "", "", "", "zipformer", "cpu", "en", "", "", 1, 0, .65f);
    }
    private static void blockedInferenceRemainsCancellable(boolean close) throws Exception {
        resetFixture(BLOCK_ACCEPT);
        var model = model();
        var failure = new AtomicReference<Throwable>();
        Thread inference = start("blocked-inference", () -> model.accept(new float[1]), failure);
        Thread cancellation = null;
        try {
            await(() -> counter(ACCEPTS) == 1, "Inference did not enter JNI");
            cancellation = start(close ? "close-cancels-inference" : "cancel-inference",
                close ? model::close : model::cancel, failure);
            await(() -> counter(CANCELS) == 1, "Cancellation waited for the inference monitor");
            join(inference);
            join(cancellation);
            healthy(failure);
        } finally {
            releaseAll();
            join(inference);
            join(cancellation);
            model.close();
        }
        require(counter(DELETES) == 1 && counter(DELETE_OVERLAP) == 0, "Inference retirement was not exclusive");
    }
    private static void closeDrainsCancellationHolder() throws Exception {
        resetFixture(BLOCK_FIRST_CANCEL);
        var model = model();
        var failure = new AtomicReference<Throwable>();
        Thread cancellation = start("held-cancellation", model::cancel, failure);
        Thread closing = null;
        try {
            await(() -> counter(ACTIVE_CANCELS) == 1, "Cancellation did not acquire its JNI holder");
            closing = start("close-waits-for-cancellation", model::close, failure);
            Thread observed = closing;
            await(() -> counter(DELETES) != 0 || observed.getState() == Thread.State.BLOCKED,
                "Close did not reach the cancellation boundary");
            require(counter(DELETES) == 0, "Close entered nativeDelete while a cancellation JNI holder was active");
        } finally {
            releaseAll();
            join(cancellation);
            join(closing);
        }
        healthy(failure);
        require(counter(DELETES) == 1 && counter(DELETE_OVERLAP) == 0, "Close did not drain its cancellation holder");
        int calls = counter(CANCELS);
        model.cancel();
        model.close();
        require(counter(CANCELS) == calls && counter(DELETES) == 1, "Closed handle reentered JNI");
    }
    private static void closeExcludesNewCancellation() throws Exception {
        resetFixture(BLOCK_DELETE);
        var model = model();
        var failure = new AtomicReference<Throwable>();
        Thread closing = start("held-delete", model::close, failure);
        Thread cancellation = null;
        try {
            await(() -> counter(DELETES) == 1, "Close did not enter nativeDelete");
            cancellation = start("late-cancellation", model::cancel, failure);
            Thread observed = cancellation;
            await(() -> counter(CANCELS) > 1 || observed.getState() == Thread.State.BLOCKED,
                "Cancellation did not reach the retirement boundary");
            require(counter(CANCELS) == 1, "Cancellation entered JNI while nativeDelete was active");
        } finally {
            releaseAll();
            join(closing);
            join(cancellation);
        }
        healthy(failure);
        require(counter(CANCELS) == 1 && counter(CANCEL_OVERLAP) == 0, "Retired handle accepted a new cancellation");
    }
    private static void unconfirmedRetirementStaysQuarantined() {
        resetFixture(FAIL_DELETE);
        var model = model();
        for (int attempt = 0; attempt < 2; ++attempt) {
            try { model.close(); throw new AssertionError("Unconfirmed retirement was acknowledged"); }
            catch (StreamingAsr.Failure failure) {
                require(failure.code.equals("ASR_RETIREMENT_UNCONFIRMED"), "Wrong retirement failure");
            }
        }
        model.cancel();
        try { model.accept(new float[1]); throw new AssertionError("Quarantined handle accepted inference"); }
        catch (StreamingAsr.Failure failure) {
            require(failure.code.equals("ASR_RETIREMENT_UNCONFIRMED"), "Wrong inference quarantine failure");
        }
        require(counter(DELETES) == 1 && counter(CANCELS) == 1 && counter(ACCEPTS) == 0,
            "Uncertain native state was retried or used");
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Expected a receipt path");
        blockedInferenceRemainsCancellable(false);
        blockedInferenceRemainsCancellable(true);
        closeDrainsCancellationHolder();
        closeExcludesNewCancellation();
        unconfirmedRetirementStaysQuarantined();
        Files.writeString(Path.of(args[0]), "{\n"
            + "  \"scope\": \"ACTUAL_JAVA_OWNERSHIP_WITH_HOST_ONLY_JNI_SCHEDULING_FIXTURE\",\n"
            + "  \"casesPassed\": 5,\n  \"modelExecuted\": false,\n  \"physicalDeviceQualified\": false\n}\n");
        System.out.println("5 actual Java cancellation/retirement cases passed; JNI scheduling fixture only");
    }
}
