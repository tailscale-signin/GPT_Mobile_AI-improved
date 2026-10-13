# Local AI Experience Redesign
## Product design and repository implementation plan

**Project:** GPT Mobile AI\
**Repository:** `tailscale-signin/GPT_Mobile_AI-improved`\
**Review date:** October 12, 2026\
**Inspected baseline:** `main` at `cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1`\
**Status:** Design specification. No application changes, builds, merges, or device qualification were performed for this document.

> **The experience:** Three clear tabs — **Models**, **Assist**, and **Settings**. Models is the default landing page. Its content order is always **Local AI Model Marketplace → Import AI Model → Local AI Profiles**. The marketplace opens separately and presents recommendations first, then the other models compatible with the current phone.

### Reading guide

Sections 1–4 define the experience and visual system. Sections 5–8 specify compatibility, import, Assist, and settings behavior. Sections 9–12 cover architecture, migration, delivery, and verification. The final appendix contains the inspected repository sources and design references.

### Specification language

**Observed** identifies behavior found in the inspected source. **Required** identifies the requested target behavior. **Proposed** identifies an implementation or default to validate during delivery. A proposed type or filename is not a claim that it already exists. Performance numbers in this document are acceptance targets, not measured results.

# 1. Product decisions and scope

## 1.1 The three-tab contract

| Tab | User question | Contents |
|---|---|---|
| **Models** — default | What can I use on this phone? | Marketplace hero bubble, import bubble, local profile bubbles, and a compact transfer status when necessary. |
| **Assist** | How should Local AI help my main AI? | Assist enablement, worker selection, task permissions, routing, sharing boundaries, budgets, recovery, and activity. |
| **Settings** | How should Local AI run? | Runtime and processor preferences, performance, memory, downloads, storage, accounts, diagnostics, and advanced compatibility options. |

Use **Models** as the visible name of the requested “default page”; avoid a literal “Default” tab because it does not explain the content. The screen title is **Local AI**. Opening Local AI normally always opens Models. Returning from a child screen restores the previous tab and scroll position. An explicit Assist or Settings deep link may open its requested tab.

All Assist-specific switches belong in Assist. All other persistent Local AI switches belong in Settings. Models and Marketplace contain actions, state, and temporary search filters rather than another collection of global toggles. Per-profile overrides open the existing profile editor or a scoped settings destination; they must not create a second preference store.

## 1.2 The intended look

The direction is a premium, restrained Material-style interface: large soft surfaces, deliberate spacing, themed icon medallions, strong typography, and one visually dominant action per area. “Fancy” should come from finish and hierarchy, not constant animation, repeated gradients, or technical density.

The Marketplace bubble receives the strongest emphasis. Import is the quieter second step. Profile bubbles are calm, consistent, and fast to scan. Detailed engine information, package manifests, benchmark tables, and version strings move out of the default page.

## 1.3 Scope boundaries

This redesign must preserve installed models, profile identities, chat entry points, download recovery, authentication, runtime evidence, local benchmarks, and all existing Local AI controls. It must not reset sampling parameters, silently change the selected processor, automatically upload local content, or make unsupported hardware appear supported.

The implementation includes complete user-facing import and recovery flows for the app's LiteRT-LM and GenieX execution paths. It does **not** equate recognizing a filename with being able to execute it. A generic `.tflite` network, a legacy `.task` bundle, a `.litertlm` chat package, a GGUF file, and a chipset-compiled QAIRT bundle are different artifacts. The precise support policy is in Section 6. [S01, S02, S03]

No unrelated global redesign, model release upgrade, or app version bump is required simply to land this experience. Preserve the repository's dependency pins until an independently tested change justifies updating them. A unified APK with GenieX would require a separately validated runtime-packaging change; a UI switch cannot install that capability.

# 2. Repository findings and integration implications

## 2.1 Baseline

The inspected `main` commit is the merge of PR #664, `version-v0.9.50.0`, containing the recent statistics, local-model, delegation, and appearance work. This plan therefore builds on that merged implementation, rather than replacing it with an earlier design. Re-check the latest base before development and reconcile later changes. [R01]

The source was inspected through GitHub. This was a source-level review, not an APK run or a complete audit of every local-runtime implementation.

## 2.2 Observed gaps

| Observed implementation | Why it conflicts with this request | Required change |
|---|---|---|
| `LocalModelsScreen` starts with search and Recommended / All models / Installed catalog tabs. | The catalog is effectively the home page; the requested profile-first management hub is absent from that layout. | Extract the catalog into a Marketplace child destination; replace root tabs with Models / Assist / Settings. [R02] |
| Runtime configuration opens in a full-width dialog. Assist appears in that dialog and inside `LocalRuntimeSettingsCard`. | Assist is buried and duplicated. | Give Assist one canonical tab and remove duplicate editable copies. [R02, R04] |
| Runtime information, NPU prerequisites, comparison controls, trial rows, tuning, and thermal information share one large card. | Everyday use is mixed with advanced diagnostics. | Split settings into coherent groups and use detail destinations for comparison and diagnostics. [R04] |
| Recommendations use a curated family order, declared minimum RAM, size, and a six-family limit. | Ranking does not itself represent live memory admission or measured suitability for a selected task. | Preserve the policy entry point but replace its internals with capability-aware ranking and explicit evidence. [R06] |
| The general compatibility resolver checks build, SoC, metadata, app version, and accelerator declarations; RAM filtering is separate in recommendations. | “Other compatible models” needs a unified decision that also covers memory and installed runtime readiness. | Introduce one shared compatibility report consumed by the catalog, import, profile creation, and load preflight. [R06] |
| Catalog runtime classification uses variant labels, text, and URL extension checks. | Names and URLs are fragile identities. | Prefer typed runtime and artifact metadata; retain a migration adapter for legacy records. [R06, R08] |
| The ordinary single-file locator accepts `.litertlm`; the bundle installer supports manifest-based GenieX QAIRT/GGUF bundles. | Import needs a coherent workflow, not one unexplained file picker. | Introduce a format-aware import coordinator over existing validators and installers. [R09, R10] |
| The current Assist worker selects exact retrieved records, with two attempts, an eight-second timeout, up to twelve candidates, and up to five selected records. | It is not yet an all-purpose local research, reasoning, and tool assistant. | Retain that behavior as a migration-compatible mode; implement broader task handlers before exposing their switches as functional. [R07] |

## 2.3 Existing work to retain

Keep `ModelArtifactManifest`, `ArtifactValidation`, `PackageDigest`, the bundle installer's extraction checks, original research results retained by Assist, existing `SettingRepository` mutation methods, download authentication coordination, the model repository interface, and on-device runtime evidence. These are valuable foundations, not disposable UI code. Imported validation claims are already cleared by the bundle installer; retain that boundary. [R05, R07–R11]

`LocalModelsViewModel.importCustomModel` obtains the document name and opens the content stream from its coroutine. Refactor provider queries, copying, hashing, and package parsing into explicit I/O work, with progress and cancellation. Do not let a slow cloud-backed document provider stall the interface. This is an implementation risk identified from the shown method, not a measured ANR. [R12]

# 3. Information architecture and screen specifications

## 3.1 Navigation structure

```text
Local AI
  Models [default]
    Local AI Model Marketplace -> Marketplace -> Model details
    Import AI Model            -> Import flow
    Local AI Profiles          -> Profile details / existing editor
    Transfer status            -> Downloads and storage
  Assist
    Setup and behavior
    Task permissions
    Advanced budgets / activity
  Settings
    Runtime and processor
    Performance and memory
    Downloads and storage
    Accounts and privacy
    Diagnostics and advanced
```

Marketplace is a child screen, not a fourth root tab. Avoid another prominent Recommended / All / Installed tab row inside it. Use ordered content sections and a filter sheet. Download management remains reachable through Settings and a contextual transfer action.

Preserve existing navigation parameters through a compatibility adapter. Map `startInMarketplace` to Marketplace, `startInDelegation` to Assist, and normal entry to Models. Replace the two booleans with a typed entry destination in new call sites. If a legacy caller supplies both flags, resolve deterministically to Marketplace and record a development warning; do not create an unstable back stack. [R02]

## 3.2 Models: exact content order

```text
<  Local AI                                   (?)

       Models          Assist          Settings
       ------

+----------------------------------------------+
|  [Store icon]                                |
|  Local AI Model Marketplace                   |
|  Find models selected for your phone.         |
|                         Explore models  >     |
+----------------------------------------------+

+----------------------------------------------+
|  [Import icon]  Import AI Model            >  |
|  Add a LiteRT-LM or GenieX model.         (?) |
+----------------------------------------------+

Local AI Profiles                       + Add

+----------------------------------------------+
|  [Profile icon]  Everyday Assistant      ... |
|  Model family / chosen variant               |
|  Ready on this phone                  [Use] |
+----------------------------------------------+

+----------------------------------------------+
|  [Profile icon]  Research Helper         ... |
|  Model family / chosen variant               |
|  Assigned to Assist                [Details] |
+----------------------------------------------+

Downloads: 1 active                     Manage
```

Names in this wireframe are illustrative, not recommended model claims or detected user profiles. Card heights grow with text; the diagram does not imply fixed-height text boxes.

### Marketplace hero bubble

Use a full-width rounded card with a themed Storefront icon, a large title, one short explanatory sentence, and a clearly labelled “Explore models” action. The card surface is also clickable. Expose one accessible navigation action rather than duplicate TalkBack actions for the same destination.

A subtle theme-derived gradient or tonal accent is acceptable on this hero only. Avoid raster artwork or a network-loaded background. Optional secondary text can say “Selected for this phone”; do not claim a model count until the catalog and filter have finished loading. A cached count must be labelled as cached where that distinction matters.

### Import bubble

This is a full-width secondary surface, not an icon-only toolbar action. The title is **Import AI Model**. Supporting text is “LiteRT-LM, GGUF, or a GenieX package” only when the corresponding handlers are present in that build; otherwise use the neutral “Add a model from your files.” The `(?)` button explains accepted formats and the runtime requirement without hiding a missing-runtime warning.

Tap opens an import source sheet. The usual action is “Choose file”; “Choose model folder” is available for supported multi-file bundles. Keep user-selected document access scoped to the selected file or folder.

### Local AI profile bubbles

Each bubble represents a **profile**, not a downloaded file. Multiple profiles may legitimately reference the same artifact. Display the profile name, model family/variant, one readiness line, and at most one role badge. Use the existing profile UID as the list key.

The main action is **Use**, **Verify**, **Repair**, or **View progress**, depending on actual state. “Use” selects the profile through the existing chat flow; it must never send a message automatically. Card-body tap opens profile details. Overflow contains rename, duplicate, pin/unpin, assign to Assist, edit settings, and delete profile. Avoid using a long press as the only way to reach these actions.

The profile card must distinguish **selected in chat**, **assigned to Assist**, and **loaded in memory**. These are independent facts. A pinned profile is not automatically active; an installed model is not automatically loaded.

### Additional states

For no profiles, keep Marketplace and Import at the top, followed by “Your local profiles will appear here” and “Create from an installed model.” Do not replace the page with a full-screen empty illustration.

For a missing artifact, keep the profile visible with “Model file missing” and a Repair action. For a build without its required runtime, keep the profile visible and explain the missing runtime. For an active transfer, show progress inside its relevant item and one compact manager row; do not insert a large banner above Marketplace.

Search and sort within profiles appear when the list is large enough to benefit, proposed at six profiles. Pin order is stable. Background progress must not constantly reorder the list.

## 3.3 Profile detail destination

The profile detail view presents identity and readiness first, then **Use in chat**, **Assign to Assist**, and **Edit profile**. Its expandable technical section contains artifact identity, runtime, selected processor, supported processor alternatives, context and sampling settings, validation state, and links to benchmark evidence.

Editing profile-specific settings reuses the existing profile data model and persistence path. A visible “Inherited from Local AI Settings” label differentiates an inherited default from an explicit profile override. Deleting a profile does not delete shared weights. Deleting weights shows every referencing profile and offers to keep those profiles in a repairable state.

## 3.4 Marketplace layout

The Marketplace header has Back, title, and one `(?)` action. Below it, show a compact “For this phone” summary with detected processor and RAM information, followed by search and a Filters button. Detailed device capabilities belong in a sheet.

The unfiltered order is always **Recommended for this phone**, then **Other compatible models**. Recommendations appear as a small vertical collection, proposed maximum four family cards; never hide additional compatible models behind a recommendation cap. “Other compatible models” contains the remaining family cards without duplicating recommended families.

Each family card shows the best eligible variant, a concise use-case description, download size, and a truthful compatibility/evidence label. A details action reveals all variants with runtime, quantization, memory estimate, context support, and modality capabilities. Never pick `variants.first()` as the representative merely because it arrived first.

Search applies to family, variant, runtime, and supported capabilities. A search result view may omit an empty recommendation section and show “Compatible results,” but the normal storefront order returns when search clears. Temporary filters include runtime, processor, task capability, installed state, and size. They are query controls, not persistent runtime toggles.

Keep Hugging Face discovery available as an explicitly labelled source action in the filter/search flow. Discovery results are candidates until normalized and checked; search results do not bypass compatibility. Gated access is clearly marked before download. Settings remains the canonical home for the account and token controls.

An unavailable catalog must not block installed profiles. Use bundled/cache fallback, an honest freshness label in the Marketplace details sheet, and a visible retry action for refresh failures. Never present an incomplete upstream snapshot as a complete inventory.

# 4. Visual design, interaction, and accessibility

## 4.1 Design principles applied

**Hierarchy and progressive disclosure.** The home page answers three common tasks before exposing advanced controls. Put explanations one level deeper, with specific labels and predictable back behavior. Do not bury essential error or consent information. This applies NN/g's distinction between showing common actions first and revealing specialized details on demand. [S04]

**Proximity and consistency.** Group settings by the user's decision, not by the underlying library name. Keep the same card anatomy and action location across profile and marketplace items. Reuse the app's existing theme icon and help conventions.

**Recognition over recall.** Show the selected profile, processor choice, and effective state in the relevant context. A user should not need to remember a chipset code to pick a valid artifact. Raw codes remain available through help.

**Visible system status.** Imported, downloaded, verified, loaded, and selected are separate states. Labels must describe observed state, not optimism. Every long operation has a meaningful stage and a cancel or recovery action.

## 4.2 Proposed design tokens

| Element | Proposed treatment |
|---|---|
| Page spacing | 16 dp horizontal on compact phones; 24 dp between major sections; 12 dp between cards. |
| Card corners | Marketplace hero 28 dp; import and profile cards 24 dp; compact grouped controls 20 dp. Map to existing design-system tokens where possible. |
| Card padding | Hero 20–24 dp; profile 16–20 dp; settings rows at least 16 dp horizontal. |
| Typography | Existing Material type scale. Hero title approximately 24 sp, section titles 18–20 sp, body 16 sp, secondary text 14 sp. Never reduce text just to force one line. |
| Icons | 24 dp for main actions; a 40–48 dp tonal medallion for card identity; help glyph 18–20 dp inside a 48 dp target. |
| Color | Theme semantic surfaces and primary/secondary accents. No fixed purple/blue requirement in application code. Distinguish error, warning, and success with words and icons as well as color. |
| Elevation | Subtle tonal elevation and restrained shadow. No blurred glass layer over scrolling text. |
| Borders | Fine theme-derived outlines for secondary surfaces; selected state may use a stronger outline, not a permanent glow. |

These dimensions are design proposals, not measurements mandated by Material. The interactive minimum of 48 dp follows Android accessibility guidance. Text contrast should satisfy the relevant 4.5:1 normal-text and 3:1 large-text WCAG thresholds; use Android accessibility review alongside those contrast checks. [S05, S06]

## 4.3 Motion and smoothness

Use 140–220 ms for press/selection feedback and 180–260 ms for a tab or detail transition, tuned with device testing. Motion explains continuity; it does not delay access. Do not sequentially animate every profile bubble or add artificial loading pauses.

Respect the operating system's animation scale and the app's existing reduced-motion preferences. Do not add a separate competing Local AI motion switch. Retain per-tab scroll state. Update only the transfer row whose progress changed. Use stable list keys and compute ranking outside composition. These choices align with Compose's guidance on avoiding expensive recomposition and maintaining stable list identity. [S07]

On narrow screens, make the three root tabs equal-width with visible text. On large font settings, allow text-first tabs and remove decorative tab icons before truncating labels. On expanded windows, use a wider content container or a list/detail arrangement without changing the three-tab mental model. Native Compose tabs provide the appropriate selectable semantics; the visual skin should not replace those semantics with arbitrary clickable boxes. [S08]

## 4.4 Help behavior

Reuse `SettingsHelpIcon` where possible and extend its presentation behind the same component boundary. Tap `(?)` opens a short, dismissible help sheet or existing accessible help dialog. Long press/hover can show a tooltip, but tap must work on phones. Compose offers simple and rich tooltip patterns; choose the one appropriate to the amount of content rather than overloading a tiny tooltip. [R04, S09]

A help surface has a title, a plain-language explanation, the practical effect, and optional technical details. Example: **Memory estimate** — “This includes more than the downloaded file. Longer conversations and image input may need additional memory. The app checks again before loading.”

Never hide the following solely behind `(?)`: required sign-in, model download size, low-storage action, unsupported build, missing assets, verification failure, cloud-sharing consent, or a destructive deletion consequence. Help explains these states; it does not replace their visible labels.

TalkBack announces “About speculative decoding” rather than “Question mark.” Keep help and switch hit targets separate so help cannot accidentally change a preference. Sheets restore focus to the launching control, support Back, and remain scrollable at 200% font scale. Decorative icons are not separately announced.

# 5. Compatibility and recommendation system

## 5.1 One decision model, several independent dimensions

Create a shared, typed `LocalCompatibilityReport` rather than treating an empty list of error strings as proof that a model works. The catalog, import review, profile creation, and load admission should consume the same report, with progressively stronger evidence as files become available. Keep the existing native admission layer authoritative; a catalog result cannot override it. [R06, R08]

| Dimension | Required inputs | Presentation and behavior |
|---|---|---|
| Artifact identity | Runtime, format, architecture, precision, artifact digest, revision, tokenizer/projector dependencies | Unknown metadata remains unknown. Do not infer the runtime solely from the display name. |
| Build and runtime | Packaged runtime capability, runtime version, process ABI, minimum app version, initialization status | “Requires GenieX build” or “Runtime unavailable” is different from incompatible hardware. |
| Processor | Supported backends, exact compiled target, canonical SoC, required driver/runtime features | A Snapdragon family name alone is insufficient for a chipset-compiled artifact. |
| Memory fit | Declared minimum, working-set estimate, context, available memory, reserve, existing allocations | Separate “fits this device in principle” from “enough free memory now.” |
| Storage and access | Download size, expanded size, staging overhead, available storage, license or authentication requirement | Explain the immediate action before transfer begins. Gated access is not a hardware failure. |
| Evidence | Manifest checks, local smoke test, workload test, runtime/device fingerprint, failures | “Installed,” “preflight passed,” and “verified on this phone” must remain separate labels. |

A recommended implementation separates `structuralSupport`, `runtimeReadiness`, `memoryFit`, `accessState`, and `evidenceLevel`. Derive actions from those fields, not by parsing localized text. Each reason has a stable code, a user-facing message, technical detail, and a recovery action.

Do not reinterpret the existing catalog's `canUse = false` as an accidental defect: the resolver deliberately does not establish runtime qualification. Add an evidence-aware action composer rather than changing all catalog entries to usable. [R06]

## 5.2 Memory calculation and live readiness

Use total physical RAM for a coarse device-tier filter and an estimate of **additional peak memory** for admission. File size is not the working set. Existing loaded-model pages must not be counted twice. A model's context length, KV cache, scratch space, optional image assets, and load-time allocations affect its required headroom; missing estimates should produce an “Estimate unavailable” state rather than zero.

A proposed admission calculation is:

```text
estimatedAdditionalPeak =
  additionalWeights + additionalKV + additionalScratch
  + loadTransientPeak + uncertaintyMargin

usableHeadroom = max(0, observedAvailableMemory - reservedHeadroom)

admitOnlyWhen = structuralSupport && runtimeReady
               && estimatedAdditionalPeak <= usableHeadroom
               && hardwareGovernorAllowsNewWork
```

Use measurements for the same artifact/backend/context when available; otherwise use declared estimates and mark their confidence. The current persisted `localMemoryReserveMb` is 768 MB. Preserve that value for existing users, expose its purpose, and validate it through the hardware governor rather than interpreting it as a universal guarantee. [R13]

Sample memory before a load, after a meaningful foreground transition, and during relevant active work. Do not poll aggressively merely to animate a marketplace chip. Use hysteresis/debouncing so transient pressure does not reorder the catalog. A structurally compatible installed model stays visible when memory is temporarily low; its action becomes “Free memory” or “Try when available.” A known mismatch never becomes compatible because free RAM increased.

## 5.3 Ranking without false precision

First apply non-negotiable eligibility checks. Then rank eligible **variants**, choose a representative variant per family, and finally rank families. Do not display the first arbitrary variant as the recommendation when another variant is the actual suitable one.

Proposed ranking order is lexicographic, avoiding an unexplained percentage score:

1. Compatibility and complete dependency metadata for this app build and processor.
2. Relevant, recent successful evidence on this phone for the requested workload; stale or failing evidence lowers confidence.
3. Memory fit with useful headroom at the intended context length.
4. Task suitability: general chat, text processing, research assistance, or supported image input.
5. Measured reliability and latency where comparable measurements exist, then download/storage cost and curated quality guidance.

Unknown speed must not be presented as slow, and a smaller download must not automatically be described as better. Show plain-language recommendation reasons such as “Already tested on this phone” or “Lower memory requirement.” “Best for Assist” requires an Assist qualification, not simply tool support in an upstream model card.

Keep recommendations to a manageable four family cards by default, with all other compatible families below. Installed models remain eligible for recommendation; do not make eligibility depend on whether a new download is needed. A recommendation can offer Use, Create profile, Verify, or Download as appropriate.

“Compatible” means known structural suitability, not guaranteed successful generation. Use **Compatible — not yet tested** until local evidence supports a stronger claim. Show models with unresolved compatibility in a separately labelled opt-in discovery view, not mixed into “Other compatible models.” Preserve access to the existing broader catalog/Hugging Face discovery without promoting its results to verified models.

## 5.4 Evidence lifetime and failure handling

Qualification is keyed by artifact digest, backend, runtime version, device/firmware fingerprint, suite version, and relevant workload configuration. A runtime or firmware update invalidates the corresponding qualification, not the downloaded model or profile identity. Display “Recheck after update” and retain previous evidence as historical.

Keep requested and reported execution distinct. When a runtime does not report its effective processor or MTP activation, say “Requested NPU; effective processor not reported” or “MTP activation not reported.” Do not substitute a requested preference for an observed fact. The current runtime card already makes this distinction for GenieX and MTP; retain it in the new details UI. [R04]

# 6. Complete import experience

## 6.1 Supported-format contract

“Full support” requires selection, inspection, dependency handling, installation, profile registration, appropriate runtime routing, execution, cancellation, and recovery. A broadened file-picker extension list is not sufficient. LiteRT-LM and GenieX expose different execution paths and artifact requirements. [S01–S03]

| Input | Required behavior | Implementation status to respect |
|---|---|---|
| `.litertlm` | Inspect LiteRT-LM metadata, determine declared backends, validate file/package, install, then create a usable profile through the LiteRT-LM path. | Ordinary single-file locator already accepts this extension. Preserve its validator while improving transaction and progress handling. [R09] |
| Manifest-based `.localmodel` GenieX bundle | Validate every declared asset, route to GenieX QAIRT or GenieX llama.cpp, preserve tokenizer/projector/context requirements, then qualify the chosen backend. | Existing installer accepts validated GenieX QAIRT/GGUF bundles. [R08, R10] |
| Standalone `.gguf` | Inspect architecture and quantization; select the installed GenieX llama.cpp backend; gather required assets; generate an internal, hashed manifest; validate before registering. | Treat the direct-file adapter as new work unless the implementation audit finds an existing equivalent. Never pass GGUF to LiteRT-LM. |
| GenieX/QAIRT folder or supported ZIP | Discover the known configuration and all referenced data files; show missing dependencies; normalize into the existing manifest-based install transaction. | New normalization UI and folder-source adapter. Preserve exact-chipset and data-only package constraints. |
| `.tflite` or legacy `.task` | Recognize and explain the distinction. Execute only if a separately implemented and tested chat loader supports that precise package; otherwise offer documented compatible conversion guidance. | Not automatically supported by the inspected `.litertlm` importer. Do not rename the extension or silently advertise universal LiteRT chat compatibility. |
| Unknown file, bare shard, or arbitrary executable package | Explain the missing supported entry point or unsupported format; leave existing installations untouched. | No arbitrary native libraries, scripts, or downloaded executable code may be installed as model assets. |

The import help should say exactly which handlers this APK contains. On a build lacking GenieX, allow format inspection and show the required supported build with a clear explanation. Do not offer a working-looking GenieX processor switch that cannot load its SDK. A one-APK experience requires a separate, tested packaging and licensing decision; the redesign must work honestly with the repository's existing build separation.

## 6.2 Flow and state machine

```text
Choose source -> Inspect -> Review -> Import -> Validate
              -> Create / choose profile -> Ready or Needs verification

At each phase: Cancel / Retry / Explain missing requirement
```

**Choose source.** Show Choose file and Choose model folder, with the latter only when folder handlers exist. Use Android's Storage Access Framework and scoped document access, not broad storage permissions. For folders, explain what the app will copy. Persist URI access only when supported and needed to resume the user's explicit import. [S10]

**Inspect.** Read metadata and a bounded header on an I/O dispatcher. Discover type from file content plus schema, not extension alone. For a multi-file model, list missing tokenizer, projector, configuration, or shard assets with a “Choose missing files” action. Do not fabricate a chat template or substitute another model's projector.

**Review.** Show model name, runtime, proposed processor, compatibility state, source size, expected installed size, and required extra storage. Advanced details contain manifest, architecture, precision, context limits, hashes, and source provenance. Give the user a clear “Import” action and disclose whether the profile will need a first-run check.

**Import.** Stream into a unique staging directory. Display the current phase and actual progress: copying, verifying assets, finalizing. Use indeterminate progress only when total bytes are unknown. Copy and hash incrementally, check cancellation, and never read a multi-gigabyte model into a `ByteArray`. Keep the user-selected source unchanged.

**Validate.** Run structural, manifest, dependency, exact-backend, storage, and model-specific checks. Treat imported “verified” labels as untrusted. The existing bundle installer clears imported test evidence; preserve that behavior. [R10]

**Register.** Atomically commit an immutable installation record, then create or select a profile. Offer “Create profile” and “Keep model only”; do not force a duplicate profile. After creation, return to Models and briefly highlight the new bubble without disturbing pinned order.

**First use.** Run the normal load preflight and, when needed, a bounded smoke test. Display the real phase, make cancellation available, and handle native-process failure as a recoverable model error. Do not perform a long benchmark automatically after import.

## 6.3 Transactions, resume, and security

Represent the import as a persisted job with a stable ID, phase, copied bytes, source descriptor, staging path, and error code. A rotation or navigation change must not restart copying. Resume after process death only when the source can be reopened and the checkpoint can be verified; otherwise clearly offer to reselect it and discard the incomplete transaction safely.

For new imports, use content-based artifact identity rather than a sanitized filename alone. Two different files named `model.litertlm` must not overwrite each other, and identical bytes should be reusable. Preserve existing IDs through migration aliases instead of renaming current profile references. Reserve expected peak storage including archive staging and expanded assets; recheck while copying.

Reuse `ModelBundleInstaller`, `ModelArtifactManifest.safeFile`, and `PackageDigest`. Preserve duplicate-entry checks, declared-size bounds, traversal rejection, hashes, and the ban on executable imports. A folder adapter must enforce the same containment rules and reject links or provider entries that escape the selected logical package. Do not relax the existing manifest-first bundle rule merely to accept more ZIPs; normalize supported source layouts through a separate bounded adapter. [R08, R10]

Use installation generation/epoch checks so cancellation, a newer download, or a deletion cannot race a stale completion into registering an unwanted artifact. A database update failure after file commit must be recoverable through reconciliation. Deletion must consider active loads, transfers, and every referencing profile; release idle handles before removing files.

## 6.4 Error copy and recovery

| Situation | Visible copy | Recovery |
|---|---|---|
| Missing GenieX runtime | “This model needs the GenieX runtime, which is not included in this build.” | View supported build details; keep the current app/profile intact. |
| Wrong compiled chipset | “This package targets a different processor.” | Show detected and required targets; suggest a compatible variant. |
| Missing tokenizer or projector | “The model package is incomplete.” | Select the exact missing assets; never silently downgrade image capability. |
| Checksum or truncated file | “The model file could not be verified.” | Recopy/redownload; show technical detail behind help. |
| Low memory now | “Not enough free memory to load this model right now.” | Release idle model, use a supported smaller context, or try later. |
| Insufficient storage | “More storage is needed to finish this import.” | Show required space and Manage storage. |
| Generic `.tflite` or unsupported `.task` | “This file is not a supported chat package for the installed runtime.” | Explain compatible formats and conversion requirements. |
| Source permission lost | “Access to the selected file expired.” | Choose the source again; retain verified checkpoints only. |

# 7. Assist tab: complete controls and actual wiring

## 7.1 Layout and first use

At the top, show a polished **Local Assist** card with one master switch, a short explanation, and a status: Off, Needs a model, Ready, Working, Waiting for memory, or Paused. Under it, place **Worker profile**, **What it helps with**, **Routing and privacy**, and **Advanced limits**. End with a compact recent-activity card and a link to the existing diagnostics view.

A collapsed group shows a useful summary, for example “Evidence selection; read-only; main AI writes the answer.” Do not present an unbroken screen of thirty switches. The default setup is brief: choose an eligible installed profile, review the local/cloud boundary, and enable Assist. When no eligible profile exists, offer Marketplace filtered for Assist and a route back to setup after profile creation.

Keep one canonical Assist preference object. The existing `localAssist` flag remains a compatibility facade during migration; remove duplicate editable versions from runtime cards. The existing implementation is an evidence-selection worker, not autonomous browsing or a general local agent. [R04, R07, R13]

## 7.2 Settings inventory and proposed defaults

The following controls define the target. **Preserve** means migration retains current behavior. **New** means persistence, reducer, runtime consumer, and tests are required before the control is enabled.

| Group / setting | Initial policy | Required connection |
|---|---|---|
| Enable Local Assist | Preserve existing `localAssist`; off for new installations | Canonical Assist policy gates all scheduling, not just visibility. |
| Worker profile | Explicit eligible local profile; none silently selected during migration | Resolve profile UID to artifact/runtime and current capability report. |
| Operating mode | Evidence selection by default; broader Adaptive mode after qualification | Choose an implemented task policy, not just a different label. |
| Evidence selection | On within enabled legacy mode | Existing exact-record `AssistCoordinator` path. |
| Clean and summarize retrieved text | New; opt-in after task tests | Chunked input, source references, separate summary field, retained original. |
| Read imported document text | New; opt-in, only authorized documents | Existing app parsers first; model receives text, not arbitrary file access. |
| Propose search queries | New; opt-in after quality tests | Return candidate queries to the primary orchestrator for authorized execution. |
| Propose task plans | New; opt-in | Structured task proposal; primary model/orchestrator decides execution. |
| Handle permitted read-only tools | New; opt-in per tool capability | Existing permission and execution-owner path; no independent bypass. |
| Memory recall assistance | New; disabled until memory permissions are connected | Same memory scope and cloud-sharing rules as the main request. |
| Main AI writes final answer | On in supported Assist modes | Worker outputs evidence or proposals, not competing final chat answers. |
| Use existing warm model only | Preserve legacy eligibility policy | No unexpected load or unload while an active local chat owns the runtime. |
| Allow prewarming chosen helper | New; off by default | Explicit user opt-in, hardware admission, idle unloading, cancellation. |
| Share authorized results within the current turn | Reuse existing shared-read-only policy | Shared cache has authorization/owner scope; no duplicated preference store. |
| Allow network tools | New; disabled unless authorized | “Local processing” is not represented as offline when network tools run. |
| Allow local content in cloud synthesis | Never broaden existing consent | Explicit per-scope permission; default deny newly introduced data categories. |
| Failure behavior | Return original tool result to the primary flow | Never convert helper failure into total research failure. |
| Busy worker behavior | Skip optional Assist and continue, by default | Bound queueing; primary work is not starved. |
| Per-turn attempts | Preserve two for migrated evidence mode | Shared counter across the turn, not two per model/profile wrapper. |
| Per-task timeout | Preserve eight seconds for migrated evidence mode | Cancellation-aware deadline; show advanced control with validated bounds. |
| Candidate and selected records | Preserve twelve candidates / up to five selections | Selection operates on complete records; no corrupt partial records. |
| Input / output budgets | Versioned per-task defaults; preserve legacy constraints | Token-aware chunking plus byte/character transport bounds; never pretend characters are tokens. |
| Concurrent native helper jobs | One until a runtime explicitly supports more | Shared native scheduler, not a separate worker per combined-mode profile. |
| Recent activity and detailed logging | Compact operational counts; detailed logging opt-in | Redacted diagnostics with no prompt/file-content capture by default. |

The legacy worker also skips small result sets and payloads under 4,096 characters and refuses oversized candidate payloads above 12,000 characters. Preserve those semantics until a tested replacement handles chunking and selection coverage. Do not expose a “100% Assist” slider whose effect is undefined. [R07]

## 7.3 Scheduling, tool ownership, and shared sources

The local model cannot access tools merely because it can generate a tool-call-shaped string. Task handlers must validate output, then route any authorized operation through the same tool consent, owner selection, budgets, and cancellation logic as the primary request. A model package does not grant permissions.

Use one request-scoped evidence registry shared by primary profiles and delegates when their authorization scopes permit it. Keys include the canonical request, retrieval options, credential/access scope, execution owner, and freshness policy. Preserve canonical URL plus original URL and source provenance; avoid stripping query parameters that change the resource. Two authenticated views of the same URL must not accidentally share private content.

Assign pending work through a bounded queue with leases or equivalent ownership, rather than sending every URL to every engine and every AI. Search engines discover sources; fetch tools retrieve pages; local models may select or summarize authorized text. A model is not an additional search engine unless a real, permitted search adapter exists. Reuse successful page fetches and avoid retry storms across combined-mode profiles.

This shared registry is a proposed integration boundary, not a claim that the inspected Assist class already implements it. Reuse the repository's existing read-only sharing controls and research infrastructure; inspect their concrete APIs during the first implementation phase rather than building a competing registry blindly. [R07, R13]

## 7.4 Quality, privacy, and termination

Retain immutable original research records and exact source URLs. A summary is a derived artifact with links back to originals, not a replacement of quoted facts. Preserve conflicting evidence. Validate every selected index or structured task response; malformed output, stale jobs, timeout, or helper crash falls back to the original result.

Enforce turn cancellation throughout queued, prefill, generation, and tool phases. Prevent recursive Assist calls and worker-to-worker delegation. Snapshot relevant settings at the start of each task; turning Assist off cancels queued optional work immediately and stops current work through the runtime's safe cancellation boundary.

The status text must distinguish “Processed locally” from “Shared with your main AI.” No switch should claim that cloud synthesis is entirely on-device. Source instructions embedded in web pages or documents remain untrusted data. Destructive or external write actions stay outside default Assist scope and keep the app's existing explicit confirmation requirements.

Before enabling a new task type, test it against a deterministic/non-model baseline for latency, retained evidence, and answer-relevant omissions. Do not claim remote-token savings without accounting for added local work and actual before/after token measurements.

# 8. Settings tab: complete organization

## 8.1 Group layout

Use six rounded section surfaces with leading icons, readable titles, a one-line current summary, and only the relevant controls expanded. Add a settings search field when the screen's density warrants it; search results open the owning group and highlight the row. Search indexes labels and help text without duplicating settings.

| Section | Existing controls to preserve | Placement of deeper information |
|---|---|---|
| **Engine and processor** | Local runtime backend, execution policy, automatic QNN fallback | Runtime availability and exact processor explanation in details; current profile's explicit accelerator stays in profile settings. |
| **Performance** | CPU threads, speculative decoding, compiled-model cache | Capability-qualified controls only; requested/effective MTP and backend in detail view. |
| **Memory and power** | Idle release interval, keep-warm behavior, memory reserve | Hardware governor status, thermal/battery state, Release idle memory action. |
| **Downloads and storage** | Wi-Fi-only downloads, download manager, storage usage, deletion/recovery | Transfer rows, installed artifacts and referencing profiles, cache management. |
| **Accounts and privacy** | Hugging Face account/token actions; links to applicable existing permissions | Masked account state, disconnect confirmation, no visible stored token value. |
| **Diagnostics and advanced** | Native performance counters, hardware refresh, local comparison, trial history, clear evidence, recommended defaults | Dedicated comparison/details screens; unsupported experiments remain explanatory, not active switches. |

A runtime-build capability is read-only status, not a preference. Do not display “Enable GenieX” as an ordinary switch when that APK does not include GenieX. Do not imply automatic QNN fallback can run the same chipset-compiled binary on CPU/GPU: an alternate compatible artifact/backend is required. [R04, R14]

## 8.2 Existing setting-to-consumer map

| Setting / owner | Preserved default | Target control and behavior |
|---|---|---|
| `localCpuThreads` | `0` — automatic | Discrete slider or number picker, only for applicable execution paths; apply at next load. |
| `localModelCache` | `true` | Switch with explanation of compiled cache versus model weights; clearing cache does not delete models. |
| `localSpeculativeDecoding` | `AUTO` | Model default / Off / On selector; unavailable capability explained, not faked. |
| `localNativeMetrics` | `false` | Diagnostics switch; measurement overhead explained. |
| `localIdleMinutes` | `10` | Automatic release selector including Keep warm; never unload an active response. |
| `localExecutionPolicy` | `PROFILE` | Policy selector; retain explicit profile choice and evidence-based policy rules. |
| `localExecutionPolicyVersion` | `1` | Internal migration/version field; not a user switch. |
| `localMemoryReserveMb` | `768` | Advanced memory reserve with recommended value and validation; same hardware admission consumer. |
| `qnnAutomaticFallback` | `true` | Available fallback toggle; explain actual alternative and retain failure reason. |
| Local runtime backend in `SettingRepository` | Preserve saved enum | Runtime selector gated by packaged capabilities, not catalog labels. |
| SharedPreferences `local-model-downloads.wifiOnly` | Existing default `false` | Migrate to canonical download policy, preserving explicit and legacy values. |
| `localAssist` | `false` | Lives only in Assist; Settings provides a navigation link, not another toggle. |

Defaults above are source observations, not recommendations for every device. [R02, R05, R13] For new installations, Wi-Fi-only may default to on to avoid accidental large metered transfers; migration must not silently alter existing users' saved behavior. Add a one-time metered confirmation when policy permits cellular downloads. Any new pause/resume or notification switch must be backed by the actual worker/service behavior and permission state.

## 8.3 Control behavior and reset rules

Write preferences through a single repository mutation path. Disable only the control or operation that is genuinely blocked; avoid a global busy flag making the entire Settings tab unresponsive during a long comparison. Debounce sliders and save on completion where appropriate. Show “Applies to the next model load” for changes that cannot alter a running engine safely.

A disabled setting includes a visible short reason and a help action. Preserve its saved value so a temporary runtime absence does not erase preferences. Show inherited versus explicit overrides, and provide a scoped “Reset this section” with a preview of affected fields. Resetting Performance must not reset Assist consent, accounts, profiles, or downloaded files.

Keep unrelated app appearance, general research, and remote-provider settings in their existing screens. Link to shared policies rather than duplicating their toggles. Preserve the existing app-wide theme and motion settings as the source of truth.

# 9. Repository architecture and file-by-file integration

## 9.1 Boundaries

Keep the current Kotlin/Compose, Hilt, repository, and `StateFlow` architecture. Treat this as a screen decomposition and domain-policy consolidation, not a rewrite of native inference or the app's database. The inspected screens already use lifecycle-aware collection and existing repositories; retain those foundations. [R02–R05]

```text
Local AI route / navigation adapter
  Models UI        Assist UI          Settings UI
        |              |                   |
  immutable UI state + user intents + one-shot effects
        |              |                   |
  existing repositories / typed policy use cases
        |              |                   |
  catalog + artifacts  Assist scheduler    runtime settings
        |              |                   |
  shared compatibility / admission / permissions / diagnostics
        |              |                   |
  download/install jobs             existing native runtime
```

Do not copy model inventories into three different view models. Produce shared artifact/profile projections from canonical repository flows. A profile UID identifies a configuration; an artifact ID/digest identifies model bytes; a runtime session ID identifies a loaded instance. This separation prevents duplicate weights, broken deletion, and inaccurate “active” badges.

## 9.2 Existing files to change

All Kotlin paths below are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`. These are observed files; their implementation changes are proposed.

| Existing file or area | Required change |
|---|---|
| `presentation/ui/setting/LocalModelsScreen.kt` | Replace root catalog layout with three-tab shell; move catalog composition into Marketplace; maintain old entry arguments through an adapter. |
| `presentation/ui/setting/LocalModelsViewModel.kt` | Separate catalog query state from the Models hub projection; expose import job state and errors; move provider I/O off the main dispatcher. |
| `presentation/ui/setting/LocalModelCatalogUi.kt` | Reuse suitable catalog pieces; adopt shared family/variant cards and typed compatibility actions. Audit its full contents before extraction. |
| `presentation/ui/setting/LocalRuntimeSettingsCard.kt` | Split the large card into grouped settings; remove duplicate Assist switch; retain benchmark and diagnostic entry points. |
| `presentation/ui/setting/LocalRuntimeSettingsViewModel.kt` | Keep existing mutations; add scoped operation state and validation; preserve profile creation and active-memory release semantics. |
| `presentation/ui/setting/LocalAssistToggle.kt` | Reuse or retire as a thin canonical Assist control; no second settings owner. |
| `presentation/ui/localmodel/` download actions/dialogs/status | Integrate transfer state with the new surfaces while preserving authentication, license, and metered-network handling. |
| `data/catalog/LocalCatalogPolicy.kt` | Introduce typed compatibility dimensions and evidence-aware action composition; rank variants before family selection. |
| `data/localmodel/ModelArtifactManifest.kt` | Add only backward-compatible metadata needed for typed import/compatibility; retain versioned validation and asset security rules. |
| `data/localmodel/ModelBundleInstaller.kt` | Keep secure transaction boundary; expose progress and structured errors; share staging/commit semantics with new adapters. |
| `data/localmodel/LocalModelLocator.kt` | Improve single-file import identity and cancellable streaming; preserve old artifact IDs with aliases. |
| `data/localmodel/LocalModelPackages.kt` | Reconcile typed runtime metadata with legacy package inference; never let NPU-only binaries fall through to CPU/GPU. |
| `data/repository/LocalModelRepository.kt` | Add structured import-job observation and transactional registration interfaces only where needed; update its actual implementation after locating it. |
| `data/model/AppFeatureSettings.kt` | Add versioned Assist/download configuration or compatible facade; preserve existing local defaults and serialized fields. |
| `data/assist/AssistCoordinator.kt` | Retain exact-record selection; move hardcoded limits into validated policy; coordinate per-turn state and optional task handlers. |
| Existing navigation callers, DI modules, resources, backup serialization | Wire new destinations, state owners, strings, and migration; retain old route behavior and backup compatibility. |

`DeviceHardwareGovernor`, `InferenceAdmission`, `LiteRtModelInspector`, `LocalAccelerators`, `LocalModelReconciler`, and the runtime comparison infrastructure are existing integration points identified in the source tree. Audit their public contracts before extending them. Do not create parallel hardware monitors or duplicate benchmark stores because the screen layout changed.

## 9.3 Proposed new components

Names below are **new design suggestions**, not discovered repository files:

| Proposed component | Responsibility |
|---|---|
| `LocalAiRoute` / `LocalAiTab` | Typed Models / Assist / Settings selection, old-entry translation, restoration. |
| `LocalAiHomeScreen` | Exact Marketplace → Import → Profiles order and contextual transfer footer. |
| `LocalMarketplaceScreen` | Recommended and remaining compatible families, search, filters, detail routing. |
| `LocalAssistScreen` / `LocalAssistViewModel` | Canonical Assist setup, policy editing, status, and activity. |
| `LocalAiSettingsScreen` | Grouped settings bound to existing mutation owners. |
| `LocalProfileBubble`, `LocalMarketplaceHero`, `LocalImportBubble` | Reusable themed surfaces with consistent interaction and accessibility. |
| `LocalCompatibilityReport` / resolver use case | Unified structural, runtime, memory, access, and evidence assessment. |
| `LocalImportCoordinator` / `LocalImportJob` | Format dispatch, source handling, transaction/progress state, recovery. |
| `ModelImportAdapter` implementations | LiteRT single file, GenieX GGUF, supported QAIRT folder/ZIP normalization. |
| `LocalAssistPolicy` / `AssistTaskHandler` | Validated task-specific settings and capability-qualified worker behavior. |

Prefer extension of existing equivalent components over creating each proposed class mechanically. Set a review rule: no new preference field without an identified consumer, no new badge without evidence, and no enabled task switch without a working handler.

## 9.4 State and lifecycle rules

Use immutable presentation states with stable IDs. Keep catalog loading, profile inventory loading, import progress, and runtime operation state independent. A network refresh error must not replace the local profile list with an error screen.

Calculate filtering, ranking, file metadata, and large list projections off the composition path. Preserve search and scroll through `SavedStateHandle` or the established navigation state mechanism. Use one-shot effects for navigation/snackbars rather than replaying them from a persistent Boolean.

Imports/downloads should be owned by a persisted background-capable operation appropriate to the app's existing workers/services and current Android constraints, not by the lifespan of a composable. Native inference remains owned by the established runtime/service. A comparison must not initialize a second engine from a settings screen.

Reuse the existing help component and theme tokens. New strings must be resources with pluralization and meaningful accessibility descriptions. Keep test tags stable and independent of translated text.

# 10. Migration, compatibility, and rollback

## 10.1 Migration sequence

**Inventory first.** Record the current local fields, backend preference, profile references, imported artifact IDs, download jobs, caches, and evidence schema. Locate every Local AI entry point and every `localAssist` writer. Source snapshots are evidence for planning, not a substitute for this implementation-time call-site audit.

**Additive schema.** Add versioned configuration with defaults that reproduce current behavior. Keep legacy serialized keys while callers transition. Migration runs once and is idempotent; a second launch cannot reset a user's changes. Use the existing repository/database transaction mechanism rather than a new database just for the redesign.

**Map navigation.** Normal entry opens Models; old Marketplace links still open Marketplace; old local delegation links open Assist. Returning from an import or model detail restores the correct root state. Unrelated remote delegation routes remain unchanged.

**Preserve identities.** Profiles keep UIDs and model references. Existing imported IDs remain valid even when new imports use digests. Do not trigger bulk hashing or re-downloads on the main thread at first launch; reconcile metadata in bounded jobs with visible progress when needed.

**Move settings without changing meaning.** Preserve `wifiOnly`, Assist enablement, backend preference, context, temperature, idle behavior, and explicit processor choices. Newly introduced network/data-sharing scopes remain ungranted. Keep Assist in its existing evidence-selection behavior until the user explicitly chooses a qualified new mode.

**Reconcile evidence.** Retain existing valid test tuples. Only invalidate qualification whose runtime/device/artifact tuple changed. An unknown historical result remains historical, not a new verified badge. Do not delete useful benchmarks because their UI destination moved.

**Backup and restore.** Add new fields to the established backup format with a schema version. Restore settings and profile identities before attempting to resolve model bytes. Missing artifacts become repairable profiles. Preserve the app's existing secure-token backup policy; do not add plaintext secrets to the new settings export.

## 10.2 Rollback

Keep the old rendering path behind a development/release flag during integration, backed by the same canonical data. Do not maintain two competing settings stores. Rollback returns the old UI without deleting new data, touching model files, or losing current downloads.

Avoid destructive schema changes in the initial release. A downgraded app must ignore new optional fields where its serializer permits that; test the actual older build rather than assuming compatibility. Where downgrade cannot safely read new schema, document that limitation before release and preserve an export/recovery path.

# 11. Carefully sequenced delivery plan

Each phase should land as a reviewable change with its own tests. The branch names below are suggested, not created. Use a fresh branch from the then-current `main`, such as `feat/local-ai-experience-redesign-2026-10-12`; do not reuse an already merged release branch blindly.

## Phase 0 — Lock the baseline and inventory the full path

Inspect all relevant call sites, concrete repository implementations, runtime build flags, download constraints, existing screenshots, and test conventions. Reconcile this plan against changes after `cbd88545`. Identify exactly which import paths work in the standard and GenieX APKs. Capture before-state screenshots and representative backups.

**Gate:** A checked-in inventory maps every existing Local AI control to its storage key and runtime consumer, and every existing navigation route to its new destination. No control is silently dropped.

## Phase 1 — Establish the shell and reusable visual components

Extract the catalog without changing its behavior. Add the Models / Assist / Settings shell and old-route adapter. Build the Marketplace hero, import bubble, profile bubble, grouped setting rows, and consistent help actions using app theme tokens. Add previews for empty, populated, error, large-font, light, and dark states.

**Gate:** Normal entry shows the requested order; Marketplace is a child; Back and state restoration work. No runtime is loaded by rendering a screen. Static visual checks and accessibility semantics pass.

## Phase 2 — Connect profiles and preserve all settings

Project local profiles and artifact states from the existing stores. Wire Use, Edit, Duplicate, Pin, Assign to Assist, and safe Delete. Split the current runtime card into the new Settings groups; retain comparison and diagnostics in child screens. Remove duplicate Assist controls only once the canonical tab is connected.

**Gate:** Profile actions persist and survive relaunch; deleting a profile never removes shared weights; every previously available local setting remains reachable and functional.

## Phase 3 — Unify compatibility and marketplace ranking

Introduce the typed report, then connect catalog cards, family representatives, import review, and profile creation. Add coarse total-RAM filtering, working-memory estimates, exact package/runtime matching, evidence-aware labels, and recommended/remaining-family deduplication. Keep broad discovery separate from confirmed structural compatibility.

**Gate:** Table-driven tests cover missing metadata, wrong SoC, absent SDK, unsupported backend, unknown RAM, temporary low memory, gated access, and stale evidence. A catalog entry alone cannot become “verified.”

## Phase 4 — Implement the full import transaction

Wrap the existing LiteRT locator and GenieX bundle installer. Add progress/cancel, persisted jobs, digest identity for new imports, recovery, and safe registration. Implement direct GGUF and supported folder/ZIP adapters where absent. Wire exact dependencies and runtime routing before changing the import help to claim those formats.

**Gate:** Positive execution tests for each advertised format/build pair and negative security tests pass. Partial imports never appear usable. Unsupported `.tflite`/`.task` input receives correct guidance rather than fake acceptance.

## Phase 5 — Make the Assist tab complete and honest

First migrate the existing evidence-selection worker into canonical policy without changing its output. Then implement selected new task handlers, worker assignment, authorization-aware sharing, native scheduling, and failure recovery. Connect settings one at a time; unsupported tasks are not shipped as functional switches.

**Gate:** Legacy behavior regression tests pass. Helper failure does not stop the primary answer. Combined-mode work shares authorized results without cross-scope leakage, duplicate native workers, or recursive delegation. Quality/latency tests justify each new enabled mode.

## Phase 6 — Migration, backup, lifecycle, and fault tests

Run upgrades from a build containing the current local settings and profiles. Exercise in-progress transfer migration, process death, source permission loss, reboot/relaunch recovery, concurrent profile edits, deletion races, and backup restore. Verify scoped resets and rollback behavior.

**Gate:** No model loss, silent consent change, unexpected re-download, or corrupted profile reference. Failed migrations fail visibly and preserve recoverable data.

## Phase 7 — Visual refinement and performance validation

Review all screens against the design tokens. Tune motion on physical devices, not only previews. Measure scrolling under active downloads and memory-pressure updates. Test TalkBack, switch access, 200% font scale, long translated strings, landscape, narrow phones, and expanded windows. Remove unnecessary labels and decorations rather than shrinking text.

**Gate:** The visual/a11y matrix in Section 12 passes, and measured regressions versus the old screen are within the agreed budget. Performance targets are recorded with device/build details.

## Phase 8 — Release qualification and documentation

Run the repository's applicable static checks, unit tests, instrumented tests, and standard/GenieX build workflows. Use existing PR validation, marketplace, UI-review, device-validation, and native-memory workflows where applicable, after inspecting their current definitions. Publish a support matrix covering runtime, format, backend, and tested device tuple.

**Gate:** No advertised format lacks a qualified execution path. Release notes distinguish redesigned UI, newly implemented features, experimental paths, and unsupported imports. Do not call the experience “fully supported” based solely on a green compile. A version bump or release publication is a separate execution task, not performed by this plan.

# 12. Acceptance tests, metrics, and release criteria

## 12.1 Mandatory acceptance matrix

| ID | Scenario | Pass condition |
|---|---|---|
| UI-01 | Open Local AI normally | Exactly Models, Assist, Settings; Models selected; Marketplace, Import, Profiles in that order. |
| UI-02 | Open Marketplace and return | Recommended first, remaining compatible models second; root tab and scroll restored. |
| UI-03 | Existing legacy deep links | Correct destination; no duplicate screen stack or lost Back behavior. |
| UI-04 | Zero, one, and many profiles | Useful empty state; stable cards; shared artifacts do not duplicate downloads. |
| UI-05 | Light/dark/theme variations | Semantic contrast, consistent spacing, no hardcoded theme mismatch or unreadable disabled state. |
| UI-06 | 200% font scale, narrow/expanded widths | No clipped titles, controls, or dialogs; root labels and primary actions remain reachable. |
| UI-07 | TalkBack and keyboard/switch navigation | Correct focus order, labelled help, separate help/toggle targets, restored focus after sheets. |
| UI-08 | Active download while scrolling | Only affected progress updates; no repeated list entrance animation or unexpected reordering. |
| CAT-01 | Matching and mismatching chipset variants | Only valid structural matches in compatible results; exact required target explained on mismatch. |
| CAT-02 | Missing GenieX build or failed runtime init | Appropriate runtime-unavailable state, not false hardware compatibility or a working-looking switch. |
| CAT-03 | Unknown RAM estimate or low free memory | Unknown remains unknown; temporary low memory changes readiness without deleting installed cards. |
| CAT-04 | Multiple variants in one family | Representative is the ranked eligible variant; family appears only once across main sections. |
| CAT-05 | Installed, preflight, smoke-tested, verified | Badges accurately reflect distinct evidence and matching runtime/device tuples. |
| CAT-06 | Offline or failed refresh | Cached catalog clearly presented; installed profiles remain usable; retry does not block the page. |
| IMP-01 | Valid `.litertlm` | Correct validator/runtime, successful registration and generation on declared backend. |
| IMP-02 | Valid GenieX `.localmodel` | Correct QAIRT or llama.cpp route; all assets/hashes checked; imported evidence not trusted. |
| IMP-03 | Direct GGUF and supported model folder | Advertised adapters complete installation and real generation, including required sidecars. |
| IMP-04 | Wrong extension, generic TFLite, legacy task | Correct detection and explanation; no renamed-file workaround or unsupported chat promise. |
| IMP-05 | Traversal, duplicate entries, oversized expansion, executable asset | Transaction rejected before unsafe write/load; staging safely cleaned. |
| IMP-06 | Same filename/different bytes; same bytes/different name | No overwrite collision; intentional reuse by artifact identity. |
| IMP-07 | Cancel, process death, permission loss, low storage | No usable partial record, no orphan active handle, clear recovery and consistent job state. |
| PROF-01 | Delete profile/model while in use or shared | Respect active work and references; show consequences; retain repairable profiles as chosen. |
| AST-01 | Assist disabled / no worker / busy worker | No hidden local load; understandable status; primary request continues. |
| AST-02 | Timeout, malformed JSON, invalid index, worker crash | Original result preserved and returned; no whole-research failure. |
| AST-03 | Conflicting evidence and long inputs | Original sources retained; bounded chunking; no fabricated source IDs or silent fact rewriting. |
| AST-04 | Combined models/delegates request same source | Single authorized shared retrieval where allowed; no cross-user/credential leak or work explosion. |
| AST-05 | Disable or cancel during work | Pending tasks stop; native cancellation respected; stale completion cannot mutate current turn. |
| AST-06 | Unauthorized tool or cloud content request | Existing permission boundaries enforced; user settings cannot grant unavailable capabilities. |
| SET-01 | Edit each local preference then relaunch | Saved value reaches the intended runtime consumer; applicable-next-load behavior is visible. |
| SET-02 | Scoped reset | Only that section changes; profiles, accounts, content, and unrelated consent are preserved. |
| MIG-01 | Upgrade and backup restore | UIDs, model references, settings, and valid evidence preserved; missing bytes marked repairable. |
| RUN-01 | Thermal pressure, low RAM, service death | Graceful admission/recovery; no UI crash or silent unsupported CPU/GPU fallback. |

## 12.2 Device and build coverage

Test the user's physical phone plus at least one lower-memory supported device, a contrasting processor family, and a build without GenieX. Include a supported exact-chipset QAIRT tuple and a deliberately mismatched one. Test CPU, GPU, and NPU only where both artifact and runtime declare support; record unsupported combinations as such.

For GenieX GGUF NPU and image input, follow the manifest's current qualification constraints, including explicit architecture/precision and exact projector assets. Those constraints are not proof that every file passing static validation will execute successfully. The inspected manifest includes an initial chipset qualification set; do not expand it through a display filter alone. [R08]

Cover Android foreground/background behavior, rotation, process recreation, battery saver, storage pressure, and network transitions. Run long comparisons only on explicit request with a visible stop action. No on-device test results are asserted in this document.

## 12.3 Proposed measurable targets

Treat these as initial acceptance targets to calibrate against a recorded baseline, not performance claims:

| Measure | Initial target |
|---|---|
| Cached Models page interactivity | p95 under 500 ms after navigation on the reference release build, with no network/native initialization dependency. |
| Profile scroll performance | No more than a two-percentage-point increase in measured janky frames versus baseline under the same workload; investigate absolute poor performance even when unchanged. |
| Input response | Normal taps acknowledged on the next available frame; no synchronous provider query, hash, or archive work in UI handlers. |
| Transfer progress updates | Coalesced to roughly 4–10 visible updates per second during transfer; terminal state delivered immediately. |
| Cancel acknowledgement | UI reflects cancellation promptly; underlying operation stops at its documented safe boundary and never commits stale results. |
| State correctness | Zero lost profiles or model files in the migration/recovery corpus; zero falsely verified test cases. |
| Settings coverage | Every shipped control has persistence, consumer, permission/capability handling, and a test. |

Collect only the metrics needed to verify these targets. Keep detailed diagnostics opt-in and redact tokens, full private paths, source text, and credentials. A count of successful local tasks is not permission to collect their contents.

## 12.4 Risks and go/no-go

The highest implementation risks are broad format claims without matching runtimes, guessed compatibility, migration identity changes, unbounded local helper work, and decorative UI that obscures errors. Mitigate them with the shared report, immutable installs, canonical settings ownership, qualification-specific release gates, and retained originals.

The release is **no-go** if it removes an existing Local AI control, loses a profile/model reference, presents an unavailable runtime as usable, hides consent behind help, accepts a model without a valid execution route, or turns optional Assist failure into main-request failure. Cosmetic completeness does not override those gates.

The release is ready when the three-tab experience is polished, every visible setting is connected, every advertised import path is tested, the migration preserves existing users' work, and the support matrix accurately distinguishes supported, untested, and unavailable combinations.

# Appendix A. Source register

Repository links below are pinned to the inspected commit unless noted. They support the **observed baseline**; the layouts, component names, defaults marked proposed, and delivery steps are design recommendations.

## Repository evidence

**R01.** [Inspected main baseline / merged PR #664](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/commit/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1)

**R02.** [LocalModelsScreen.kt — current root UI and settings dialog](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/LocalModelsScreen.kt)

**R03.** [LocalModelsViewModel.kt — catalog and local inventory state](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/LocalModelsViewModel.kt)

**R04.** [LocalRuntimeSettingsCard.kt — existing controls and help component usage](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/LocalRuntimeSettingsCard.kt)

**R05.** [LocalRuntimeSettingsViewModel.kt — mutations, profile creation, runtime state](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/LocalRuntimeSettingsViewModel.kt)

**R06.** [LocalCatalogPolicy.kt — compatibility and recommendation policies](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/catalog/LocalCatalogPolicy.kt)

**R07.** [AssistCoordinator.kt — current exact-record evidence selection](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/assist/AssistCoordinator.kt)

**R08.** [ModelArtifactManifest.kt — typed artifacts, validation, security, evidence](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localmodel/ModelArtifactManifest.kt)

**R09.** [LocalModelLocator.kt — ordinary LiteRT single-file import](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localmodel/LocalModelLocator.kt)

**R10.** [ModelBundleInstaller.kt — GenieX manifest-based secure installation](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localmodel/ModelBundleInstaller.kt)

**R11.** [LocalModelRepository.kt — existing inventory/transfer/import interface](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/LocalModelRepository.kt)

**R12.** [LocalModelsViewModel.kt — document-source import entry point](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/LocalModelsViewModel.kt#L291-L325)

**R13.** [AppFeatureSettings.kt — existing local preferences and defaults](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/AppFeatureSettings.kt)

**R14.** [LocalModelPackages.kt — device-specific artifact/backend distinction](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localmodel/LocalModelPackages.kt)

## Runtime and design references

**S01.** [Google: LiteRT-LM Android integration](https://developers.google.com/edge/litert-lm/android)

**S02.** [Qualcomm: GenieX runtime and model ecosystem](https://github.com/qualcomm/GenieX)

**S03.** [Qualcomm: GenieX SDK integration](https://github.com/qualcomm/GenieX/blob/main/sdk/README.md)

**S04.** [Nielsen Norman Group: Progressive Disclosure](https://www.nngroup.com/articles/progressive-disclosure/)

**S05.** [Android: Accessibility defaults in Compose](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)

**S06.** [W3C: Understanding minimum contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)

**S07.** [Android: Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)

**S08.** [Android: Compose tabs](https://developer.android.com/develop/ui/compose/components/tabs)

**S09.** [Android: Compose tooltips](https://developer.android.com/develop/ui/compose/components/tooltip)

**S10.** [Android: Access documents and files through the Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)

External documentation was consulted for runtime distinctions, progressive disclosure, accessible controls, scoped file access, and Compose implementation guidance. Upstream documentation may evolve; re-verify it against the dependency versions packaged in the target build before implementation.

# Appendix B. Implementation handoff

Implement the three-tab shell and extract Marketplace first. Preserve all existing profiles, settings, model files, and runtime validation. Deliver shared compatibility and transactional import before expanding format claims. Migrate the current Assist worker before enabling additional task types. Require the acceptance matrix and real runtime execution evidence before publishing broad compatibility claims.

**Repository deliverable location:** proposed `docs/design/Local_AI_Experience_Redesign_Implementation_Plan_2026-10-12.md`. This document has not been committed to the repository.
