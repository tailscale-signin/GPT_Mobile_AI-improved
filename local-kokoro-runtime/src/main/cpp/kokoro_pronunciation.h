#pragma once

#include <string>
#include <vector>

// The input is a normalized English dictionary miss. The output uses the model's symbols.
std::vector<std::string> PredictEnglishPhones(const std::string &word, bool british);
