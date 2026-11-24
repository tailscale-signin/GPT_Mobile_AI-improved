#include "kokoro_output_validation.h"
#include <fstream>
#include <iostream>
#include <vector>

int main(int argc, char **argv) {
  if (argc != 2) return 2;
  const uint32_t endian = 1;
  if (*reinterpret_cast<const uint8_t *>(&endian) != 1) return 3;
  std::ifstream input(argv[1], std::ios::binary);
  uint32_t frames = 0, tokens = 0;
  input.read(reinterpret_cast<char *>(&frames), sizeof(frames));
  input.read(reinterpret_cast<char *>(&tokens), sizeof(tokens));
  if (!input || frames == 0 || frames > 720000 || tokens == 0 || tokens > 511) return 4;
  std::vector<float> samples(frames);
  std::vector<int64_t> durations(tokens);
  input.read(reinterpret_cast<char *>(samples.data()), samples.size() * sizeof(float));
  input.read(reinterpret_cast<char *>(durations.data()), durations.size() * sizeof(int64_t));
  if (!input || input.peek() != std::char_traits<char>::eof()) return 5;
  kokoro_runtime::OutputValidationObservation observation;
  observation.output_count = 2;
  observation.audio_frames = frames; observation.audio_type = 1; observation.audio_rank = 1;
  observation.timing_count = tokens; observation.timing_type = 7; observation.timing_rank = 1;
  kokoro_runtime::ObserveDurations(durations.data(), tokens, observation);
  kokoro_runtime::ValidateOutputStructure(observation, tokens, 24000, 600, 1, 7);
  const auto measured = kokoro_runtime::MeasureAudio(samples.data(), samples.size());
  kokoro_runtime::ValidateMeasuredAudio(measured, observation);
  std::cout << "{\"frames\":" << frames << ",\"tokens\":" << tokens
            << ",\"rms\":" << measured.rms << ",\"peak\":" << measured.peak
            << ",\"clipped_samples\":" << measured.clipped_samples
            << ",\"current_validator_passed\":true}" << std::endl;
}
