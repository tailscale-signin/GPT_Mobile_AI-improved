#ifndef MELO_KOKORO_PROFILE_H_
#define MELO_KOKORO_PROFILE_H_

#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>

struct KokoroProfileStats {
  int kernels = 0;
  int64_t microseconds = 0;
};

namespace kokoro_profile_detail {

// Scan once without copying whole events or repeatedly searching nested shape
// and thread records. Only event-level labels and args.provider prove execution.
class Reader {
 public:
  Reader(const std::string &json, const std::string &provider)
      : json_(json), provider_(provider) {}

  KokoroProfileStats Read() {
    Expect('[');
    if (!Take(']')) {
      do {
        if (Peek() == '{') Event();
        else Skip(1);
      } while (Take(','));
      Expect(']');
    }
    Space();
    if (position_ != json_.size()) Invalid();
    return result_;
  }

 private:
  [[noreturn]] static void Invalid() { throw std::runtime_error("Malformed speech profile"); }
  void Space() {
    while (position_ < json_.size() && (json_[position_] == ' ' || json_[position_] == '\n' ||
           json_[position_] == '\r' || json_[position_] == '\t')) ++position_;
  }
  char Peek() { Space(); return position_ < json_.size() ? json_[position_] : '\0'; }
  bool Take(char value) {
    if (Peek() != value) return false;
    ++position_;
    return true;
  }
  void Expect(char value) { if (!Take(value)) Invalid(); }
  static bool Digit(char c) { return c >= '0' && c <= '9'; }

  uint32_t Hex() {
    uint32_t value = 0;
    for (int i = 0; i < 4; ++i) {
      if (position_ == json_.size()) Invalid();
      const char c = json_[position_++];
      const int digit = c >= '0' && c <= '9' ? c - '0' :
          c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
      if (digit < 0) Invalid();
      value = value * 16 + digit;
    }
    return value;
  }

  static void Utf8(std::string *out, uint32_t value) {
    if (!out) return;
    if (value < 0x80) out->push_back(static_cast<char>(value));
    else {
      if (value >= 0x10000) out->push_back(static_cast<char>(0xf0 | (value >> 18)));
      if (value >= 0x800) out->push_back(static_cast<char>((value < 0x10000 ? 0xe0 : 0x80) | ((value >> 12) & 0x3f)));
      out->push_back(static_cast<char>((value < 0x800 ? 0xc0 : 0x80) | ((value >> 6) & 0x3f)));
      out->push_back(static_cast<char>(0x80 | (value & 0x3f)));
    }
  }

  void String(std::string *out = nullptr) {
    Expect('"');
    while (position_ < json_.size()) {
      const unsigned char c = json_[position_++];
      if (c == '"') return;
      if (c < 0x20) Invalid();
      if (c != '\\') { if (out) out->push_back(static_cast<char>(c)); continue; }
      if (position_ == json_.size()) Invalid();
      const char escaped = json_[position_++];
      if (escaped == 'u') {
        uint32_t value = Hex();
        if (value >= 0xd800 && value <= 0xdbff) {
          if (position_ + 2 > json_.size() || json_[position_++] != '\\' || json_[position_++] != 'u') Invalid();
          const uint32_t low = Hex();
          if (low < 0xdc00 || low > 0xdfff) Invalid();
          value = 0x10000 + ((value - 0xd800) << 10) + low - 0xdc00;
        } else if (value >= 0xdc00 && value <= 0xdfff) Invalid();
        Utf8(out, value);
      } else {
        char decoded;
        switch (escaped) {
          case '"': case '\\': case '/': decoded = escaped; break;
          case 'b': decoded = '\b'; break;
          case 'f': decoded = '\f'; break;
          case 'n': decoded = '\n'; break;
          case 'r': decoded = '\r'; break;
          case 't': decoded = '\t'; break;
          default: Invalid();
        }
        if (out) out->push_back(decoded);
      }
    }
    Invalid();
  }

  std::string_view Number() {
    Space();
    const size_t start = position_;
    if (position_ < json_.size() && json_[position_] == '-') ++position_;
    if (position_ == json_.size()) Invalid();
    if (json_[position_] == '0') ++position_;
    else {
      if (!Digit(json_[position_])) Invalid();
      while (position_ < json_.size() && Digit(json_[position_])) ++position_;
    }
    if (position_ < json_.size() && json_[position_] == '.') {
      ++position_;
      if (position_ == json_.size() || !Digit(json_[position_])) Invalid();
      while (position_ < json_.size() && Digit(json_[position_])) ++position_;
    }
    if (position_ < json_.size() && (json_[position_] == 'e' || json_[position_] == 'E')) {
      ++position_;
      if (position_ < json_.size() && (json_[position_] == '+' || json_[position_] == '-')) ++position_;
      if (position_ == json_.size() || !Digit(json_[position_])) Invalid();
      while (position_ < json_.size() && Digit(json_[position_])) ++position_;
    }
    return json_.substr(start, position_ - start);
  }

  void Skip(unsigned depth) {
    if (depth > 64) Invalid();
    const char type = Peek();
    if (type == '"') String();
    else if (type == '{' || type == '[') {
      ++position_;
      const char close = type == '{' ? '}' : ']';
      if (Take(close)) return;
      do {
        if (type == '{') { String(); Expect(':'); }
        Skip(depth + 1);
      } while (Take(','));
      Expect(close);
    } else if (type == '-' || Digit(type)) Number();
    else {
      const std::string_view literal = type == 't' ? "true" : type == 'f' ? "false" : "null";
      if (json_.substr(position_, literal.size()) != literal) Invalid();
      position_ += literal.size();
    }
  }

  void Label(std::string &value) {
    if (Peek() == '"') String(&value);
    else Skip(2);
  }

  void Provider(std::string &provider) {
    if (!Take('{')) { Skip(2); return; }
    bool seen = false;
    if (!Take('}')) {
      do {
        std::string key;
        String(&key);
        Expect(':');
        if (key == "provider") {
          if (seen) Invalid();
          seen = true;
          Label(provider);
        } else Skip(3);
      } while (Take(','));
      Expect('}');
    }
  }

  void Event() {
    Expect('{');
    std::string category, phase, name, provider;
    double duration = 0;
    unsigned seen = 0;
    if (!Take('}')) {
      do {
        std::string key;
        String(&key);
        Expect(':');
        const unsigned bit = key == "cat" ? 1 : key == "ph" ? 2 : key == "name" ? 4 :
            key == "args" ? 8 : key == "dur" ? 16 : 0;
        if (seen & bit) Invalid();
        seen |= bit;
        if (bit == 1) Label(category);
        else if (bit == 2) Label(phase);
        else if (bit == 4) Label(name);
        else if (bit == 8) Provider(provider);
        else if (bit == 16 && (Peek() == '-' || Digit(Peek()))) {
          const auto number = Number();
          try { duration = std::stod(std::string(number)); }
          catch (const std::out_of_range &) { duration = 0; }
        } else Skip(2);
      } while (Take(','));
      Expect('}');
    }
    constexpr std::string_view suffix = "_kernel_time";
    if (category == "Node" && phase == "X" && provider == provider_ &&
        name.size() >= suffix.size() && name.compare(name.size() - suffix.size(), suffix.size(), suffix) == 0 &&
        duration > 0 && std::isfinite(duration) && duration <= 300000000) {
      ++result_.kernels;
      result_.microseconds += static_cast<int64_t>(std::ceil(duration));
    }
  }

  const std::string_view json_;
  const std::string &provider_;
  size_t position_ = 0;
  KokoroProfileStats result_;
};

}  // namespace kokoro_profile_detail

inline KokoroProfileStats KokoroExecutedProviderStats(const std::string &json, const std::string &provider) {
  return kokoro_profile_detail::Reader(json, provider).Read();
}

inline int KokoroExecutedProviderKernels(const std::string &json, const std::string &provider) {
  return KokoroExecutedProviderStats(json, provider).kernels;
}
#endif
