#include "gguf_chat_template.h"
#include "jinja/parser.h"
#include "json.h"
#include <stdexcept>

namespace {
constexpr size_t maxBytes = 4 * 1024 * 1024;

jinja::program parse(const std::string & source) {
    if (source.empty() || source.size() > maxBytes || source.find('\0') != std::string::npos) {
        throw std::runtime_error("GGUF chat template is missing or exceeds metadata bounds");
    }
    try {
        return jinja::parse_from_tokens(jinja::lexer().tokenize(source));
    } catch (const std::exception & e) {
        throw std::runtime_error("GGUF Jinja template syntax error: " + std::string(e.what()));
    }
}
}

void GgufChatTemplate::validateSyntax(const std::string & source) {
    parse(source);
}

GgufChatTemplate::GgufChatTemplate(std::string source, std::string bos, std::string eos,
                                 bool addBos, bool addEos)
    : source_(std::move(source)), bos_(std::move(bos)), eos_(std::move(eos)),
      addBos_(addBos), addEos_(addEos), program_(parse(source_)), caps_(jinja::caps_get(program_)) {
    // Check actual execution with model tokens before allocating a context.
    // An inert string is valid Jinja, but is not a usable chat template.
    const std::string marker = "7461938572";
    if (render({{"user", marker}}).find(marker) == std::string::npos) {
        throw std::runtime_error("GGUF Jinja template does not preserve user messages");
    }
}

std::string GgufChatTemplate::render(const Messages & messages) const {
    try {
        if (messages.empty()) throw std::runtime_error("empty history");
        Messages normalized = messages;
        // Match llama.cpp's system-role workaround. Keep the app's system
        // instructions and tool schema even on Gemma-style user/assistant templates.
        if (!caps_.supports_system_role && normalized.front().first == "system") {
            if (normalized.size() < 2 || normalized[1].first != "user") {
                throw std::runtime_error("system instructions require a following user message");
            }
            normalized[1].second = normalized.front().second + "\n\n" + normalized[1].second;
            normalized.erase(normalized.begin());
        }
        auto input = common_json::array();
        for (const auto & message : normalized) {
            common_json content = message.second;
            if (!caps_.supports_string_content && caps_.supports_typed_content) {
                content = common_json::array({common_json{{"type", "text"}, {"text", message.second}}});
            }
            input.push_back(common_json{{"role", message.first}, {"content", content}});
        }
        jinja::context context(source_);
        jinja::global_from_json(context, common_json{
            {"messages", input}, {"bos_token", bos_}, {"eos_token", eos_},
            {"add_generation_prompt", true}, {"enable_thinking", false}
        }, false);
        jinja::runtime runtime(context);
        auto result = jinja::runtime::gather_string_parts(runtime.execute(program_))->as_string().str();
        if (result.empty() || result.size() > maxBytes || result.find('\0') != std::string::npos) {
            throw std::runtime_error("rendered prompt is empty or exceeds transport bounds");
        }
        // llama_tokenize(add_special=true) supplies these automatically. Follow
        // upstream common_chat_template_direct_apply to avoid doubled BOS/EOS.
        if (addBos_ && !bos_.empty() && result.compare(0, bos_.size(), bos_) == 0) {
            result.erase(0, bos_.size());
        }
        if (addEos_ && !eos_.empty() && result.size() >= eos_.size() &&
            result.compare(result.size() - eos_.size(), eos_.size(), eos_) == 0) {
            result.erase(result.size() - eos_.size());
        }
        return result;
    } catch (const std::exception & e) {
        throw std::runtime_error("GGUF Jinja template rendering failed: " + std::string(e.what()));
    }
}
