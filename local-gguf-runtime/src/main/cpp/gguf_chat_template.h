#pragma once

#include "jinja/caps.h"
#include <string>
#include <utility>
#include <vector>

// Text-only adapter around the Jinja engine from our immutable llama.cpp pin.
// Native tools stay disabled: the app supplies its authorized tool bridge in
// the system message and executes tool requests outside the inference process.
class GgufChatTemplate {
public:
    using Messages = std::vector<std::pair<std::string, std::string>>;

    static void validateSyntax(const std::string & source);
    GgufChatTemplate(std::string source, std::string bos, std::string eos,
                     bool addBos, bool addEos);
    std::string render(const Messages & messages) const;

private:
    std::string source_, bos_, eos_;
    bool addBos_, addEos_;
    jinja::program program_;
    jinja::caps caps_;
};
