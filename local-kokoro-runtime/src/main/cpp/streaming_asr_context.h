#ifndef GPTMOBILE_STREAMING_ASR_CONTEXT_H_
#define GPTMOBILE_STREAMING_ASR_CONTEXT_H_

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>

namespace asr_runtime {

// QNN consumes the serialized context during synchronous creation. Mapping the
// verified, leased asset avoids two full heap copies of the 609 MB encoder.
// This mapping lives through metadata reading and contextCreateFromBinary;
// QNN's resulting context owns its internal state after the call returns.
class MappedContextBinary {
 public:
  explicit MappedContextBinary(const std::string &path) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) throw std::runtime_error("ASR_CONTEXT_LOAD");
    struct Close { int fd; ~Close() { close(fd); } } close_file{fd};
    struct stat status{};
    if (fstat(fd, &status) != 0 || !S_ISREG(status.st_mode) ||
        status.st_size <= 0 || static_cast<uint64_t>(status.st_size) > 2ULL * 1024 * 1024 * 1024)
      throw std::runtime_error("ASR_CONTEXT_LOAD");
    size_ = static_cast<size_t>(status.st_size);
    data_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
    if (data_ == MAP_FAILED) {
      data_ = nullptr;
      throw std::runtime_error("ASR_CONTEXT_LOAD");
    }
  }
  ~MappedContextBinary() { if (data_) munmap(data_, size_); }
  MappedContextBinary(const MappedContextBinary &) = delete;
  MappedContextBinary &operator=(const MappedContextBinary &) = delete;
  uint8_t *data() const { return static_cast<uint8_t *>(data_); }
  size_t size() const { return size_; }
 private:
  void *data_ = nullptr;
  size_t size_ = 0;
};

}  // namespace asr_runtime
#endif
