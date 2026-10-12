# Local Models Catalog
## Clean discovery. Complete GenieX coverage.

**Design and implementation specification**

GPT Mobile AI (Improved)  |  12 October 2026  |  Version 1.0

**Repository:** tailscale-signin/GPT_Mobile_AI-improved
**Inspected branch:** main
**Inspected commit:** 7553e4ff14316f0d28639d5cc0ed24cb59511066

**Decision:** Keep the existing local-inference architecture, replace the fragmented catalog interface, and complete GenieX integration through explicit artifact, runtime, device, and workflow checks.

**Latest-release finding:** GenieX v0.8.0 is the latest published release found in this audit. The repository already declares `com.qualcomm.qti:geniex-android:0.8.0` in its separate GenieX build. A version bump is not currently required; broader supported-model execution is. [R01-R03, U01]

**Delivery status:** Design only. No repository files were changed, dependencies resolved, APKs built, or physical-device tests performed for this document. Proposed behavior is not a claim about the installed app.

**Primary device target:** The user's ROG Phone 9 Pro / SM8750. Detect RAM, firmware, free storage, and runtime availability at execution time rather than assuming a fixed configuration.

### Reading map

01-02  Verified baseline and product requirements
03-04  Screen, cards, details, and interaction design
05-06  Recommended models and complete catalog coverage
07-08  GenieX build, execution, and feature integration
09-10  Data contracts and migration
11-13  Installation and file-level implementation plan
14-15  Tests, rollout, and completion criteria
Appendix  Primary sources and repository references

---
# 01 / Verified baseline

The source snapshot is app version **0.9.43.0**, build **112**. Its normal build declares LiteRT-LM **0.18.0** and QNN **2.51.0**. GenieX is an optional, separately identified APK, not proof of availability in the normal installation. [R01-R03]

| Area | Observed implementation | Required change |
| --- | --- | --- |
| Navigation | Library/Settings, a separate marketplace, then Discover/Browse/Downloads. | One catalog route with three primary destinations. |
| Filters | All, LiteRT, QNN, and MTP mix runtime, processor, and feature concepts. | Independent runtime, processor, task, and feature filters. |
| Recommendations | Screen-level checks include download URL, declared minimum RAM, and accelerator eligibility. | One shared compatibility result and task-aware ordering. |
| Catalog source | Repository applies device filtering before returning visible entries. | Retain an unfiltered snapshot for complete catalog browsing. |
| Native packaging | GenieX and qnn-runtime dependencies are mutually exclusive. | Preserve separation; make runtime availability visible. |
| GGUF NPU | Manifest validation restricts GENIEX_LLAMA_CPP to CPU/GPU. | Implement a qualified Hexagon route, not a blanket bypass. |
| GenieX workflows | Adapter rejects vision, images, tools, constrained JSON, thinking, and speculative decoding. | Track each capability separately and add tested adapters. |

These observations come from the catalog screen, ViewModel, repository, artifact manifest, and GenieX adapter, not from running the app. [R04-R10]

### Keep the parts that already protect users

Reuse `LocalRuntime`, the private `:local_inference` worker, resource admission, interruption handling, artifact hashes, per-device evidence, and the existing downloader. The redesign must not move native work into composition or replay prompts/tools after a crash. Existing GPU/NPU qualification requirements remain authoritative. [R09-R11]

### Resolve the version-versus-support distinction

The latest release tag is v0.8.0, published on 2 October 2026. Current upstream documentation describes capabilities beyond this app's preview adapter. Documentation on a moving website is not proof that every method is present in the exact Maven artifact: record the resolved binary, inspect its API, and qualify the adapter before exposing a capability. [U01, U03, R10]

---
# 02 / Product requirements

### P0: A simple path to a working model

A new user should reach an appropriate model without choosing a runtime first. Open **Recommended**, inspect one card, confirm the selected package, and download. A model must explain its next actionable step: install, accept a license, choose a different build, free storage, verify, or use.

The redesigned screen must retain every existing installed model, profile association, download action, import flow, local-assist setting, and runtime setting. Simplify presentation; do not silently remove functionality.

### P0: Complete coverage without false support claims

“Full GenieX coverage” means representing every model/artifact in the supported upstream GenieX catalog snapshot, plus a compatible-GGUF import/discovery path. It does not mean every upstream artifact works on every phone, nor that the entire Qualcomm AI Hub inventory is a GenieX chat-model inventory.

An entry may be listed but not installable. Show the exact reason and keep it discoverable in All models. Conversely, an installed model that disappears upstream must remain in Installed with its original identity.

### P0: A current, auditable runtime

Retain GenieX 0.8.0 until a newer verified release is available. Centralize the version and dependency alias, audit its actual native libraries, and test the standard and GenieX builds separately. Do not replace the normal build's QNN libraries with GenieX libraries through `pickFirst`. The existing source explicitly separates these distributions. [R02-R03]

### P1: The best models for the task and device

“Top” means useful, compatible, and evidenced for the selected workload, not simply the largest model or highest download count. Recommend at most six families, initially four where enough candidates qualify. This is a presentation limit only: All models and search remain exhaustive for the accepted snapshot.

### P1: Full supported Android workflow closure

Text, GGUF NPU, compiled bundles, and image-model integration are explicit workstreams. Thinking, tools, constrained output, audio, and acceleration features require individual adapter/API decisions. A model-family badge must not enable an unsupported app workflow.

**Out of scope:** unrelated UI renovation, desktop gateway model ranking, speculative GPU/NPU partitioning, automatic native-library downloads, and claims of device speedups without measurements.

---
# 03 / Screen architecture

### One entry point, three destinations

**Recommended** is the default for a new installation. **All models** provides full discovery. **Installed** owns installed models and a filter for downloads. A settings icon opens the existing local-runtime settings route; import and refresh live in overflow. Preserve existing navigation entry points by mapping them to the new destinations rather than breaking callers.

```text
Local models                         Settings  More
Search models...                            Filters

Recommended       All models       Installed

For this device
4 candidates available               Why these?

Qwen3 1.7B
Everyday chat candidate
GenieX | selected variant | download size
Compatibility and evidence status          Details

Gemma 3 1B
Small text model
LiteRT-LM | selected variant | download size
Compatibility and evidence status          Details

Downloads: 1 active                         Manage
```

The wireframe illustrates structure, not measured recommendations or actual installed state. Names, package sizes, counts, and statuses must come from resolved data.

### Remove competing controls

Do not repeat an overview card, import panel, runtime settings, local-assist toggle, Wi-Fi switch, and marketplace promotion above the model list. Move preferences to Settings and preserve a compact downloads tray only when work exists. No nested primary tabs and no separate “Browse marketplace” gate.

### Search and filters

Search family name, publisher, model ID, task, and variant label. A single filter sheet separates **Runtime** (All, LiteRT-LM, GenieX), **Processor** (Any, CPU, GPU, NPU), and **Capabilities**. Put precision, context, compiled/GGUF engine, size, and evidence filters in an expandable Advanced group. MTP is a feature, never a processor.

All models defaults to **Works on this device**, with a prominent **Show every listed model** control and “available / listed” counts. A failed source refresh must not change the count to zero or hide cached results. Recommended explains its selection and links to the unfiltered catalog.

---
# 04 / Cards, details, and states

### Card contract

One card represents a model family, not every quantization. Show its name, one plain-language purpose, a selected-variant summary, one compatibility line, and one primary action. Use at most two small badges. Keep filenames, hashes, large capability grids, and tuning controls off the card.

Selecting the card opens details. The details screen contains **Overview**, **Variants**, and collapsed **Technical details** sections rather than another tab hierarchy. Variants identify format, runtime, processor, size, context, precision, and evidence. Changing a variant recomputes compatibility before changing the action.

| State | Primary action | Required explanation |
| --- | --- | --- |
| Resolved package; checks pass | Download | “Not yet tested on this device” where applicable. |
| License or authorization needed | Review access | Name the source and missing requirement. |
| Downloading or paused | Pause / Resume | Bytes, progress, and persistent task state. |
| Download verified; not tested | Verify model | Installation integrity is not execution evidence. |
| Installed and usable | Use model | Reuse or create a compatible profile. |
| GenieX absent in this APK | View GenieX build | Explain the separate installation and data boundary. |
| Unsupported or quarantined | View reason | No misleading enabled Use or Download-to-run action. |
| Source unavailable | Retry refresh | Keep the last valid catalog and timestamp. |

Resume is exposed only when the underlying transfer supports a validated resume point; otherwise label the operation Restart. Unverified execution may be offered through the existing isolated verification flow, never disguised as ordinary retry.

### Detail information that prevents bad downloads

Before download, show the total selected package size, auxiliary assets, required temporary storage, estimated working memory at the chosen context, and whether a sign-in/license action is needed. Explain estimates explicitly. A GPU variant's capabilities must not be inherited by a compiled NPU variant.

### Visual and accessibility specification

Use existing Material 3 theme tokens in light and dark mode, 16 dp page gutters, 12 dp card spacing, restrained surfaces, and a single accent for the primary action. Use 48 dp minimum touch targets, descriptive icon labels, logical TalkBack order, and non-color status text. Verify 200% text scaling and narrow screens. [U08]

On larger windows, show list and details side by side using the current navigation architecture; this task does not require migrating navigation libraries.

---
# 05 / Recommended model portfolio

This is a **seed selection for evaluation**, not a benchmark ranking. Do not label any candidate “best,” “fastest,” or “verified on your phone” until the required evidence exists. Keep the complete existing catalog even when a model is not selected for Recommended.

| Candidate | Intended evaluation role | Initial inclusion rule |
| --- | --- | --- |
| Gemma 3 1B Instruct | Small general text model | Retain current LiteRT artifacts; select the exact eligible export. |
| Qwen2.5 1.5B Instruct | Compact ungated text option | Retain the current CPU/GPU package. |
| DeepSeek-R1-Distill-Qwen 1.5B | Reasoning-style task evaluation | Compare useful-answer quality and latency, not reasoning length. |
| Gemma 3n E2B | Existing image-capable candidate | Require the app's supported image/backend path. |
| Gemma 4 E2B | Richer local-workflow candidate | Preserve existing restrictions; no assumed SM8750 NPU variant. |
| Gemma 3 270M | Small-footprint fallback | Present limited-task intent rather than broad assistant superiority. |
| Qwen3 0.6B / 1.7B | GenieX lightweight/balanced candidates | Resolve current artifacts and measure exact-device behavior. |
| Qwen3 4B / 4B Instruct 2507 | GenieX larger text candidates | Admit only with memory headroom and validated task quality. |
| SmolLM2 1.7B Instruct | Additional compact text candidate | Resolve runtime/export before inclusion. |

The first six families are present in the inspected portion of the repository catalog. The Qwen3 and SmolLM2 families are visible in Qualcomm's model index; that index alone does not establish an installable SM8750 export. [R08, U07]

### Selection algorithm

First exclude missing artifacts, wrong builds, incompatible devices, blocked workflows, failed integrity, and quarantine. Then group by family, select an eligible variant, and order by evidence tier, relevant task quality, completion reliability, and measured end-to-end latency. Use memory/storage burden as a tie-breaker. Retain the user's explicit choice and avoid automatically replacing installed profiles.

Within the same evidence tier, NPU preference may influence a tie when it satisfies the user's performance preference. Never rank an unverified NPU route above a reliable measured route solely because it says NPU. Keep deterministic editorial ordering when comparable measurements do not exist, and label it **Curated** rather than **Benchmarked**.

Do not combine scores from different exports, contexts, samplers, device firmware, or test suites. Display “Why recommended” with the actual evidence and remaining unknowns.

---
# 06 / Complete GenieX catalog coverage

### Use a maintained registry, not a frozen top-ten list

Upstream documents `geniex model list` for the current chipset and `geniex model list --all` for all chipsets. Its catalog changes independently of runtime releases. Use that complete inventory as a parity reference; do not mistake a paginated website's first page for the supported set. [U03]

The upstream page also exposes Gemma-4-E4B-it, Intern3.5-VL-2B/4B, Qwen2.5-VL-7B-Instruct, Qwen3-8B, and Qwen3-VL-4B/8B-Instruct. These are discovery seeds alongside section 05, not a complete manifest or a device-support guarantee. [U07]

### Required ingestion pipeline

**Discover -> resolve -> normalize -> validate -> review diff -> publish snapshot.** Build an updater that consumes the official GenieX model registry used by the pinned tooling, retains every page and chipset variant, and emits the app's normalized catalog. Record source version, retrieval time, counts, unresolved entries, and a digest of the input snapshot.

The remote registry endpoint and machine-readable schema were not established in this audit. Resolving them is the first implementation spike. Do not invent an endpoint or a `--json` flag. If the only supported export is CLI output, use a version-pinned parser with golden fixtures and fail publication on layout changes. Run native GenieX tooling on a supported Snapdragon host; ordinary CI may process captured data without executing that runtime. [U03-U04]

### Coverage contract

Every upstream artifact must resolve to one of: **represented**, **unsupported platform**, **unsupported app workflow**, **missing artifact metadata**, or **withdrawn**. Never silently drop it. Display total source coverage separately from device-available count. A partial refresh must not replace the previous complete snapshot.

The publication gate requires zero unexplained omissions and exact reconciliation of family IDs, variant IDs, chipsets, and runtime identifiers. Review additions, removals, license changes, URLs, and capability changes. Nightly checking may open a review diff; runtime or catalog behavior must not be silently auto-promoted.

### GGUF coverage beyond curated listings

Keep Hugging Face discovery and local import available for compatible GGUF artifacts. Do not claim to mirror every community file. Resolve exact architecture, tokenizer/template, precision, revision, asset hashes, and backend requirements. The recommended list is curated; import compatibility is capability-driven rather than restricted to those family names.

Non-chat models need an appropriate app workflow. Listing an upstream model does not turn an LLM chat adapter into an image generator, speech engine, or embedding service.

---
# 07 / GenieX runtime and build support

### Preserve the two-build boundary

The existing property `-PgeniexRuntime=true` selects the GenieX source set, arm64 build, and `dev.melo.gptmobile.improved.geniex` application ID. The normal package is `dev.melo.gptmobile.improved`. Preserve that separation until a separately reviewed native-library coexistence design exists. [R03]

Centralize `geniex = "0.8.0"` and a Maven alias in `gradle/libs.versions.toml`; generate the version used by BuildConfig, manifests, diagnostics, and tests from that single source. Replace hardcoded comparisons only with an explicit compatibility policy, not an unrestricted minimum-version check. The exact QAIRT distribution packaged with GenieX must retain its own audit identity.

| Route | Current app boundary | Target behavior |
| --- | --- | --- |
| LiteRT-LM CPU/GPU | Existing runtime path | Preserve; use common catalog decisions. |
| LiteRT-LM compiled NPU | Package/device-specific | Preserve exact variant and crash protections. |
| GenieX GGUF CPU/GPU | Preview text adapter | Bring into the unified catalog and qualification UI. |
| GenieX GGUF NPU | Explicitly rejected by manifest | Add a versioned, tested Hexagon execution path. |
| GenieX compiled QAIRT | Strict imported-bundle path | Add current upstream bundle normalization and discovery. |
| GenieX vision/audio/tools | Rejected or not integrated | Separate capability-specific integration gates. |

Current app boundaries are established by its manifest and adapter. Upstream describes GGUF CPU/GPU/NPU and compiled QAIRT NPU-only execution; those descriptions do not certify this app. [R09-R10, U04]

### Reproducible dependency evidence

Resolve the exact Maven AAR in CI, record its digest and transitive/native inventory, and compare it against the reviewed lock/audit. Release-asset hashes are useful references but must not be assumed identical to Maven artifacts. Test the release/R8 boundary, JNI callback signatures, ABI packaging, and loading without network access. [U02]

For a newer release, capture the changelog and binary/API delta, update the pin and audit together, then rerun both build matrices and device gates. Never use a dynamic `latest` dependency. Version currentness and hardware qualification must appear as separate diagnostic fields.

---
# 08 / Execution and workflow completion

### GGUF Hexagon NPU: required implementation, controlled activation

Replace the manifest's unconditional GGUF CPU/GPU-only rule with a capability policy keyed by runtime build, device/firmware, GGUF architecture, precision, and adapter revision. Keep compiled-bundle SoC rules separate from portable GGUF eligibility. Start the NPU qualification corpus with Q4_0; upstream identifies it as the preferred Hexagon format, while K-quants are typically GPU/CPU choices. [R09, U05]

The adapter must request NPU explicitly, confirm the effective execution path when the native interface permits it, and report **unknown** otherwise. Library presence or token production alone does not prove NPU execution. Add precise failure classification and an explicit alternative-route action; do not silently relabel a CPU/GPU run as NPU. Preserve resource admission and isolated cancellation.

### Compiled QAIRT bundles

Current upstream imports recognize `metadata.json` plus binary shards and generate SDK metadata. The app currently requires a validated `genie_config.json` package. Add a reviewed normalizer or SDK-backed importer that produces an app-owned, fully hashed manifest. Do not weaken relative-path, declared-asset, or executable-file restrictions to make imports pass. [U05, R09]

Pass the exact device SoC to Android AI Hub pulls. Keep compiled context and precision immutable, set QAIRT `nCtx` and `nGpuLayers` to zero, and reject explicit CPU/GPU requests rather than accepting upstream coercion. The documented Android validation targets include SM8750 and SM8850; other chipsets remain separately assessed. [U04]

### Vision, thinking, tools, and audio

Implement an image-capable adapter with the exact projector/bundle assets, preprocessing, image-to-answer tests, and worker IPC support. Text-only fallback must not silently discard an image. Upstream documents image VLMs and GGUF audio encoders, but its cited audio examples are CLI/server/Python; Android audio API parity must be established before activation. [U05]

For thinking, tool calls, or constrained JSON, prove the pinned Android interface and app protocol support. Model metadata alone is insufficient. Keep current rejections until each pipeline is implemented; explain them in details. Treat speculative decoding, MTP, and hybrid scheduling as separate capabilities, not implications of GenieX availability. [R10-R11]

Maintain conservative context admission until an audited tokenizer-counting path replaces it. Never remove the current limit merely to hide a failure.

---
# 09 / Data and compatibility contracts

### Extend existing identities

Reuse `CatalogEntry.familyId`, variant metadata, `ModelArtifactManifest`, and `ArtifactEvidence`. Do not create a parallel model database with a second definition of Installed or Verified. A family is the display group; an artifact is the exact runnable package; a profile points to an installed artifact and its settings. [R06, R09]

Add an unfiltered `CatalogSnapshot` API to `ModelCatalogRepository`. It should include source provenance, revision, completeness, refresh error, and all entries. Maintain the existing visible-entry method for callers during migration. Move applicability evaluation out of repository-level `forDevice` filtering into a shared resolver. [R07]

```kotlin
// Proposed presentation/domain contract; not an existing SDK API.
data class SupportDecision(
    val canDownload: Boolean,
    val canVerify: Boolean,
    val canUse: Boolean,
    val reasons: List<SupportReason>,
    val evidence: ArtifactEvidence,
    val effectiveBackend: String?,
    val nextAction: CatalogAction
)
```

### Resolver order and authority

Evaluate build availability, artifact completeness, runtime/API compatibility, platform/SoC, requested workflow, install integrity, memory/storage admission, and quarantine. Return all useful reasons while selecting one primary next action. Make the same resolver authoritative for cards, details, import, profile creation, chat, and benchmark entry.

A downloadable package need not be locally verified. A successful hash check establishes integrity, not execution compatibility. A smoke test is not full qualification. Show these distinctions rather than compressing everything into a green “Supported” badge.

Treat upstream metadata as claims. Local tests own local verification records; imported or restored badges never become authoritative. Bind evidence to artifact digest, runtime/native-library identity, firmware, backend, workflow, context, sampler, and suite revision, using the existing execution-evidence architecture. [R09-R11]

### Capabilities need their own evidence

Keep advertised, adapter-supported, and observed capabilities separate. Replace `hasMtp()` filename heuristics as an activation source with explicit metadata and runtime evidence. Legacy heuristics may aid search during migration but cannot enable a feature. [R06]

Use nullable/unknown values for unresolved sizes and capabilities. Zero must not mean “free storage required,” “zero failures,” or “supported everywhere.”

---
# 10 / Catalog schema and migration

### Version the catalog without breaking older apps

Publish a proposed `model_catalog_v2.json` alongside the current v1 endpoint. V2 groups families, exact artifact variants, source coverage, and capability claims. Generate the v1-compatible projection from the same reviewed source; do not manually maintain two inconsistent catalogs. The current parser rejects unsupported schema versions, so overwriting v1 with v2 would strand older readers. [R07]

Each v2 artifact needs a stable ID, family ID, runtime, format, source revision, asset inventory with SHA-256, context limits, required app/runtime versions, processor constraints, and capability claims. Multi-file details can be separate hashed manifests so the summary remains bounded. Do not remove existing parser limits merely because the registry grows.

### Preserve installed content as immutable evidence

Keep original artifact IDs, source revisions, digests, profiles, and installed manifests. Join current catalog metadata for display without replacing the installed identity. The existing repository merges installed and device entries by ID; the new design must explicitly resolve collisions instead of allowing new catalog metadata to redefine an installed package. [R07]

Retain manifest v1 read support for the migration window and historical restores. Add a versioned normalization layer for new forms. Unknown executable semantics must fail closed; harmless descriptive additions may remain ignorable. Writes use staging plus an atomic commit. No migration downloads model weights or upgrades runtime binaries automatically.

### Cache, refresh, and offline behavior

Render the bundled or last valid snapshot immediately. Refresh off the main thread, retain source timestamp, and swap only a complete validated snapshot. A timeout, unavailable source, duplicate ID, bad hash, unsupported schema, or malformed entry produces a visible refresh notice, not an empty successful catalog.

Use request revision tokens so a stale search/refresh cannot overwrite a newer result. Keep the current search text, tab, filter, and scroll position through navigation and rotation. Detail pages remain usable offline for installed and cached artifacts.

### Backup and restoration

Preserve preferences and manifest references through existing backup infrastructure. Model-weight inclusion remains an explicit storage choice, not a surprise multi-gigabyte default. On another phone, restore evidence as historical and recompute compatibility. Never serialize native handles, loaded-session state, runtime libraries, or live qualification authority. Retain existing benchmark evidence rules. [R11]

---
# 11 / Download, import, and storage lifecycle

### One owner for each installation

Retain the app's download coordinator and durable work tracking. Wrap GenieX model acquisition/import behind that ownership rather than introducing an independent download UI and cache with conflicting delete actions. The Android reference exposes `ModelManagerWrapper` import and path-resolution APIs; verify their exact v0.8.0 signatures before coding. [R05, U06]

Prefer one app-controlled staging directory. If the SDK copies an imported bundle into its cache, account for the peak duplicated storage and remove staging only after the final package is verified. Never assume an SDK cache entry is a finished app installation. If direct adoption is not supported, document and test the copy boundary explicitly.

### Atomic installation state machine

```text
Discovered -> Access checked -> Download queued -> Downloading
            -> Assets complete -> Integrity verified
            -> Normalized -> Atomically installed -> Verification available

Failure / cancellation -> keep a resumable journal or clean staging
Native verification failure -> retain files and record exact evidence
```

Check network policy, available storage, every asset length/hash, archive traversal, declared references, and package runtime before committing. A checksum failure must not reuse the corrupt bytes on Retry. Never import native executables or libraries as model assets; app releases own executable updates. The existing manifest already rejects code-bearing extensions and unsafe paths. [R09]

### User-visible memory and storage

Download size is not working memory. Show file size and estimated memory separately; include context/KV effects, projector assets, normalization copies, and partial downloads. Detect current headroom again before loading. A device's total RAM can guide discovery but cannot grant runtime admission. Keep the hardware governor as the final authority. [R11]

### Credentials and licenses

Reuse the app's Hugging Face authorization flow. A gated model requires source-side license acceptance and appropriate access; do not promise anonymous access or bypass gating. Keep tokens out of URLs, catalog snapshots, logs, crash reports, and portable evidence. [R05, U05]

Review the exact runtime distribution's notices and each model's terms before redistribution. Do not assume the repository's top-level license grants permission for every bundled model or native component.

### Deletion and reuse

Deleting one variant must not delete assets referenced by another installed variant. Use reference counting or ownership records for shared data. Confirm profile impact and unload active models through the existing runtime owner before deletion. Catalog refreshes never delete installed files.

---
# 12 / File-level implementation plan: catalog

Paths below are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/` unless another root is shown. “New” identifies a proposed file, not a file inspected in the repository.

| File or area | Implementation responsibility |
| --- | --- |
| presentation/ui/setting/LocalModelsScreen.kt | Replace marketplace/tab booleans with the three-destination UI; preserve legacy navigation arguments. |
| presentation/ui/setting/LocalModelsViewModel.kt | Own reactive snapshot, query, independent filters, refresh status, and shared support decisions. Preserve installed search identity. |
| data/catalog/ModelCatalog.kt | Extend/reuse existing family and variant identity; separate v1 compatibility DTOs from v2 domain data. |
| data/repository/ModelCatalogRepository.kt | Add unfiltered snapshot observation and explicit refresh result; preserve legacy callers. |
| data/repository/ModelCatalogRepositoryImpl.kt | Validate v2, retain fallback/provenance, and stop destroying full coverage before the UI sees it. |
| data/catalog/LocalCatalogCompatibilityResolver.kt — New | Pure, testable capability/availability decision service. Runtime admission still belongs to runtime code. |
| data/catalog/LocalCatalogRecommendationPolicy.kt — New | Deterministic family grouping and evidence-aware task ordering. |
| presentation/ui/localmodel/LocalModelCatalogCard.kt — New | Compact family card and stable accessibility semantics. |
| presentation/ui/localmodel/LocalModelDetailsScreen.kt — New | Variant choice, requirements, provenance, evidence, and one authoritative action. |
| presentation/ui/localmodel/LocalCatalogFilterSheet.kt — New | Independent filter dimensions and reset behavior. |

### Repository-level assets and checks

Add a canonical normalized catalog source and proposed v2 output at the repository root; synchronize the bundled asset with the published snapshot during the build. Preserve the existing hosted `model_catalog.json` v1 contract. Add proposed `scripts/sync_geniex_catalog.py` and schema/coverage tests only after the upstream input contract has been established.

The ViewModel currently catches initial catalog failures into an empty list. Replace that outcome with typed data-and-error state. Do not make opening the screen wait for a remote refresh. The new composables should render state only; no native model inspection or download side effects during recomposition. [R05-R07]

---
# 13 / File-level implementation plan: runtime

| Exact path or existing area | Implementation responsibility |
| --- | --- |
| gradle/libs.versions.toml | Add the single GenieX version and library alias; retain LiteRT/QNN pins. |
| app/build.gradle.kts | Consume alias, generate version identity, preserve exclusive native distributions, and build both modes. |
| app/src/geniex/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/GenieXRuntimeAdapter.kt | Add audited backend selection, capability reporting, and exact-package error handling without weakening callback delivery. |
| app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localmodel/ModelArtifactManifest.kt | Introduce versioned GGUF NPU capability validation and reviewed compiled-bundle normalization. Preserve integrity/path checks. |
| app/src/geniex/kotlin/.../GenieXModelAcquisitionAdapter.kt — New | Bridge SDK import/path APIs into the app's durable installation owner. |
| app/src/geniex/kotlin/.../GenieXVlmRuntimeAdapter.kt — New | Implement qualified image input and projector/bundle handling. Use the existing worker boundary. |
| app/src/litertOnly/kotlin/ source set | Keep standard-build stubs and explicit GenieX-unavailable states; no fake fallback. |
| docs/audits/local-runtime-native-libraries.json | Record reviewed native identities for each build distribution; generate diagnostics from audited inputs. |
| Existing LocalRuntime / process / planner / benchmark areas | Extend contracts additively for capabilities and evidence; preserve admission, cancellation, and no-replay behavior. |
| scripts/check_local_runtime_apk.py | Extend existing APK validation where needed to check both native inventories and reject contamination. |

For the two new GenieX files, `...` means the same `dev/chungjungsoo/gptmobile/data/localruntime/` package as the existing adapter, not a new package hierarchy. The audit file and APK checker are already referenced by current build/documentation; inspect their full implementations before patching. [R03, R12]

### API verification checklist before implementation

Confirm SDK initialization completion semantics, lifecycle/thread requirements, cancellation, native-handle ownership, error codes, and the precise behavior of `runtime_id`, `compute_unit`, `getPaths`, and image creation. The current adapter uses low-level callbacks deliberately; replacing it wholesale with a convenience Flow can reintroduce dropped-output behavior. [R10, U06]

Any missing native API must become a tracked blocker or a narrowly scoped binding change with tests. A UI toggle is not an implementation of an absent engine capability.

---
# 14 / Validation matrix

| Test group | Required pass condition |
| --- | --- |
| Catalog completeness | All upstream families/variants accounted for; no unexplained omission, partial publication, or hidden page limit. |
| Build availability | Standard APK explains GenieX absence; GenieX APK resolves its actual runtime; no conflicting QAIRT libraries. |
| Device/artifact matching | Wrong SoC compiled bundles rejected; portable GGUF evaluated by its own policy; unknown support stays unknown. |
| Capability isolation | Images, tools, thinking, audio, and MTP cannot be enabled by a family label or filename alone. |
| Install integrity | Missing shard, wrong hash, interrupted archive, malicious path, and executable asset fail before installation. |
| State recovery | Network loss, cancellation, process death, low storage, and duplicate tasks leave recoverable consistent state. |
| Evidence integrity | Imported badges, stale firmware evidence, changed runtime, and changed context cannot certify a new tuple. |
| Migration | Existing profiles, installed variants, v1 catalog readers, imports, and backups retain correct identities. |
| UI/accessibility | Small screen, 200% font, TalkBack, theme contrast, long names, offline state, and list/detail navigation pass. |
| Runtime regression | Cancellation, worker death, bounded Unicode streaming, resource admission, and no tool replay remain correct. |

### Device execution corpus

On the ROG/SM8750 target, test at least a compact text GGUF CPU/GPU route, Q4_0 NPU route, exact-SoC compiled text bundle, and image model after its adapter exists. Include negative K-quant/NPU cases, nonmatching compiled bundles, missing projectors, warm reload, long conversation, and memory/thermal pressure. Unsupported devices need negative and graceful-degradation tests, not blanket exclusion of LiteRT.

A short smoke run only creates smoke evidence. Use the repository's existing G1-G8 release qualification for promotion, including effective-backend proof, retained raw trials, reliability, cancellation/death testing, and workflow correctness. Its documented reliability gate includes 500 controlled turns, at least 99% expected completion, and no unhandled native crashes or ANRs. [R11]

### Performance budgets to measure, not claim

Proposed UI targets are cached-list rendering within 500 ms on the reference device and debounced local search results within 150 ms after debounce. Measure cold and warm cases with a realistically large catalog. No network or native initialization may block first render. Record regression data and revise targets openly when evidence requires it.

Model comparisons must report quality, completion rate, first visible answer, sustained decode, peak-memory measurement method, and thermal conditions. Unknown metrics remain blank, not zero.

---
# 15 / Delivery sequence and definition of done

### Implementation sequence

**PR 1 — Baseline and dependency audit.** Capture the inspected source baseline, resolved GenieX artifact/API inventory, native audits, and upstream catalog input contract. Rebase on the then-current main before implementation; do not assume this document's SHA is still the latest.

**PR 2 — Catalog schema and resolver.** Add v2, v1 projection, unfiltered snapshots, shared support decisions, migration fixtures, and source-coverage checks.

**PR 3 — UI replacement.** Ship the three-destination catalog, family cards, details, filters, downloads tray, and restored navigation/settings entry points against the shared state.

**PR 4 — Complete GenieX discovery and installation.** Normalize every upstream catalog entry and integrate compiled/GGUF acquisition without a second install owner.

**PR 5 — Native support completion.** Add qualified GGUF NPU and image workflows. Complete the pinned Android capability matrix; keep unsupported audio/tools/thinking routes visibly unavailable until their tracked adapter work closes.

**PR 6 — Device qualification and release.** Run both APK matrices, preserve raw evidence, verify backup/upgrade behavior, update documentation, and publish only the capabilities that passed. Do not describe an interim UI-only milestone as “full GenieX support.”

### Build validation entry points

The following commands are based on current repository guidance. Run standard and GenieX property modes as separate clean CI jobs so generated sources/native artifacts cannot leak between configurations. Device tests are additional, not replaced by compilation. [R11-R12]

```bash
export OBX_DISABLE_ANALYTICS=true
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug
./gradlew :app:compileDebugAndroidTestKotlin
python3 -m unittest discover -s scripts/tests
python3 scripts/check_local_runtime_apk.py <built-apk-path>
# Separate job: repeat with -PgeniexRuntime=true
```

### Completion and rollback

Done requires complete source reconciliation, preserved installed/profile data, current pinned dependency evidence, usable qualified target-device routes, honest per-workflow states, passing regression/accessibility tests, and release notes naming remaining unsupported capabilities. No omitted test may be counted as a pass.

Gate the UI and new execution routes independently. Rollback restores the old presentation/routing without deleting models or evidence. Disable a failing exact execution tuple, not an entire unrelated catalog. Preserve parser, integrity, and quarantine protections in every mode.

---
# Appendix A / Repository source register

All repository references below are pinned to **7553e4ff14316f0d28639d5cc0ed24cb59511066**, inspected on **12 October 2026**. Descriptions identify the portions read; a named related file is not a claim of full-file audit.

[R01] [Main snapshot and commit](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/7553e4ff14316f0d28639d5cc0ed24cb59511066). Baseline identity; the source snapshot may differ from the user's installed APK.

[R02] [gradle/libs.versions.toml](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/gradle/libs.versions.toml). LiteRT-LM and QNN pins; dependency aliases.

[R03] [app/build.gradle.kts](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/build.gradle.kts). Build/source-set selection, application IDs, version, native dependency declaration, and audit asset preparation.

[R04] [LocalModelsScreen.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/LocalModelsScreen.kt). Lines 1-240: navigation, existing controls, tabs, and recommendation prefilters.

[R05] [LocalModelsViewModel.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/LocalModelsViewModel.kt). Lines 1-260: initial load, list state, search, device filtering, downloader integration, and installed Hub identity.

[R06] [ModelCatalog.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/catalog/ModelCatalog.kt). Family/variant fields, accelerator/capability metadata, and MTP helper.

[R07] [ModelCatalogRepositoryImpl.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ModelCatalogRepositoryImpl.kt) and [interface](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ModelCatalogRepository.kt). Remote/cache/bundled selection, schema gating, device transformation, and installed-entry merge.

[R08] [model_catalog.json](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/model_catalog.json). Inspected first 320 source lines, with the final tool excerpt truncated. Seed-family evidence only; this audit does not claim a full static inventory.

[R09] [ModelArtifactManifest.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localmodel/ModelArtifactManifest.kt). Runtime/evidence types, backend and SoC rules, data-only validation, hashes, and atomic manifest storage.

[R10] [GenieXRuntimeAdapter.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/geniex/kotlin/dev/chungjungsoo/gptmobile/data/localruntime/GenieXRuntimeAdapter.kt). Lines 1-180: runtime creation, context admission, unsupported workflows, callback streaming, and metrics.

[R11] [GPU/NPU execution plans](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/docs/gpu-npu-execution-plans.md). Existing architecture and qualification contract; historical implementation statements were not independently re-executed.

[R12] [README.md](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/README.md). Lines 1-170: local-model scope, source/release caveat, and build validation entry points.

---
# Appendix B / Upstream evidence and open checks

All upstream pages were checked on **12 October 2026**. Live documentation may advance independently of the versioned release and must be reconciled with the binary used for implementation.

[U01] [GenieX v0.8.0 release](https://github.com/qualcomm/GenieX/releases/tag/v0.8.0). Latest release resolved during the audit; published 2 October 2026. Release identity, not an app qualification certificate.

[U02] [v0.8.0 release assets](https://github.com/qualcomm/GenieX/releases/expanded_assets/v0.8.0). Full and CPU-only Android AAR distributions are separate artifacts. The full release AAR's published SHA-256 is recorded below solely as an upstream reference, not a verified Maven checksum.

```text
330d4d20ca27b449ba3e1b588fa2e964124e1682c01502952b483875d902dcc9
```

[U03] [Qualcomm GenieX README](https://github.com/qualcomm/GenieX). Two-runtime architecture, independent catalog refresh, and documented `geniex model list --all` inventory command. The README's older Maven example is not the current release-selection authority.

[U04] [Platforms and runtimes](https://geniex.aihub.qualcomm.com/en/get-started/platforms). Validated platform scope, Android chipset selection, compute routes, and compiled-runtime constraints.

[U05] [Models guide](https://geniex.aihub.qualcomm.com/en/models/supported). Model formats, quantization guidance, package import forms, modalities, projector assets, and source access requirements. Cross-interface examples must not be assumed to prove Android parity.

[U06] [Android API reference](https://geniex.aihub.qualcomm.com/en/run/android/api-reference). Model acquisition/path interfaces and runtime configuration concepts. Exact class signatures and native behavior still require verification against the resolved v0.8.0 artifact.

[U07] [Qualcomm model index](https://aihub.qualcomm.com/models?runtime=geniex). Current visible candidate-family discovery. The paginated page is not the full GenieX model/artifact inventory and does not prove a device-specific export exists.

[U08] [Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps). Touch targets, labels, and assistive-technology behavior supporting the proposed UI tests.

### Open checks that must close during implementation

The audit did not resolve or execute the Maven dependency, inspect the APK's packaged native libraries, run the full upstream catalog exporter, or test an SM8750 device. The registry's machine-readable interface, exact v0.8.0 Android modality API parity, effective-backend reporting, and sustained model rankings therefore remain implementation verification tasks.

These unknowns do not prevent the UI/data redesign. They do prevent claiming complete runtime support or presenting an unevaluated model as verified. This specification makes those checks release gates rather than hiding them behind optimistic catalog labels.
