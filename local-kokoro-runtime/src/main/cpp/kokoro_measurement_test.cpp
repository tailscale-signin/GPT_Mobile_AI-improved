#include "kokoro_measurement.h"
#include "kokoro_output_validation.h"
#include "kokoro_shape_cache.h"
#include "kokoro_gpu_discovery.h"
#include <limits>
#include <stdexcept>

namespace {
void Require(bool pass) { if (!pass) throw std::runtime_error("Speech measurement regression"); }
struct Session { explicit Session(int value) : value(value) {} int value; };
kokoro_runtime::OutputValidationObservation Output(int64_t frames, int64_t tokens) {
  kokoro_runtime::OutputValidationObservation result;
  result.output_count = 2;
  result.audio_frames = frames; result.audio_type = 1; result.audio_rank = 1;
  result.timing_count = tokens; result.timing_type = 7; result.timing_rank = 1;
  return result;
}
void InvalidStructure(kokoro_runtime::OutputValidationObservation &value, int64_t expected,
    kokoro_runtime::OutputValidationReason reason) {
  try {
    kokoro_runtime::ValidateOutputStructure(value, expected, 24000, 600, 1, 7);
    throw std::runtime_error("Invalid structure passed");
  } catch (const kokoro_runtime::OutputFailure &failure) {
    Require(failure.reason == reason && value.reason == reason && !value.audio_measured);
  }
}
void ValidationObservations() {
  using namespace kokoro_runtime;
  const int64_t normal[] = {12, 2, 3};
  auto value = Output(17 * 600, 3);
  ObserveDurations(normal, 3, value);
  ValidateOutputStructure(value, 3, 24000, 600, 1, 7);
  Require(value.duration_stats_available && value.duration_sum == 17 && value.duration_min == 2 && value.duration_max == 12);
  Require(!value.audio_measured);  // Structural success is not a waveform scan.
  auto too_long = Output(93 * 45 * 600, 93);
  std::vector<int64_t> saturated(93, 45);
  ObserveDurations(saturated.data(), saturated.size(), too_long);
  InvalidStructure(too_long, 93, OutputValidationReason::AudioTooLong);
  Require(too_long.duration_sum == 4185 && too_long.duration_min == 45 && too_long.duration_max == 45);
  auto short_timing = value; short_timing.timing_count = 2;
  InvalidStructure(short_timing, 3, OutputValidationReason::TimingTokenCount);
  auto wrong_type = value; wrong_type.audio_type = 7;
  InvalidStructure(wrong_type, 3, OutputValidationReason::AudioTensorType);
  auto wrong_timing_type = value; wrong_timing_type.timing_type = 1;
  InvalidStructure(wrong_timing_type, 3, OutputValidationReason::TimingTensorType);
  auto no_output = value; no_output.output_count = 1;
  InvalidStructure(no_output, 3, OutputValidationReason::OutputCount);
  auto empty = value; empty.audio_frames = 0;
  InvalidStructure(empty, 3, OutputValidationReason::AudioFrameCount);
  auto mismatch = value; mismatch.audio_frames++;
  InvalidStructure(mismatch, 3, OutputValidationReason::TimingPcmMismatch);
  const int64_t invalid[] = {12, 0, 5};
  auto invalid_timing = value; ObserveDurations(invalid, 3, invalid_timing);
  InvalidStructure(invalid_timing, 3, OutputValidationReason::TokenDurationInvalid);
  const int64_t overflow[] = {INT64_MAX, 1, 1};
  auto overflow_timing = value; ObserveDurations(overflow, 3, overflow_timing);
  InvalidStructure(overflow_timing, 3, OutputValidationReason::TokenDurationInvalid);
  Require(overflow_timing.duration_sum_overflow && overflow_timing.duration_max == INT64_MAX);
  const int64_t underflow[] = {INT64_MIN, -1, 1};
  auto underflow_timing = value; ObserveDurations(underflow, 3, underflow_timing);
  InvalidStructure(underflow_timing, 3, OutputValidationReason::TokenDurationInvalid);
  Require(underflow_timing.duration_sum_overflow && underflow_timing.duration_min == INT64_MIN);
  auto bounded = Output(600, 512);
  ObserveDurations(normal, 512, bounded);  // Must not read beyond the supplied array.
  Require(!bounded.duration_stats_available);
  const float silence[] = {0, 0, 0};
  try { ValidateMeasuredAudio(MeasureAudio(silence, 3), value); throw std::runtime_error("Silence passed"); }
  catch (const OutputFailure &error) { Require(error.reason == OutputValidationReason::SilentPcm && value.audio_measured); }
  value = Output(600, 1);
  const float corrupt[] = {std::numeric_limits<float>::infinity()};
  try { ValidateMeasuredAudio(MeasureAudio(corrupt, 1), value); throw std::runtime_error("Nonfinite PCM passed"); }
  catch (const OutputFailure &error) { Require(error.reason == OutputValidationReason::NonFinitePcm && value.audio_measured); }

  // One exact full-scale sample is already outside the requested zero-clipping
  // gate; small counts must not disappear inside a relative CPU tolerance.
  for (const float sample : {1.f, -1.f, 1.2f, -1.2f}) {
    auto clipped_output = Output(600, 1);
    std::vector<float> clipped(600, .1f);
    clipped[299] = sample;
    const auto before = clipped;
    const auto measured = MeasureAudio(clipped.data(), clipped.size());
    try { ValidateMeasuredAudio(measured, clipped_output); throw std::runtime_error("Clipped PCM passed"); }
    catch (const OutputFailure &error) {
      Require(error.reason == OutputValidationReason::ClippedPcm && clipped_output.audio_measured &&
          clipped_output.reason == OutputValidationReason::ClippedPcm && measured.finite &&
          measured.clipped_samples == 1 && measured.peak >= 1 && measured.rms > 0);
    }
    Require(clipped == before);  // Validation never repairs or clamps the waveform.
  }
  std::vector<float> reported_gpu(25800, .1f);
  for (size_t i = 0; i < 8; ++i) reported_gpu[100 + i] = i % 2 == 0 ? 1.f : -1.2f;
  const auto measured_gpu = MeasureAudio(reported_gpu.data(), reported_gpu.size());
  auto rejected_gpu = Output(reported_gpu.size(), 1);
  try { ValidateMeasuredAudio(measured_gpu, rejected_gpu); throw std::runtime_error("Sparse GPU clipping passed"); }
  catch (const OutputFailure &error) {
    Require(error.reason == OutputValidationReason::ClippedPcm && measured_gpu.clipped_samples == 8);
  }
  // The first reported GPU phrase was unclipped, but all 43 durations
  // collapsed to one frame unit; reject before any PCM is returned.
  std::vector<int64_t> collapsed(43, 1);
  std::vector<float> collapsed_pcm(43 * 600, .1f);
  auto collapsed_output = Output(collapsed_pcm.size(), collapsed.size());
  ObserveDurations(collapsed.data(), collapsed.size(), collapsed_output);
  ValidateOutputStructure(collapsed_output, 43, 24000, 600, 1, 7);
  ValidateMeasuredAudio(MeasureAudio(collapsed_pcm.data(), collapsed_pcm.size()), collapsed_output);
  try { ValidateGpuDurationPattern(collapsed_output); throw std::runtime_error("Collapsed GPU duration passed"); }
  catch (const OutputFailure &error) {
    Require(error.reason == OutputValidationReason::TokenDurationInvalid && collapsed_output.audio_measured);
  }
  auto varied = Output(44 * 600, 43);
  collapsed[0] = 2;
  ObserveDurations(collapsed.data(), collapsed.size(), varied);
  ValidateGpuDurationPattern(varied);
  // A signal immediately below full scale remains eligible without a changed
  // waveform. Nonfinite corruption retains its existing, more specific reason.
  const float below_scale[] = {std::nextafter(1.f, 0.f), std::nextafter(-1.f, 0.f), .1f};
  auto accepted = Output(600, 1);
  ValidateMeasuredAudio(MeasureAudio(below_scale, 3), accepted);
  Require(accepted.audio_measured && accepted.reason == OutputValidationReason::None);
  const float clipped_nonfinite[] = {1.f, std::numeric_limits<float>::quiet_NaN()};
  auto rejected_nonfinite = Output(600, 1);
  try { ValidateMeasuredAudio(MeasureAudio(clipped_nonfinite, 2), rejected_nonfinite); throw std::runtime_error("Clipped nonfinite PCM passed"); }
  catch (const OutputFailure &error) { Require(error.reason == OutputValidationReason::NonFinitePcm); }
}

int ReplayLongShapes(size_t maximum) {
  using namespace kokoro_runtime;
  constexpr int64_t mib = 1024 * 1024;
  ShapeCache<Session> cache(maximum, kShapeCacheMaximumBudgetBytes);
  int preparations = 0;
  for (int repeat = 0; repeat < 3; ++repeat) for (const int tokens : {84, 71, 84, 27}) {
    const int64_t bytes = (tokens == 84 ? 453 : tokens == 71 ? 335 : 309) * mib;
    const auto found = cache.Find(tokens);
    auto allowance = AdmitShapeCache(true, 3500 * mib, cache.Bytes(), found ? 0 : bytes);
    allowance.limit = std::min(maximum, allowance.limit);
    cache.Configure(allowance);
    if (!found) { cache.Insert(tokens, bytes, std::make_unique<Session>(tokens)); ++preparations; }
    Require(cache.Bytes() <= kShapeCacheMaximumBudgetBytes && cache.Size() <= maximum);
  }
  return preparations;
}
uint32_t vendor = 0x5143, available = 1;
uint64_t type = 4;
bool fail_enumeration = false, fail_info = false;
kokoro_runtime::OpenClApi Api() {
  kokoro_runtime::OpenClApi api;
  api.platforms = [](uint32_t maximum, void **platforms, uint32_t *count) -> int32_t {
    Require(maximum <= 16);
    if (fail_enumeration) return -1001;
    *count = 1; platforms[0] = reinterpret_cast<void *>(1); return 0;
  };
  api.devices = [](void *, uint64_t requested, uint32_t maximum, void **devices, uint32_t *count) -> int32_t {
    Require(requested == 4 && maximum <= 16);
    *count = 1; devices[0] = reinterpret_cast<void *>(2); return 0;
  };
  api.info = [](void *, uint32_t key, size_t bytes, void *output, size_t *) -> int32_t {
    if (fail_info) return -30;
    if (key == 0x1001 && bytes == sizeof(vendor)) *static_cast<uint32_t *>(output) = vendor;
    else if (key == 0x1027 && bytes == sizeof(available)) *static_cast<uint32_t *>(output) = available;
    else if (key == 0x1000 && bytes == sizeof(type)) *static_cast<uint64_t *>(output) = type;
    else return -30;
    return 0;
  };
  return api;
}
}

int main() {
  ValidationObservations();
  const float silence[] = {0, 0, 0};
  Require(!kokoro_runtime::MeasureAudio(silence, 3).NonSilent());
  const float signal[] = {0, .5f, -.5f, 1.f, -1.2f};
  auto audio = kokoro_runtime::MeasureAudio(signal, 5);
  Require(audio.NonSilent() && audio.clipped_samples == 2 && audio.non_silent_samples == 4);
  Require(std::abs(audio.rms - std::sqrt(2.94 / 5)) < 1e-6 && std::abs(audio.peak - 1.2) < 1e-6);
  const float corrupt[] = {1, std::numeric_limits<float>::quiet_NaN()};
  Require(!kokoro_runtime::MeasureAudio(corrupt, 2).finite);
  const float faint[] = {1e-9f, -1e-9f};
  Require(!kokoro_runtime::MeasureAudio(faint, 2).NonSilent());

  kokoro_runtime::ShapeCache<Session> cache(2, 100);
  cache.Insert(43, 40, std::make_unique<Session>(1));
  cache.Insert(117, 40, std::make_unique<Session>(2));
  Require(cache.Find(43)->value == 1 && cache.Size() == 2 && cache.Bytes() == 80);
  Require(cache.Evictions() == 0);
  cache.Reserve(40);  // Evict 117 before allocating its replacement.
  Require(cache.Size() == 1 && cache.Find(117) == nullptr);
  Require(cache.Evictions() == 1);
  cache.Insert(83, 40, std::make_unique<Session>(3));
  Require(cache.Find(43)->value == 1 && cache.Find(83)->value == 3);
  cache.UpdateActiveBytes(80);  // Measured memory growth retires idle 43.
  Require(cache.Size() == 1 && cache.Find(43) == nullptr && cache.Bytes() == 80);
  Require(cache.Evictions() == 2);
  cache.SetLimit(1);
  cache.Reserve(120);  // One indispensable model may exceed the cache budget.
  Require(cache.Size() == 0);
  Require(cache.Evictions() == 3);
  cache.Insert(111, 120, std::make_unique<Session>(4));
  Require(cache.Size() == 1 && cache.Bytes() == 120 && cache.Find(112) == nullptr);
  cache.Erase(111);
  Require(cache.Size() == 0 && cache.Bytes() == 0);
  Require(cache.Evictions() == 4);

  const int64_t gib = 1024LL * 1024 * 1024;
  auto fp32 = kokoro_runtime::AdmitShapeCache(true, 4 * gib, gib, gib, true);
  kokoro_runtime::ShapeCache<Session> fp32_cache(1, gib);
  fp32_cache.Configure(fp32);
  fp32_cache.Insert(25, gib, std::make_unique<Session>(25));
  fp32_cache.Insert(93, gib, std::make_unique<Session>(93));
  Require(fp32_cache.Find(25) && fp32_cache.Find(93) && fp32_cache.Evictions() == 0);
  fp32_cache.Configure(kokoro_runtime::AdmitShapeCache(true, gib / 2, 2 * gib, gib, true));
  Require(fp32_cache.Size() == 1);
  Require(kokoro_runtime::AdmitShapeCache(true, 4 * gib, gib, gib).budget < 2 * gib);

  // The reported 25/93-token HTP pair exceeds the old 640 MiB budget. With
  // observed free headroom both exact sessions survive alternating hits.
  constexpr int64_t mib = 1024 * 1024;
  constexpr int64_t short_bytes = 323715810, medium_bytes = 410095616;
  auto allowance = kokoro_runtime::AdmitShapeCache(true, 2 * 1024 * mib, short_bytes, medium_bytes);
  Require(allowance.limit == 3 && allowance.budget <= kokoro_runtime::kShapeCacheMaximumBudgetBytes);
  kokoro_runtime::ShapeCache<Session> alternating(1, kokoro_runtime::kShapeCacheSingleBudgetBytes);
  alternating.Configure(allowance);
  alternating.Insert(25, short_bytes, std::make_unique<Session>(25));
  alternating.Insert(93, medium_bytes, std::make_unique<Session>(93));
  for (int repeat = 0; repeat < 6; ++repeat) {
    Require(alternating.Find(25)->value == 25 && alternating.Find(93)->value == 93);
    // No new resident growth on a hit must not count the other cached model.
    const auto before = alternating.Bytes();
    alternating.UpdateActiveBytes(kokoro_runtime::AddResidentGrowth(medium_bytes, before, before));
  }
  Require(alternating.Size() == 2 && alternating.Evictions() == 0);
  Require(kokoro_runtime::AddResidentGrowth(medium_bytes, 700 * mib, 710 * mib) == medium_bytes + 10 * mib);
  Require(kokoro_runtime::AddResidentGrowth(medium_bytes, 0, 710 * mib) == medium_bytes);
  Require(kokoro_runtime::AddResidentGrowth(INT64_MAX - 2, 1, 10) == INT64_MAX);
  alternating.Configure(kokoro_runtime::AdmitShapeCache(true, 400 * mib, alternating.Bytes(), 0));
  Require(alternating.Size() == 1 && alternating.Find(93)->value == 93);
  Require(kokoro_runtime::AdmitShapeCache(true, 0, short_bytes, medium_bytes).reason == kokoro_runtime::ShapeCacheAdmission::MemoryUnknown);
  Require(kokoro_runtime::AdmitShapeCache(true, 600 * mib, short_bytes, medium_bytes).limit == 1);
  Require(kokoro_runtime::AdmitShapeCache(false, 8 * 1024 * mib, 0, short_bytes).limit == 1);
  alternating.Configure(kokoro_runtime::AdmitShapeCache(true, 8 * 1024 * mib, medium_bytes, 1400 * mib));
  alternating.Insert(117, 1400 * mib, std::make_unique<Session>(117));
  Require(alternating.Size() == 1 && alternating.Bytes() > alternating.Budget());

  // Counterfactual: the exact 84/71/84/27 workload prepares seven times with
  // two slots, and only its three initial shapes with admitted three slots.
  Require(ReplayLongShapes(2) == 7 && ReplayLongShapes(3) == 3);
  kokoro_runtime::ShapeCache<Session> three(3, kokoro_runtime::kShapeCacheMaximumBudgetBytes);
  three.Insert(84, 453 * mib, std::make_unique<Session>(84));
  three.Insert(71, 335 * mib, std::make_unique<Session>(71));
  three.Insert(27, 309 * mib, std::make_unique<Session>(27));
  Require(three.Size() == 3 && three.Bytes() == 1097 * mib);
  Require(three.Find(84)->value == 84);  // Promote before pressure trims idle entries.
  three.Configure(kokoro_runtime::AdmitShapeCache(true, 500 * mib, three.Bytes(), 0));
  Require(three.Size() == 1 && three.Find(84)->value == 84 && three.Evictions() == 2);
  three.Configure(kokoro_runtime::AdmitShapeCache(true, 3500 * mib, three.Bytes(), 500 * mib));
  three.Insert(71, 500 * mib, std::make_unique<Session>(71));
  three.Insert(27, 500 * mib, std::make_unique<Session>(27));
  Require(three.Size() == 2 && three.Bytes() <= kokoro_runtime::kShapeCacheMaximumBudgetBytes);
  three.Configure(kokoro_runtime::AdmitShapeCache(true, 0, three.Bytes(), 0));
  Require(three.Size() == 1 && three.Find(27)->value == 27);

  // Four distinct long-response shapes previously exceeded the three-slot cache.
  kokoro_runtime::ShapeCache<Session> four(1, kokoro_runtime::kShapeCacheSingleBudgetBytes);
  int preparations = 0;
  for (int repeat = 0; repeat < 6; ++repeat) for (int tokens : {84, 71, 39, 72}) {
    const auto found = four.Find(tokens);
    const auto admitted = kokoro_runtime::AdmitShapeCache(true, 3 * gib, four.Bytes(), found ? 0 : 400 * mib, false, true);
    Require(admitted.limit == 4 && admitted.reason == kokoro_runtime::ShapeCacheAdmission::BoundedFourShapes);
    four.Configure(admitted);
    if (!found) { four.Insert(tokens, 400 * mib, std::make_unique<Session>(tokens)); ++preparations; }
    Require(four.Bytes() <= 2 * gib);
  }
  Require(preparations == 4 && four.Evictions() == 0);
  four.Configure(kokoro_runtime::AdmitShapeCache(true, 2 * gib, four.Bytes(), 0, false, true));
  Require(four.Size() <= 3 && four.Bytes() <= kokoro_runtime::kShapeCacheMaximumBudgetBytes);
  Require(kokoro_runtime::AdmitShapeCache(true, 4 * gib, 0, 400 * mib, true, true).limit == 3);

  // Replay the observed 26/59/71/39/72-token utterance, not padded variants.
  // A four-slot LRU misses all five shapes on each repeat; five admitted
  // exact-shape sessions prepare only once each within the existing ceiling.
  kokoro_runtime::ShapeCache<Session> four_slots(4, 2 * gib);
  kokoro_runtime::ShapeCache<Session> five(1, kokoro_runtime::kShapeCacheSingleBudgetBytes);
  int previous_preparations = 0, repaired_preparations = 0;
  for (int repeat = 0; repeat < 4; ++repeat) for (int tokens : {26, 59, 71, 39, 72}) {
    if (!four_slots.Find(tokens)) {
      four_slots.Insert(tokens, short_bytes, std::make_unique<Session>(tokens));
      ++previous_preparations;
    }
    const auto found = five.Find(tokens);
    const auto admitted = kokoro_runtime::AdmitShapeCache(true, 4 * gib, five.Bytes(), found ? 0 : short_bytes, false, true);
    five.Configure(admitted);
    if (!found) {
      five.Insert(tokens, short_bytes, std::make_unique<Session>(tokens));
      ++repaired_preparations;
    }
    Require(five.Find(tokens)->value == tokens && five.Bytes() <= 2 * gib);
  }
  Require(previous_preparations == 20 && repaired_preparations == 5 && five.Evictions() == 0);
  Require(five.Size() == 5 && five.Bytes() == 5 * short_bytes);
  Require(kokoro_runtime::AdmitShapeCache(true, 4 * gib, five.Bytes(), 0, false, true).reason ==
      kokoro_runtime::ShapeCacheAdmission::BoundedFiveShapes);
  five.Configure(kokoro_runtime::AdmitShapeCache(true, 3 * gib, five.Bytes(), 0, false, true));
  Require(five.Size() == 4 && five.Find(72)->value == 72);
  five.Configure(kokoro_runtime::AdmitShapeCache(true, 0, five.Bytes(), 0, false, true));
  Require(five.Size() == 1 && five.Find(72)->value == 72);
  Require(kokoro_runtime::AdmitShapeCache(true, 4 * gib, 0, short_bytes, false, false).limit == 3);
  Require(kokoro_runtime::AdmitShapeCache(false, 4 * gib, 0, short_bytes, false, true).limit == 1);
  five.Configure(kokoro_runtime::AdmitShapeCache(true, 4 * gib, five.Bytes(), 1100 * mib, false, true));
  five.Insert(117, 1100 * mib, std::make_unique<Session>(117));
  five.Insert(118, 1100 * mib, std::make_unique<Session>(118));
  Require(five.Size() == 1 && five.Bytes() <= 2 * gib);

  using Code = kokoro_runtime::GpuDiscoveryCode;
  Require(kokoro_runtime::ProbeQualcommGpu({}).code == Code::ApiUnavailable);
  Require(kokoro_runtime::ProbeQualcommGpu(Api()).code == Code::Available);
  vendor = 0x10de;
  Require(kokoro_runtime::ProbeQualcommGpu(Api()).code == Code::NoAvailableQualcommGpu);
  vendor = 0x5143; available = 0;
  Require(kokoro_runtime::ProbeQualcommGpu(Api()).code == Code::NoAvailableQualcommGpu);
  available = 1; type = 2;  // A CPU that appears in a broken GPU query is rejected.
  Require(kokoro_runtime::ProbeQualcommGpu(Api()).code == Code::NoAvailableQualcommGpu);
  type = 4; fail_info = true;
  Require(kokoro_runtime::ProbeQualcommGpu(Api()).code == Code::EnumerationFailed);
  fail_info = false; fail_enumeration = true;
  Require(kokoro_runtime::ProbeQualcommGpu(Api()).code == Code::EnumerationFailed);
  Require(kokoro_runtime::SafeDiscoveryAtom("private/path with spaces") == "privatepathwithspaces");
  Require(kokoro_runtime::SafeDiscoveryAtom(std::string(100, 'a').c_str()).size() == 48);
  return 0;
}
