#ifndef MELO_KOKORO_DIGEST_H_
#define MELO_KOKORO_DIGEST_H_

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

namespace kokoro_runtime {

// SHA-256 identifies cache inputs and detects interrupted/corrupt artifacts.
// No source text, model contents, paths or driver logs enter diagnostics.
class Sha256 {
 public:
  void Update(const void *source, size_t size) {
    const auto *bytes = static_cast<const uint8_t *>(source);
    length_ += size;
    while (size) {
      const auto take = std::min(size, block_.size() - used_);
      std::memcpy(block_.data() + used_, bytes, take);
      bytes += take; size -= take; used_ += take;
      if (used_ == block_.size()) { Transform(); used_ = 0; }
    }
  }
  std::string Finish() {
    const auto bits = length_ * 8;
    const uint8_t marker = 0x80, zero = 0;
    Update(&marker, 1);
    while (used_ != 56) Update(&zero, 1);
    uint8_t length[8];
    for (size_t i = 0; i < 8; ++i) length[7 - i] = static_cast<uint8_t>(bits >> (8 * i));
    Update(length, sizeof(length));
    static constexpr char hex[] = "0123456789abcdef";
    std::string result;
    result.reserve(64);
    for (const auto word : state_) for (int shift = 28; shift >= 0; shift -= 4)
      result.push_back(hex[(word >> shift) & 15]);
    return result;
  }
 private:
  static uint32_t Rotate(uint32_t value, unsigned count) { return (value >> count) | (value << (32 - count)); }
  void Transform() {
    static constexpr uint32_t constants[64] = {
      0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
      0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
      0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
      0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
      0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
      0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
      0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
      0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    uint32_t words[64];
    for (size_t i = 0; i < 16; ++i) words[i] = (uint32_t(block_[4*i]) << 24) |
        (uint32_t(block_[4*i+1]) << 16) | (uint32_t(block_[4*i+2]) << 8) | block_[4*i+3];
    for (size_t i = 16; i < 64; ++i) {
      const auto a = words[i-15], b = words[i-2];
      words[i] = words[i-16] + (Rotate(a,7) ^ Rotate(a,18) ^ (a >> 3)) + words[i-7] +
          (Rotate(b,17) ^ Rotate(b,19) ^ (b >> 10));
    }
    auto a=state_[0], b=state_[1], c=state_[2], d=state_[3], e=state_[4], f=state_[5], g=state_[6], h=state_[7];
    for (size_t i = 0; i < 64; ++i) {
      const auto first = h + (Rotate(e,6)^Rotate(e,11)^Rotate(e,25)) + ((e&f)^(~e&g)) + constants[i] + words[i];
      const auto second = (Rotate(a,2)^Rotate(a,13)^Rotate(a,22)) + ((a&b)^(a&c)^(b&c));
      h=g; g=f; f=e; e=d+first; d=c; c=b; b=a; a=first+second;
    }
    state_[0]+=a; state_[1]+=b; state_[2]+=c; state_[3]+=d;
    state_[4]+=e; state_[5]+=f; state_[6]+=g; state_[7]+=h;
  }
  std::array<uint32_t,8> state_{0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
  std::array<uint8_t,64> block_{};
  size_t used_ = 0;
  uint64_t length_ = 0;
};

inline bool IsSha256(const std::string &value) {
  return value.size() == 64 && value.find_first_not_of("0123456789abcdef") == std::string::npos;
}
inline std::string Digest(const std::string &value) {
  Sha256 hash; hash.Update(value.data(), value.size()); return hash.Finish();
}
inline std::string DigestFile(const std::string &path, int64_t maximum) {
  const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
  if (fd < 0) throw std::runtime_error("Speech cache input unavailable");
  struct Close { int fd; ~Close() { close(fd); } } guard{fd};
  struct stat before{}, after{};
  if (fstat(fd, &before) || !S_ISREG(before.st_mode) || before.st_size <= 0 || before.st_size > maximum)
    throw std::runtime_error("Speech cache input bounds");
  Sha256 hash;
  std::array<uint8_t,65536> buffer{};
  int64_t read_bytes = 0;
  for (;;) {
    const auto count = read(fd, buffer.data(), buffer.size());
    if (count < 0) throw std::runtime_error("Speech cache input read failed");
    if (!count) break;
    read_bytes += count;
    if (read_bytes > maximum) throw std::runtime_error("Speech cache input grew");
    hash.Update(buffer.data(), static_cast<size_t>(count));
  }
  if (fstat(fd, &after) || read_bytes != before.st_size || before.st_size != after.st_size ||
      before.st_mtim.tv_sec != after.st_mtim.tv_sec || before.st_mtim.tv_nsec != after.st_mtim.tv_nsec ||
      before.st_ctim.tv_sec != after.st_ctim.tv_sec || before.st_ctim.tv_nsec != after.st_ctim.tv_nsec)
    throw std::runtime_error("Speech cache input changed");
  return hash.Finish();
}

}  // namespace kokoro_runtime
#endif
