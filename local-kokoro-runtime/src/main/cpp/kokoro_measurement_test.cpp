#include "kokoro_measurement.h"
#include "kokoro_shape_cache.h"
#include "kokoro_gpu_discovery.h"
#include <limits>
#include <stdexcept>

namespace {
void Require(bool pass) { if (!pass) throw std::runtime_error("Speech measurement regression"); }
struct Session { explicit Session(int value) : value(value) {} int value; };
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

  // The reported 25/93-token HTP pair exceeds the old 640 MiB budget. With
  // observed free headroom both exact sessions survive alternating hits.
  constexpr int64_t mib = 1024 * 1024;
  constexpr int64_t short_bytes = 323715810, medium_bytes = 410095616;
  auto allowance = kokoro_runtime::AdmitShapeCache(true, 2 * 1024 * mib, short_bytes, medium_bytes);
  Require(allowance.limit == 2 && allowance.budget <= kokoro_runtime::kShapeCacheMaximumBudgetBytes);
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
