#include "kokoro_pronunciation.h"

#include <iostream>

// Golden phone sequences use Flite's reference lts_apply output and the two
// pinned dictionaries' spelling of stress, diphthongs and syllable-final R.
int main() {
  struct Case { const char *word; bool british; std::vector<std::string> phones; };
  for (const Case &c : std::vector<Case>{
      {"graphgear", false, {"ɡ", "ɹ", "ˈ", "æ", "f", "ɡ", "ˈ", "ɪ", "ɹ"}},
      {"graphgear", true,  {"ɡ", "ɹ", "ˈ", "a", "f", "ɡ", "ˈ", "ɪ", "ə"}},
      {"microvoxel", false, {"m", "ˈ", "I", "k", "ɹ", "ˈ", "ɑ", "v", "ˈ", "ɑ", "k", "s", "ə", "l"}},
      {"seeley", true, {"s", "ˈ", "i", "ː", "l", "i"}}
  }) {
    if (PredictEnglishPhones(c.word, c.british) != c.phones) {
      std::cerr << "Unexpected predicted phones for " << c.word << '\n';
      return 1;
    }
  }
  return 0;
}
