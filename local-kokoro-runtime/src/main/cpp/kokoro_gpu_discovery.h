#ifndef MELO_KOKORO_GPU_DISCOVERY_H_
#define MELO_KOKORO_GPU_DISCOVERY_H_

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>

namespace kokoro_runtime {

// Only this small, stable OpenCL C ABI is needed. No ICD or vendor implementation
// is bundled or substituted. Android already opts into optional libOpenCL.so.
struct OpenClApi {
  using Platform = void *;
  using Device = void *;
  int32_t (*platforms)(uint32_t, Platform *, uint32_t *) = nullptr;
  int32_t (*devices)(Platform, uint64_t, uint32_t, Device *, uint32_t *) = nullptr;
  int32_t (*info)(Device, uint32_t, size_t, void *, size_t *) = nullptr;
};

enum class GpuDiscoveryCode {
  NotAttempted, LibraryUnavailable, ApiUnavailable, EnumerationFailed,
  NoAvailableQualcommGpu, Available, AlreadyEnumerated
};

inline const char *GpuDiscoveryName(GpuDiscoveryCode code) {
  switch (code) {
    case GpuDiscoveryCode::LibraryUnavailable: return "library_unavailable";
    case GpuDiscoveryCode::ApiUnavailable: return "api_unavailable";
    case GpuDiscoveryCode::EnumerationFailed: return "enumeration_failed";
    case GpuDiscoveryCode::NoAvailableQualcommGpu: return "no_available_qualcomm_gpu";
    case GpuDiscoveryCode::Available: return "available";
    case GpuDiscoveryCode::AlreadyEnumerated: return "already_enumerated";
    default: return "not_attempted";
  }
}

struct GpuDiscovery {
  GpuDiscoveryCode code = GpuDiscoveryCode::NotAttempted;
  uint32_t vendor = 0, ordinal = 0;
};

inline GpuDiscovery ProbeQualcommGpu(const OpenClApi &api) {
  if (!api.platforms || !api.devices || !api.info) return {GpuDiscoveryCode::ApiUnavailable};
  std::array<OpenClApi::Platform, 16> platforms{};
  uint32_t platform_count = 0;
  if (api.platforms(platforms.size(), platforms.data(), &platform_count) != 0)
    return {GpuDiscoveryCode::EnumerationFailed};
  bool read_failed = false;
  for (uint32_t i = 0; i < std::min<uint32_t>(platform_count, platforms.size()); ++i) {
    std::array<OpenClApi::Device, 16> devices{};
    uint32_t device_count = 0;
    // CL_DEVICE_TYPE_GPU == 1 << 2; an NPU/CPU platform cannot satisfy this.
    const auto status = api.devices(platforms[i], uint64_t{1} << 2, devices.size(), devices.data(), &device_count);
    if (status == -1) continue;  // CL_DEVICE_NOT_FOUND is a normal empty result.
    if (status != 0) { read_failed = true; continue; }
    for (uint32_t j = 0; j < std::min<uint32_t>(device_count, devices.size()); ++j) {
      uint32_t vendor = 0, available = 0;
      uint64_t type = 0;
      // Require a real driver-enumerated, available GPU of the QNN vendor.
      if (!devices[j] || api.info(devices[j], 0x1001, sizeof(vendor), &vendor, nullptr) != 0 ||
          api.info(devices[j], 0x1027, sizeof(available), &available, nullptr) != 0 ||
          api.info(devices[j], 0x1000, sizeof(type), &type, nullptr) != 0) {
        read_failed = true; continue;
      }
      if (vendor == 0x5143 && available != 0 && (type & (uint64_t{1} << 2)) != 0)
        return {GpuDiscoveryCode::Available, vendor, i * 16 + j};
    }
  }
  return {read_failed ? GpuDiscoveryCode::EnumerationFailed : GpuDiscoveryCode::NoAvailableQualcommGpu};
}

}  // namespace kokoro_runtime
#endif
