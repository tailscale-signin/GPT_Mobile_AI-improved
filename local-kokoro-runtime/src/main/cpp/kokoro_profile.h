#ifndef MELO_KOKORO_PROFILE_H_
#define MELO_KOKORO_PROFILE_H_

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

struct KokoroOperatorStats {
  std::string operation;
  int kernels = 0;
  int64_t microseconds = 0;
};

struct KokoroProfileStats {
  int kernels = 0;
  int64_t microseconds = 0;
  std::vector<KokoroOperatorStats> operators;
};

namespace kokoro_profile_detail {

// These are public operator types, never node names, graph hashes, paths or
// caller-controlled strings. QNN's opaque fused-node names describe partitions,
// not the operators inside them; collapse them to an explicitly generic label.
inline std::string OperatorLabel(const std::string &operation, const std::string &provider) {
  static constexpr std::string_view known[] = {
      "Abs", "Add", "And", "ArgMax", "ArgMin", "Attention", "AveragePool", "Cast", "Ceil", "Clip",
      "Concat", "Constant", "ConstantOfShape", "Conv", "ConvInteger", "ConvTranspose", "Cos", "CumSum",
      "DequantizeLinear", "Div", "DynamicQuantizeLinear", "DynamicQuantizeLSTM", "EmbedLayerNormalization",
      "Equal", "Erf", "Exp", "Expand", "FastGelu", "Flatten", "Floor", "FusedConv", "Gather",
      "GatherElements", "GatherND", "Gelu", "Gemm", "GlobalAveragePool", "Greater", "GreaterOrEqual",
      "Identity", "If", "InstanceNormalization", "LayerNormalization", "LeakyRelu", "Less", "LessOrEqual",
      "Log", "Loop", "LSTM", "MatMul", "MatMulInteger", "MatMulIntegerToFloat", "Max", "MaxPool", "Min",
      "Mul", "Neg", "NonZero", "Not", "Pad", "Pow", "PRelu", "QLinearConv", "QLinearMatMul",
      "QuantizeLinear", "RandomNormal", "RandomNormalLike", "Range", "Reciprocal", "ReduceL2", "ReduceMax",
      "ReduceMean", "ReduceMin", "ReduceProd", "ReduceSum", "Relu", "Reshape", "Resize", "Round",
      "ScatterElements", "ScatterND", "Shape", "Sigmoid", "Sin", "SkipLayerNormalization", "Slice",
      "Softmax", "Split", "Sqrt", "Squeeze", "Sub", "Tanh", "Tile", "TopK", "Transpose", "Trilu",
      "Unsqueeze", "Where"};
  if (provider == "QNNExecutionProvider" && operation.rfind("QNNExecutionProvider_", 0) == 0)
    return "QnnPartition";
  for (const auto label : known) if (operation == label) return std::string(label);
  return "Other";
}

inline void ObserveOperator(KokoroProfileStats &profile, const std::string &operation,
    const std::string &provider, int64_t microseconds) {
  const auto label = OperatorLabel(operation, provider);
  for (auto &observed : profile.operators) if (observed.operation == label) {
    ++observed.kernels;
    observed.microseconds += microseconds;
    return;
  }
  // The fixed vocabulary above plus Other/QnnPartition bounds unique buckets.
  profile.operators.push_back({label, 1, microseconds});
}

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

  void Arguments(std::string &provider, std::string &operation) {
    if (!Take('{')) { Skip(2); return; }
    unsigned seen = 0;
    if (!Take('}')) {
      do {
        std::string key;
        String(&key);
        Expect(':');
        const unsigned bit = key == "provider" ? 1 : key == "op_name" ? 2 : 0;
        if (seen & bit) Invalid();
        seen |= bit;
        if (bit == 1) Label(provider);
        else if (bit == 2) Label(operation);
        else Skip(3);
      } while (Take(','));
      Expect('}');
    }
  }

  void Event() {
    Expect('{');
    std::string category, phase, name, provider, operation;
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
        else if (bit == 8) Arguments(provider, operation);
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
      const auto microseconds = static_cast<int64_t>(std::ceil(duration));
      result_.microseconds += microseconds;
      ObserveOperator(result_, operation, provider, microseconds);
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

// Optional formatting must not replace the original JNI failure or throw from
// its catch handler. The caller owns both strings; failure borrows the existing
// discovery without allocating or copying it, even when enrichment ran out of
// memory after partially filling its storage.
template <typename Compose>
inline const std::string &KokoroBestEffortEvidenceSummary(const std::string &discovery,
    std::string &storage, Compose &&compose) noexcept {
  try {
    storage = compose();
    return storage;
  } catch (...) {
    return discovery;
  }
}

// The existing JNI discovery channel accepts at most 512 safe ASCII bytes.
// Keep complete semicolon-delimited records, retain an explicit first-inference
// scope, and alternate hottest CPU/QNN observations so neither provider hides
// the other. These are observed kernel placements/times, not support reasons,
// utilization, exclusive wall time, or timings from the current reused call.
inline std::string KokoroObservedOperatorSummary(const std::string &discovery,
    const KokoroProfileStats &cpu, const KokoroProfileStats &qnn, bool reused) {
  if (cpu.operators.empty() && qnn.operators.empty()) return discovery;
  constexpr size_t maximum = 512, discovery_limit = 192;
  const std::string truncated = ";truncated=1";
  std::string result;
  size_t position = 0;
  while (position < discovery.size()) {
    const auto next = discovery.find(';', position);
    const auto length = (next == std::string::npos ? discovery.size() : next) - position;
    const auto record = std::string(result.empty() ? "" : ";") + discovery.substr(position, length);
    if (result.size() + record.size() + truncated.size() > discovery_limit) {
      result += truncated;
      break;
    }
    result += record;
    if (next == std::string::npos) break;
    position = next + 1;
  }
  result += ";observed_ops_scope=first_inference;observed_ops_reused=";
  result += reused ? "1" : "0";
  auto ordered = [](const KokoroProfileStats &profile) {
    auto operators = profile.operators;
    std::sort(operators.begin(), operators.end(), [](const auto &first, const auto &second) {
      return first.microseconds != second.microseconds ? first.microseconds > second.microseconds :
          first.operation < second.operation;
    });
    return operators;
  };
  const auto cpu_ops = ordered(cpu), qnn_ops = ordered(qnn);
  const std::string omitted = ";observed_ops_more=1";
  bool more = cpu_ops.size() > 3 || qnn_ops.size() > 3;
  for (size_t rank = 0; rank < 3; ++rank) {
    for (bool accelerator : {false, true}) {
      const auto &operators = accelerator ? qnn_ops : cpu_ops;
      if (rank >= operators.size()) continue;
      const auto &item = operators[rank];
      const auto record = std::string(accelerator ? ";observed_qnn_ops=" : ";observed_cpu_ops=") +
          item.operation + ":" + std::to_string(item.kernels) + ":" + std::to_string(item.microseconds);
      if (result.size() + record.size() + omitted.size() <= maximum) result += record;
      else more = true;
    }
  }
  if (more) result += omitted;
  return result;
}
#endif
