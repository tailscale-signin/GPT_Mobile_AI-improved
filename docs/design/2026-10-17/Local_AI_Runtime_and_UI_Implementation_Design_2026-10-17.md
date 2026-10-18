# Local AI Runtime and UI Implementation Design 2026-10-17

Text requirements imported from the supplied design document. Original diagrams and visual references remain in the DOCX. This specification is not an implementation-completion claim.

Local AI runtime and interface implementation design

GPT Mobile AI improved

Prepared for Emanuel Melo and the development team · 17 October 2026

1 Implementation decision

Build one local model experience over the repository's existing runtime services. The app should select a compatible execution plan, keep useful work warm, preserve context correctly, and explain which processor actually ran the request. Present this through My models and Marketplace, with focused settings and a separate advanced view.

The primary performance goal is the shortest reliable time from Send to a useful answer at the requested quality and context length. Measure first visible answer, complete response time, sustained generation, memory, heat, and tool success. Maximum speed is a policy for choosing among validated configurations; it must not silently change the selected model, discard conversation content, or override device limits.

This specification covers LiteRT-LM, its Qualcomm NPU route, standalone llama.cpp GGUF, and the existing GenieX preview routes. It includes runtime changes, NPU optimization, a simpler marketplace, settings, migration, instrumentation, and implementation acceptance. The reference handset is the project's ASUS ROG Phone 9 Pro. Device detection must resolve the actual SoC and firmware at runtime; Qualcomm maps SM8750 to Hexagon V79. [E4]

Repository baseline

The reviewed main snapshot is 7060f3245cb4012ed71043a09aa5595f9629563b. It includes the local diagnostics and tool-routing changes from PR 711. At review time, PR 712 remained an open draft at 1392525e6db560b1fc715d5534c0a02307b2d962 with the GGUF Jinja generation repair. Treat that repair as an implementation dependency and recheck its status when development begins. [R1, R15]

| Component | Reviewed dependency or state |
| --- | --- |
| LiteRT-LM | 0.18.0 already pinned |
| Qualcomm QNN runtime | 2.51.0 already pinned |
| GenieX preview | 0.8.0 already pinned |
| Standalone llama.cpp | Revision 4413a192410fad1f054e9216114087a3e01527a0 |
| Standard GGUF route | CPU and build-enabled Vulkan GPU; text manifest |

Sources: dependency catalog, runtime capability registry and artifact manifest. These are source facts, not handset performance results. [R2, R16, R17]

Delivery priorities

Preserve the recent correctness repairs and remove repeated work from the request path.

Make runtime identity, capability, memory planning and performance policy consistent across all local routes.

Qualify the SM8750 NPU configuration and optimize its sustained behavior.

Replace the dense local screens with two tabs, clearer model actions and focused settings.

Expand native tools, multimodal support and experimental accelerators through independently tested additions.

2 Current implementation and concrete gaps

The repository already has isolated inference, an engine holder, a runtime router, artifact manifests, memory admission, thermal governance, tool routing, download workers, qualification records and release gates. Extend these components so the changes remain integrated with chat, Local Assist and diagnostics. [R3, R6, R9, R10, R16]

Repeated artifact verification on the request path

The provider inspects a model before streaming. For GGUF and GenieX artifacts, inspection validates the manifest and invokes SHA-256 verification across the declared files. This can reread large weight files before warm requests. LiteRT has a cached validation path already. Introduce equivalent immutable installation identity and cached inspection for artifact routes, while retaining complete verification at import, installation and explicit integrity checks. [R5, R10]

GGUF context is rebuilt for each generation

The current JNI implementation clears llama memory before generation and tokenizes the rendered history again. GPU placement is effectively all layers or CPU, and native batching is fixed at 256 with the same prefill and decode thread count. These are concrete opportunities for context reuse and device-specific tuning. Their speed benefit still requires measurement. [R4]

Automatic planning excludes manifest routes

PlannedLocalRuntime identifies LiteRT using the model-path suffix and invokes the evidence-based planner only for that route. GGUF and GenieX manifests receive no equivalent selection decision. Its shared cold-memory estimate also uses the length of the manifest path's file, rather than the model assets it describes. Replace these path-based decisions with typed artifact information and runtime-specific memory estimates; retain the adapters' existing admission checks. [R3]

Composer warmup misses the selected local chat model

ChatReadinessCoordinator warms remote transport when the composer is engaged. Its local preload targets configured Local Assist while the composer is not engaged. Add selected-local-profile warmup to this existing coordinator and scheduler so selecting or focusing a local chat can prepare its engine under the existing resource lease. [R8]

Marketplace labels and filters need correction

LocalMarketplaceScreen identifies any download URL ending in .gguf as GenieX, although the artifact layer distinguishes standalone llama.cpp from GenieX. Its Tools filter currently returns true for every item. Family grouping, downloads and profile actions already exist; correct the projection and simplify the surface. [R11, R12, R16]

Verification levels need one consistent presentation

The current Verify load action explicitly does not qualify inference or NPU performance. Some settings correctly distinguish requested acceleration from unreported effective acceleration, while another overview uses the configured accelerator as actual. The new UI must read one shared evidence record and retain Unknown when runtime execution is unreported. [R13]

3 Supported execution routes

Model format, runtime and accelerator are independent identities. A .gguf file identifies an artifact format. LiteRT-LM and llama.cpp identify engines. QNN is part of a Qualcomm execution stack. CPU, GPU and NPU identify compute placement. Selecting NPU cannot convert an incompatible artifact. [R16, E1, E3]

| Route | Artifact and current app scope | Required implementation policy |
| --- | --- | --- |
| LiteRT-LM CPU or GPU | Compatible .litertlm; current engine route | Intersect inspected model capabilities, adapter features and device support |
| LiteRT-LM Qualcomm NPU | SoC-targeted .litertlm with matching dispatch and QAIRT runtime | Verify exact target and observed NPU execution before a tested badge |
| Standalone llama.cpp | Schema 3 GGUF; current CPU or Vulkan text route | Preserve reliable text and app-managed tools; add native features separately |
| GenieX QAIRT preview | Compiled context shards, tokenizer and configuration; NPU only | Preserve preview build gate and compiled package constraints |
| GenieX GGUF preview | Separate runtime with explicit artifact restrictions | Qualify each backend and feature in the pinned preview SDK |
| Standalone GGUF Hexagon extension | Upstream experimental backend; not exposed by the standard app registry | New native integration and device qualification before availability |

The official LiteRT-LM NPU guide includes an SM8750 Gemma 3 1B artifact. It illustrates why device-targeted variants are necessary; it is not a universal compatibility list. Generic LiteRT CompiledModel support for .tflite and vendor compilation is a separate integration from LiteRT-LM chat. [E1, E3, E10]

Upstream llama.cpp supports Hexagon through an explicitly experimental backend. Therefore GGUF NPU support is a valid development track, but the standard app cannot advertise it merely because upstream supports it. Preserve the current CPU/Vulkan implementation while evaluating the native extension. [R17, E5]

Capability calculation

Resolve effective support as the intersection of the artifact declaration, inspected metadata, packaged runtime, adapter implementation, device compatibility and current request requirements. Track evidence separately from availability. A successful load does not imply successful tools, images or sustained generation.

Use distinct feature states for text, tools, images, audio, thinking control and speculative decoding. For tools, retain the transport as native structured, app prompt bridge, unavailable, or unknown. A verified prompt bridge can support tools without claiming native function calling. Native tools=false must not suppress a separately tested prompt bridge; an explicit user setting that disables tools blocks both. Preserve missing metadata as Unknown, and never treat it as verified support.

A fallback may choose another supported backend for the same artifact at a safe request boundary. A different quantization or compiled model is a different artifact and requires an explicit compatible selection. Preserve the user's model, context and tool permissions when a speed preset changes.

4 Shared architecture and data contracts

Keep LocalRuntime as the common execution interface and ProcessLocalRuntime with LocalInferenceService as the native ownership boundary. Keep LocalRuntimeRouter and ArtifactRuntimeMultiplexer responsible for route selection. UI state should be derived from repositories and runtime events rather than reconstructing compatibility in Compose. [R6]

Figure 1. Proposed ownership and request flow. The planner consumes capability and performance evidence; one inference service owns native execution. Tools return through the existing app orchestrator.

Extend existing identities

Extend ModelArtifactManifest, LocalModelCapabilities, LocalExecutionPlan and RuntimeQualificationKey rather than creating competing registries. Add a small immutable descriptor for normalized inspection and a presentation projection for the UI. Preserve existing serialized enum values, including the LITERT_LM umbrella client type used by local profiles. [R3, R11, R16]

| Contract | Required fields and responsibilities |
| --- | --- |
| Artifact descriptor | Family and artifact IDs, runtime, format, installation epoch, manifest and asset digests, tokenizer and template identity, total asset bytes |
| Resolved capabilities | Available backends, per-modality support, tool transport, context ceiling, dynamic context support, precision, target SoC, requirements and reasons |
| Execution evidence | Requested backend, observed backend or Unknown, evidence origin, runtime build, loaded libraries, device identity, request and attempt IDs |
| Performance identity | Existing qualification tuple plus benchmark protocol revision, workload class, prompt band, cache state and relevant tuning |
| UI projection | Installation, compatibility, readiness, observed acceleration, profile references, measurements and one primary action |

Keep installation, compatibility, readiness and performance qualification as separate state dimensions. Installed means the files are present. Load checked means initialization passed. Response checked means a controlled request produced valid output. Qualified means the release evidence requirements passed for the matching identity.

Immutable artifact inspection

Verify all assets during a staged import or download, then atomically publish an installation directory that is never modified in place. Cache metadata by installation epoch, manifest digest and inspection/runtime capability revision. For user-selected external files, copy into this managed installation before granting the fast path. A matching filename or timestamp alone must not establish integrity. A locally computed digest establishes installed identity; publisher verification additionally requires a trusted source digest or signature.

Invalidate the inspection cache on replacement, repair, deletion, manifest change or importer revision. Invalidate compiled-engine caches and device qualifications when their runtime, compiler, driver, firmware, SoC or settings identity changes. Keep those invalidation scopes distinct so an OS change does not require needlessly re-hashing unchanged weights on every request.

Runtime ownership

Keep one heavy local generation lease by default. Foreground chat takes priority over speculative warmup and background Assist or indexing. Two local profiles in Combined mode should queue or share only explicitly supported resources; they must not silently create competing NPU engines. Additional concurrency is a separately benchmarked mode.

Native commands carry session generation and request IDs so callbacks from a cancelled, replaced or crashed worker cannot update a newer conversation. Preserve the operation journal, lossless terminal ordering, admission and worker-recovery paths. Retain the native lease and handle until terminal acknowledgment; Kotlin cancellation alone cannot authorize freeing, resetting or reusing native memory. If cancellation exceeds the existing bound, quarantine and stop the worker before reuse. Apply tuning changes at a safe boundary and show when a reload is pending.

5 NPU implementation and performance strategy

Exact compatibility before allocation

For the reference handset, resolve SM8750 and V79 from device detection and verified runtime evidence. Match the compiled model target, package metadata, ABI, dispatch library, QAIRT version and required runtime assets. Record the loaded dependency inventory in diagnostics. The repository already pins QNN 2.51.0; keep the compiled artifact and dispatch build compatible with that pin. [R2, R6, E4]

Qualcomm recommends matching the deployed QAIRT version to the version used to compile model assets. Use package-specific compatibility records instead of copying a version from a sample setup guide. A library-presence probe is a prerequisite check; a controlled response establishes functional readiness separately from accelerator confirmation. [E3]

The current QNN executionQualified result combines successful nonempty output with a configured NPU engine. Do not relabel it as independent hardware observation. The new record must distinguish requested/configured placement, runtime-reported backend, and native trace or profiling evidence. Runtime-confirmed NPU routing does not imply every operation ran on NPU; preserve mixed or unknown operator coverage. A functional check may pass while accelerator evidence remains unconfirmed. [R6]

Fast load and context allocation

Reuse a prepared NPU engine while its identity remains valid and resources permit. Give the runtime a writable, private compiled-cache directory where its API supports one. Store cache identity and failure reason, and delete only the failing entry during cache recovery. Keep binary compilation cache, loaded-engine residency and conversation KV state separate in code and diagnostics.

LiteRT-LM 0.18.0 adds ModelInfo introspection and dynamic on-demand NPU KV growth. LiteRtModelInspector already calls ModelInfo and reads context, backend, modality, tool, thinking and speculative support. Preserve that implementation, strengthen its installed-identity cache, and consume the metadata consistently in planning and UI. Validate dynamic KV behavior per artifact. A fixed compiled QAIRT context cannot be enlarged by a runtime slider; confirm the applicable contract in the pinned GenieX SDK. [R7, E2, E12]

Performance controls that reach the native runtime

Map the product preset to native options only when the pinned adapter exposes them. The inspected QNN wrapper passes Backend.NPU with its native-library directory; it does not establish an app-controlled HTP performance-mode bridge. Add a typed native configuration bridge only after confirming the supported SDK surface. Otherwise show runtime-managed behavior in Advanced and avoid ineffective toggles. [R6]

Benchmark bounded startup or prefill boosting and sustained execution independently where supported. Restore any temporary performance request when the operation ends. Upstream Hexagon documents a burst mode that requests maximum clocks and can throttle during sustained work, so Maximum speed must consider full-response and repeated-response performance. [E5]

Thermal and power policy

Extend DeviceHardwareGovernor and ThermalForecast. Preserve the existing shared headroom sampler and recovery hysteresis. Sample headroom no more frequently than once every ten seconds, use thermal-status listeners for intervening events, and treat unavailable or NaN readings as unknown. [R6, E8]

At increasing pressure, stop speculative warmup and defer background local work first. Reduce optional CPU work or backend settings only when the runtime can change them safely. Release idle engines under memory pressure. At severe conditions, pause or terminate through the existing recovery policy and offer a clear retry condition. Do not silently shorten the user's requested answer to produce a better speed score.

NPU readiness shown to the user

Show a short result such as NPU ready, Check required, or Needs a compatible model, backed by the evidence state. Details contain model target, installed runtime, requested and observed accelerator, last check and available remedy. If execution evidence is unavailable, show Requested NPU · execution unconfirmed. Keep failed initialization, incompatible SoC, unavailable runtime, context limit and temporary memory pressure as different reasons.

6 LiteRT and GGUF integration improvements

LiteRT-LM

Preserve the current engine and conversation reuse checks, including profile, history, system prompt, tool schema and configuration identity. LiteRtLmAdapter already has these checks and dirties tool conversations when needed. Extend behavior only after preserving those correctness conditions. [R10]

Preserve the existing ModelInfo-based inspection and its tested compatibility fallbacks for older artifacts. Extend downstream consumption of context, thinking, speculative decoding and per-modality backend metadata. Keep tri-state settings where unspecified means use the runtime default. Do not force unsupported reasoning-off or sampling settings into an NPU artifact. [R7]

Initialize outside the UI thread and use asynchronous streaming. Google documents a writable cache directory and explicit engine/conversation cleanup. Apply the existing lifecycle abstraction consistently so leaving Settings does not recreate an active engine. [E1]

GGUF template and context correctness

First incorporate and validate the existing PR 712 Jinja path. Preserve pre-allocation syntax checks, per-engine compiled templates, model BOS/EOS behavior, Unicode handling and system/tool instruction preservation. Retain real-template and minified Android inference regressions. Syntax acceptance must remain distinct from a successful render and actual generation. [R15]

Add persistent conversation state to the JNI adapter. Store the committed token sequence and native sequence identity. Render the new conversation with the same template, tokenize it, and compare the actual token prefix before reusing KV. Prefill only the uncached suffix when that prefix is valid. If the native API safely supports truncation, remove changed suffix state; otherwise reset the conversation. Text-prefix matching alone is insufficient because templates and tokenization can change earlier tokens.

Invalidate or reconcile state after edited history, changed system instructions, altered tools, template changes, context rollover, partial cancellation, model switch or worker restart. Never append the full history onto an already populated KV cache. Keep a committed checkpoint so cancelled generation cannot contaminate the next request. Compare cached and fresh execution on the same runtime, backend and configuration, with identical effective context tokens and defined numerical tolerances. Use exact text fixtures where the backend supports repeatable output.

GGUF tuning and expanded support

Expose backend-specific options through a typed native configuration after adding support end to end. Tune prefill threads separately from decode threads, batch and microbatch size, partial GPU offload, flash attention and KV precision only when the pinned backend supports them. Admission must account for the additional workspace. Default choices come from measured configurations; retain safe CPU recovery. [R4]

Evaluate ARM kernel acceleration and Adreno OpenCL as separate experiments. The current build disables KleidiAI, while upstream OpenCL documents Snapdragon 8 Elite support. Compare against the app's Vulkan route with the same workload, compatible quantization, driver and thermal conditions. A newer backend or more threads is not automatically faster. [R17, E6]

Add native GGUF function calling after the text-template repair, including structured assistant tool calls, call IDs, arguments and tool-result messages. Upstream function-calling support uses template handlers and parsers beyond basic text generation. Continue the existing app prompt bridge for combinations that pass its tests. [R10, E7]

For GGUF images, add a separate multimodal adapter using the matching model and hashed projector, with image-token accounting and cancellation. Upstream uses libmtmd; the standard app's current manifest deliberately permits text only. Do not loosen that flag before implementing and testing the full path. Audio and video follow the same per-artifact qualification rule. [R16, E9]

7 Tools and full application integration

All local runtimes must participate in the existing chat, Multi-chat, Combined, Research and Local Assist flows through the same event contract. Share discovered and permitted tool definitions with the chosen adapter. Keep tool ownership, permissions, deadlines, cancellation and result accounting in the app orchestrator. Preserve the current research and ordinary-chat budget policies.

For LiteRT native tools, use manual execution where appropriate so the runtime emits the request and the app executes it once. The documented automaticToolCalling setting supports this pattern. Adapt tool responses back into the same structured conversation. Avoid simultaneous SDK-owned and app-owned execution loops. [E1]

Validate names and JSON arguments before dispatch. Correlate each call with its request, attempt and durable execution result. After a native worker dies, recover completed results without replaying a side effect. A mutation with an uncertain completion state needs explicit recovery handling; a retry must not blindly execute it again.

Retain the prompt bridge for tested models that lack native structured support. Bound parsing buffers, preserve normal streamed text, and report malformed calls clearly. Capability diagnostics should distinguish tools discovered, permitted, exposed, requested, executed and returned. An unreachable MCP endpoint is a connection failure rather than a missing model capability. [R10]

For images, verify the entire route from attachment URI and decoded input through IPC to the native encoder and model response. Use file descriptors or bounded file-based input for large media when supported, rather than repeatedly copying large image arrays. A model must not receive a vision badge when its adapter drops image data.

8 Fast response lifecycle and memory design

Prepare the selected model before Send

Extend ChatReadinessCoordinator rather than adding another warmup service. On a settled local-profile selection, run cached capability inspection. On composer focus, request one cancellable warmup for that selected identity. Use the existing scheduler, idle lease, memory reserve and thermal gates. Give Send queue priority and let it safely join or cancel preparation. An already-running blocking JNI operation must reach a safe terminal boundary before its resources can be reassigned. [R8]

Cancel stale preparation when the selected model or chat changes. Do not tokenize draft text or generate an answer before Send. Keep profile selection, engine load and conversation creation distinct so a prepared engine remains useful without committing an incomplete conversation.

Estimate actual resident memory

Replace the manifest-size input with declared weight, shard, tokenizer and projector sizes. For each backend, account for KV allocation, compute workspace, image or audio encoders, draft model memory, runtime overhead and existing resident engines. Use metadata-derived estimates where possible and measured high-water marks as additional evidence. Store the estimation method and uncertainty. [R3, R16]

Use available memory and a reserve, rather than a fixed percentage of advertised device RAM. Avoid double-counting shared mappings across UI and inference processes. Recheck admission before load, context expansion and large media preprocessing. Display Needs more free memory as a temporary state, separate from permanent model incompatibility.

Stream without UI overhead

Keep native token events ordered and lossless. Coalesce only UI presentation updates at a bounded cadence, flush the final event immediately, and avoid reparsing the full answer or rebuilding the model list per token. Benchmark IPC serialization, text processing and rendering separately from inference. Retain explicit first-native-token and first-visible-answer timestamps so a fast engine cannot conceal a slow UI.

9 Local models interface

The primary navigation is My models \| Marketplace. A Settings action stays in the top bar. Downloads appear as a small active-work badge. Local Assist remains a visible row on My models and is also reachable from Settings. Reuse the current profile actions and setup flow. [R11]

My models

Show pinned and recent profiles first. Each compact card contains model name, profile name when needed, readiness, installed storage and a processor label only when supported by evidence. Use one primary action: Chat, Check, Resume when supported, or Fix. Put Edit, Duplicate, Pin, Assign Assist and Delete in the overflow menu.

An installed artifact without a profile offers Create profile. A profile with missing files offers Repair while retaining its settings. Empty state copy is Get a model to use AI on this phone, followed by Browse models and Import. Remove the large marketplace introduction once Marketplace is a primary tab.

Keep profile deletion separate from removing model files. Removing an artifact must show the profiles that depend on it and use the existing active-model removal guard. Repair and reimport should deduplicate identical assets and preserve profile identity. [R11, R13]

Marketplace

Put search and Filters at the top, followed by Suggested for this phone. Use one card per model family and choose a compatible default variant. Cards show the family, a short purpose, selected download size, compatibility and one action. Keep Curated as the initial source with Hugging Face available from a compact source control. Existing family grouping and worker state remain the data source. [R11, R12]

Default to compatible models. Filters expose task, size and processor; format, runtime, source detail and experimental availability sit under Advanced. Show the active filter count and make Clear filters reset every filter, including npuOnly. Preserve independent search, filter and scroll state for each tab through navigation and process recreation.

Opening a model shows a short variant sheet with its recommended option selected. Explain what the selected variant can run on before Download. Keep Other versions, Requirements, Source and licence, and Performance expandable. Offer CPU or GPU alternatives only when an actual compatible artifact exists.

Installation and recovery

Reuse LocalModelDownloadWorker, LocalModelImportWorker and their existing coordinators. Route every import entry point through one inspect-and-review flow. Persist queue, progress and errors outside composables. Required authorization and model-licence actions appear when the chosen download needs them. [R11, R13]

Represent Queued, Downloading, Verifying, Installed, Checking and Failed accurately. Preserve the worker's existing partial-file and range-resume behavior. Expose Pause only after adding a durable user-paused state and validating restart and content-identity semantics; retain Cancel and Retry otherwise. Hash verification and extraction can be indeterminate phases; avoid an artificial percentage based on elapsed time.

On successful installation, offer a small response check and profile activation. A completed download must not immediately become a performance-qualified result. Interrupted, corrupt or incompatible packages remain recoverable without creating a profile that claims readiness.

Proposed primary screens

Figure 2. Interface specification, not screenshots or measured device results. The examples show the content hierarchy and actions. Production colors come from the selected app theme; sizes and status values come from actual packages and tests.

10 Settings and visual design

Simple performance presets

| Visible choice | Existing policy mapping | Resulting behavior |
| --- | --- | --- |
| Automatic | AUTO | Choose a compatible qualified plan; otherwise retain the selected compatible setup |
| Maximum speed | SPEED | Prefer measured sustained response speed with quality, memory and thermal constraints |
| Battery saver | BATTERY | Prefer valid energy evidence; reduce optional preparation when evidence is unavailable |
| Custom in Advanced | PROFILE | Preserve the explicitly selected configuration |
| Prefer reliability in Advanced | STABILITY | Preserve the existing reliability policy and its qualification requirements |

Use Automatic for new installations. Preserve each existing user's saved policy during migration, including older settings that omitted the policy field and used the prior default. Use a settings-version migration rather than changing the deserialization default for everyone. Changing the visible preset updates the existing execution policy rather than a second preference. Per-model settings own processor, context and package-specific options; global settings own policy, cache, readiness retention, idle release and download preferences. [R3, R13]

The main Settings screen contains five short rows: Performance and acceleration, Memory and battery, Downloads and storage, Local Assist and tools, and Advanced and diagnostics. Each opens a focused surface. Link to global Execution and efficiency for cross-provider settings without duplicating those controls.

Keep Keep model ready as a bounded option governed by foreground use, reserve memory and heat. Keep Model cache separate. Explain reload-required changes in the affected model detail. Hide unsupported native controls entirely or show a disabled control with a concrete reason when it explains a compatibility problem.

Diagnostics without clutter

Offer Check model for a small correctness test, Compare speed for a bounded comparison, and Sustained test in Advanced. Do not start a test when a screen opens. Show a duration estimate, progress, Stop and preserved completed results. The existing long comparison can approach 50 minutes per candidate, so it must not be the default user setup flow. [R9, R13]

Normal results contain readiness, observed processor and a concise performance summary. Debug detail includes library identities, exact failure stages, raw counters, qualification reasons and exports. Keep measured, historical and unverified results visibly distinct.

Proposed settings and model detail

Figure 3. Focused settings and an example model detail. Readiness and runtime labels illustrate the proposed states; they are not evidence about an installed model. Technical details remain available through focused rows.

Theme and accessibility requirements

Reuse GPTMobileTheme, ThemeIcon and the shared Motion specifications. Use theme surface roles for cards, primary/onPrimary for actions and onSurface/onSurfaceVariant for text. Default layout uses 16 dp outer spacing, 20–24 dp card corner radii and at least 48 dp interactive targets. Treat these dimensions as proposed design tokens. [R14]

Coordinate with the requested glass UI direction: popup and menu backgrounds use a shared theme-tinted surface at 85% opacity. Apply alpha to the background layer only. Text and icons remain opaque. A whole-composable blur would blur content and is not the desired effect. Implement backdrop blur only through a supported, measured surface path; use the tinted surface when visual effects are reduced or blur adds inference-time jank.

Keep status text alongside icons, accessible descriptions on icon actions, restored focus after sheets, and paced progress announcements. Validate composited contrast in light, dark, dynamic and custom themes, with an opaque accessibility fallback. Validate large fonts at 200%, TalkBack, RTL, landscape and narrow screens. Reuse shared short transitions and avoid pulsing accelerator gauges, continuous decoration or per-token layout animation.

11 Benchmark and qualification design

Extend LocalRuntimeBenchmarkSuite, RuntimeQualificationKey, BenchmarkStore and RuntimeReleaseGates. The existing quick suite records MEASURED results; it intentionally does not grant QUALIFIED status. Preserve that distinction. Automatic selection must not promote a fast but unverified run. [R3, R9]

Correct the measurement protocol

The current first repetition is both cold and warmup, then warmup records are excluded from aggregation. A single-candidate comparison can consequently lack retained cold-load measurements. Prompt bands are recorded on trials but the summary groups by a key without that band, and generated prose length is not an exact token count. Correct these conditions before relying on the rankings. [R9]

Create a suite run ID and protocol revision. Separate deliberately cold load, warm engine with new conversation, warm reused conversation, cache-hit start and sustained trials. Use native token counts where available and label estimates otherwise. Include workload and prompt band in comparability or aggregate stratified results explicitly. Avoid mixing unrelated historical runs into a current comparison.

Rotate candidate order and record ambient starting conditions, battery/charging state, thermal state, firmware and exact artifact/runtime hashes. Compare the same task, input budget and output constraints. A different quantization or model variant needs an explicit quality comparison; its tokens per second are not an automatic quality-equivalent win.

| Measurement | Required interpretation |
| --- | --- |
| Load time | Separate first installation/cache creation, cold and cached reload |
| First visible answer | Send-to-visible-answer including preparation, tool rounds and UI delivery |
| Prefill and decode | Native counters with token counts, runtime identity and sample count |
| End-to-end latency | Complete task, including tool results and retries |
| Memory | UI and inference process/native accounting with method and peak stage |
| Sustained performance | Fixed repeated workload and thermal history; compare late-run throughput |
| Energy | Measured method and uncertainty; battery estimates remain labelled proxies |
| Correctness | Valid answer, tool schema/result integrity, media support and recovery |

Proposed performance acceptance

These are implementation targets, not measured results. Require no full-weight hashing on an unchanged warm request; no model initialization or catalog I/O on the main thread; no unbounded queue or streaming loss; and bounded cancellation/worker recovery. Target app-side warm-dispatch overhead below 100 ms at p95 on the reference handset, excluding native inference and required tool/network work, then document any measured exception.

An optimization is eligible for default use only when the same quality-qualified workload improves or remains within the approved regression budget. Retain the existing release gates: at least 500 controlled turns, required completion counts, zero native crashes and ANRs, 50 cancellation scenarios, 10 worker-death scenarios, 20 memory scenarios, no tool replay, lossless streaming and persistence checks. Respect the additional sustained-gain requirements for experimental modes. [R9]

The current gate code combines a 99% controlled-turn condition with at least 500 completed source trials for qualification. Preserve both constraints when assembling evidence; 500 attempted requests with failures is not sufficient for that second check. First-visible p95 must also satisfy the existing ratio limit against its baseline. [R9]

Device coverage

Use the ROG Phone 9 Pro SM8750/V79 for acceptance of the target NPU route. Include a second Qualcomm generation with matching artifacts and a non-Qualcomm CPU/GPU device for compatibility. Emulator tests cover process recovery and UI behavior; they do not establish NPU speed or driver correctness. Test the exact GGUF model that failed in the user's reports with the rebuilt minified APK before closing that incident.

12 Implementation work packages

The order below keeps correctness dependencies ahead of optimization. Each package should be a reviewable change with source tests, a migration impact statement and evidence from the affected path. Relative effort is a planning estimate, not a delivery commitment.

| ID | Main scope | Dependency | Effort |
| --- | --- | --- | --- |
| LM01 | Complete GGUF Jinja and stream correctness dependency | Existing PR 712 and current main | Medium |
| LM02 | Typed artifact inspection cache and correct memory inputs | LM01 baseline | Medium |
| LM03 | Shared capabilities, observed backend and planner for artifact routes | LM02 | Large |
| LM04 | Selected local composer warmup and safe residency | LM02 and existing scheduler | Medium |
| LM05 | GGUF context reuse and backend-specific tuning | LM01–LM03 | Large |
| LM06 | NPU metadata, cache behavior and native tuning qualification | LM02–LM03 | Large |
| LM07 | Shared UI state, routes and two-tab local hub | LM03 projection contract | Medium |
| LM08 | Marketplace cards, variants, filters and unified import | LM07 | Medium |
| LM09 | Focused settings and shared theme overlays | LM07 | Medium |
| LM10 | Benchmark protocol, device gates and rollout | Begins with baseline; validates all packages | Large |
| LM11 | Native GGUF tools and multimodal extension | LM01, LM03, LM05 | Large |
| LM12 | Experimental Hexagon, OpenCL and advanced execution | LM10 plus each adapter's prerequisites | Large |

Existing files to extend

Paths beginning data or presentation below are relative to app/src/main/kotlin/dev/chungjungsoo/gptmobile. Proposed new files are identified explicitly.

| Area | Existing files or symbols | Implementation change |
| --- | --- | --- |
| Identity and installation | data/​localmodel/​ModelArtifactManifest.kt; PackageDigest.kt; LocalModelInstallationEpoch.kt; ModelBundleInstaller.kt | Immutable verification result, normalized inspection and atomic invalidation |
| Runtime planning | data/​localruntime/​PlannedLocalRuntime.kt; LocalExecutionPlan.kt; RuntimeEvidenceIdentity.kt | Replace path heuristics; include artifact routes; correct comparability and memory |
| NPU and lifecycle | LocalRuntimeQnnImpl.kt; QnnEnvironment.kt; QualcommSocSupport.kt; LocalEngineHolder.kt | Exact package/​runtime match, cache scope, safe native tuning and observed execution |
| Load and thermal control | LocalMemoryEstimate.kt; LocalWorkloadAdmission.kt; DeviceHardwareGovernor.kt; ThermalForecast.kt | Runtime-specific estimates and coordinated sustained policy |
| Readiness | data/​readiness/​ChatReadinessCoordinator.kt | Warm selected local chat through the current scheduler |
| Native GGUF | local-gguf-runtime/​src/​main/​cpp/​gguf_jni.cpp; GgufRuntimeAdapter.kt | Persistent sequence/​KV state, template correctness and typed tuning |
| Tools | data/​agent/​provider/​LiteRtLmAdapter.kt; LocalPromptToolBridge.kt | Shared capability labels, structured native tools and lossless app bridge |
| Catalog and hub | LocalAiHub.kt; LocalModelsScreen.kt; LocalModelsViewModel.kt; LocalModelCatalogUi.kt | Two tabs, one projection, one filter state and simpler family cards |
| Settings and routes | LocalAiSettingsScreen.kt; LocalRuntimeSettingsCard.kt; LocalRuntimeSettingsViewModel.kt; presentation/​common/​NavigationGraph.kt | Focused screens, durable UI state, explicit routes and legacy aliases |
| Benchmarking | data/​benchmark/​LocalRuntimeBenchmarkSuite.kt; BenchmarkStore.kt; RuntimeReleaseGates.kt | Protocol revision, stratified metrics, retained cold trials and qualification evidence |
| Native build | app/​build.gradle.kts; gradle/​libs.versions.toml; local-gguf-runtime/​src/​main/​cpp/​CMakeLists.txt | Audited pins, optional-backend inventory and minified package verification |

For entries that list a filename alone, use its existing neighboring package: runtime filenames are under data/localruntime; local settings and catalog composables are under presentation/ui/setting. The native module path is relative to the repository root. Source references below provide direct links to the audited files.

Proposed additions are LocalArtifactDescriptor, ResolvedLocalCapabilities, LocalModelPresentationState, LocalCatalogFilterState, ModelFamilyCard, ModelVariantSheet and LocalDownloadQueue. Keep each class or component focused and use the repository's MVVM, StateFlow, Hilt and Kotlin style. Reuse the existing feature settings and database ownership instead of creating a parallel settings store. [R18]

13 Migration and release integration

Preserve profile IDs, installed files, conversation links, tool permissions, Local Assist assignments and user tuning. Add explicit Room/serialization migrations for new fields and default unknown evidence to Unknown. Import older manifests through their current schema validation and enrich them without pretending a newer runtime has already qualified them.

Standard GGUF manifests currently require an exact runtime revision match. A native revision upgrade needs explicit compatibility revalidation and a new manifest binding while retaining unchanged weight bytes; old qualifications become stale. Compiled QAIRT bundles need their own compatibility decision and may require a different artifact. Do not rebind them as though they were portable GGUF files. [R16]

Keep legacy navigation compatible. The current runtime=true query opens Assist through startInDelegation; retain that alias while adding explicit section destinations and updating runtime-focused settings-search callers. Preserve marketplace setup links and the existing Use profile navigation. Persist filter and benchmark draft state with SavedStateHandle or the appropriate saveable navigation owner. [R13]

A restored benchmark remains historical until verified locally, matching the existing store behavior. After an app/runtime/firmware update, retain compatible model files and user settings while invalidating only affected caches and qualifications. A rollback disables the new optimization and returns to the last compatible profile configuration without deleting models or silently changing processor claims. [R9]

Keep runtime libraries in audited application packaging; model downloads remain data assets. Preserve the separate GenieX preview build until its compatibility and package-size gates justify a broader channel. Inventory ABI coverage, duplicate native libraries, R8/JNI retention, library provenance and transitive dependencies for every shipped artifact. Android's 16 KB support requires checking prebuilt libraries and final APK/AAB alignment as well as the toolchain. [R17, E11]

Release the cache/planner corrections and UI simplification independently of experimental Hexagon or multimodal work. Gate each new accelerator behind its native inventory, model corpus, correctness and sustained device evidence. Rebase the implementation document's source references against current main when coding begins, especially the status of PR 712.

14 Acceptance scenarios

| Scenario | Required result |
| --- | --- |
| Unchanged warm GGUF or GenieX request | Cached inspection; no full file re-hash; correct model identity |
| GGUF multi-turn conversation | Valid token/KV prefix; identical effective context; defined numerical agreement with fresh evaluation |
| Edited history or cancelled generation | Stale state discarded or safely reconciled; no duplicate text or poisoned next turn |
| Standard GGUF catalog entry | Shown as llama.cpp/GGUF throughout; never inferred as GenieX from URL |
| Tools filter | Respects typed tool support and chosen evidence level; unknown is not verified |
| Requested NPU with a permitted alternate backend | Only an artifact supporting that fallback may use it; display confirmed fallback and reason |
| SM8750 package mismatch | Rejected before expensive initialization with a specific compatible action |
| NPU load passes but response fails | Load checked retained; response and performance remain unqualified |
| Low memory or high heat | Foreground policy and recovery work; no silent model/context substitution |
| Chat with tools | One execution owner; correlated result returns; restart does not replay mutation |
| Local image request | Exact supported media route receives the image; unsupported route explains the remedy |
| Model import from either tab | Same review, validation, authorization and worker behavior |
| Interrupted download or corrupt package | Clear persistent state; retry/repair without duplicate jobs or false Ready |
| Switch tabs or recreate process | Search, filters, queue and profile identity survive as specified |
| Large fonts, RTL and TalkBack | No clipped controls; usable focus order and non-color-only status |
| Maximum speed selected | Uses comparable qualified evidence, including sustained data; preserves user requirements |
| Restore or runtime update | Evidence marked historical/stale appropriately; profiles and assets retained |
| Older settings omit execution policy | Upgrade preserves the former default; new-install Automatic does not overwrite it |
| Native tools absent but tested bridge available | Authorized bridge works; explicit user tool restrictions still apply |
| Interrupted transfer, Cancel or Pause | Each follows its distinct partial-file retention and restart policy |
| Theme-tinted overlay over varied content | 85% background alpha remains readable or uses the opaque accessibility fallback |
| Release APK and AAB | Real native inference, minification, ABI/library inventory and alignment checks pass |

The task is complete when these behaviors are implemented and the applicable device evidence is attached to the release. The finished UI must make getting and using a compatible model straightforward, while diagnostics retain enough detail to explain every runtime and acceleration decision.

15 Source references

Repository references use the reviewed main commit unless marked PR 712. Line locations and symbols refer to that snapshot. External documentation was checked on 17 October 2026; current upstream APIs must be reconciled with the app's pinned revisions before implementation.

Repository evidence

[R1] Reviewed main commit — baseline with PR 711 merged.

[R2] Dependency catalog — LiteRT-LM, QNN and GenieX pins.

[R3] PlannedLocalRuntime — lines 99, 109–126; LocalExecutionPlan — planner, qualification identity and experiment gates.

[R4] GGUF JNI — batching, threads and line 141 memory clear; GgufRuntimeAdapter — inspection and GPU layer selection.

[R5] PackageDigest — full-file hashing; GenieXRuntimeAdapter — artifact inspection and session reset. Also see R4, R10 and R16.

[R6] Local runtime sources — LocalRuntimeRouter, LocalRuntimeQnnImpl, QnnEnvironment, QnnExecutionEvidence, LocalEngineHolder, ProcessLocalRuntime, LocalInferenceService, DeviceHardwareGovernor and ThermalForecast.

[R7] LiteRtModelInspector — model metadata inspection.

[R8] ChatReadinessCoordinator — composer and local Assist preparation around lines 103–132.

[R9] LocalRuntimeBenchmarkSuite, BenchmarkStore, and RuntimeReleaseGates — trial generation, qualification and migration requirements.

[R10] LiteRtLmAdapter and LocalPromptToolBridge — inspection, conversation reuse and app-managed tool path.

[R11] LocalAiHub, LocalModelsScreen and LocalModelsViewModel — navigation, cards, profile actions, filter bugs and worker state.

[R12] LocalCatalogPolicy — compatibility report, evidence states and recommendation ordering.

[R13] LocalRuntimeSettingsViewModel, LocalRuntimeSettingsCard, LocalAiSettingsScreen, ExecutionEfficiencyScreen, and NavigationGraph — load verification, settings, actual/requested processor presentation and legacy routes.

[R14] Theme, ThemeIcon and Motion — shared theme, tint and animation behavior.

[R15] PR 712 and reviewed draft commit — Jinja template and stream-repair dependency; inspect current state before implementation.

[R16] ModelArtifactManifest — runtime/format separation, schema limits, asset verification and device evidence.

[R17] RuntimeCapabilityRegistry, native CMake, and app build — packaged features, runtime pin, native configuration and flavor gates.

[R18] AGENTS guidance — repository architecture, style and test organization.

Official platform evidence

[E1] Google LiteRT-LM Android API — lifecycle, cache, modalities and manual tool execution.

[E2] LiteRT-LM 0.18.0 release — ModelInfo, dynamic NPU KV growth and GPU attention optimization.

[E3] Google LiteRT-LM NPU guide and Qualcomm Genie deployment — target-specific artifacts and QAIRT version matching.

[E4] Qualcomm QAIRT overview — SM8750, SoC model 69 and V79 device mapping.

[E5] llama.cpp Snapdragon backend — experimental Hexagon, v79 libraries and power-mode behavior.

[E6] llama.cpp OpenCL backend — Adreno and Snapdragon Android support.

[E7] llama.cpp function calling — native and generic template handlers.

[E8] Android Thermal API — status, headroom, sampling and device limitations.

[E9] llama.cpp multimodal guide — libmtmd and paired model/projector inputs.

[E10] Google LiteRT Qualcomm integration — separate generic LiteRT vendor integration.

[E11] Android 16 KB page sizes — native library and final-package compatibility.

[E12] GenieX CLI reference — compiled QAIRT context limit; reconcile current upstream behavior with pinned 0.8.0.

