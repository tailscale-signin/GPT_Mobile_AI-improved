#ifndef MELO_KOKORO_OUTPUT_VALIDATION_H_
#define MELO_KOKORO_OUTPUT_VALIDATION_H_

#include "kokoro_measurement.h"
#include <limits>

namespace kokoro_runtime {

// Stable numeric JNI codes. These contain no tensor values, caller text or
// provider exception. Add new reasons at the end to preserve older receipts.
enum class OutputValidationReason : int64_t {
  None, OutputCount, AudioTensorType, AudioFrameCount, AudioTooLong,
  TimingTensorType, TimingTokenCount, TokenDurationInvalid, TimingPcmMismatch,
  NonFinitePcm, SilentPcm, TensorMetadataUnavailable, ClippedPcm
};

struct OutputValidationObservation {
  OutputValidationReason reason = OutputValidationReason::None;
  bool audio_measured = false, duration_stats_available = false, duration_sum_overflow = false;
  int64_t output_count = -1, audio_frames = -1, audio_type = -1, audio_rank = -1;
  int64_t timing_count = -1, timing_type = -1, timing_rank = -1;
  int64_t duration_sum = 0, duration_min = 0, duration_max = 0;
};

struct OutputFailure : std::runtime_error {
  explicit OutputFailure(OutputValidationReason reason)
      : std::runtime_error("Kokoro output validation failed"), reason(reason) {}
  OutputValidationReason reason;
};

inline void RequireOutput(bool condition, OutputValidationReason reason,
    OutputValidationObservation &observation) {
  if (!condition) {
    observation.reason = reason;
    throw OutputFailure(reason);
  }
}

// At most the model's 511 token durations are read. Summaries remain useful
// when PCM is too long or timing is invalid; an overflow never wraps a sum.
inline void ObserveDurations(const int64_t *durations, int64_t count,
    OutputValidationObservation &observation) {
  if (!durations || count <= 0 || count > 511) return;
  observation.duration_stats_available = true;
  observation.duration_min = observation.duration_max = durations[0];
  observation.duration_sum = 0;
  observation.duration_sum_overflow = false;
  for (int64_t i = 0; i < count; ++i) {
    const auto value = durations[i];
    observation.duration_min = std::min(observation.duration_min, value);
    observation.duration_max = std::max(observation.duration_max, value);
    if (!observation.duration_sum_overflow) {
      const auto sum = observation.duration_sum;
      if ((value > 0 && sum > std::numeric_limits<int64_t>::max() - value) ||
          (value < 0 && sum < std::numeric_limits<int64_t>::min() - value)) {
        observation.duration_sum_overflow = true;
        observation.duration_sum = 0;
      } else {
        observation.duration_sum += value;
      }
    }
  }
}

// Tensor metadata and duration summaries must be observed before calling this
// function. No PCM is scanned until its type and bounded length are validated.
inline void ValidateOutputStructure(OutputValidationObservation &observation,
    int64_t expected_tokens, int64_t sample_rate, int64_t samples_per_unit,
    int64_t float_type, int64_t int64_type) {
  using Reason = OutputValidationReason;
  RequireOutput(observation.output_count == 2, Reason::OutputCount, observation);
  RequireOutput(observation.audio_type == float_type, Reason::AudioTensorType, observation);
  RequireOutput(observation.audio_frames > 0, Reason::AudioFrameCount, observation);
  RequireOutput(observation.audio_frames <= 30 * sample_rate, Reason::AudioTooLong, observation);
  RequireOutput(observation.timing_type == int64_type, Reason::TimingTensorType, observation);
  RequireOutput(observation.timing_count == expected_tokens, Reason::TimingTokenCount, observation);
  RequireOutput(observation.duration_stats_available && !observation.duration_sum_overflow &&
      observation.duration_min > 0 && observation.duration_sum > 0 &&
      observation.duration_sum <= observation.audio_frames / samples_per_unit,
      Reason::TokenDurationInvalid, observation);
  RequireOutput(observation.duration_sum * samples_per_unit == observation.audio_frames,
      Reason::TimingPcmMismatch, observation);
}

inline void ValidateMeasuredAudio(const AudioMeasurement &audio, OutputValidationObservation &observation) {
  observation.audio_measured = true;
  RequireOutput(audio.finite, OutputValidationReason::NonFinitePcm, observation);
  RequireOutput(audio.NonSilent(), OutputValidationReason::SilentPcm, observation);
  // Validate the original float waveform before it is copied to Java or any
  // playback conversion can clamp it. The speech quality target is zero
  // clipped samples, including exact full-scale values measured by the same
  // >= 1.0 rule as the benchmark. Preserve the measured signal on failure so
  // an accelerator's unplayed phrase can be retried on CPU without concealing
  // corruption behind normalization or a reference-relative tolerance.
  RequireOutput(audio.clipped_samples == 0, OutputValidationReason::ClippedPcm, observation);
}

// A long sequence collapsed to the minimum duration at every token matched
// corrupt Adreno output in the device benchmark. Reject it before playback;
// the caller can use CPU fallback. This does not establish GPU correctness.
inline void ValidateGpuDurationPattern(OutputValidationObservation &observation) {
  RequireOutput(observation.timing_count < 16 || observation.duration_min != 1 ||
      observation.duration_max != 1, OutputValidationReason::TokenDurationInvalid, observation);
}

}  // namespace kokoro_runtime
#endif
