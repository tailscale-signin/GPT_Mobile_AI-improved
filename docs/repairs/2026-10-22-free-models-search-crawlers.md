# Free Models, explicit search and managed crawling

This follows the October 22 provider/search and crawler audits on
`fix/research-combined-delivery-2026-10-20`. The user's export observations are
reported observations, not reproduced live provider tests.

## One Free Models connection

An idempotent migration creates one `Free Models` connection and one enabled,
service-named profile for Kilo, OVHcloud, BlockRun, AI Horde, Vireonix, OpenCode,
Hugging Face Spaces, Pollinations Legacy, BridgeNode, LLM7, Cehpoint and PorchLM.
Existing profile identifiers and choices survive migration. Deleting a default
profile after migration does not cause it to reappear on every startup.
Selectable models come from checked presets and an anonymously refreshed catalog;
a new account or manually created profile is not required. This is availability
in the app, not a guarantee of third-party inference availability.

Cards show authentication, shared quota scope, capabilities, lifecycle, model
choices, privacy notices, observation time and status. Green means the catalog
or metadata endpoint responded, explicitly **not** a successful generation test.
Unknown/stale observations are gray. Red retains the observed failure. The app
never silently moves a quota-exhausted request to a paid route or another service.

Native tools depend on the selected model and actual adapter. Legacy Pollinations
and the LLM7 Nemo preset have no native tools. Free-only allowlists protect
mixed paid/free gateways. Requested and served model identifiers are separate;
missing token usage stays unknown. Public-cloud memory and attachment restrictions,
per-profile MCP consent and the existing LLM7 approval gate remain in force.
Experimental/noncommercial/demo routes retain their notices even though the user
requested pre-created enabled profiles. Updated LLM7 agreement coverage and native
OpenCode/BridgeNode inference remain externally unverified.

AI Horde uses native text/image jobs with bounded polling, progress and explicit
cancellation. One vetted Qwen Gradio Space has a schema-checked queue adapter.
Accepted job IDs survive request interruption for regular conversations. Unknown
submission outcomes are not resubmitted. Temporary conversations keep queue state
in memory. Completed responses, reasoning-only output and empty jobs are distinct.
No image quality or volunteer-worker availability claim follows from these tests.

Private llama.cpp, Ollama, LM Studio, KoboldCpp and LocalAI settings remain local/
custom transports. Anonymous local validation checks the actual model API without
requiring an app-specific gateway endpoint. Native Ollama routes normalize to the
server root. Grouping these options in the Free screen does not apply public-cloud
privacy normalization to private servers. WebLLM is described as requiring a
separate browser runtime, not exposed as a working native Android HTTP service.

## Explicit search and truthful completion

Explicit search requests resolve an elliptical follow-up against the previous
question and run the already-authorized app search service before text-only model
inference. Evidence packets keep source URLs intact and distinguish a search
snippet from a fully read page. No authorized engine or no usable evidence produces
a precise unavailable outcome. It does not become a successful verified answer
merely because a provider returns text. Search acquisition and answer-delivery
telemetry are separate.

Initial and deferred catalogs use one `web_search` interface. Dynamic raw engines
are permission/budget-bound before aggregation. Profiles show their effective
engines, unavailable/excluded reasons and repair actions. Shared DuckDuckGo cooldowns
retain the original challenge/status instead of losing it across profiles. A manual
connection test clears a repaired configured engine's access block and tests one
request. Ambiguous transport/decode failures remain ambiguous.

Whole-response tool-control JSON and assistant envelopes trigger bounded protocol
recovery rather than appearing as answers. User-requested JSON and code examples
remain text; no JSON-looking content executes a tool. Empty discovery arguments
return the authorized read-only inventory, including what is currently advertised.

## Crawlers Tool Plugin

The optional Crawlers plugin has a single managed `crawler` tool with fetch,
start, status, results, resume and cancel actions. Native HTML/PDF/Office reading,
optional anonymous Jina Reader, and explicitly selected connected read-only tools
are alternative readers. Jina is separately selected per profile, receives only
public URLs, and has a shared 20/minute local ceiling and observed-429 cooldown.
The app never uses a second reader to bypass a detected login, forbidden response,
robots denial or challenge.

Quick and smart modes read seeds; deep mode follows same-host links within the
selected page/depth bounds. Two workers share the root tool budget. Robots reads
also consume child capacity. Per-host spacing, DNS/private-destination checks,
bounded frontiers, per-page size limits and a content-storage ceiling apply.
A 25-second time slice pauses at committed pages. Status/results do not fetch.
Restart does not automatically dispatch network work; resume rechecks the accepted
conversation, profile, selected readers and permission revision. Cancellation stops
active workers while retaining committed evidence.

Web Fetch and managed jobs reuse captured pages for five minutes within the same
conversation and permission scope, preserving retrieval time, hashes and source
metadata. Temporary evidence stays in memory. Job settings show progress, failures,
resume/cancel/delete and an explicit copy into the Research evidence archive.
Scientific Examination remains a separate operation; a successful crawl does not
claim study appraisal or complete scientific evidence.

The optional Python companion is now in the catalog. Its alias is
`crawler_companion`; its configured HTTP bearer credential and separate 32–64-byte
grant key remain distinct. The grant key is entered as hexadecimal in Crawlers
settings, encrypted in the vault and tied to the connection endpoint. Endpoint
changes and uninstall remove its grant reference. Signed grants travel in MCP
metadata, never tool arguments or model prompts. They bind the profile, accepted
turn, permission revision, request/byte limits, expiry and temporary scope.
Repeated companion jobs share a grant rather than multiplying its allowance.
Companion chunk imports validate source/revision, code-point offsets and SHA-256
before using text as evidence. Partial pagination stays partial.

The companion supports Trafilatura, Scrapling fallback, PDF text and optional
local English Tesseract OCR. OCR handles at most three scanned pages, retains
`ocrUsed`, and always reports partial evidence. Cancellation kills the extraction
process group. Dependencies and language data must be installed by the server
owner; the phone does not download document models.

## Qualification boundaries

Browser-rendering egress remains unqualified in the Python companion and its health
resource reports `browserReady=false`. A selected connected reader may offer its
own rendering, but installing Crawl4AI alone does not establish secure, metered
browser execution. No CAPTCHA or access-control bypass is implemented. Android
screens have not been rendered on a physical device in this workspace. Live generation, all
12-provider sustained operation, actual device frame timing, and phone-to-server
recovery still require runtime acceptance.

The existing Unified Reliability v2 release gate is unchanged. Unit tests and
fixture integration tests cannot replace its unrun device/server cases. A future
version may only be published after those records have genuine passing evidence.

## Validation

- Android JVM regression suite: 3,975 passed across 659 suites, zero failures or
  errors; two opt-in heap cases skipped. This includes the Combined audit cases,
  migration of twelve default service profiles, queue interruption/resume,
  exact free-route admission, per-model/shared quotas, domain-scoped crawl reuse,
  bounded child dispatch, companion grants and protocol output handling.
- Constrained 256 MiB Java heap: all six tests passed, including more than 40 MiB
  of retained Research evidence and a 2,000-message export. All 661 normal-suite
  report files were preserved byte for byte.
- Python crawler: all 41 tests passed with zero skips, including real scanned-PDF
  OCR with local Tesseract/Poppler, SQLite persistence, authorization, metering,
  cancellation and HTTP transport fixtures. Resource warnings were treated as errors.
- Repository script tests: 147 passed. Android resource preflight, exported Room
  schema verification, and telemetry coverage for all 36 tool-definition producers
  passed. Coverage inventory is not an end-to-end execution claim.
- Android regex compatibility: 740 literal patterns passed; 45 dynamic patterns
  were skipped by the static checker. All 163 changed Kotlin files passed ktlint.
- Fresh generated DEX: 1,824 guarded methods passed the exclusive 16,383-code-unit
  limit. The largest was `AgentToolResolver.resolve` at 15,822 units, followed by
  `ChatRepositoryImpl.delegateToProfile` at 15,604. This is generated debug DEX,
  not a claim that a new release APK was installed or qualified.
- Native-graphics Compose renders cover a 360 dp provider card at 1.3 font scale,
  expanded privacy/model details, working profile controls, and an untested service
  with a gray status circle. Visual review led to a contrast-only status-label
  correction and singular model-label correction after the normal suite. Both
  corrected render tests passed; the dark large-text render was inspected again.
- Final Android lint passed with zero errors, 2,911 warnings and 24 hints.
  Instrumentation sources compiled; instrumentation was not executed on a device.
  Lint caught an Android 12 incompatibility in `InputStream.readNBytes`; the
  connection probe now uses an API-31-compatible bounded read loop. This and the
  UI-only polish followed the normal-suite run. All four focused tests then passed:
  both provider renders and two real HTTP local-server cases for anonymous API
  paths, absent authorization headers, bounded bodies and malformed catalogs.
  Fresh CI on the published head remains a separate requirement.

The inventory-integrity checker passes. The stricter
`check_unified_reliability_v2.py --require-done` deliberately still fails:
48 tasks are not DONE and 99 acceptance cases are NOT_RUN. No fixture results were
substituted for those qualification records. Version 0.9.55.0 / build 124 is
unchanged; this work is not a published release or a full-completion claim.

## Official integration references

- Kilo: https://kilo.ai/docs/gateway
- OVHcloud: https://docs.ovhcloud.com/en/guides/public-cloud/ai-machine-learning/ai-endpoints-getting-started
- BlockRun: https://blockrun.ai/free
- AI Horde API and terms: https://aihorde.net/api/swagger.json and https://aihorde.net/terms
- Vireonix: https://vireonix.ai/docs and https://vireonix.ai/privacy
- OpenCode: https://opencode.ai/v2/docs/console/api/inference/ and https://opencode.ai/v2/docs/console/models/
- Qwen Space schema: https://qwen-qwen3-demo.hf.space/gradio_api/info
- BridgeNode: https://bridgenode.cc
- LLM7: https://llm7.io/terms
- Cehpoint: https://ai-api.cehpoint.co.in
- PorchLM: https://guythatlives.net/terms
- Jina Reader: https://jina.ai/reader and https://docs.jina.ai/
- Tesseract: https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage
- Crawl4AI self-hosting: https://docs.crawl4ai.com/core/self-hosting/

These documentation/catalog checks are dated October 22, 2026. They do not certify
future model availability, access policy, free quotas or upstream privacy promises.
