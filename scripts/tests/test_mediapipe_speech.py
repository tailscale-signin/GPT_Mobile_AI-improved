"""Protocol controls for the real-model smoke; these do not claim inference."""
from contextlib import closing
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import io

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import smoke_mediapipe_speech as smoke


class CaptionWordCoverageTest(unittest.TestCase):
    def test_first_and_last_source_words_do_not_hide_missing_middle_words(self):
        words = smoke.lexical_word_spans(smoke.FIXTURE)
        self.assertEqual(12, len(words))
        result = smoke.caption_word_coverage(smoke.FIXTURE, [words[0], words[-1]])
        self.assertFalse(result["complete"])
        self.assertEqual(2, result["individually_timed_words"])
        self.assertTrue(smoke.caption_word_coverage(smoke.FIXTURE, words)["complete"])

    def test_one_grouped_timestamp_cannot_qualify_individual_word_highlighting(self):
        result = smoke.caption_word_coverage("one two three", [(0, 7), (8, 13)])
        self.assertEqual(3, result["covered_words"])
        self.assertEqual(1, result["individually_timed_words"])
        self.assertEqual(1, result["grouped_word_spans"])
        self.assertFalse(result["complete"])

    def test_unicode_marks_apostrophes_numbers_and_punctuation_keep_exact_utf8_spans(self):
        text = "“Café”, nai\u0308ve l’été 42 🙂"
        words = smoke.lexical_word_spans(text)
        encoded = text.encode("utf-8")
        self.assertEqual(["Café", "nai\u0308ve", "l’été", "42"], [encoded[start:end].decode("utf-8") for start, end in words])
        result = smoke.caption_word_coverage(text, words * 4)
        self.assertEqual(4, result["expected_words"])
        self.assertEqual(4, result["individually_timed_words"])
        self.assertTrue(result["complete"])
        self.assertEqual("SDK_ESTIMATED", result["timing_provenance"])
        self.assertEqual("NOT_MEASURED", result["acoustic_alignment"])

    def test_truncated_words_and_split_multibyte_codepoints_do_not_qualify(self):
        result = smoke.caption_word_coverage("Café ready", [(0, 3), (6, 11)])
        self.assertFalse(result["complete"])
        self.assertEqual(1, result["partial_word_spans"])
        with self.assertRaisesRegex(RuntimeError, "splits its UTF-8 source"):
            smoke.caption_word_coverage("Café", [(0, 4)])

    def test_consumer_retains_latest_sdk_revision_and_requires_every_fixed_fixture_word(self):
        # The dedicated real-model smoke installs this pinned dependency; generic tooling jobs may not.
        try:
            import numpy as np
        except ImportError:
            self.skipTest("NumPy is installed by the speech smoke dependency set")
        spans = smoke.lexical_word_spans(smoke.FIXTURE)
        words = [SimpleNamespace(start_char=start, end_char=end, start_sec=index / 10, end_sec=(index + .9) / 10)
                 for index, (start, end) in enumerate(spans)]
        revised = SimpleNamespace(start_char=spans[0][0], end_char=spans[0][1], start_sec=0.0, end_sec=.06)

        def chunks(timestamps):
            yield SimpleNamespace(samples=np.full(24_000, .1, dtype=np.float32), sample_rate_hz=24_000, word_timestamps=timestamps, is_final=False)
            yield SimpleNamespace(samples=np.full(24_000, .1, dtype=np.float32), sample_rate_hz=24_000, word_timestamps=[revised], is_final=True)

        with patch.object(smoke, "input_chunks", return_value=chunks(words)):
            result = smoke.consume(None, smoke.FIXTURE, np, include_word_cues=True, require_complete_words=True)
        self.assertEqual(.06, result["word_cues"][0]["end_s"])
        self.assertEqual(12, result["caption_word_coverage"]["individually_timed_words"])
        self.assertTrue(result["full_input_cues_observed"])
        with patch.object(smoke, "input_chunks", return_value=chunks([words[0], words[-1]])):
            with self.assertRaisesRegex(RuntimeError, "Every lexical source word"):
                smoke.consume(None, smoke.FIXTURE, np, require_complete_words=True)


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

    def open_stream(self, *, initial_text, finish_input, voice_id=None, locale=None):
        self.selection = (voice_id, locale)
        self.events.append(("open", initial_text, finish_input))
        stream = ControlledStream(self.events, **self.options)
        stream.finished = finish_input
        return stream


class VoiceCatalogCheckpointTest(unittest.TestCase):
    def test_pinned_multilingual_pack_and_language_specific_voice_probes_are_complete(self):
        model = smoke.MODELS["pocket-multilingual"]
        smoke.verify_catalog(model)
        selected = []
        def consume(task, text, np, **options):
            selected.append((options["voice_id"], options["locale"], text))
            return {"word_cues": [{"utf8_start": 0, "utf8_end": 1}]}
        with patch.object(smoke, "consume", side_effect=consume):
            rows = smoke.consume_selectable_voices(None, model, None)
        self.assertEqual(set(model["required_voices"]), {row["voice_id"] for row in rows})
        self.assertEqual(14, len(selected))
        self.assertEqual({"en", "fr", "de", "es", "it", "pt", "nl"}, {locale for _, locale, _ in selected})
        self.assertIn(("estelle", "fr", smoke.FRENCH_CORPUS), selected)
        for engine, count in (("pocket", 8), ("phonon", 5)):
            smoke.verify_catalog(smoke.MODELS[engine])
            self.assertEqual(count, len(smoke.MODELS[engine]["required_voices"]))


class IncrementalCheckpointProtocolTest(unittest.TestCase):
    def collect(self, task, *, incremental=True):
        evidence = {}
        with closing(smoke.input_chunks(task, smoke.FIXTURE, incremental=incremental, evidence=evidence)) as chunks:
            output = list(chunks)
        return evidence, output

    def test_french_voice_and_locale_apply_to_only_the_requested_stream(self):
        task = ControlledTask()
        with closing(smoke.input_chunks(task, smoke.FRENCH_CORPUS, incremental=False, evidence={}, voice_id="ff_siwis", locale="fr")) as chunks:
            self.assertTrue(list(chunks))
        self.assertEqual(("ff_siwis", "fr"), task.selection)
        self.collect(task)
        self.assertEqual((None, None), task.selection)

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


class GpuEvidenceTests(unittest.TestCase):
    def test_kernel_events_must_execute_during_the_corpus(self):
        output = io.BytesIO(b"Created event 1 (0x11) Command ndrange_kernel\nKOKORO_CORPUS_BEGIN\n"
                            b"pthread: Command complete, event 1\nCreated event 2 (0x22) Command ndrange_kernel\n"
                            b"Created event 3 (0x33) Command read_buffer\npthread: Command complete, event 3\n"
                            b"pthread: Command complete, event 2\npthread: Command complete, event 2\n"
                            b"Created event 4 (0x44) Command ndrange_kernel\nKOKORO_CORPUS_END\n"
                            b"pthread: Command complete, event 4\nGPU accelerator could not be loaded and registered.\n")
        evidence = {"pocl_completed_corpus_kernels": 0, "runtime_reported_gpu_unavailable": False}
        smoke.read_worker_output(output, [], evidence)
        self.assertEqual(evidence, {"pocl_completed_corpus_kernels": 1, "runtime_reported_gpu_unavailable": True})

    def test_numeric_and_cue_mismatches_do_not_qualify(self):
        corpus = dict(input_sha256="fixture", sample_rate_hz=24000, frames=24000, rms=.2, clipped_frames=0,
                      word_cues=[dict(utf8_start=0, utf8_end=3)])
        self.assertTrue(smoke.compare_corpus(corpus, corpus)["consistent"])
        for change in (dict(frames=5000), dict(rms=.01), dict(clipped_frames=3000),
                       dict(word_cues=[dict(utf8_start=1, utf8_end=3)])):
            self.assertFalse(smoke.compare_corpus(corpus, corpus | change)["consistent"])
        with self.assertRaises(RuntimeError):
            smoke.compare_corpus(corpus, corpus | dict(input_sha256="other"))

    def test_gpu_unavailability_is_specific_to_acceleration(self):
        self.assertTrue(smoke.gpu_unavailable("GPU accelerator could not be loaded and registered."))
        self.assertFalse(smoke.gpu_unavailable("Invalid model schema"))
        self.assertFalse(smoke.gpu_unavailable("Failed to allocate GPU buffer for the model"))


if __name__ == "__main__":
    unittest.main()
