# GPT Mobile AI (Improved)

**Your AI chat, research and tools in one Android app.**

Chat with online AI services or compatible models running on your phone. Compare answers, research topics with sources, ask questions about your documents, and keep useful memories for future conversations.

[![Android 12+](https://img.shields.io/badge/Android-12%2B-green.svg)](app/build.gradle.kts)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

[Download the app](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/latest) · [Get started](#get-started) · [Explore features](#what-you-can-do) · [Privacy](#privacy-and-your-data) · [Get help](#help-and-troubleshooting)

> This page describes the latest code on `main`. The downloadable app may be behind the source code; check the release notes for what your installed version includes. New hardware support and experimental integrations still need device testing.

## Get started

1. **Install the app.** You need Android 12 or newer. Open [Releases](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/releases/latest), expand **Assets**, and download a release `.apk`, not the source-code ZIP or `.aab`. The universal APK includes the supported processor variants. Android may ask you to allow installation from your browser or file manager.
2. **Choose an AI connection.** In **Settings → AI**, add an online provider and its API key, select an available free-service option, or set up a compatible model from **Local Models**. An API key is the access credential supplied by your chosen AI service.
3. **Start a conversation.** Select one AI profile for a normal chat, or several profiles to compare their answers. A profile is a saved model and its settings.
4. **Add extras when you need them.** Enable research, memory or a plugin separately. Review its permissions and setup requirements before using it. You do not need a PC gateway or an MCP server for basic chat.

**Costs:** the app is open source, but online AI providers and some tools may charge for usage. Free-service availability and limits can change. Using several models, running benchmarks or doing deeper research can use more requests, data and battery.

## What you can do

### Chat with one AI or compare several

Use different AI models in the same conversation, watch answers appear as they are written, and open each model's individual response. **Combined mode** brings contributions together in one answer while keeping the original responses available to inspect.

Save separate profiles for writing, coding or research. Queue follow-up questions, edit messages, retry interrupted responses, and compare conversation branches without replacing the original discussion. Optional **delegation** lets a helper model do research and a separate reviewer check the material before the main model writes the answer. A second AI opinion is not a guarantee of accuracy.

### Research the web and academic papers

**Deep Research** combines ordinary web search with supported academic sources. In a conversation, open **Multi Search → Research**, turn research on, and choose **Web + Papers**, **Papers only** or **Web only**. Academic access also needs the plugin, selected profile and papers permission enabled.

The effort slider controls how much research to attempt:

| Research effort | Source target |
| --- | ---: |
| 0% | Research off |
| 25% | 30 |
| 50% | 90 |
| 75% | 180 |
| 100% | 300 |

These are **targets, not promised results**. Your safety cap, enabled engines, permissions, request limits, available pages and service restrictions can reduce the total. More results do not automatically mean better evidence.

The research view keeps source links, captured passages, reading progress and coverage information. It distinguishes pages actually read from search listings and partial reads. Where captured text is available, evidence checks can verify that a quotation appears in that source; they do not prove the claim itself is true.

Supported core academic connections include **Crossref, OpenAlex, arXiv, Europe PMC and DataCite**, with **OpenCitations** for citation links. Eligible PubMed Central text is retrieved through Europe PMC, not counted as a separate independent search. The core anonymous routes do not require a separate academic API key, but still have quotas. Other listed connectors may remain pending or disabled.

Deep Research settings show successful and failed HTTP attempts for supported academic engines, with lifetime, 24-hour, 7-day and 30-day views. No recorded requests means **unknown**, not 100% success. You can also review pending engines and adjust source, time, request and storage limits.

### Use your documents and remember useful details

Import supported documents, ask questions about their contents, and follow links back to the retrieved material. The app searches both matching words and related meaning. Extraction limits apply; a scanned image-only document does not become searchable through built-in OCR.

Local memory can retain useful facts and recurring topics for later conversations. Review, pin, correct or remove memories, and inspect where they came from. Conflicting automatic facts are presented for review instead of silently replacing an existing fact. Memory processing runs on the phone; **allowing a cloud model to recall those memories is a separate choice**.

### Add tools without enabling everything

Open **Plugins/Tools** to manage integrated plugins and external connections. Enable only the tools you need and choose which AI profiles may use them. Saved approvals can be reviewed and revoked.

| Optional tool | What it adds | What to know |
| --- | --- | --- |
| Web search and News | Web results and supported news/trend sources | Internet access; some connections need their own setup or credentials. |
| Maps, places and travel | Place results, embedded maps and supported travel/listing cards | Location permission and provider setup depend on the tool. Availability and prices can change. |
| Amazon search | Product search, detail cards and available photos | Results depend on the selected provider; check current details on the retailer's site. |
| GitHub workspace | Browse repositories and files, edit changes, and work with branches, commits and pull requests | A connected GitHub account with the necessary repository permissions. |
| External MCP tools | Connect compatible third-party tools and services | MCP is a standard way for an AI app to connect to tools. Each server has its own access and setup requirements. |

A marketplace listing is not proof that a service is configured, free, reachable or supported on your device. Tools can contact external services even when the chat model runs locally.

### Run compatible AI models on your phone

Use **Local Models** to find, download and configure supported model packages. Compatible models can answer without an internet connection after setup. Storage, memory and speed requirements vary by model.

The app includes LiteRT-LM and compatible Qualcomm QNN/NPU paths. In plain language, **CPU** is the general processor, **GPU** handles graphics and parallel work, and **NPU** is a specialised AI processor. A package must support the selected hardware; choosing NPU does not make a GPU-only model compatible. Advanced acceleration options are not a promise that every model can use several processors at once.

Local runtime checks and diagnostics help identify incompatible packages and loading failures. Optional **Local Assist** can use an already loaded, eligible model to select relevant research material; it skips that step when no suitable model is ready. A PC running Ollama or the optional llama.cpp gateway is another connection option, but it is **not** the same as running the model on your phone.

### Test models and compare results

The benchmark tools offer a **Quick test with 5 tests** and a **Full test with 50 tests**, plus detailed results and comparisons. They cover response timing, task accuracy, structured answers, tool use and a small capability test.

The **Scoreboard** has **Local** and **Online** views for remote-model results. Here, Local means results saved on your device, not on-device AI models. Entries use the provider and actual model ID rather than your profile name. On-device models retain their Run, Compare and History views.

Open a result for individual test outcomes. Retest charts show gains in yellow and losses in red, with numeric differences. Model and delegation rankings are separate, and Quick and Full runs are not mixed. Sharing online is an explicit action through your connected GitHub account; it is not automatic.

Community scores are self-reported best runs, not independent certification or an IQ test. Your GitHub username is public when you submit a result. See [how the scoreboards work](scoreboards/README.md).

### View supported smartwatch and health data

The optional **Smartwatch Health Connect** plugin reads data that a compatible companion app has already shared with Health Connect on your phone. It does not connect directly to every watch or stream its live sensors.

View permitted activity, sleep, heart-rate and other supported summaries locally. Separately enable AI sharing and the desired profiles to ask questions about that data. AI reads are limited to 30 days and require the app to be visible and the phone unlocked.

An explicit local import can save a **summary covering up to five years**, where the records and history permission are available. This is not a complete archive of every raw reading. Moving that saved summary through backup requires the separate encrypted **Health data** section. The imported summary is not automatically sent to an AI.

Compatibility depends on what the companion app actually shares. This feature is not medical advice or a diagnostic device. See [Health Connect setup and privacy](docs/health-connect.md).

### Keep chats organised and take your data with you

Use folders, favourites, pins, search and archives to organise conversations. Personalise the interface with themes and profile colours. Export a selected response as plain text (`.txt`) or Markdown (`.md`). Debug mode adds a more detailed export for troubleshooting; review it before sharing.

Selective backup and restore let you choose what to keep, including profiles, conversations, memory, attachments, settings and benchmark history. Health summaries have their own encrypted selection. Follow the archive's encryption and recovery instructions, and keep any required recovery key safe before changing devices or uninstalling the app.

For repeatable tasks, advanced users can save editable recipes and optionally schedule supported runs. Scheduling is off by default and remains subject to Android timing and app safety limits.

## Privacy and your data

**Local storage and local AI are useful options, but they do not make every action offline.** Your selected model, recall settings and enabled tools determine what leaves the phone.

| Activity | Network behaviour |
| --- | --- |
| Reviewing saved chats, local memories and already indexed documents | Can work offline. |
| Running a compatible on-device model | Can work offline after the model is installed. |
| Asking an online AI model | Sends the selected conversation context to that service. |
| Web research, external tools and GitHub | Contacts the configured services. |
| Using Ollama or a PC gateway | Requires access to that server. |
| Viewing Health Connect data locally | Reads the phone's health store; separately authorised AI sharing may send results to a model, gateway or delegate. |

Credentials and authoritative fact memory use protected encrypted storage. **Not every chat, attachment, database or search index is encrypted by the app.** App lock and screenshot protection are additional controls, not a replacement for storage encryption.

Temporary conversations turn off memory capture and recall, avoid portable backups, and are removed when you leave or the app restarts. This is not guaranteed forensic erasure. Deleting local conversations cannot delete copies retained by an external provider.

Automatic Android cloud backup and device transfer are disabled; use the app's explicit backup flow. Local diagnostics are optional. A debug export can contain conversation and execution details, so review and redact it before attaching it to a public issue. Health information repeated in a conversation may remain in that chat, its exports or a provider's records even when health-tool traces are redacted.

## Help and troubleshooting

**A local model will not load?** Check the model package, available memory, device compatibility and selected processor. An unsupported NPU package cannot always fall back to CPU or GPU. See the [local-runtime guide](docs/gpu-npu-execution-plans.md).

**Research returns fewer sources than expected?** Check the effort setting, safety cap, selected scope, enabled engines, permissions and engine status. Blocked pages, service quotas and time limits can leave a run partial.

**No smartwatch data?** Confirm that the companion app has synced records into Health Connect and that the app has read permission for those categories. Missing data is not the same as a zero measurement.

**The online scoreboard is empty?** There may be no shared results yet. A connection error is different; the app keeps available local results. See the [scoreboard guide](scoreboards/README.md).

Report bugs through [GitHub Issues](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/issues). Include the app version, phone model, Android version and steps to reproduce. Do not include API keys, private documents, health records or unreviewed debug exports.

## Guides and development

| Topic | Guide |
| --- | --- |
| Research architecture and source access | [Academic research](docs/deep-research-implementation.md) |
| Research, Combined and GitHub reliability repair | [Implementation and validation](docs/repairs/2026-10-21-reliability-and-workspace.md) |
| Current research limits and engine catalogue | [Effort settings](app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/DeepResearchSettings.kt) · [Engine registry](app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/engine/ResearchEngineRegistry.kt) |
| Local models and hardware qualification | [GPU/NPU execution](docs/gpu-npu-execution-plans.md) |
| Memory behaviour and chat folders | [Memory and organisation](docs/implementation/memory-v2-chat-folders-2026-10-09.md) |
| Benchmarks and scoreboards | [Benchmark guide](docs/implementation/npu-benchmark-assist-comparisons-2026-10-11.md) · [Community scores](scoreboards/README.md) |
| Health data | [Health Connect](docs/health-connect.md) |
| PC-assisted AI | [Gateway setup](gateway/README.md) |
| Debug exports | [Export and recovery details](docs/implementation/debug-export-combined-integrity.md) |
| Contributing and validation | [Contributing](CONTRIBUTING.md) · [Validation](docs/validation.md) · [Changelog](CHANGELOG.md) |

Technical guides include historical implementation notes and unfinished rollout stages. Their presence does not mean every planned feature is enabled. Current code and release notes take precedence over older snapshots.

<details>
<summary><strong>Build and test the app</strong></summary>

Use JDK 21, Android SDK platform 37, build tools 37.0.0 and the included Gradle wrapper. See [app configuration](app/build.gradle.kts) and [dependency versions](gradle/libs.versions.toml) for the current source settings.

```bash
# Downloads once into the SHA-256-addressed Gradle model cache; Gradle performs no weight downloads.
python3 scripts/provision_memory_model.py
# Offline provisioning can use --file /path/to/universal_sentence_encoder.tflite --offline.
# Direct Gradle overrides remain supported: -PmemoryModelFile=/path/to/the/pinned/model.
./gradlew :core-domain:jvmTest :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug
python3 scripts/check_local_runtime_apk.py app/build/outputs/apk/debug/*.apk
./gradlew :app:assembleDebugAndroidTest
```

For cached/offline model verification run `python3 scripts/provision_memory_model.py --offline`.
`python3 scripts/check_model_configuration_cache.py` verifies cache reuse for model
packaging and the portable accounting/routing module. Full Android configuration
caching remains disabled because ObjectBox retains Gradle Project state; this does
not disable the build artifact cache. Provision normal Gradle/SDK dependencies before
an offline Android build.

On Windows, use `gradlew.bat` in place of `./gradlew`. Device-required checks include:

```bash
./gradlew :app:connectedDebugAndroidTest
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

Dependency verification stays enabled. Review [validation and dependency maintenance](docs/validation.md) before changing runtime packages or checksum locks. Passing a build does not establish real-phone speed, watch compatibility or full end-to-end correctness. Release signing is separate; do not distribute a debug or benchmark APK as a production release.

The PC gateway is optional and updated separately from the Android app. Deploy the matching gateway package and modules rather than replacing one server file. Keep authentication enabled and use the documented network setup.

</details>

## Credits and license

Based on [GPT Mobile](https://github.com/Taewan-P/gpt_mobile), with additional research, local-model, memory, tool and workflow features in this fork.

Licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE); upstream credits and third-party notices remain applicable.

For an explicit memory-leak investigation, build the debug app with
`-PleakDetection=true`. This enables the catalog-pinned LeakCanary 2.14 integration
for destroyed activities, fragments, views, view models and services. It is omitted
from ordinary debug runs and every release build so heap dumping does not distort
runtime benchmarks. See [LeakCanary's setup](https://square.github.io/leakcanary/getting_started/).

Complete backups can include GitHub connections and API/OAuth credentials under
**API keys & connection secrets**, and saved editor/programmer drafts under
**GitHub workspace drafts**. Both require encryption. Include **Settings & tools**,
**Conversations**, and **AI platforms** to restore workspace/profile/chat mappings
together. Settings-only imports match stable conversation identities. Restored
GitHub OAuth sessions verify the account again; expired or revoked credentials may
require reconnecting. Pending sign-ins, previous write approvals, execution claims,
and response caches are deliberately not portable. Restoring drafts never commits
or pushes them. Keep a password or the separate recovery key for another device.
