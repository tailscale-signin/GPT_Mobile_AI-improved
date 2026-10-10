# Diagnostics fixes and SearXNG / YouTube implementation start

Baseline: `a66155dfdc0cf647dd368019f605d91689d48583`.
Source plan: the October 9 `IMPLEMENTATION_PLAN.md`, prepared against the same baseline.

## Changes delivered

- Preserve the existing plugin-specific connection manager. Web Search now lists its assigned provider connections instead of offering unrelated GitHub settings.
- Saved marketplace MCP cards edit the exact connection UID, preserving credentials and permissions through the existing editor. Curated aliases remain stable. The native Airbnb card opens its own settings instead of a fictitious MCP setup form.
- Native optional plugins keep their checksum-verified download/install/configure/enable flow. Hosted MCP cards connect directly; no inert setup ZIP is required. Android does not execute downloaded Python or Node programs.
- Reduce marketplace copy and repeated status badges. Required endpoint/key fields remain highlighted. Usage and setup details remain in the expandable area.
- Add explicit GitHub issue/PR query discriminators, normalize full repository references without overriding conflicting owners, and enforce header-based cooldown by API resource. Real tool instances share bounded rate state per credential; successful core calls do not erase search exhaustion.
- Stop provider calls after MCP quota exhaustion across both modern and legacy transports. Surface unauthorized calls with a provider-specific credential action. No tool call is retried after a dropped connection.
- Separate multi-search rate limits from authentication failures and cool down failing engines while retaining sibling providers. Allow one tools-disabled recovery for an output-limit failure when no visible text has been emitted; existing partial output remains preserved when present.
- Normalize legacy flat/JSON-encoded memory inputs before the existing selected-action validation and privacy checks. No model-authored capture content is substituted for the user message.
- Set map labels to the OpenFreeMap-supported Noto Sans font. Log MCP health only on state/catalog changes, retaining known latency/counts.

## Plan work started

SearXNG has a stable optional preset requiring a reachable adapter endpoint and host bearer token. It starts disabled when saved. The adapter uses `pageno`, `num_results`, full structured JSON, and supported day/month/year ranges. Seven-day filtering is explicitly reported as approximate; this does not claim verified publication dates. Existing owner routing, merge/dedup and budgets remain in place. Source engine labels and timestamps survive normalization.

YouTube has a separately optional, initially disabled computer-hosted preset and a pinned prototype configuration. `mcp/research/youtube/artifacts.mjs` implements the first artifact/contract work package: video-reference validation, normalized actual language/track provenance, immutable timestamped segments, bounded memory/admission, scoped cache keys with configuration revision, identical-request single-flight, expiry and advancing cursors. Remote excerpt reads require a separate grant. Oversized segments return a typed error, rather than an empty page with the same cursor.

This module is **not yet exposed as a production MCP facade**. Its acquisition function is injected and has no network adapter in this change. The prototype upstream wrapper is a separate compatibility path, not an implementation of this artifact API. Persistent artifact storage, isolated workers/deadlines, caption imports, video cards/viewer, evidence chunk routing/coverage and restore integration are subsequent plan work packages.

## Log findings and remaining runtime actions

| Finding in the supplied log | Resolution / boundary |
| --- | --- |
| GitHub issue search HTTP 422 | Add missing discriminator while retaining explicit PR searches. |
| Invalid repository owner/name | Normalize unambiguous `owner/name` and GitHub repository URLs; conflicting/invalid references fail before network work. |
| GitHub HTTP 403 rate limit | Resource-specific shared cooldown; no retry loop until reset. A provider quota cannot be removed by app code. |
| Nine malformed memory action inputs | Accept valid nested, flat and bounded JSON-encoded objects; reject malformed payloads. |
| Anonymous provider allowance used up | Shared connection cooldown; configure provider credentials or wait for allowance renewal. |
| MCP HTTP 401 | Actionable provider credential error. Actual tokens must be corrected on the device/host. |
| Private MCP connection EOF/closed | Keep existing bounded discovery recovery; do not replay a potentially mutating tools/call. Host/network availability requires a live check. |
| Search engine cooldown/unavailable responses | Preserve partial results; classify throttling before HTTP 403 authentication. |
| Per-response repeat limits and 64-call limit | Retain intentional bounds; synthesize from collected evidence rather than silently increasing allowance. |
| Permission denied / already dispatched | Retain consent and duplicate-action protection. These are not authorization to bypass user choices. |
| Provider output limit | One no-text finalization attempt; preserve streamed partial answers. Actual provider/model output ceilings still apply. |
| Read URL HTTP 404/429 | Honest unavailable/throttled evidence; no fabricated page content or alternate unapproved endpoint. |
| Missing glyph PBF/label font | Set Noto Sans Regular explicitly. |
| Android codec, IME, GC and native feedback warnings | Platform diagnostics, with no fatal crash demonstrated in this capture; no blanket suppression. |

## Verification

- Eight Node artifact tests passed, including ten concurrent requests sharing one acquisition, pagination, denied grants, expiry and malformed timestamps.
- Production search adapter/result extraction and new pure Kotlin helpers compiled and were exercised in an isolated JVM harness with dependency stubs (11 provider fixtures plus the new regression checks). This is not an Android build.
- Kotlin style checked with the repository's ktlint 1.3.1.
- Android unit/build execution was attempted but blocked: the required Gradle 9.8.0 distribution could not be downloaded in this environment. Android CI and real phone/PC checks remain required.
- No Docker deployment, provider sign-in, live caption acquisition or user endpoint credential test was performed.

## Host prototype

From `mcp/research`, install the locked bridge dependencies. Install `uv` separately on the computer. Set a strong `RESEARCH_MCP_TOKEN`, `RESEARCH_MCP_CONFIG=youtube.config.example.json`, `RESEARCH_MCP_PORT=8114`, then run `node bridge.mjs`. Default binding is loopback. Configure a deliberate private tunnel/HTTPS route and exact allowed hosts before connecting the phone; localhost on Android means the phone itself.

The pinned wrapper exposes `youtube__get_timed_transcript` and `youtube__get_available_languages`. Its existing limitations are recorded in the design plan; it is not claimed to provide durable artifact caching, worker isolation or verified full-video understanding. Do not configure its certificate-verification-disabling proxy path.

For SearXNG, follow the plan's two-service deployment: JSON-enabled SearXNG `/search` and a distinct authenticated MCP adapter `/mcp`. Do not enter the SearXNG REST URL into an MCP field. Do not activate either integration before endpoint verification and explicit profile selection.

## Follow-up: chat, appearance and memory

- Newest-response braking now starts only after a released upward fling of at least 1,000 dp/s. It clips overshoot for at most one second. Touch, slow movement, downward motion and navigation already above the response remain free. Four production boundary tests passed in an isolated JVM runner.
- Removed the favourite-entry half-screen spacer and its item-count contribution. Target-response measurement waits are bounded, so a missing measurement reveals the positioned conversation rather than leaving its alpha at zero. Centering remains constrained by real content.
- Fresh settings default to dark mode with #00FFDE cyan, #CC00FF violet and #001219 background. Existing explicit modes and palettes are preserved. Cyan is a selectable preset, and Restore default applies its complete palette and mode.
- Appearance uses Presets / Custom / Display tabs, a selected-preset indicator, a smaller colour wheel, compact labelled sliders and fewer headings and promotional labels.
- The injected memory graph/document repositories continue backing the unified native tool. Temporary chats do not advertise persistent memory; absent backends do not advertise unsupported actions. Unified tool regressions cover backend availability, routing, cloud opt-in and the master switch; these Android-dependent tests await CI.
- Six actual memory/folder SQLite schema tests and seven native-smoke-checker fixture tests passed. The latter validate the smoke-result parser, not an attached device run. Eight transcript artifact tests and the prior isolated search/helper regressions passed again.
- Android build/unit execution remains blocked by the unavailable Gradle distribution. Gesture behaviour and theme layout still need phone/emulator verification; no visual or device verification is claimed.
