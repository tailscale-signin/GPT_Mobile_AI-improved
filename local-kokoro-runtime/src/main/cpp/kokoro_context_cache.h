#ifndef MELO_KOKORO_CONTEXT_CACHE_H_
#define MELO_KOKORO_CONTEXT_CACHE_H_

#include <cerrno>
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <set>
#include <sstream>
#include <sys/file.h>
#include <sys/statvfs.h>
#include <vector>
#include "kokoro_digest.h"

namespace kokoro_runtime {

enum class GraphPreparation : int64_t { None, CpuDynamic, Compiled, DiskContext, Resident };
enum class ContextCacheStatus : int64_t {
  Disabled, Miss, Hit, Stored, Invalidated, Unavailable, Pending, WriteFailed, TooLarge, Busy, NotAccessed,
  InvalidConfiguration, IdentityUnavailable, FirmwareUnavailable, DirectoryUnavailable, LockUnavailable,
  InventoryUnavailable, SpaceQueryFailed, InsufficientStorage, HitPreviousProcess
};
struct ContextCacheLimits {
  size_t entries = 6;
  int64_t bytes = 512LL * 1024 * 1024;
  int64_t entry_bytes = 256LL * 1024 * 1024;
  int64_t free_reserve_bytes = 64LL * 1024 * 1024;
};

// The temporary BASIC model belongs only to one private export transaction.
// Its hidden name is deliberately inadmissible to the published-cache inventory.
// The size bound is checked after ORT saves it; it is not an in-flight writer cap.
class ContextSourceSnapshot {
 public:
  static constexpr int64_t kMaximumBytes = 512LL * 1024 * 1024;
  explicit ContextSourceSnapshot(const std::string &context_path) {
    const std::filesystem::path output(context_path), parent = output.parent_path();
    struct stat metadata{};
    if (!output.is_absolute() || output.filename() != "context.onnx" ||
        parent.filename().string().rfind("pending-", 0) != 0 ||
        std::filesystem::canonical(parent) != parent || lstat(parent.c_str(), &metadata) ||
        !S_ISDIR(metadata.st_mode) || metadata.st_uid != geteuid() || (metadata.st_mode & 0777) != 0700)
      throw std::runtime_error("Speech context source directory invalid");
    path_ = parent / ".source.onnx";
    const int fd = open(path_.c_str(), O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) throw std::runtime_error("Speech context source unavailable");
    close(fd);
  }
  ContextSourceSnapshot(const ContextSourceSnapshot &) = delete;
  ContextSourceSnapshot &operator=(const ContextSourceSnapshot &) = delete;
  ~ContextSourceSnapshot() { if (!path_.empty()) unlink(path_.c_str()); }
  std::string path() const { return path_.string(); }
  void Validate() const {
    struct stat metadata{};
    if (lstat(path_.c_str(), &metadata) || !S_ISREG(metadata.st_mode) ||
        metadata.st_uid != geteuid() || metadata.st_nlink != 1 || metadata.st_size <= 0 || metadata.st_size > kMaximumBytes)
      throw std::runtime_error("Speech context source bounds");
  }
  void Remove() {
    if (path_.empty() || unlink(path_.c_str())) throw std::runtime_error("Speech context source cleanup failed");
    path_.clear();
  }
 private:
  std::filesystem::path path_;
};

// One transaction holds a nonblocking process lock through context loading or
// first inference. Busy/unwritable/unsupported caches never prevent synthesis.
// A published directory is complete: embedded EPContext model, any generated
// CPU-weight sidecars, and their SHA-256 manifest. No partial entry is loadable.
// Limits bound retained artifacts, not the provider's in-flight export writer.
class CompiledContextCache {
 public:
  CompiledContextCache(const std::string &root, const std::string &identity,
      ContextCacheLimits limits = {}) : identity_(identity), limits_(limits) {
    if (root.empty()) return;
    status = ContextCacheStatus::InvalidConfiguration;
    try {
      if (!IsSha256(identity) || limits.entries == 0 || limits.bytes <= 0 || limits.entry_bytes <= 0 ||
          limits.entry_bytes > limits.bytes || limits.free_reserve_bytes < 0) return;
      status = ContextCacheStatus::DirectoryUnavailable;
      const std::filesystem::path requested(root);
      if (!requested.is_absolute() || std::filesystem::canonical(requested) != requested) return;
      struct stat metadata{};
      if (lstat(root.c_str(), &metadata) || !S_ISDIR(metadata.st_mode) || metadata.st_uid != geteuid() ||
          (metadata.st_mode & (S_IWGRP | S_IWOTH))) return;
      root_ = requested;
      status = ContextCacheStatus::LockUnavailable;
      lock_ = open((root_ / ".lock").c_str(), O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600);
      if (lock_ < 0 || fstat(lock_, &metadata) || !S_ISREG(metadata.st_mode) || metadata.st_uid != geteuid()) return;
      if (flock(lock_, LOCK_EX | LOCK_NB)) {
        if (errno == EWOULDBLOCK || errno == EAGAIN) status = ContextCacheStatus::Busy;
        return;
      }
      locked_ = true;
      status = ContextCacheStatus::InventoryUnavailable;
      path_ = root_ / identity_;
      // Interrupted exports are never inputs. Only owned names inside the
      // canonical app cache are retired; remove_all does not follow symlinks.
      size_t visited = 0;
      for (const auto &entry : std::filesystem::directory_iterator(root_)) {
        if (++visited > 4096) throw std::runtime_error("Speech context cache inventory bounds");
        const auto name = entry.path().filename().string();
        if (name.rfind("pending-", 0) == 0 && SafeName(name)) Remove(entry.path());
      }
      Trim(0, 0);
      status = ContextCacheStatus::Miss;
    } catch (...) { Release(); }
  }
  CompiledContextCache(const CompiledContextCache &) = delete;
  CompiledContextCache &operator=(const CompiledContextCache &) = delete;
  ~CompiledContextCache() { Discard(); Release(); }

  bool available() const { return locked_; }
  std::string Lookup() {
    if (!locked_) return {};
    try {
      if (!std::filesystem::exists(path_) && !std::filesystem::is_symlink(path_)) return {};
      if (!Verify()) { Invalidate(); return {}; }
      std::filesystem::last_write_time(path_, std::filesystem::file_time_type::clock::now());
      status = previous_process_ ? ContextCacheStatus::HitPreviousProcess : ContextCacheStatus::Hit;
      return (path_ / "context.onnx").string();
    } catch (...) { Invalidate(); return {}; }
  }
  std::string BeginExport(int64_t temporary_bytes = 0) {
    if (!locked_) return {};
    try {
      struct statvfs space{};
      if (temporary_bytes < 0 || statvfs(root_.c_str(), &space)) {
        status = ContextCacheStatus::SpaceQueryFailed; return {};
      }
      if (static_cast<long double>(space.f_bavail) * space.f_frsize <
              static_cast<long double>(limits_.entry_bytes) + limits_.free_reserve_bytes + temporary_bytes) {
        status = ContextCacheStatus::InsufficientStorage; return {};
      }
      std::string pattern = (root_ / "pending-XXXXXX").string();
      auto *directory = mkdtemp(pattern.data());
      if (!directory) { status = ContextCacheStatus::WriteFailed; return {}; }
      staging_ = directory;
      status = ContextCacheStatus::Pending;
      return (staging_ / "context.onnx").string();
    } catch (...) { status = ContextCacheStatus::WriteFailed; return {}; }
  }
  bool Commit() {
    if (!locked_ || staging_.empty()) return false;
    try {
      auto files = Inventory(staging_, true);
      if (files.empty() || files.size() > 15 || std::none_of(files.begin(), files.end(), [](const File &file) { return file.name == "context.onnx"; }))
        throw std::runtime_error("Speech context export missing");
      int64_t total = 0;
      std::ostringstream manifest;
      const auto origin = ProcessOrigin();
      manifest << (origin.empty() ? "kokoro-ep-context-v1" : "kokoro-ep-context-v2") << '\n' << identity_ << '\n';
      if (!origin.empty()) manifest << origin << '\n';
      for (const auto &file : files) {
        total += file.bytes;
        manifest << file.bytes << ' ' << file.digest << ' ' << file.name << '\n';
        Sync(staging_ / file.name, false);
      }
      const auto value = manifest.str();
      if (total > limits_.entry_bytes - static_cast<int64_t>(value.size())) {
        status = ContextCacheStatus::TooLarge; Discard(); return false;
      }
      Write(staging_ / "manifest", value);
      Sync(staging_, true);
      Trim(total + value.size(), 1);
      if (std::filesystem::exists(path_) || std::filesystem::is_symlink(path_)) Remove(path_);
      std::filesystem::rename(staging_, path_);
      staging_.clear();
      Sync(root_, true);
      bytes = total + value.size();
      status = ContextCacheStatus::Stored;
      return true;
    } catch (const std::length_error &) { status = ContextCacheStatus::TooLarge; }
    catch (...) { status = ContextCacheStatus::WriteFailed; }
    Discard(); return false;
  }
  void Invalidate() noexcept {
    if (!locked_) return;
    try { if (std::filesystem::exists(path_) || std::filesystem::is_symlink(path_)) { Remove(path_); ++evictions; } }
    catch (...) {}
    status = ContextCacheStatus::Invalidated;
    bytes = 0;
  }
  void Discard() noexcept {
    if (!staging_.empty()) { try { Remove(staging_); } catch (...) {} staging_.clear(); }
  }
  ContextCacheStatus status = ContextCacheStatus::Disabled;
  int64_t bytes = 0;
  int64_t evictions = 0;

 private:
  struct File { std::string name; int64_t bytes; std::string digest; };
  struct Entry { std::filesystem::path path; int64_t bytes; std::filesystem::file_time_type used; };
  static bool SafeName(const std::string &name) {
    return !name.empty() && name.size() <= 128 && name.front() != '.' &&
        name.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-") == std::string::npos;
  }
  static void Remove(const std::filesystem::path &path) { std::filesystem::remove_all(path); }
  static void Sync(const std::filesystem::path &path, bool directory) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW | (directory ? O_DIRECTORY : 0));
    if (fd < 0) throw std::runtime_error("Speech context sync unavailable");
    const int result = fsync(fd); close(fd);
    if (result) throw std::runtime_error("Speech context sync failed");
  }
  static void Write(const std::filesystem::path &path, const std::string &value) {
    const int fd = open(path.c_str(), O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) throw std::runtime_error("Speech context manifest unavailable");
    struct Close { int fd; ~Close() { close(fd); } } close_file{fd};
    size_t done = 0;
    while (done < value.size()) {
      const auto count = write(fd, value.data() + done, value.size() - done);
      if (count <= 0) throw std::runtime_error("Speech context manifest write failed");
      done += count;
    }
    if (fsync(fd)) throw std::runtime_error("Speech context manifest sync failed");
  }
  std::vector<File> Inventory(const std::filesystem::path &directory, bool digest) const {
    struct stat metadata{};
    if (lstat(directory.c_str(), &metadata) || !S_ISDIR(metadata.st_mode) || metadata.st_uid != geteuid())
      throw std::runtime_error("Speech context directory invalid");
    std::vector<File> files;
    int64_t total = 0;
    for (const auto &entry : std::filesystem::directory_iterator(directory)) {
      const auto name = entry.path().filename().string();
      if (!SafeName(name) || files.size() >= 16 || lstat(entry.path().c_str(), &metadata) ||
          !S_ISREG(metadata.st_mode) || metadata.st_uid != geteuid() || metadata.st_size <= 0)
        throw std::runtime_error("Speech context artifact invalid");
      if (metadata.st_size > limits_.entry_bytes - total) throw std::length_error("Speech context artifact too large");
      total += metadata.st_size;
      files.push_back({name, metadata.st_size, digest ? DigestFile(entry.path(), limits_.entry_bytes) : ""});
    }
    std::sort(files.begin(), files.end(), [](const File &a, const File &b) { return a.name < b.name; });
    return files;
  }
  // This opaque marker stays inside the private manifest. Only the observed
  // cross-process hit is exported. Legacy entries remain usable without it.
  static std::string ProcessOrigin() {
    static const auto secret = [] {
      char random[32];
      std::ifstream input("/dev/urandom", std::ios::binary);
      return input.read(random, sizeof(random)) ? std::string(random, sizeof(random)) : std::string{};
    }();
    return secret.empty() ? std::string{} : Digest(secret + std::to_string(getpid()));
  }
  bool Verify() {
    const auto files = Inventory(path_, false);
    const auto manifest_file = std::find_if(files.begin(), files.end(), [](const File &file) { return file.name == "manifest"; });
    if (manifest_file == files.end() || manifest_file->bytes > 8192) return false;
    std::ifstream input(path_ / "manifest");
    std::string line;
    if (!std::getline(input, line)) return false;
    const bool has_origin = line == "kokoro-ep-context-v2";
    if ((!has_origin && line != "kokoro-ep-context-v1") || !std::getline(input, line) || line != identity_) return false;
    previous_process_ = false;
    if (has_origin) {
      if (!std::getline(input, line) || !IsSha256(line)) return false;
      const auto current = ProcessOrigin();
      previous_process_ = !current.empty() && line != current;
    }
    std::set<std::string> seen{"manifest"};
    int64_t total = manifest_file->bytes;
    while (std::getline(input, line)) {
      std::istringstream row(line);
      int64_t size = 0; std::string digest, name, excess;
      if (!(row >> size >> digest >> name) || row >> excess || size <= 0 || !IsSha256(digest) || !SafeName(name) ||
          !seen.insert(name).second || seen.size() > 16) return false;
      const auto file = std::find_if(files.begin(), files.end(), [&](const File &value) { return value.name == name; });
      if (file == files.end() || file->bytes != size || size > limits_.entry_bytes - total ||
          DigestFile((path_ / name).string(), limits_.entry_bytes) != digest) return false;
      total += size;
    }
    if (!input.eof() || !seen.count("context.onnx") || seen.size() != files.size()) return false;
    bytes = total;
    return true;
  }
  void Trim(int64_t incoming, size_t count) {
    std::vector<Entry> entries;
    int64_t total = incoming;
    for (const auto &entry : std::filesystem::directory_iterator(root_)) {
      if (!IsSha256(entry.path().filename().string())) continue;
      try {
        int64_t size = 0;
        for (const auto &file : Inventory(entry.path(), false)) size += file.bytes;
        total += size;
        entries.push_back({entry.path(), size, std::filesystem::last_write_time(entry.path())});
      } catch (...) { Remove(entry.path()); ++evictions; }
    }
    std::sort(entries.begin(), entries.end(), [](const Entry &a, const Entry &b) { return a.used < b.used; });
    size_t retained = entries.size() + count;
    for (const auto &entry : entries) {
      if (retained <= limits_.entries && total <= limits_.bytes) break;
      Remove(entry.path); total -= entry.bytes; --retained; ++evictions;
    }
  }
  void Release() noexcept {
    if (lock_ >= 0) { if (locked_) flock(lock_, LOCK_UN); close(lock_); }
    lock_ = -1; locked_ = false;
  }
  std::string identity_;
  ContextCacheLimits limits_;
  std::filesystem::path root_, path_, staging_;
  int lock_ = -1;
  bool locked_ = false;
  bool previous_process_ = false;
};

}  // namespace kokoro_runtime
#endif
