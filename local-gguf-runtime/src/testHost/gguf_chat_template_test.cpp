#include "gguf_chat_template.h"
#include "llama.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>

namespace {
void check(bool condition, const char * message) {
    if (!condition) throw std::runtime_error(message);
}

template <typename Fn> void rejects(Fn fn, const char * message) {
    try { fn(); } catch (const std::runtime_error &) { return; }
    throw std::runtime_error(message);
}
}

int main(int argc, char ** argv) {
    try {
        // No recognized legacy template markers: this reproduces the old failure.
        const std::string custom = "{% macro turn(m) %}{{ m.role|upper }}: {{ m.content|trim }}\n{% endmacro %}"
            "{{ bos_token }}{% for m in messages %}{{ turn(m) }}{% endfor %}"
            "{% if add_generation_prompt %}ASSISTANT: {% endif %}";
        const llama_chat_message probe{"user", "Hello"};
        check(llama_chat_apply_template(custom.c_str(), &probe, 1, true, nullptr, 0) < 0,
              "Regression fixture must fail the former legacy formatter");
        GgufChatTemplate::validateSyntax(custom);
        GgufChatTemplate renderer(custom, "<s>", "</s>", false, false);
        const GgufChatTemplate::Messages history = {
            {"system", "Use lookup: {\"query\":\"string\"}"}, {"user", "  Olá 世界  "},
            {"assistant", "Hello"}, {"user", "Continue"}
        };
        const auto expected = "<s>SYSTEM: Use lookup: {\"query\":\"string\"}\nUSER: Olá 世界\nASSISTANT: Hello\nUSER: Continue\nASSISTANT: ";
        check(renderer.render(history) == expected, "Custom Jinja lost roles, tools, Unicode, or generation header");
        check(renderer.render(history) == expected, "Cached template leaked state between renders");
        GgufChatTemplate autoBos(custom, "<s>", "</s>", true, false);
        check(autoBos.render(history) == std::string(expected).substr(3), "BOS was duplicated");

        // Recognized legacy templates must also execute the actual embedded logic.
        const std::string thinking = "{% for m in messages %}{{ '<|im_start|>' + m.role + '\n' + m.content + '<|im_end|>\n' }}{% endfor %}"
            "{% if add_generation_prompt %}<|im_start|>assistant\n{% if enable_thinking is defined and not enable_thinking %}<think>\n\n</think>\n\n{% endif %}{% endif %}";
        GgufChatTemplate noThinking(thinking, "", "", false, false);
        check(noThinking.render({{"user", "Hi"}}) == "<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n",
              "Template's non-thinking generation prefix was ignored");

        const std::string alternating = "{% for m in messages %}"
            "{% if m.role == 'system' %}{{ raise_exception('System role unsupported') }}{% endif %}"
            "{% if (m.role == 'user') != (loop.index0 % 2 == 0) %}{{ raise_exception('Roles must alternate') }}{% endif %}"
            "{{ m.role + ': ' + m.content + '\n' }}{% endfor %}assistant: ";
        GgufChatTemplate noSystem(alternating, "", "", false, false);
        check(noSystem.render(history) == "user: Use lookup: {\"query\":\"string\"}\n\n  Olá 世界  \nassistant: Hello\nuser: Continue\nassistant: ",
              "System instructions/tool schema lost on strict alternating template");
        rejects([&] { noSystem.render({{"system", "Keep me"}}); }, "System-only history was silently dropped");
        rejects([&] { noSystem.render({{"user", "Hi"}, {"user", "Again"}}); }, "Template role errors were hidden");

        const std::string typed = "{% for m in messages %}{{ m.role }}: {% for p in m.content %}{% if p.type == 'text' %}{{ p.text }}{% endif %}{% endfor %}{{ '\n' }}{% endfor %}assistant: ";
        GgufChatTemplate typedContent(typed, "", "", false, false);
        check(typedContent.render({{"user", "Hello"}}) == "user: Hello\nassistant: ", "Typed text content was lost");

        const std::string eos = "{{ bos_token }}{% for m in messages %}{{ m.content }}{% endfor %}{{ eos_token }}";
        GgufChatTemplate autoSpecial(eos, "<s>", "</s>", true, true);
        check(autoSpecial.render({{"user", "Hello"}}) == "Hello", "Automatic EOS was duplicated");
        GgufChatTemplate explicitSpecial(eos, "<s>", "</s>", false, false);
        check(explicitSpecial.render({{"user", "Hello"}}) == "<s>Hello</s>", "Explicit special tokens were removed");

        rejects([] { GgufChatTemplate::validateSyntax("{% if true %}broken"); }, "Malformed template admitted");
        rejects([] { GgufChatTemplate::validateSyntax(""); }, "Empty template admitted");
        rejects([] { GgufChatTemplate::validateSyntax(std::string("{{x}}\0", 6)); }, "Null metadata admitted");
        rejects([] { GgufChatTemplate::validateSyntax(std::string(4 * 1024 * 1024 + 1, 'x')); }, "Oversized metadata admitted");
        rejects([] { GgufChatTemplate inert("constant prompt", "", "", false, false); }, "Template that drops user input admitted");
        rejects([&] { renderer.render({{"user", std::string(4 * 1024 * 1024, 'x')}}); }, "Oversized rendered prompt admitted");
        check(argc == 2, "Pass the pinned upstream template directory");
        for (const auto * name : {
            "google-gemma-2-2b-it.jinja", "google-gemma-4-31B-it.jinja",
            "Qwen-Qwen3-0.6B.jinja", "Qwen3.5-4B.jinja",
            "microsoft-Phi-3.5-mini-instruct.jinja", "mistralai-Mistral-Nemo-Instruct-2407.jinja"
        }) {
            std::ifstream file(std::string(argv[1]) + "/" + name);
            check(file.good(), "Missing pinned upstream template fixture");
            const std::string source((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
            GgufChatTemplate modelTemplate(source, "<s>", "</s>", false, false);
            const auto rendered = modelTemplate.render({
                {"system", "SYSTEM_INSTRUCTIONS_AND_TOOL_SCHEMA"}, {"user", "FIRST_QUESTION"},
                {"assistant", "PREVIOUS_ANSWER"}, {"user", "CURRENT_QUESTION"}
            });
            for (const auto * marker : {"SYSTEM_INSTRUCTIONS_AND_TOOL_SCHEMA", "FIRST_QUESTION", "PREVIOUS_ANSWER", "CURRENT_QUESTION"}) {
                if (rendered.find(marker) == std::string::npos) {
                    throw std::runtime_error(std::string(name) + " lost " + marker);
                }
            }
            std::cout << "Pinned template passed: " << name << '\n';
        }
        std::cout << "GGUF Jinja regression tests passed\n";
        return 0;
    } catch (const std::exception & e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
