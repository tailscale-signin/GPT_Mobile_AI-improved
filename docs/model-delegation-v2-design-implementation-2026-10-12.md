# Model Delegation V2
## Design and implementation specification

**GPT Mobile AI improved** · 12 October 2026 · Version 1.0

**Repository:** `tailscale-signin/GPT_Mobile_AI-improved`  
**Audited baseline:** `main` at `7553e4ff14316f0d28639d5cc0ed24cb59511066`  
**Status:** Proposed application upgrade; documentation-only deliverable.  
**Scope:** Delegation settings, model/profile/chat integration, execution policy, tool wiring, recovery, diagnostics, and migration.

> **Design decision:** Make delegation easy to configure, explicit about where data goes, and predictable at runtime. Upgrade the existing infrastructure rather than build a competing agent system.

The findings below come from targeted source inspection at the pinned commit. They are not results of running the Android application. No device tests, compilation, performance measurements, or full security audit were performed for this document. New component names, defaults, metrics, and acceptance targets are proposals unless explicitly identified as existing behavior.

## 1. Product direction

Model Delegation should answer four questions immediately: **Is it active? Which model helps? What is it allowed to do? What happened?** Most users should not need to understand input-token floors, worker-call allowances, reviewer repair loops, or multiple timeout ceilings to obtain a reliable response.

The proposed experience has three destinations: **Overview, Workers, and Activity**. Advanced controls remain available through a clearly labelled editor, not an enormous expanded settings column. The primary model continues to own the user-facing answer. Delegation can prepare evidence, read authorized sources, extract structured information, and perform task-specific analysis; it does not automatically acquire additional permissions.

The upgrade must preserve existing profiles, theme preferences, research capabilities, tool connections, backup data, output settings, and user consent. Migration must not turn delegation on for previously disabled users, select a different paid provider silently, or treat a private-network address as proof that a server is trusted.

### 1.1 Required outcomes

| Outcome | Release acceptance target |
|---|---|
| Understandable setup | A ready user can enable delegation and choose a helper from Overview/Workers without opening Advanced. |
| One meaning per control | The saved policy, preview, chat override, runtime dispatch, and activity record agree. |
| Visible explanations | Every blocked or changed route has a reason and a relevant action; no unexplained fallback. |
| Safe continuity | Completed evidence survives eligible failures; uncertain side effects are not replayed automatically. |
| Honest reporting | Partial output, unavailable review, unknown usage, and estimates are labelled accurately. |
| Feature preservation | Existing global/profile/chat options remain representable through migration or a documented compatibility control. |

These are acceptance targets, not measured improvements. Do not advertise faster responses, token savings, or higher answer quality until paired tests demonstrate them.

## 2. Repository findings and upgrade priorities

**P0** means correctness or permission behavior must be resolved before enabling the replacement execution path. **P1** means required for the complete user-facing release. **P2** means optional optimization after the release gates pass.

| ID / priority | Observed code or bounded inference | Required change |
|---|---|---|
| A01 / P0 | `ConversationDelegationSettings.applyTo()` combines cloud permission using logical OR. A conversation cannot express denial when its defaults allow cloud helpers. [R05] | Add explicit inheritance and denial; lower scopes can restrict the allowed destinations. |
| A02 / P0 | Selecting a remote profile in `ConversationDelegationCard` sets `allowRemoteWorker` from its destination classification. [R04] | Separate selecting a model from granting permission to send it data. |
| A03 / P0 | `delegationFor()` gates enabled state with global and profile flags, but the conversation override later assigns its own `enabled`. Additional resolver gates exist; an end-to-end bypass has not been demonstrated. [R05][R08][R12] | One resolver must enforce master/profile gates after applying overrides; add explicit regression tests. |
| A04 / P0 | `LocalToolsViewModel.update()` returns immediately while busy. Feature changes use read-then-whole-object writes. Cross-screen lost updates are a risk, not a reproduced failure. [R03][R10] | Serialize accepted commands and transform the latest state inside one persistence transaction. |
| A05 / P0 | The UI filters enabled/privacy/reviewer candidates; runtime additionally checks the primary, installation/readiness, unavailable workers, tool restrictions, and local-runtime conflicts. [R01][R06] | Share eligibility logic and show the reason a profile cannot be selected or dispatched. |
| A06 / P1 | Effective call/input allowances can exceed the configured values through ownership floors. At ownership zero, a configured 1,000-token input limit can become an effective 8,000-token allowance. [R02] | Distinguish a preset recommendation from an explicit ceiling; show requested, effective, and limiting values. |
| A07 / P1 | UI output-help text describes larger reasoning/repair allowances, while the inspected reasoning-only repair in `ChatRepositoryImpl` retries at the same cap. [R01][R08] | Generate help from the policy contract and test it against provider request metadata. |
| A08 / P1 | Global settings use two tabs, a large hero, many advanced sliders, and two reset entry points. Reset preserves only a subset of role/settings fields. [R01][R03] | Simplify navigation, group advanced edits, and preview scoped resets. |
| A09 / P1 | Automatic fallback in `localTarget()` chooses the first eligible profile; benchmark-derived UI scores do not constitute a shared routing policy. [R03][R06] | Make automatic selection deterministic and explainable; keep pinned selection and approved fallback distinct. |
| A10 / P1 | A turn passes a settings callback that rereads delegation configuration. Ordinary edits can therefore affect later steps in an admitted run. [R08] | Snapshot ordinary policy at admission; retain a separate live revocation/stop channel. |
| A11 / P1 | Global and conversation controls duplicate state handling; the delegation panel also edits the separate memory cloud-recall setting. [R01][R04] | Reuse one scoped editor and label memory policy as a linked setting, not an implicit delegation permission. |
| A12 / P1 | User-facing source contains many literal English labels. The preparation-time UI range ends at 300 seconds while normalization accepts up to 3,600. [R01][R02] | Localize strings and derive editor bounds from the same versioned settings definitions as validation. |

### 2.1 Existing foundations to retain

The repository already contains per-turn coordination, worker counters, readiness cooldowns, review-and-repair behavior, cancellation tracking, and useful-output recovery. It also has a read-only tool-sharing broker, invocation accounting, research journals, and an application-level run coordinator. These are **not** new features to claim in the upgrade. [R06][R07][R08][R09]

`AssistCoordinator` is a separate, limited feature: it uses an eligible warm local worker to choose complete records from a retrieved result, validates the returned indices, and retains the original result. It is not a general replacement for Model Delegation. Preserve that boundary and share capacity/accounting instead of introducing double processing. [R13]

## 3. Information architecture

### 3.1 Entry points and ownership

| Entry point | Destination and scope |
|---|---|
| Settings → AI → Model Delegation | Global Overview; an explicit “Global defaults” scope label. |
| AI profile → Delegation | Same editor scoped to that primary profile; inherited values show their origin. |
| Chat model controls → Delegation | Compact conversation sheet with enable/inherit, helper, workload, and privacy summary. |
| Response → Delegation activity | Read-only run detail, with permitted recovery controls for an active/interrupted run. |
| Existing Debug/Benchmark screens | Deep links to the same run records and benchmark results, not duplicate stores. |

The replacement panel should be introduced behind the existing route first. Preserve old navigation destinations as redirects so saved navigation state and settings-search results continue to work. Exact navigation host changes must be confirmed during implementation; this audit did not inspect every route declaration.

### 3.2 Three primary destinations

**Overview** contains the master switch, readiness explanation, workload preset, preparation preference, effective-policy summary, and the latest run. **Workers** contains helper and reviewer assignment, allowed worker pool, fallback order, and capability/readiness details. **Activity** contains current and historical runs, outcomes, timings, usage, and recovery status.

Advanced controls, saved templates, and benchmark setup are secondary destinations. Do not add a top-level tab for every settings category or nest a second tab bar inside the compact chat sheet.

### 3.3 Terminology

Use **Primary model**, **Helper**, **Reviewer**, **On device**, **Private server**, and **Cloud provider** in ordinary UI. Keep provider/model identifiers available in details. Replace “local calls” with “helper calls” when a cloud helper is possible. “Reviewed” describes a model assessment, not independent proof of truth.

Keep the existing numeric delegation amount available in Advanced for compatibility. Explain that it is a **preparation preference**, not a measured percentage of total tokens, processor use, research coverage, or cost. Proposed quick choices are Primary-led (25), Shared (50), and Delegate-led (100). Other saved values display “Custom”, including the legacy default amount of 80. These choices are independent of the Focused/Balanced/Thorough research presets; changing one must not masquerade as changing the other.

## 4. Detailed UI specification

### 4.1 Overview

Replace the oversized hero with a compact title row, switch, and one status sentence. Example states: “Ready · Helper on this device”, “Off for this profile”, or “Choose a reviewer for delegate-led preparation”. A blocked state must link to the relevant setting rather than display a generic error.

Below it, show a single workload card. Focused, Balanced, and Thorough preserve the existing meaning of research breadth. Changing a preset must not change cloud consent, worker assignment, reviewer identity, output limits, or tool permissions. Manual changes to preset-owned fields produce a **Custom** badge even when the depth number still matches a preset.

A second card summarises the effective plan: helper, review policy, allowed destinations, research state, and the most important limiting budget. “Preview plan” opens a deterministic configuration preview without model calls, tool calls, network probes, or billable work. It must be labelled a preview, not a promise of the model's exact future actions.

```text
Model Delegation                         [On]
Global defaults · Ready on this device

[ Overview ]     Workers       Activity

Workload
Focused       [Balanced]       Thorough
Preparation   Shared                  >

Your setup
Helper        My local profile        >
Review        When requested          >
Data          On device only          >
Limits        16 helper calls         >

[ Preview plan ]
Latest run: Completed · 3 helper tasks >
Advanced controls                     >
```

This is a structural wireframe, not a screenshot or a pixel-accurate rendering. Values are illustrative; production values must come from the effective policy.

### 4.2 Workers and the model picker

Show a Helper card followed by an optional Reviewer card. Each displays profile name, canonical model ID, execution destination, readiness, and a single Change action. Expanded details show context/output capability, supported tools, runtime backend, recent relevant benchmark information, and unavailable reasons.

The searchable picker has filters **All / On device / Private / Cloud**. Disabled candidates remain discoverable with an explanation: not installed, connection unavailable, primary-role conflict, reviewer conflict, policy denied, or unsupported capability. Opening the picker does not load a large local model or initiate a paid generation.

Selection is an explicit type: **Inherit**, **Automatic from approved pool**, or **Specific profile**. Do not use an empty string for all three meanings. An unavailable specific profile does not silently become Automatic. Offer “Choose another helper” or a previously approved fallback.

Model identity must use provider identity, normalized model ID, and a resolved alias where available; keep deployment identity separate for endpoint/runtime capacity. Do not assume two arbitrary model strings identify independent models. Unknown independence must be reported honestly. The reviewer cannot be the selected primary or helper model under the configured independence rule.

### 4.3 Fallback editor

Offer **No fallback**, **Ask me**, and **Approved order**. The last option exposes a reorderable list with accessible Move up/Move down actions. Automatic selection within a private-only pool is allowed only when the user chose that mode. Crossing from on-device/private processing to a cloud provider requires an existing applicable grant or a new consent decision.

Keep a blocked profile's assignment while showing its status. Do not erase it because a connection is temporarily unavailable. “Check readiness” distinguishes a local metadata check from a connection probe and from a billable sample; the latter requires an explicit start.

### 4.4 Reviewer controls

Use review choices **Off**, **Requested tasks**, and **Required for delegate-led preparation**. Preserve the legacy full-delegation requirement during migration. Missing or failed required review results in an explicit blocked/partial/unreviewed path according to the saved recovery policy; never promote it to a successful review.

Move score threshold, review timeout, repair attempts, and correction policy into the Review editor. Show the assessment's issues and evidence coverage before its numerical score. Display “Reviewer assessment: 78/100” rather than “78% accurate”. Separate corrected evidence from the original and retain provenance for both.

### 4.5 Conversation sheet and profile overrides

The compact sheet uses the same state model as global settings. Its first row shows **Inherit settings / Custom for this chat**. Override rows display both the chosen value and the inherited source. “Use defaults” clears the override rather than copying today's values into a permanent custom object.

Show an explicit note when the global master or profile participation prevents enabling delegation. A chat toggle cannot bypass either. An override can deny cloud helpers even when global defaults permit them. Selecting a cloud model alone never changes consent.

When a run is active, ordinary edits display **Applies to the next response**. Stop/revoke controls act immediately and are visually separate from saved configuration edits.

### 4.6 Activity and run detail

Collapsed activity shows the phase, helper identity, elapsed wall time, completed tasks, and whether the run is local, private-server, or cloud-assisted. Expanded detail shows queued, loading, first-output, tool work, review, handoff, and final-writing stages when applicable.

```text
Delegation activity                 Running
Helper: My local profile · On device

Done      Read repository source       2.1s
Running   Extract relevant changes     4.8s
Queued    Review evidence

Evidence  8 retained items · 1 gap
Usage     1,240 input · output pending

[ Continue with primary ]
Stop entire response
```

“Continue with primary” cancels remaining helper work, retains completed authorized evidence, and prevents another helper call for that run. “Stop entire response” uses the existing parent-run cancellation path. Pause means stop admitting new steps at a safe boundary; do not claim that every provider can suspend an in-flight request.

Run detail includes a typed reason for partial/failure/cancellation, source references, role-separated usage, route changes, and a sanitized export. Default activity shows task summaries and events, not an invented reasoning transcript. Preserve existing optional model-supplied reasoning/output controls without making them a prerequisite for diagnostics. [R11]

### 4.7 Advanced controls and visual system

Group Advanced into **Budgets**, **Research**, **Recovery**, and **Privacy & retention**. Use exact numeric inputs with validation and increment/decrement controls for hard limits. Sliders are appropriate for preference scales, not the only way to select 6,000 tokens or a precise deadline.

Show each constrained value as “Requested → Effective”, with a short reason such as “Limited by helper profile”. Reject internally impossible combinations before saving. Changing a recommendation must not silently raise an explicit user ceiling.

Use neutral Material surfaces and the existing theme roles; reserve strong accent colour for selection and the primary action. Proposed spacing tokens are 8/12/16/24 dp, with a 16 dp compact-screen gutter and consistent card shapes. Reuse the existing settings/theme abstractions after checking their APIs; do not hardcode an independent colour system in this feature.

Use minimum 48 dp interactive targets and meaningful semantics. Android's Compose accessibility defaults support this sizing direction. [S04] Test TalkBack, keyboard navigation, RTL, 200% font scale, long translated labels, dark/light themes, and landscape. Status must have text/icon meaning in addition to colour. Expanded layouts use list-detail behaviour when the available window supports it; compact layouts preserve a single clear back stack. [S03]

### 4.8 Required states and editing rules

| State | Required presentation |
|---|---|
| Loading saved settings | Skeleton/Loading; do not briefly present default-enabled or default-denied as saved truth. |
| No helper profiles | Explain prerequisites; link to AI profiles or local model setup. |
| Missing pinned profile | Preserve missing reference; offer repair, not silent substitution. |
| Invalid configuration | Field-level error and explanation; disable Apply for the invalid draft. |
| Saving / failed save | Show pending state; retain the draft and offer Retry. |
| Offline / runtime busy | Distinguish waiting for capacity from connection failure. |
| Permission denied | Name the scope that denies access; do not repeatedly prompt. |
| Partial / review unavailable | Retain useful evidence and clearly identify the missing work. |
| Interrupted after restart | Offer eligible recovery; never automatically replay uncertain writes. |

Simple switches may save immediately through the atomic command path. Multi-field editors use a local draft and Apply/Cancel. Changes must not disappear because an unrelated save is busy. Reset presents a diff and scope; by default, restore tuning only and preserve roles, consent, and enabled state.

## 5. Feature upgrade catalogue

The core release includes the following capabilities. Items already partly implemented are extensions, not replacements.

| Requirement | Capability | Priority |
|---|---|---|
| F01 | Shared policy resolver and identical eligibility reasons across global/profile/chat UI and runtime. | P0 |
| F02 | Explicit per-scope cloud denial, model-selection semantics, and global/profile master enforcement. | P0 |
| F03 | Atomic settings edits, versioned migration, save feedback, and safe reset preview. | P0 |
| F04 | Immutable run policy plus live stop/revocation checks before every new dispatch. | P0 |
| F05 | Clean Overview/Workers/Activity navigation and reusable scoped editor. | P1 |
| F06 | No-call plan preview showing roles, tools, destinations, budgets, and blocking reasons. | P1 |
| F07 | Role-aware helper templates: Research, Document extraction, Read-only repository analysis, and Structured comparison. | P1 |
| F08 | An approved worker pool, optional per-task role assignment, and explicit fallback order. | P1 |
| F09 | Bounded parallel read-only tasks, with local-runtime capacity shared across chats and benchmarks. | P1 |
| F10 | Typed activity records, stage timings, retained evidence, and role-separated accounting. | P1 |
| F11 | Continue-with-primary, safe-boundary pause, and resumable eligible read-only steps. | P1 |
| F12 | Reviewer issue/coverage presentation, uncertainty labels, and evidence-preserving corrections. | P1 |
| F13 | Capability/readiness inspector with explicit metadata/probe/sample distinctions. | P1 |
| F14 | Relevant benchmark provenance, pinned-worker tests, and optional direct-vs-delegated comparisons. | P1 |
| F15 | Versioned, secret-free template export/import, with a validation preview before applying. | P1 |
| F16 | Optional policy recommendations from measured local history; never auto-enable permissions or paid destinations. | P2 |

Task templates express allowed operations, output schema, review preference, and budgets. They do not embed credentials, install tools, grant permissions, or require users to create several helpers for normal chat. A single helper remains the default configuration. Multi-helper work is an optional expansion inside Workers, not a separate mandatory setup flow.

## 6. Target architecture and integration boundaries

The repository uses Kotlin, Compose, ViewModels, repositories, and Hilt. Preserve those conventions. [R14] State-hoisting guidance supports separating immutable UI state and user events from business logic; local editor-only state can remain local. [S02]

```text
Overview / Workers / Chat sheet / Profile editor
                  |
     DelegationViewModel + typed commands
                  |
      SettingRepository atomic mutation
                  |
          DelegationPolicyResolver
                  |
ChatRepository: admit one immutable turn policy
                  |
      DelegationEngine compatibility facade
                  |
     Existing LocalDelegationCoordinator
                  |
    Existing AgentRunner + provider adapters
                  |
 Authorized tools / runtime capacity / budgets
                  |
 Existing ledger, research journal, run persistence
                  |
        Typed activity projection for UI
```

`DelegationViewModel`, `DelegationPolicyResolver`, and `DelegationEngine` are **proposed components**. Introduce the engine as a small facade around existing coordination first. Do not create a second set of provider clients, foreground services, tool approval prompts, token ledgers, or independent retry loops.

`AgentRunCoordinator` remains the lifecycle owner for ongoing parent runs. Leaving the delegation screen must not cancel chat generation. `ChatRepositoryImpl` remains the integration point while policy calculation, task admission, and event projection are extracted incrementally. Existing provider adapters, useful-output recovery, synthesis-safe routing, and native GitHub task routing remain in place. [R08][R09]

## 7. Policy precedence and control semantics

### 7.1 Enablement

Resolve enablement in this order: global master eligibility, primary-profile participation, conversation preference, per-run recovery state, and live revocation. A conversation or template may restrict the global/profile policy but cannot bypass it. Missing legacy profile participation preserves its existing disabled meaning until explicitly changed. [R12]

The global “enabled” switch is a master gate. Turning it off during a run stops further helper admission and requests cancellation of active helper work, while the parent follows the chosen primary-only recovery policy. Do not silently cancel the user's entire response unless they chose that action.

### 7.2 Permissions are not ordinary preferences

Use `INHERIT`, `ALLOW`, and `DENY` for scoped destination preferences. An ALLOW is effective only with the required global capability, current authorization, and an applicable user grant. A narrower-scope DENY wins. Role selection, presets, restored templates, and model recommendations cannot grant access.

Keep cloud-worker permission, memory cloud recall, tool execution authorization, and sensitive-data export distinct. A private server may still forward data to a cloud model; where that route cannot be established, label the trust/forwarding status unknown and require explicit approval under the configured privacy policy.

### 7.3 Selection and fallback

Represent selection as Inherit, Automatic, or Fixed(profile UID). Inherit resolves from the next broader scope; Automatic selects only from the approved pool. Fixed must remain fixed unless a permitted fallback decision is made. A different model with the same display name is not a valid identity match.

Automatic ranking is deterministic: eligibility first, required capability second, explicit user ordering/locality preference next, then relevant health and measured task performance, with a stable identity tie-break. Unknown data must not become a perfect score. A generic chat benchmark must not be represented as a verified delegation benchmark. [R03]

### 7.4 Ordinary settings versus live controls

Snapshot workload, role assignment, context/output budgets, template version, and provider/model configuration revisions once at parent admission. Later ordinary edits apply to the next turn. Live revocation, global disable, endpoint removal, security-policy restriction, and user stop are checked before each new dispatch and before reusing cached sensitive results.

A revoked permission does not retroactively retrieve already transmitted data. The UI must distinguish “No more requests will be sent” from “The provider has stopped processing”.

### 7.5 Defaults and boundary conditions

For migration, preserve the user's existing effective values rather than applying a new universal preset. For a fresh V2 configuration, delegation remains off, cloud helpers are denied, no fixed helper is chosen, and review is off until required or selected. Use Balanced breadth (70), the legacy preparation preference (80, shown as Custom), one local inference slot, and the existing validated budget defaults as the starting proposal. These defaults are not automatically written over existing installations.

A preparation amount of zero admits no helper preparation. At 100, preserve required reviewed preparation and expose a missing-reviewer state. A zero remaining parent tool allowance permits no tool dispatch, even if a child profile has a larger allowance. An empty approved pool is blocked, not an invitation to select any enabled provider. Selecting a fallback that changes privacy or cost boundaries must be validated again.

## 8. Proposed contracts and persistence model

The following are illustrative contracts, not a claim that these types already exist or compile unchanged against main.

```kotlin
enum class ScopedPermission { INHERIT, ALLOW, DENY }

sealed interface WorkerSelection {
    data object Inherit : WorkerSelection
    data object Automatic : WorkerSelection
    data class Fixed(val profileUid: String) : WorkerSelection
}

data class PolicyReason(
    val code: String,
    val field: String?,
    val sourceScope: String
)

sealed interface DelegationDecision {
    data class Ready(
        val policy: EffectiveDelegationPolicy
    ) : DelegationDecision
    data class Blocked(
        val reasons: List<PolicyReason>
    ) : DelegationDecision
}
```

Implement reason codes as stable, typed values with localized presentation, not raw exception strings. `EffectiveDelegationPolicy` should contain a schema/policy version, selected role identities, allowed destinations, tool-authorization reference, explicit caps, effective caps with provenance, fallback policy, and revision identifiers. Store credential references only, never resolved secrets.

A `DelegationPlan` contains its parent run ID, immutable policy revision, typed steps, dependencies, evidence references, and a plan version. A `DelegationStep` contains a stable step ID, role, required capabilities, operation risk, selected deployment, input-reference IDs, budget reservation, and attempt history. Reject dependency cycles before execution. Initial templates should use simple sequential or fan-out/fan-in plans, not unrestricted model-created recursion.

### 8.1 Atomic settings API

Add a transform-based repository API that mutates the **latest persisted** `AppFeatureSettings` in one transaction. If the current backing store is Preferences DataStore with serialized JSON, decode, transform, validate, and encode inside the existing store's `edit`/`updateData` transaction. A ViewModel mutex around an earlier read is insufficient across multiple callers. DataStore documents transactional read-modify-write semantics. [S01]

All writers of the same feature-settings object must use the shared mutation boundary, including delegation, token budgets, profile settings, and chat overrides. Preserve unrelated fields. Validation returns typed errors without persisting a half-valid object. Do not perform network calls or model loading inside a storage transaction.

Use one observable settings revision for success confirmation. Coalesce repeated edits to the same field only when the final accepted value is retained; never discard a different setting's command. Preserve draft state through rotation, and report conflicts when a field changed externally during a multi-field edit.

### 8.2 Run records

Keep small configuration in the existing settings store. Persist relational step/run/evidence references through the existing Room/run-persistence infrastructure, not a growing JSON array in preferences. This follows the documented distinction between small settings storage and relational datasets. [S01]

Extend existing records where appropriate; introduce new tables only when their lifecycle or cardinality requires it. Required fields include run/step/attempt IDs, policy version, role and deployment identity, transition timestamps, status/reason, output/evidence references, usage provenance, and approval references. Match any Room migration to the actual schema at implementation time; do not assume the AGENTS overview's version is the latest schema.

## 9. Runtime scheduling, budgets, and recovery

### 9.1 One turn, one preparation authority

Create one admitted delegation context per parent run. Automatic preparation, explicit `delegate_to_model`, Local Assist result selection, reviewer work, and final synthesis must share its identity, budgets, and permission authority. Avoid starting automatic preparation twice when a model also requests the delegation tool.

Share the existing local inference capacity across chats, benchmarks, helpers, reviewers, and Local Assist. Use one on-device inference slot unless the selected runtime's implementation explicitly supports independent concurrent sessions and passes testing. Network/page-fetch concurrency is a separate resource from model-inference concurrency.

Do not remove current protections against conflicting local primary/helper execution merely to make every profile selectable. Sequential handoff or explicitly supported resource switching is required before relaxing that restriction. [R06]

### 9.2 Budget contract

For every dispatch, reserve from the parent budget atomically. Effective allowance is the minimum of applicable explicit user caps, profile/provider capability, conversation restriction, remaining parent allowance, and available context after required overhead. Every limiting source is visible in the preview and trace.

Input budgeting must account for system instructions, tool schemas, attachments, replay, output reserve, and any provider-specific accounting rules. Do not equate raw characters with exact tokens. Use known tokenizers where supported and label conservative estimates otherwise. Existing token/byte budgets remain enforced through their current owners rather than copied counters.

Research breadth is a target, not permission to ignore tool or provider limits. Show requested sources versus attempted/read/retained sources and a stop reason. Keep research acquisition separate from helper-input/handoff budgets: the helper may process more evidence in bounded chunks without sending all raw pages into one model request.

The upgrade must integrate with the existing Deep Research policy instead of creating another source-count slider with contradictory behavior. A delegation workload setting must not silently override a separately configured acquisition mode. Provider limits, user spending limits, device capacity, and cancellation still apply.

### 9.3 No hidden ceiling increases

Replace ownership floors with explicit preset-derived recommendations or minimum-required validation. If the user explicitly sets a smaller cap than required, report that the configuration cannot support the selected operation; do not silently increase the cap. During migration, preserve the previous effective allowance as a disclosed compatibility value so behavior does not change accidentally.

Reserve final-writing capacity deliberately. Helper output caps and review output caps do not replace the primary profile's final-answer setting. Preserve the existing same-cap reasoning-only recovery and cap-mismatch detection while exposing their effects clearly. [R08]

### 9.4 Deadlines and progress

Use a monotonic clock for elapsed/deadline decisions. Record queue, runtime loading, first meaningful progress, idle progress, and total preparation separately. A child deadline cannot extend the parent deadline, and retries consume the same parent time allowance.

Coroutine timeouts are cooperative; cancellation must propagate through provider, native-runtime, and tool adapters, with resources released in cleanup. [S05] Where MCP cancellation applies, send it for the correct active request and negotiated implementation; MCP cancellation itself is best effort. [S06] Do not claim that closing a stream guarantees an external provider stopped work or billing.

A transport heartbeat is not evidence acquisition or model output. Preserve current explicit tool-progress handling and distinguish waiting/loading from a genuinely stalled stream. [R08] Local-native cancellation that has not acknowledged completion must keep the runtime slot occupied until it is safe to reuse.

### 9.5 Failure policy

| Failure | Required handling |
|---|---|
| Missing model / invalid configuration / denied permission | Stop that route; offer repair or approved fallback. No repeated identical retry. |
| Authentication or exhausted quota | Explain and block; do not repeatedly charge/probe another provider without permission. |
| Rate limit / transient transport failure | Bounded backoff with jitter and provider hints, within the shared deadline. |
| Empty or malformed output | Classify explicitly; permit a bounded schema/finalization repair when policy allows. |
| Reasoning-only completion | Preserve existing bounded final-answer repair; record waste separately. |
| Useful partial output | Retain evidence with incomplete status; do not declare full task success. |
| Review unavailable / rejected | Retain provenance, mark unreviewed/rejected, and follow explicit primary recovery policy. |
| Uncertain side-effect result | Mark outcome unknown; reconcile externally or request a decision. Never blind-retry. |
| User cancellation | Stop admission, propagate cancellation, retain permitted completed evidence, and terminalize once. |

Existing readiness caches and failure classes should feed this policy. Ensure cache keys invalidate when installation, credentials, endpoint, capability, or relevant policy revisions change. Avoid logging raw credentials or deriving exported identifiers from secret values.

## 10. Tools, permissions, privacy, and evidence

### 10.1 One authorized tool path

Each helper receives only the intersection of parent-authorized tools, profile restrictions, conversation permissions, role/template permissions, current consent, and available execution owners. The helper cannot acquire a tool merely by naming it. Keep gateway/client ownership metadata through every wrapper and retain existing synthesis-safe and repository-specific routing. [R07][R08]

Reviewer tasks default to evidence-only, without tool writes or nested delegation. Expanding review to additional source reads is a separate, explicit feature with its own authorized budget, not an automatic consequence of a score threshold.

Before increasing worker-to-worker delegation depth, require cycle detection, ancestor identity checks, parent budget propagation, and role permissions. Preserve the existing limited-depth behavior as a compatibility setting, but do not expose unrestricted recursion in templates.

### 10.2 Read reuse and side effects

Extend `SharedToolCallBroker` rather than adding another cache. Confirm that its supplied scope/tool identity includes the relevant connection and authorization partition; add policy/credential-reference revisions where required. This is a verification requirement, not a claim that the current cache leaks data. Recheck live permission before reuse. Share only operations independently classified as read-only. [R07]

For writes, use a dispatch ledger with a stable operation identity and explicit approval tied to the exact destination and arguments. Reuse provider idempotency keys only when supported. If a connection fails after sending a write, external execution may be unknown; local deduplication cannot guarantee exactly-once behavior outside the app.

### 10.3 Data boundaries

Treat source pages, tool results, repository content, memories, and helper outputs as untrusted data rather than instructions granting authority. Instructions embedded in retrieved content cannot change routing, consent, or tool scope. Retain source IDs and quoted evidence references across compaction and review.

Do not remove the existing `excludesMemory()` checks from delegation eligibility without identifying the underlying privacy boundary. Separate “no memory recall” from “no sharing” only with migration and tests that preserve the original intent. [R01][R06]

Cloud memory recall remains owned by the memory subsystem. Delegation may show a linked summary and the applicable destination policy, but cannot turn recall on through a workload preset. Temporary conversations must not begin durable delegation/evidence capture merely because Activity was enabled. Preserve existing temporary-scope behavior and test it at every persistence boundary. [R08][R13]

### 10.4 Evidence contract

A handoff includes the task requirements, retained findings, source references, material contradictions, omitted/unread material, completeness state, and review state. Keep structured evidence outside the prose summary so a shorter brief does not erase source provenance.

A model-generated sufficiency score is a heuristic, not an objective completeness guarantee. Display which requested requirements have supporting evidence and which remain open. Do not convert unavailable review or a stopped research run into a verified final handoff through wording alone.

## 11. Settings-to-runtime wiring matrix

Every release control must have a testable path through persistence, policy resolution, runtime behavior, and a trace field. A visible control without that path is not complete.

| Control | Persistence / command | Runtime effect | Evidence / test |
|---|---|---|---|
| Global enabled | Global policy mutation | Final enablement gate; live off blocks helper admission | Effective gate source; T01/T10 |
| Profile participation | Profile-scoped mutation | Allows participation only under global gate | Profile reason; T01 |
| Chat inherit/custom | Nullable typed override | Resolve scope without copying defaults | Scope provenance; T02 |
| Helper selection | Inherit/Automatic/Fixed | Shared eligibility and deterministic selection | Selected identity; T04/T05 |
| Cloud helpers | Scoped permission plus grant | Destination restriction at dispatch and reuse | Consent decision; T02/T03 |
| Workload preset | Preset-owned breadth fields | Research target only; no permission changes | Changed-field diff; T06 |
| Preparation preference | Legacy-compatible preference | Preparation strategy, not literal token share | Plan strategy; T06 |
| Reviewer | Role/policy configuration | Review requirement and evidence state | Reviewer identity/verdict; T09 |
| Fallback order | Approved candidate list | Switch only on eligible failure and consent | Route-change reason; T04/T13 |
| Input/output/call caps | Validated explicit limits | Atomic parent reservation and request constraints | Requested/effective cap; T07/T11 |
| Research integration | Existing research policy reference | One source-acquisition policy and ledger | Stop reason/source counts; T11 |
| Continue with primary | Run control, not saved defaults | Cancel helpers; retain evidence; forbid re-delegation | Run transition; T10 |
| Stop response | Existing parent cancel command | Cancel the entire run tree | Terminal status; T10 |
| Local Assist | Existing feature plus handling marker | Share capacity; avoid repeated selection/compaction | Processing lineage; T08 |
| Reset/import | Validated diff transaction | Next-run configuration only | Revision/diff; T06/T16 |
| Retention/export | Scoped privacy configuration | Redaction, purge, and secret-free export | Export/purge tests; T15 |

## 12. Activity records and benchmark design

### 12.1 Typed observability

Add typed events such as PolicyResolved, StepQueued, RuntimeLoading, RequestStarted, ToolStarted, EvidenceRetained, ReviewFinished, FallbackChosen, StepFinished, and RunTerminal. UI labels are derived from these events; avoid parsing English log lines or strings such as `[UNVERIFIED]` as the long-term state model.

Keep compatibility translation at the old coordinator boundary while gradually replacing string-marked outcomes with structured results. Redacted human-readable logs remain useful, but are not the authoritative database.

### 12.2 Metrics and truthful labels

Report wall-clock duration separately from the sum of concurrent step durations. Separate queue/loading/prefill/output/tool/review timings when observable. Token fields carry **provider-reported**, **estimated**, or **unavailable** provenance; unavailable is not zero. Reconcile cumulative and delta usage through the existing accounting path, including retries and canceled work. [R08]

Use separate identities for a profile alias, canonical model, and deployment/runtime. Aggregate a model's usage only after maintaining its per-role and per-deployment contributions. “Helpful work” should mean retained evidence or accepted structured output, not simply an HTTP success.

Never display “tokens saved” from input reduction alone as verified net savings. A meaningful comparison must include the primary, all helpers, reviewers, repairs, tool charges where known, and comparable answer quality. Pricing estimates need provider/model/rate timestamp and an Unknown state.

### 12.3 Benchmarks

Link from Workers to the existing benchmark area with a delegation-focused configuration. Freeze the selected primary/helper/reviewer identities and policy revision for the test. Do not substitute another helper during a scored run; record the failure against the intended configuration. Show sample count, task class, cold/warm state, device/backend, run date, and rubric version.

Add an optional paired comparison: primary-only versus the configured delegated plan, using the same task/fixtures and a declared evaluation rubric. Compare completeness, factual/source support, usable output, latency, total accounted tokens, and known cost. Repeat enough samples to show variability rather than selecting one flattering best run.

Keep deterministic fixture tests separate from live provider/device benchmarks. Opening Workers or Activity does not trigger a benchmark. Publishing community results remains opt-in and must omit prompts, secrets, private endpoints, and private evidence.

## 13. Migration, backup, and rollback

Introduce an explicit delegation schema version. Read legacy fields only when a V2 policy is absent. A newer explicit DENY must never be reinterpreted through the legacy `allowRemoteWorkers || !localPlatformsOnly` expression.

| Legacy data | Migration rule |
|---|---|
| Global enabled and profile participation | Preserve both values and their restrictive relationship. |
| Inverse `processingOwnership` amount | Preserve exact value; derive Overview label without rounding persisted intent. |
| Full delegation / required reviewer | Preserve review requirement; surface missing reviewer rather than granting a new route. |
| Remote-worker legacy flags | Resolve previous effective consent once; preserve scope and require fresh grants for new destinations. |
| Blank helper reference | Determine legacy inherited/automatic meaning using scope; migrate to an explicit type. |
| Missing fixed profile | Preserve unresolved UID with a repair state; never choose a new model automatically. |
| Effective allowance floors | Store disclosed compatibility-effective values; allow user review, not silent restriction/increase. |
| Review settings and fallback | Preserve exact supported values and ordered semantics; show migration notes for ambiguity. |
| Unknown future enum / malformed policy | Fail closed for permissions, retain recoverable original data, and report a repair action. |

Migration must be deterministic and idempotent, with a version marker committed in the same transaction as the migrated configuration. Preserve unrelated feature settings and all benchmark/history data. Backups include policy version, templates, assignments, and optional sanitized run summaries; credentials remain in the existing secure path, not templates.

For imported data, present a preview that distinguishes matched profiles, unresolved references, permission changes, and unsupported fields. Import does not automatically enable cloud access or execute a plan. Apply validated settings atomically.

Use a feature flag to roll back the new presentation/engine adapter independently of the stored user policy. Any legacy compatibility adapter must preserve new denials. Do not project restrictive V2 settings into a more permissive old representation. Database migrations must not use destructive fallback to make the release pass.

## 14. Source-level implementation map

`K/` below means `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`. Existing files were inspected unless marked as an integration boundary requiring implementation-time confirmation.

| Existing location | Proposed responsibility/change |
|---|---|
| `K/presentation/ui/setting/ModelDelegationSettingsPanel.kt` | Compatibility host forwarding into the new scoped screen; remove duplicated business-policy filtering. |
| `K/presentation/ui/setting/ConversationDelegationCard.kt` | Compact shared editor; explicit inheritance and consent, not selection-based grants. |
| `K/presentation/ui/setting/LocalToolsViewModel.kt` | Migrate delegation commands to a dedicated VM and atomic repository API; retain unrelated local-tool duties. |
| `K/data/model/ModelDelegationSettings.kt` | Legacy reader plus V2 conversion; remove hidden policy mutation from new normalization. |
| `K/data/model/ConversationDelegationSettings.kt` | Typed nullable overrides, tri-state destination preference, explicit selection mode. |
| `K/data/model/ProfileBehaviorSettings.kt` | Preserve participation gate; resolve profile overrides through the shared resolver. |
| `K/data/model/AppFeatureSettings.kt` | Versioned V2 policy and references; preserve unrelated settings and old backup compatibility. |
| `K/data/repository/SettingRepositoryImpl.kt` | Transactional mutation facade; connect to actual DataStore mutation boundary. |
| `K/data/repository/ChatRepositoryImpl.kt` | Admit policy snapshot, pass scoped execution context, adapt typed events and outcomes. |
| `K/data/agent/tool/LocalDelegationCoordinator.kt` | Reuse scheduling/review/recovery logic behind facade; consume immutable policy and live controls. |
| `K/data/agent/tool/SharedToolCallBroker.kt` | Verify authorization partitioning; retain read-only sharing and cancellation behavior. |
| `K/data/agent/AgentRunCoordinator.kt` | Continue to own run lifecycle; add narrowly scoped helper-stop controls if needed. |
| `K/data/assist/AssistCoordinator.kt` | Share capacity and lineage markers; preserve exact-record selection and retained originals. |
| Existing provider adapters, approval manager, budget/ledger, journal, Room DAOs, navigation, backup | Extend contracts only where needed; inspect exact implementations and migration versions before editing. |

### 14.1 Proposed new files

Under `K/presentation/ui/delegation/`, add `DelegationScreen.kt`, `DelegationViewModel.kt`, `DelegationUiState.kt`, `DelegationOverview.kt`, `DelegationWorkers.kt`, `DelegationActivity.kt`, and reusable scoped picker/editor components. Keep files small enough to test and preview independently; do not replace one oversized panel with one oversized screen.

Under a new `K/data/delegation/` package, add `DelegationPolicyResolver.kt`, `DelegationEligibility.kt`, `DelegationPolicyV2.kt`, `DelegationPolicyMigration.kt`, `DelegationEngine.kt`, `DelegationPlan.kt`, and `DelegationEvent.kt`. These are proposed names, not claims about existing repository files. Use Hilt bindings and repository conventions rather than a new parallel dependency framework.

Add unit tests under `app/src/test/kotlin/` and Compose/device tests under `app/src/androidTest/kotlin/`, following the repository's existing package conventions. New run/evidence persistence classes must be selected after inspecting the current DAOs and schema; avoid speculative schema numbers in implementation commits.

## 15. Verification plan and acceptance tests

Write characterization tests for existing behavior before changing the resolver or coordinator. The following suite is mandatory; the test names are proposed.

| ID | Scenario | Required result |
|---|---|---|
| T01 | Global off or profile participation off; chat override enabled | No helper dispatch; UI and runtime report the same gating source. |
| T02 | Global cloud allowed; chat cloud denied; restored V2 deny | No cloud helper, fallback, or shared-result path bypasses the denial. |
| T03 | Choose a cloud profile without a grant | Selection is saved separately; execution waits for consent or remains blocked. |
| T04 | Fixed helper deleted, offline, or failed; fallback modes varied | No silent substitution; only explicitly approved fallback is used. |
| T05 | Primary/helper/reviewer conflicts and unknown model aliases | Shared picker/runtime eligibility; no falsely independent review claim. |
| T06 | Rapid edits, concurrent screens, reset, presets, template import | No accepted edit lost; unrelated fields preserved; scope/diff accurate. |
| T07 | Tiny input cap, overhead growth, output cap mismatch | Reject impossible setup or fit within effective cap; never secretly raise it. |
| T08 | Two chats, a benchmark, and Local Assist compete for one local runtime | Capacity respected; no duplicate processing; cancellation releases resources safely. |
| T09 | Malformed review, missing reviewer, rejected draft, correction loop | Bounded recovery; explicit unreviewed/rejected status; evidence provenance retained. |
| T10 | Continue-primary, stop-all, global-off, revoke while queued/in-flight | No later unauthorized dispatch; correct parent/helper terminal transitions. |
| T11 | Concurrent budget reservations; research fan-out; exhausted tool allowance | No overspend by local counters; one acquisition policy; exact stop reason. |
| T12 | Valid output followed by disconnect; useful truncated result | Retain useful evidence without declaring incomplete work fully complete. |
| T13 | 429, timeout, auth failure, quota exhausted, repeated identical errors | Failure-specific bounded recovery; no inappropriate identical retries. |
| T14 | Write sent, connection lost, app restarts | Outcome unknown/reconciliation state; no automatic duplicate side effect. |
| T15 | Temporary chat, redaction, retention expiry, export | No unintended durable sensitive records; no secrets in export or logs. |
| T16 | Old backups, unresolved UIDs, malformed/future policy, repeat migration | Idempotent conversion, no consent expansion, no unrelated data loss. |
| T17 | Screen rotation, navigation away, process death | Drafts/selection recover; app-owned runs are not tied to screen lifetime. |
| T18 | Paired benchmark with an unavailable chosen helper | Report that configuration's failure, not another model's success. |
| T19 | Long localization, RTL, TalkBack, keyboard, 200% font scale | No clipped controls; meaningful labels, focus order, and accessible actions. |
| T20 | Permission changes between planning and actual tool dispatch/reuse | Revalidate live restrictions; stale authorization cannot authorize execution. |

### 15.1 Build and device gates

First inspect `./gradlew :app:tasks --all` and the current build/workflow definitions to select the actual supported variants. The repository guidance documents `assembleDebug`, `test`, and `connectedAndroidTest`; these are verification starting points, not claims that they were run here. Run the repository's configured ktlint and relevant JVM/instrumentation tasks, then build each affected local-runtime/provider variant. [R14]

Device coverage must include the user's ROG Phone 9 Pro configuration when available, another lower-memory Android device/emulator for UI/lifecycle cases, local runtime unavailable states, a private-server helper, and at least two supported remote provider adapters using test accounts/fixtures as appropriate. No device-specific speed improvement is asserted before those runs.

Test screen opening without model loading or inference. Measure jank, settings-load latency, peak memory, helper queue behavior, and cancellation acknowledgement. Set performance thresholds against a recorded baseline on the same device rather than inventing a universal throughput target.

## 16. Delivery sequence and release gates

| Stage | Work package | Exit gate |
|---|---|---|
| 0 · Baseline | Confirm current main, navigation/storage boundaries, dependency variants; add characterization fixtures. | Findings reproducible in tests or explicitly downgraded to risks. |
| 1 · Correctness | Atomic writes, V2 migration, scope precedence, eligibility, consent separation. | T01–T07, T16, T20 pass; no UI-only permission fixes. |
| 2 · Interface | Shared Overview/Workers/Activity, compact chat sheet, numeric editors, scoped reset. | UI previews, save/error states, navigation and T19 pass. |
| 3 · Execution | Snapshot admission, facade integration, typed events, shared capacity, explicit fallback. | T08–T14, T17 pass; existing provider/research regression tests remain green. |
| 4 · Features | Templates, approved pools, read-only plans, benchmark provenance/comparison, import/export. | F07–F15 wired end-to-end; T15/T18 pass. |
| 5 · Release | Device matrix, migration rehearsal, privacy review, docs, staged enablement. | Every required build/check passes and rollback preserves restrictive policies. |

Do not merge a cosmetic screen replacement and call the upgrade complete while the old permission and settings semantics remain underneath. Conversely, keep stages independently reviewable: one coherent policy/persistence change, one reusable UI change, and one execution integration change are easier to verify than a single broad rewrite.

Use a documentation-only branch for this specification. Subsequent implementation branches should refresh against current main and record any deviations from the pinned baseline. No automatic merge, version increase, or application release is part of this documentation deliverable.

## 17. Definition of done

The complete application upgrade is done only when all P0/P1 requirements have implemented owners, each control in the wiring matrix has an automated or documented device test, existing settings survive migration, and the new UI/runtime agree on every effective policy decision.

A completed release also requires readable error/recovery states, no unauthorized destination changes, bounded resource use, reliable cancellation/continuation behavior, accurate review/completeness labels, and secret-free diagnostics. Claims about savings or quality must be backed by comparable benchmark evidence. Optional P2 recommendations may remain disabled without blocking the core release.

**This document completes the design specification, not those implementation or testing gates.**

## 18. Sources and audit traceability

All repository references below are pinned to the audited commit. The audit read complete small configuration/UI files and selected integration/coordinator ranges; it did not exhaustively read every provider, database migration, navigation host, or gateway implementation. External references establish narrow platform guidance, not proof that a proposed architecture is already present in this app.

### 18.1 Repository references

**R01 · Global delegation UI and controls.** [presentation/ui/setting/ModelDelegationSettingsPanel.kt][R01]  
Read lines 1–560; the file ended within that range.

**R02 · Legacy policy, normalization, allowance floors and endpoint classification.** [data/model/ModelDelegationSettings.kt][R02]  
Complete file.

**R03 · Settings writes, reset behavior and benchmark-derived scores.** [presentation/ui/setting/LocalToolsViewModel.kt][R03]  
Complete file.

**R04 · Conversation editor and selection-based remote flag.** [presentation/ui/setting/ConversationDelegationCard.kt][R04]  
Complete file.

**R05 · Conversation override application.** [data/model/ConversationDelegationSettings.kt][R05]  
Complete file.

**R06 · Worker eligibility, readiness, fallback and review coordination.** [data/agent/tool/LocalDelegationCoordinator.kt][R06]  
Selected ranges 1–310 and 345–535.

**R07 · Read-only tool sharing and cancellation behavior.** [data/agent/tool/SharedToolCallBroker.kt][R07]  
Complete file.

**R08 · Parent/child execution integration, recovery and accounting.** [data/repository/ChatRepositoryImpl.kt][R08]  
Selected ranges 1–240, 850–1090 and 1240–1490.

**R09 · Application-owned runs and foreground cancellation.** [data/agent/AgentRunCoordinator.kt][R09]  
Selected range 1–285.

**R10 · Feature-settings repository boundary.** [data/repository/SettingRepositoryImpl.kt][R10]  
Selected range 1–220; underlying DataStore implementation not inspected.

**R11 · Feature, diagnostic and conversation settings.** [data/model/AppFeatureSettings.kt][R11]  
Selected range 1–210.

**R12 · Profile participation and delegation defaults.** [data/model/ProfileBehaviorSettings.kt][R12]  
Complete file.

**R13 · Existing warm local record selector.** [data/assist/AssistCoordinator.kt][R13]  
Complete file.

**R14 · Repository development conventions.** [AGENTS.md][R14]  
Complete root file.

### 18.2 Official implementation guidance

**S01 · [Android Developers — DataStore][S01].** Atomic settings updates and the boundary between small settings and relational data.

**S02 · [Android Developers — Where to hoist state][S02].** UI state ownership and business-logic separation.

**S03 · [Android Developers — Build a list-detail layout][S03].** Adaptive list-detail presentation guidance.

**S04 · [Android Developers — Compose accessibility API defaults][S04].** Touch-target sizing and accessible component semantics.

**S05 · [Kotlin coroutines — withTimeout][S05].** Cooperative timeout/cancellation and cleanup considerations.

**S06 · [Model Context Protocol — Cancellation, 2025-11-25 specification][S06].** Best-effort protocol cancellation; not a claim about the app’s negotiated MCP version.

External guidance was checked on 12 October 2026. No dependency version change is prescribed solely because a documentation page shows a newer sample dependency. Verify compatibility with this repository's actual version catalog and build variants before adding APIs.

[R01]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/ModelDelegationSettingsPanel.kt
[R02]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/ModelDelegationSettings.kt
[R03]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/LocalToolsViewModel.kt
[R04]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/ConversationDelegationCard.kt
[R05]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/ConversationDelegationSettings.kt
[R06]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/LocalDelegationCoordinator.kt
[R07]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool/SharedToolCallBroker.kt
[R08]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt
[R09]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/AgentRunCoordinator.kt
[R10]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepositoryImpl.kt
[R11]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/AppFeatureSettings.kt
[R12]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/ProfileBehaviorSettings.kt
[R13]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/assist/AssistCoordinator.kt
[R14]: https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/AGENTS.md
[S01]: https://developer.android.com/topic/libraries/architecture/datastore
[S02]: https://developer.android.com/develop/ui/compose/state-hoisting
[S03]: https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail
[S04]: https://developer.android.com/develop/ui/compose/accessibility/api-defaults
[S05]: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout.html
[S06]: https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/cancellation
