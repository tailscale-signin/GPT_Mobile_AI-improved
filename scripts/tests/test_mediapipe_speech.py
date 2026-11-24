"""Protocol controls for the real-model smoke; these do not claim inference."""
from contextlib import closing
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import smoke_mediapipe_speech as smoke


def chunk(*, final=False, frames=24):
    return SimpleNamespace(samples=SimpleNamespace(size=frames), is_final=final)


class ControlledStream:
    def __init__(self, events, *, early_end=False, premature_final=False, failure=None, cancel_failure=None):
        self.events = events
        self.early_end = early_end
        self.premature_final = premature_final
        self.failure = failure
        self.cancel_failure = cancel_failure
        self.finished = False
        self.cancelled = False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.events.append("close")

    def push_text(self, text):
        self.events.append(("push", text))
        if self.failure == "push":
            raise RuntimeError("native push failed")

    def finish_input(self):
        self.events.append("finish")
        if self.failure == "finish":
            raise RuntimeError("native finish failed")
        self.finished = True

    def cancel(self):
        self.events.append("cancel")
        self.cancelled = True

    def __iter__(self):
        self.events.append("read")
        if self.cancelled:
            if self.cancel_failure:
                raise RuntimeError(self.cancel_failure)
            return
        if self.early_end:
            return
        yield chunk(final=self.premature_final)
        self.events.append("read")
        if self.cancelled:
            if self.cancel_failure:
                raise RuntimeError(self.cancel_failure)
            return
        if not self.finished:
            raise AssertionError("The real protocol must finish its input before draining the tail")
        yield chunk(final=True)
        self.events.append("eos")


class ControlledTask:
    def __init__(self, **options):
        self.events = []
        self.options = options

    def open_stream(self, *, initial_text, finish_input):
        self.events.append(("open", initial_text, finish_input))
        stream = ControlledStream(self.events, **self.options)
        stream.finished = finish_input
        return stream


class IncrementalCheckpointProtocolTest(unittest.TestCase):
    def collect(self, task, *, incremental=True):
        evidence = {}
        with closing(smoke.input_chunks(task, smoke.FIXTURE, incremental=incremental, evidence=evidence)) as chunks:
            output = list(chunks)
        return evidence, output

    def test_real_probe_withholds_later_text_and_finish_until_first_pcm(self):
        task = ControlledTask()
        evidence, chunks = self.collect(task)
        self.assertEqual([
            ("open", "", False), ("push", smoke.INCREMENTAL_PARTS[0]), "read",
            ("push", smoke.INCREMENTAL_PARTS[1]), "finish", "read", "eos", "close",
        ], task.events)
        self.assertEqual("".join(smoke.INCREMENTAL_PARTS), smoke.FIXTURE)
        self.assertEqual(2, len(chunks))
        self.assertEqual({"text_delta_count": 2, "finish_input_calls": 1, "first_pcm_before_finish_input": True}, evidence)

    def test_existing_single_input_path_remains_sealed_and_never_pushes(self):
        task = ControlledTask()
        evidence, _ = self.collect(task, incremental=False)
        self.assertEqual([("open", smoke.FIXTURE, True), "read", "read", "eos", "close"], task.events)
        self.assertEqual(0, evidence["finish_input_calls"])
        self.assertFalse(evidence["first_pcm_before_finish_input"])

    def test_early_eos_or_final_before_remaining_text_is_a_hard_failure(self):
        for options in ({"early_end": True}, {"premature_final": True}):
            task = ControlledTask(**options)
            with self.subTest(options=options), self.assertRaises(RuntimeError):
                self.collect(task)
            self.assertEqual("close", task.events[-1])
            self.assertNotIn("finish", task.events)

    def test_native_push_or_finish_failure_propagates_and_closes(self):
        for stage in ("push", "finish"):
            task = ControlledTask(failure=stage)
            with self.subTest(stage=stage), self.assertRaisesRegex(RuntimeError, "native " + stage):
                self.collect(task)
            self.assertEqual("close", task.events[-1])

    def test_pcm_validation_failure_can_close_without_feeding_more_text(self):
        task = ControlledTask()
        with closing(smoke.input_chunks(task, smoke.FIXTURE, incremental=True, evidence={})) as chunks:
            next(chunks)
        self.assertEqual([("open", "", False), ("push", smoke.INCREMENTAL_PARTS[0]), "read", "close"], task.events)

    def test_cancellation_leaves_input_open_before_or_after_first_pcm(self):
        for after in (False, True):
            task = ControlledTask()
            result = smoke.cancellation_probe(task, after, incremental=True)
            with self.subTest(after=after):
                self.assertTrue(result["input_open_at_cancel"])
                self.assertEqual(0, result["finish_input_calls"])
                self.assertEqual("end_of_stream", result["outcome"])
                self.assertEqual(("open", "", False), task.events[0])
                self.assertEqual("close", task.events[-1])
                self.assertNotIn("finish", task.events)

    def test_only_concrete_native_cancellation_status_is_accepted(self):
        task = ControlledTask(cancel_failure="C API error (1): cancelled")
        self.assertEqual("native_cancelled", smoke.cancellation_probe(task, True, incremental=True)["outcome"])
        task = ControlledTask(cancel_failure="C API error (13): internal failure")
        with self.assertRaisesRegex(RuntimeError, "Unexpected cancellation failure"):
            smoke.cancellation_probe(task, True, incremental=True)
        self.assertEqual("close", task.events[-1])

    def test_recovery_reopens_a_distinct_unsealed_stream_on_the_same_task(self):
        task = ControlledTask()
        smoke.cancellation_probe(task, True, incremental=True)
        receipt, _ = self.collect(task)
        self.assertEqual(2, task.events.count(("open", "", False)))
        self.assertEqual(2, task.events.count("close"))
        self.assertEqual(1, receipt["finish_input_calls"])


if __name__ == "__main__":
    unittest.main()
