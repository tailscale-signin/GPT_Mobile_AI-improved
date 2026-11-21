#include "kokoro_pronunciation.h"

#include <cstdint>
#include <unordered_map>

extern "C" {
extern const unsigned char cmu_lts_model[];
extern const unsigned short cmu_lts_letter_index[27];
extern const char *const cmu_lts_phone_table[76];
}

namespace {
// Flite's CMU decision trees use four characters of context on each side.
// Each node is a feature, comparison byte, and two little-endian child indices.
const char *LetterPhones(const std::string &padded, size_t pos) {
  unsigned index = cmu_lts_letter_index[padded[pos] - 'a'];
  for (;;) {
    const auto *node = cmu_lts_model + 6 * index;
    const unsigned feature = node[0];
    if (feature == 255) return cmu_lts_phone_table[node[1]];
    const char context = feature < 4 ? padded[pos - 4 + feature] : padded[pos + feature - 3];
    const auto *child = node + (context == node[1] ? 2 : 4);
    index = child[0] | (static_cast<unsigned>(child[1]) << 8);
  }
}

void AppendPhone(std::string phone, bool british, bool beforeCodaR, std::vector<std::string> *out) {
  static const std::unordered_map<std::string, std::string> symbols = {
    {"aa","ɑ"}, {"ae","æ"}, {"ah","ʌ"}, {"ax","ə"}, {"ao","ɔ"}, {"aw","W"}, {"ay","I"},
    {"eh","ɛ"}, {"er","ɜ"}, {"ey","A"}, {"ih","ɪ"}, {"iy","i"}, {"ow","O"}, {"oy","Y"},
    {"uh","ʊ"}, {"uw","u"}, {"ch","ʧ"}, {"dh","ð"}, {"g","ɡ"}, {"hh","h"}, {"jh","ʤ"},
    {"ng","ŋ"}, {"r","ɹ"}, {"sh","ʃ"}, {"th","θ"}, {"y","j"}, {"zh","ʒ"}
  };
  const bool stressed = phone.back() == '1';
  if (stressed || phone.back() == '0') phone.pop_back();
  if (stressed) out->emplace_back("ˈ");
  auto it = symbols.find(phone);
  std::string symbol = it == symbols.end() ? phone : it->second;
  if (british && phone == "aa") symbol = beforeCodaR ? "ɑ" : "ɒ";
  if (british && phone == "ae") symbol = "a";
  if (british && phone == "ow") symbol = "Q";
  if (phone == "er" && !stressed) symbol = "ə";
  out->push_back(symbol);
  if (phone == "er" && !british) out->emplace_back("ɹ");
  if (beforeCodaR && (phone == "ih" || phone == "ay" || phone == "aw" || phone == "uh")) {
    out->emplace_back("ə");
  } else if (british && (phone == "ao" || (stressed && (phone == "iy" || phone == "uw" || phone == "er")) ||
                         (beforeCodaR && (phone == "aa" || phone == "eh")))) {
    out->emplace_back("ː");
  }
}
}  // namespace

std::vector<std::string> PredictEnglishPhones(const std::string &word, bool british) {
  const std::string padded = "000#" + word + "#000";
  std::vector<std::string> cmu;
  for (size_t i = 4; i < word.size() + 4; ++i) {
    if (padded[i] < 'a' || padded[i] > 'z') continue;
    std::string cluster = LetterPhones(padded, i);
    if (cluster == "epsilon") continue;
    const auto split = cluster.find('-');
    cmu.push_back(cluster.substr(0, split));
    if (split != std::string::npos) cmu.push_back(cluster.substr(split + 1));
  }
  // CMU marks vowels with a stress digit. In GB, a syllable-final R changes the
  // preceding vowel, while an R before another vowel is still pronounced.
  const auto vowel = [&](size_t i) {
    return i < cmu.size() && (cmu[i].back() == '0' || cmu[i].back() == '1');
  };
  std::vector<std::string> phones;
  for (size_t i = 0; i < cmu.size(); ++i) {
    bool codaR = british && vowel(i) && i + 1 < cmu.size() && cmu[i + 1] == "r" && !vowel(i + 2);
    AppendPhone(cmu[i], british, codaR, &phones);
    if (codaR) ++i;
  }
  return phones;
}
