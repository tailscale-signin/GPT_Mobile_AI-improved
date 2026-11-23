#ifndef MELO_KOKORO_MEASUREMENT_H_
#define MELO_KOKORO_MEASUREMENT_H_

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>

namespace kokoro_runtime {

constexpr int kInputByteLimit = 128;

// These reason codes are an allowlisted diagnostic, never caller text or a
// driver exception. Keep counts useful even when encoding fails before Run.
struct InputFailure : std::runtime_error {
  InputFailure(const char *code, int bytes, int tokens = 0, int token_limit = 511)
      : std::runtime_error(code), code(code), bytes(bytes), tokens(tokens), token_limit(token_limit) {}
  const char *code;
  int bytes, tokens, token_limit;
};

struct AudioMeasurement {
  double rms = 0, peak = 0;
  int64_t clipped_samples = 0, non_silent_samples = 0;
  bool finite = true;
  bool NonSilent() const { return finite && non_silent_samples > 0 && rms >= 1e-6; }
};

inline AudioMeasurement MeasureAudio(const float *pcm, size_t size) {
  AudioMeasurement result;
  double squares = 0;
  for (size_t i = 0; i < size; ++i) {
    const double sample = pcm[i];
    if (!std::isfinite(sample)) { result.finite = false; continue; }
    const double absolute = std::abs(sample);
    result.peak = std::max(result.peak, absolute);
    squares += sample * sample;
    if (absolute >= 1.) ++result.clipped_samples;
    if (absolute >= 1e-5) ++result.non_silent_samples;
  }
  result.rms = size ? std::sqrt(squares / size) : 0;
  return result;
}

// Discovery summaries expose only a bounded grammar, not library paths, device
// labels or exception strings. Hardware names may be controlled by a driver.
inline std::string SafeDiscoveryAtom(const char *value) {
  std::string result;
  if (!value) return "unknown";
  for (size_t i = 0; value[i] && i < 48; ++i) {
    const char c = value[i];
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
        (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') result += c;
  }
  return result.empty() ? "unknown" : result;
}

}  // namespace kokoro_runtime
#endif
