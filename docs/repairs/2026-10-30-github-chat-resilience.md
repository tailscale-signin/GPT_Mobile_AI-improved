# GitHub chat resilience

Repository: tailscale-signin/GPT_Mobile_AI-improved
Base: main at 481c14bb0ddedb7eb6d76dbc5f434546cfcbbad7
Working branch: fix/github-chat-resilience-2026-10-30
Intent: recover the GitHub task after transient read/provider failures, retain completed receipts, repair malformed calls, and request strong supported reasoning.

## Evidence

The supplied schema-6 diagnostics identify app 0.9.63.0 (131), commit e69abe9919aec81f9fe09e468865d88c732553af. GitHub reads failed with HTTP 502 and HTTP/2 stream resets. A PR context request omitted pull_number; saved-view pagination mixed cursor and remote arguments. Local provider recovery repeatedly ran before retryAfterMs elapsed. An unparsed tool frame followed visible text and was terminal. Unreachable private MCP endpoints, public DNS failures, and Android vendor/OS warnings are also present; code cannot restore an offline server or change OS permissions.

## Changes

- GitHub model rounds retain tool access and completed receipts during bounded transient recovery, including failures after visible progress. Recovery waits for provider retryAfterMs and does not consume productive tool rounds. Six failed recovery attempts end with an explicit blocker; user cancellation remains immediate.
- Malformed frames after a preamble, output-limit failures and empty rounds receive one repair with tools retained. Unsafe raw tool markup remains withheld. GitHub bypasses the text-only continuation wrapper that previously dropped tools after an interruption.
- GitHub honors the configured profile/chat tool allowance instead of the hidden 24-call cap. Resource, context, approval and failure-loop guards still apply.
- Public DNS cooldowns no longer block literal LAN/Tailscale/IPv6 endpoints or localhost. Remote DNS failures retain their existing single-probe backoff.
- Native GitHub and workspace GET reads retry transient 500/502/503/504 and transport failures twice. Read bodies are consumed inside the retry boundary. Retry-After is respected; writes, authentication failures, certificate failures, cancellation, and long cooldowns are never blindly replayed.
- Added canonical github_pr action=list, state filtering, pagination and exact continuation arguments. Missing PR numbers are rejected before network dispatch with a discovery hint. github_pull_request aliases resolve only to an unambiguous authorized capability.
- Explicit discovery prioritizes requested schemas, including authorized staging and memory. Bounded schema windows remain enforced; discovery explains missing authorization/configuration rather than implying every omitted schema is unavailable permanently.
- Automatic GitHub output allowance reserves up to 16,384 tokens within the context budget and existing explicit profile/chat caps, so stronger reasoning has room to produce tool calls and a visible answer.
- GitHub requests strong reasoning without editing saved profiles: known OpenAI models use high/xhigh, Groq GPT-OSS and OpenRouter use high, supported Anthropic/Gemini/local adapters enable thinking. Manual Anthropic thinking leaves output space; llama.cpp enables thinking only with a verified control and otherwise uses Auto. Actual model computation is not guaranteed by a requested setting.

## Validation

- `bash scripts/validate-local.sh fast`: passed, including 233 host tests, repository policy, secret scan, Android regex and resource/provenance checks. Fixture output is not device qualification.
- 65 focused Kotlin/JUnit tests passed using cached compiler dependencies and Android/infrastructure boundary stubs: GitHub task recovery, catalog admission/discovery, operation contracts/pagination, read retry policy, reasoning and DNS-isolation policies. Core changed logic was compiled from this checkout. This is not a full Android build.
- ktlint 1.3.1 formatting/check and `git diff --check`: passed.
- Full Gradle Android tests could not start: distribution download reported Network is unreachable. The host also has no configured Android SDK or JDK 21. HTTP adapter integration tests are added for Android CI.
- The supplied private endpoint/DNS outages still require network/server availability. Existing diagnostic classification already recognizes the harmless hardware-property and foreground-job warnings. No physical device validation was performed.

## Publication follow-up

The user authorized final review, PR publication, merge after green checks, and signed publication. The release candidate is 0.9.64.0 (132), based on the last published 0.9.63.0 commit af348e0a4c556bf7cb212b8455a83deacbefbe67; its explicit scope also includes the already merged PRs #760–#763. Final review added regression cases ensuring cooldown values containing 403 or 429 are not mistaken for HTTP authentication/rate-limit failures. CI and release results must be reconciled against the final head before merge or publication.
