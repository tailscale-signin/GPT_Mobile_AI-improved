#ifndef MELO_KOKORO_PROFILE_H_
#define MELO_KOKORO_PROFILE_H_
#include <regex>
#include <stdexcept>
#include <string>

// Parse top-level profiler events without trusting registration or initialization
// metadata. Nested shapes and quoted braces cannot join two separate events.
inline int KokoroExecutedProviderKernels(const std::string &json, const std::string &provider) {
  const std::regex category("\\\"cat\\\"\\s*:\\s*\\\"Node\\\"");
  const std::regex phase("\\\"ph\\\"\\s*:\\s*\\\"X\\\"");
  const std::regex kernel("\\\"name\\\"\\s*:\\s*\\\"[^\\\"]*_kernel_time\\\"");
  const std::regex duration("\\\"dur\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
  const std::regex assigned("\\\"provider\\\"\\s*:\\s*\\\"" + provider + "\\\"");
  int depth = 0, executed = 0;
  bool quoted = false, escaped = false;
  size_t event_start = std::string::npos;
  for (size_t i = 0; i < json.size(); ++i) {
    const char c = json[i];
    if (quoted) {
      if (escaped) escaped = false;
      else if (c == '\\') escaped = true;
      else if (c == '"') quoted = false;
      continue;
    }
    if (c == '"') { quoted = true; continue; }
    if (c == '{' || c == '[') {
      if (depth == 1 && c == '{') event_start = i;
      ++depth;
    } else if (c == '}' || c == ']') {
      if (--depth < 0) throw std::runtime_error("Malformed speech profile");
      if (depth == 1 && c == '}' && event_start != std::string::npos) {
        const auto event = json.substr(event_start, i - event_start + 1);
        std::smatch elapsed;
        if (std::regex_search(event, category) && std::regex_search(event, phase) &&
            std::regex_search(event, kernel) && std::regex_search(event, assigned) &&
            std::regex_search(event, elapsed, duration) && std::stod(elapsed[1].str()) > 0)
          ++executed;
        event_start = std::string::npos;
      }
    }
  }
  if (quoted || depth != 0) throw std::runtime_error("Incomplete speech profile");
  return executed;
}
#endif
