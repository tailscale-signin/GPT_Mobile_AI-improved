"""Pure numerical controls; no native/provider/device execution is claimed."""
import unittest

import numpy as np

from kokoro_qnn_model_qualification import compare_audio, validate


def output(durations, level=.1):
    timing = np.asarray(durations, dtype=np.int64)
    return np.full(int(timing.sum()) * 600, level, dtype=np.float32), timing


class KokoroReferenceComparisonTest(unittest.TestCase):
    def test_identical_and_rounding_outputs_remain_eligible(self):
        reference = output([12, 2, 2, 12])
        self.assertEqual("CONSISTENT", compare_audio(reference, reference)["status"])
        self.assertEqual("CONSISTENT", compare_audio(reference, output([12, 3, 2, 12]))["status"])

    def test_structurally_valid_collapsed_gpu_durations_are_rejected(self):
        reference = output([12, 2, 2, 12])
        candidate = output([1, 1, 1, 1])
        validate(candidate, 4)
        self.assertEqual("DURATION_MISMATCH", compare_audio(reference, candidate)["reason"])

    def test_structurally_valid_expanded_durations_are_rejected(self):
        reference = output([12, 2, 2, 12])
        candidate = output([45, 45, 45, 45])
        validate(candidate, 4)
        self.assertEqual("DURATION_MISMATCH", compare_audio(reference, candidate)["reason"])

    def test_same_length_wrong_timing_is_rejected(self):
        self.assertEqual("TOKEN_TIMING_MISMATCH", compare_audio(output([12, 2, 2, 12]), output([7, 7, 7, 7]))["reason"])

    def test_signal_and_clipping_and_activity_are_independent(self):
        reference = output([12, 2, 2, 12])
        self.assertEqual("SIGNAL_LEVEL_MISMATCH", compare_audio(reference, output([12, 2, 2, 12], .3))["reason"])
        clipped_reference = output([12, 2, 2, 12], .6)
        clipped = output([12, 2, 2, 12], .6)
        clipped[0][:100] = 1.0
        self.assertEqual("CLIPPING_MISMATCH", compare_audio(clipped_reference, clipped)["reason"])
        inactive = output([12, 2, 2, 12])
        inactive[0][:5000] = 0
        self.assertEqual("SIGNAL_ACTIVITY_MISMATCH", compare_audio(reference, inactive)["reason"])

    def test_different_token_count_is_rejected(self):
        self.assertEqual("TOKEN_COUNT_MISMATCH", compare_audio(output([12, 2, 2, 12]), output([14, 14]))["reason"])

    def test_sparse_clipping_in_either_reference_or_candidate_cannot_qualify(self):
        reference = output([12, 2, 2, 12])
        candidate = output([12, 2, 2, 12])
        candidate[0][:8] = 1.0
        self.assertEqual("CLIPPING_MISMATCH", compare_audio(reference, candidate)["reason"])
        self.assertEqual("CLIPPING_MISMATCH", compare_audio(candidate, reference)["reason"])
        self.assertEqual("CLIPPING_MISMATCH", compare_audio(candidate, candidate)["reason"])
        with self.assertRaisesRegex(AssertionError, "Clipped PCM"):
            validate(candidate, 4)
        candidate[0][:8] = np.nextafter(np.float32(1), np.float32(0))
        validate(candidate, 4)

    def test_duration_error_keeps_precedence_when_output_is_also_clipped(self):
        reference = output([12, 2, 2, 12])
        collapsed = output([1, 1, 1, 1])
        collapsed[0][:8] = 1.0
        self.assertEqual("DURATION_MISMATCH", compare_audio(reference, collapsed)["reason"])


if __name__ == "__main__":
    unittest.main()
