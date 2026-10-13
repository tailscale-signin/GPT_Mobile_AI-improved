# New Chat: Benchmark Scores & Provider Pricing

## Design and implementation specification

**Project:** `tailscale-signin/GPT_Mobile_AI-improved`  
**Prepared:** October 12, 2026  
**Baseline:** `main` at `cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1`  
**Document version:** 1.0  
**Status:** Proposed implementation; no application code, branch, release, or production pricing catalogue was changed for this document.

**Product decision:** Every AI profile in the New Chat popup should identify its selected model, display that model's matching benchmark result, and show its provider's applicable input and output rates in green. Prices refresh from an official structured source when supported; otherwise they come from a dated, source-backed manual catalogue. Missing information must remain visibly unknown, not become a fabricated score or a zero price.

**Delivery boundary:** This is an implementation-ready specification and repository inspection, not a claim that every provider exposes live pricing. One manually checked pricing example is included in section 15. No recurring manual maintenance has been scheduled.

---

## 1. Requirements and release contract

| ID | Required behaviour | Release evidence |
|---|---|---|
| RQ-01 | Show benchmark information on every visible AI profile row in New Chat. | A matching score or an explicit unbenchmarked/unavailable state appears. |
| RQ-02 | Follow the model currently selected in each profile. | Changing the model immediately removes the previous model's score and prices. |
| RQ-03 | Show provider-specific monetary rates in green. | Input, output, currency, units, and relevant tier are unambiguous. |
| RQ-04 | Prefer automatically refreshed official prices. | A source adapter, validation timestamp, cache policy, and manual refresh action work end to end. |
| RQ-05 | Provide a maintainable manual alternative. | Reviewed entries carry sources, review dates, scope, and expiry; no entry is labelled live. |
| RQ-06 | Allow the feature to be disabled in Advanced Settings. | Independent score and price switches persist and take effect immediately. |
| RQ-07 | Preserve chat creation and existing controls. | Favourites, labels, search, enabled-state handling, and Separate/Combined behaviour remain intact. |
| RQ-08 | Keep the popup responsive and free of inference charges. | No benchmark run, model generation, credit purchase, or publication is triggered by opening it. |

**Scope.** Enrich the existing popup rather than replacing the entire New Chat workflow. Add score and pricing details, a shared resolution layer, persistent metadata, settings, migrations, and acceptance tests. Cover remote profiles and honest local-model states. Do not turn this into a new model marketplace or a pricing-based automatic model router.

**Non-negotiable distinctions.** A listed token rate is not a final bill. A recently fetched rate is not a price lock. A community benchmark is not a measurement of the user's exact profile. A profile that has never been benchmarked must not display zero out of 100. A provider with unknown pricing must not display “Free.”

**Default experience.** Scores and prices are visible. Official pricing metadata refreshes while this feature is actively used. Community fallback and background refresh are separate, opt-in network behaviours. Existing profile order remains stable as data arrives; no automatic score or price sorting is introduced.

## 2. Repository findings and integration decisions

The inspected baseline already contains the main building blocks, but they are not joined in the New Chat picker. Findings below are limited to the listed files and pinned commit. [R1–R12]

| Existing component | Observed behaviour | Implementation decision |
|---|---|---|
| `HomeScreen.kt` / `SelectPlatformDialog` | Renders profile names, favourites, labels, search, and Separate/Combined selection. Passes `description = null` to the row. | Restore selected-model identity and add an insight row without changing the confirmation payload. [R1] |
| `PlatformCheckBoxItem.kt` | Provides selection, an optional description, favourites, and labels; no price or benchmark state. | Add an optional supporting-content slot, or compose a dedicated New Chat row using the same interaction contract. [R2] |
| `HomeViewModel.kt` | Exposes platform and feature-setting flows; selection still uses an index-aligned boolean list. | Add immutable enrichment keyed by profile UID. Keep index translation only at a compatibility boundary. [R3] |
| `BenchmarkScoreboard.kt` | Computes versioned scores, validates complete submissions, and selects the best result per identity/plan/suite. | Reuse this evaluator. Do not write a second popup-only scoring formula. [R4] |
| `ScoreboardRepository.kt` | Has a persistent public-feed cache, but cache hydration is inside the refresh operation. | Separate cache-only hydration from network refresh so offline and disabled states cannot accidentally fetch. [R5] |
| `OpenRouterModelRepository.kt` | Fetches the public model catalogue, has a one-hour in-memory cache and a short failure cooldown. | Reuse/coalesce its upstream fetch; add durable pricing snapshots and freshness metadata. [R6] |
| `OpenRouterModel.kt` | Reads only prompt and completion price strings. | Extend the pricing adapter contract; missing dimensions remain unknown. [R7] |
| `ProfileModelCatalog.kt` | Discovers model IDs/names through the resolved profile endpoint, including custom deployments. | Keep discovery and rate resolution separate. A discovered model is not proof of a known price. [R8] |
| `PlatformV2.kt` | Carries model, connection UID, endpoint, compatible type, routing, batch, and runtime settings. | Resolve actual provider and effective request context before matching a price. [R9] |
| Advanced Settings and `AppFeatureSettings.kt` | Boolean settings are driven by an enum, exhaustive mapping, grouping, search, and persisted settings. | Update the entire chain, not only the screen. [R10–R11] |

**Important benchmark caveat.** `benchmarkProfile()` deliberately creates a fixture copy with standardized temperature, output budgets, tool settings, system prompt, and synchronous execution. Its score is not an evaluation of arbitrary everyday profile settings. The popup must explain this rather than suggesting a profile's custom instructions were tested. [R12]

**Important provider caveat.** `compatibleType` identifies a transport family, not necessarily the company billing the request. A custom endpoint using OpenAI-compatible requests must never inherit OpenAI's prices solely from that enum. Resolve the saved provider connection and effective endpoint first. [R8–R9]

## 3. Popup design and interaction specification

### 3.1 Information hierarchy

Retain the popup's current title, explanation, mode controls, search, label filters, and confirmation actions. Make the profile list the main scrollable region, with a bounded height and reachable footer. Each profile becomes a compact, softly rounded card with this order:

1. Selection control, profile name, and favourite marker.
2. Provider label, remote/on-device icon, and the exact selected model's display name.
3. Benchmark badge with score, source, and test plan.
4. Green input/output price line with currency and unit.
5. A quiet but readable freshness/provenance line; existing user labels remain available.

**Illustrative layout — every number below is fictional:**

```text
NEW CHAT
Choose AI profiles                         Separate | Combined
Search profiles, providers, or models...

[✓] Research profile                                  ☆
    Provider A · Model A
    Benchmark 86/100    My best · Full                 (?)
    Input US$1.20 · Output US$4.80 / 1M tokens          (?)
    Provider API · checked 8 minutes ago
    [Research] [Writing]

[ ] Offline assistant
    On device · Local model B
    Device benchmark: not run                         (?)
    No API token charge                               (?)

                           Cancel          Start chat
```

The final app uses actual data only. The profile name remains the strongest text. Benchmark scores are a compact secondary emphasis; green prices must not overpower selection or the model name. Do not place a large graph in every row.

### 3.2 Visual treatment

Use the existing theme's surface, outline, spacing, shape, typography, and icon components. Suggested measurements are 16 dp internal padding, 8–12 dp gaps, and 16–20 dp corner radii. These are design targets to adapt to the current design system, not a new competing theme framework.

Create semantic `priceAmount` and `priceContainer` tokens. A proposed light-theme amount is `#2E7D32`; a proposed dark-theme amount is `#81C784`. Validate contrast on every actual surface, including selected and high-contrast states. Keep amounts green across themes, but communicate freshness with text and icons, not colour alone. A stale amount can remain green only beside an unmistakable “Out of date — last known” label. Unknown prices use neutral text, not a green zero.

Use the existing local/remote visual language where available. Selection uses a checkmark and outline as well as colour. Do not shrink important labels to fit: wrap model names and split input/output into two lines at narrow widths or larger font scales. Preserve horizontal space by moving extended cost details behind the existing-style `(?)` help affordance.

### 3.3 Interaction behaviour

Tapping the main selection surface toggles that profile. Long press keeps the current favourite action. Tapping the benchmark badge or its help button opens benchmark details without toggling selection. Tapping pricing opens pricing details without starting a request. Implement these as distinct accessible actions; do not let a nested tap propagate to the parent row.

Benchmark details open the corresponding saved result or existing scoreboard/detail destination, with a compact explanation of source and scope. Back navigation restores popup selection, search, label filter, and scroll position. A “Run benchmark” action routes to the existing benchmark workflow and requires the normal explicit confirmation; it never runs automatically.

Pricing details show input, output, cache rates, conditional tiers, applicable extras, source, currency, exact last-check/review time, and any account-rate limitation. “Refresh prices” is available even when automatic refresh is off. A source link opens an official source or the reviewed catalogue record, never an untrusted executable URL.

Opening, closing, sorting existing lists, or receiving updated metadata must not lose selection. Disabled profiles keep their existing eligibility behaviour. New Chat remains usable when either metadata service fails. Combined mode continues to require at least two eligible selections, as in the inspected code. [R1]

### 3.4 Accessibility and motion

Provide at least 48 dp interactive targets, meaningful accessible names, separate checkbox semantics, and a predictable focus sequence. Android's Compose accessibility guidance describes minimum touch targets and the need to avoid ambiguous adjacent actions. [W6]

Announce prices as “US dollars per one million input tokens” rather than reading an unexplained slash expression. Expose full model names to accessibility even when visually truncated. Test 200% text scaling, TalkBack, keyboard/switch navigation, light/dark/high-contrast themes, and right-to-left layouts. Refresh updates use a subtle 120–180 ms crossfade or no animation under reduced motion. Do not animate numbers upward like earnings or repeatedly reorder cards.

## 4. Benchmark matching and display policy

### 4.1 One authoritative score

For eligible remote model runs, reuse the current model scoreboard evaluation. Its weights are task accuracy 35%, structured output 20%, tool success 20%, intelligence 15%, and completion 10%, with the score capped by completion. Timing is reported separately. Delegation uses another formula and must not substitute for a model capability score. [R4]

Display “Benchmark 86/100” with “My best · Full” or “My best · Quick.” The detail view identifies the suite/formula version, run date, completion, category breakdown, and test conditions. A best result is not an average; do not label it “typical performance.” [R4]

Default resolution is deterministic: first choose compatible, complete personal model results from the current suite; prefer Full when present, otherwise Quick. Select the best score within that plan using the existing tie-breaker. An optional community fallback applies only when no compatible personal result is available. It must read “Community · self-reported,” not “Your score.” Do not choose the maximum of mixed personal/community or Full/Quick groups.

### 4.2 Identity, configuration, and legacy records

A profile UID identifies the card. The benchmark lookup uses provider identity, exact model ID, benchmark kind, plan, and suite version. Retain model-ID case and provider namespace. Store canonical aliases only when supported by an explicit provider mapping; never merge similar names heuristically.

The public v1 scoreboard currently keys provider/model and excludes profile names and UIDs. Its provider field is derived from client type, which is insufficient to certify arbitrary custom endpoints. Treat ambiguous historical entries as provider/model references, not verified matches to a custom deployment. Preserve them in history; do not rewrite provenance based on a guess. [R4, R8–R9]

Add an additive benchmark-origin record for new results: actual provider identity, resolved model, endpoint class, suite/formula version, and tested execution configuration. Keep endpoint/account-sensitive fields local. A future public schema change requires a versioned feed and matching validation; it must not silently extend strict v1 decoding.

Changing the selected model, provider, or relevant deployment invalidates the old match immediately. Renaming a profile does not. Two profiles selecting the same provider/model can share the standardized model score, with a “model benchmark” explanation. Configuration-sensitive diagnostics require matching tested configuration. Changing creativity alone must not imply the standardized fixture score has been rerun or necessarily become invalid: the fixture deliberately overrides settings. [R12]

### 4.3 Missing, running, local, and historical states

| State | Row text | Rule |
|---|---|---|
| Compatible completed result | `Benchmark 86/100 · My best · Full` | Use the existing validated score. |
| Community fallback enabled and available | `Benchmark 84/100 · Community` | Explain self-reporting, date, suite, and source. |
| No matching result | `Not benchmarked` | Never substitute zero or a different model's score. |
| Run currently in progress | Previous valid score plus `Test running`, or `Benchmark running` | Do not rate partial samples. |
| Canceled, stopped, or incomplete run | No new headline rating | Keep diagnostic history accessible. |
| Older incompatible suite | `Older benchmark available` | No silent conversion to the current scale. |
| Local model | `Device benchmark` with compatible local metrics, or `Not run` | Keep artifact, quantization, runtime, accelerator, and device context. |

Local runtime scores and remote capability scores must not be mixed into one apparent ranking without a separately validated common methodology. Public local-device benchmark publication is not added by this feature. Existing remote-only scoreboard restrictions remain intact. [R4]

## 5. Provider pricing: meaning, units, and scope

### 5.1 What the green line means

The headline presents the selected provider's applicable **listed rate** for the known request context. Default text pricing shows uncached input and output separately, in the provider's billing currency, per one million tokens. The current tier is visible when it differs from ordinary interactive text usage.

When an authorized account-specific price is known, identify it as “Account rate.” Otherwise show “Public list rate — account pricing unverified” in details, with a compact “List rate” qualifier on the row. Never claim that a public catalogue has authenticated a user's negotiated discount, credits, taxes, exchange fees, or remaining allowance.

A New Chat picker does not yet know the final prompt length, output length, cache hits, tools, or routing outcome. Show conditional context tiers or “Varies by request” rather than pretending to know a precise conversation charge. Pricing details explain excluded dimensions. A future cost estimate must be labelled an estimate with explicit assumptions and is not part of the required headline.

### 5.2 Resolution key and applicability

Resolve billing provider, connection/account scope, exact model, endpoint/region where relevant, interactive/batch mode, service tier, routing constraints, modality, currency, and effective time. Connection identity and secret-vault references are not themselves prices. Public catalogues can be shared across profiles; account overrides and custom deployment information remain scoped locally.

Treat `:free`, batch, routing variants, and provider namespaces deliberately. Do not reuse the scoreboard's benchmark-only `:batch` normalization for billing. A discounted batch price is eligible only when the effective execution path really uses that batch product, not because a global capability toggle exists. [R9, R12]

For a fixed, known endpoint, show its matching rate. For an automatic route with different eligible rates, show a valid scoped range, or “Varies by route.” Use “From” only when the lower bound genuinely applies to the configured eligible routes and is explicitly labelled; a top-provider catalogue price is not automatically the next routed price. OpenRouter documents its model pricing as top-provider metadata. [W1]

Unknown provider, unknown account tier, unresolved alias, or unrecognized conditional price logic results in a qualified list rate or unavailable state, depending on whether a safe base rate is known. Never borrow another company's rate for a similarly named model.

### 5.3 Numerical correctness

Parse monetary values as exact decimal strings into `BigDecimal`, preserving original units. Convert per-token amounts to per-million-token display values once. Do not use binary floating-point for monetary calculation, and do not multiply per-million input twice. Keep validation independent from formatting.

A present, explicit zero and a missing field are different states. A negative value, invalid decimal, infinity, or unit mismatch is rejected. A small positive value must never round into a misleading “Free” label; increase precision or show a positive lower-than-display-threshold form. Use explicit `US$` or `USD`, not an ambiguous dollar sign. Currency conversion is deferred from the initial release; provider billing currency remains authoritative.

Prices for images, audio, requests, storage, tools, cache operations, and reasoning are distinct dimensions. Do not force them into a text-token unit. Provider-specific adapters define mutually exclusive versus additive billing dimensions; in particular, never double-count reasoning tokens already included in an output category.

### 5.4 Free and local cases

“Free token rate” requires an explicit zero price for the selected eligible tier, with quota/conditions visible. A profile's inclusion in a free-model category, a missing price, or an unverified free allowance is not sufficient.

A confirmed on-device runtime displays “No API token charge,” not a universal claim of zero electricity, storage, hardware, or licensing cost. Ollama and custom servers are not automatically on-device or free: use a verified deployment classification or a scoped manual rate. If local assistance can call a remote primary/delegate, the local badge must not imply the entire workflow is free.

Combined mode does not sum rates from all profiles into a fake total. Keep per-profile prices. A compact note can explain that multiple model calls, synthesis, research, and delegates may add charges; token-rate sums alone are not a conversation estimate.

## 6. Automatic sources and provider capability matrix

Provider support is explicit, versioned, and testable. Do not assume every `/models` endpoint supplies prices, and do not use an LLM to guess a missing rate during chat creation.

| Provider class | Initial source strategy | Required qualifier and limitation |
|---|---|---|
| OpenRouter | Official model metadata through the existing repository, extended behind a pricing adapter. | API-sourced catalogue rate; qualify routing, variants, and conditional applicability. [R6–R7, W1] |
| Direct OpenAI | Reviewed catalogue sourced from official pricing/model pages; use structured price retrieval only after a dedicated capability is verified. | Manual review date and direct-provider scope. The inspected model-list reference exposes model metadata, not a price table. [W2, W5] |
| Direct Anthropic | Reviewed catalogue from official Claude pricing documentation. | Match model and relevant caching/batch/tool dimensions. Do not infer a uniform discount. [W3] |
| Gemini Developer API | Reviewed catalogue from official Gemini API pricing documentation. | Match the correct free/paid tier, modality, service tier, and effective dates. Do not substitute Vertex or another reseller's pricing. [W4] |
| NVIDIA, Groq, other saved providers | Explicit adapter registration or a reviewed, provider-specific manual entry. | Their complete price coverage was not audited in this task. Unknown stays unknown until sourced. |
| Custom deployments / negotiated contracts | Explicit account/connection-scoped manual entry, or a separately verified provider contract. | “Manual account rate” or “Custom rate”; never inherit a protocol vendor's price. |
| Confirmed on-device runtime | Runtime classification, not a network catalogue. | “No API token charge”; no pricing network request. |

OpenRouter publishes decimal-string prices with token/request/unit dimensions and optional conditional overrides. The adapter must validate recognized fields, retain original units and source metadata, and decline an exact quote when conditions cannot be evaluated. Known time and prompt-length boundaries need provider-contract tests; unknown conditions must not be applied optimistically. Keep any third-party benchmark metadata separate from the app's benchmark scale. [W1]

Manual catalogue refresh and live provider refresh use separate provenance. Downloading an unchanged manually maintained catalogue does not mean its underlying provider rates were rechecked that day. Support source-specific conditional HTTP requests where available, but never equate a catalogue HTTP 304 with a fresh human verification of provider prices.

Before enabling an adapter, add contract fixtures, a parser-version record, a documented official source, matching rules, and a supported-dimensions declaration. Changes to upstream fields should degrade to qualified or unavailable data, not crash the picker.

## 7. Freshness, refresh, and failure state machine

### 7.1 Proposed defaults

These are product policies, not provider guarantees. They intentionally distinguish recent API observations from manually reviewed data. A shorter source expiry always wins.

| Source/state | Default age policy | Presentation |
|---|---|---|
| Official API snapshot | Fresh for up to 1 hour after successful source validation. | `Provider API · checked 12 minutes ago` |
| API snapshot needing refresh | Older than 1 hour and up to 72 hours. | `Out of date · last checked …`; retain last known amount with warning. |
| Expired API snapshot | Older than 72 hours, or explicitly withdrawn/expired. | `Current price unavailable`; old amount only in history/details. |
| Reviewed manual entry | Recently reviewed for up to 7 days. | `Manual catalogue · reviewed Oct 12, 2026` |
| Aged manual entry | Older than 7 days and up to 30 days. | `Out of date · manual rate`; prominent dated warning. |
| Expired manual entry | Older than 30 days, or earlier recorded expiry. | No active headline amount; request an update. |
| Local account override | Explicit effective/expiry dates, plus manual review-age policy. | Always disclose override provenance; never label it API-verified. |

Store `fetchedAt`, `sourceValidatedAt`, `reviewedAt`, `effectiveFrom`, and `validUntil` separately. Fields can be absent when the source does not supply them. Preserve source date precision; a date-only human review must not be converted into an invented exact verification time. Use a clock abstraction for tests. Future dates, clock rollback, and inconsistent server timestamps cannot make an entry indefinitely fresh.

### 7.2 Refresh scheduling

On popup opening, display eligible cached data immediately. When price visibility and foreground auto-refresh are enabled, coalesce refresh requests per provider and price context for missing or due entries. Resolve a newly selected model from the cache first, then refresh only what is needed. Repeated composition or scrolling must not start requests.

Preserve the existing one-hour OpenRouter catalogue reuse window unless the shared transport and pricing consumers are updated together. “Refresh now” requests bypass application age checks, while still respecting in-flight deduplication, server limits, and a short anti-repeat window. A force refresh is not a guarantee that the upstream edge cache is freshly recomputed. [R6, W1]

Optional background refresh is off by default. When enabled, schedule best-effort work approximately every six hours for configured provider catalogues, with network constraints and backoff. This is not an exact delivery promise or a reason to keep the app alive. No work is scheduled when there are no eligible consumers. Known effective-time boundaries are reevaluated locally while the popup is active, even without another download.

### 7.3 Failure and cancellation rules

A timeout, offline state, rate limit, malformed response, authentication failure, or partial catalogue must not clear unrelated valid entries. Preserve last-known eligible data with its real age. Do not update freshness timestamps on failed refreshes. Respect retry guidance; use bounded retries and cooldown rather than repeatedly polling a failing service.

Distinguish an authoritative full-snapshot removal from a partial response or transient empty payload. Confirmed model withdrawal removes the active quote; accidental empty refreshes retain a warned snapshot. Cancellation propagates cleanly and always clears loading indicators. A failure in one provider does not block cards from other providers or the Start Chat button.

“Provider API — checked just now” is the preferred wording. Do not show a continuously glowing “LIVE” badge unless the app actually has a continuous source contract. Even a freshly observed applicable list price can change before execution; actual provider-reported billing remains authoritative.

## 8. Manual pricing catalogue and update ownership

### 8.1 Two manual mechanisms

**Maintainer catalogue.** Ship a reviewed JSON snapshot for known public rates and support a separately published update feed, so an ordinary catalogue correction need not require an APK update. Each entry has exact provider/model/tier scope, source URL, review date, original price units, effective/expiry dates when known, reviewer/review ID, schema version, and revision history.

**Local override.** Advanced Settings includes “Manage manual prices” for account-specific or unsupported providers. The user chooses the exact connection/model/context, enters input/output rates and currency/units, adds a source or contract note, and sets review/expiry dates. It remains visibly manual. Public defaults must not silently overwrite a valid explicit account override, and an expired override must not conceal a fresher public rate without explanation.

Resolution priority is: an active, explicitly selected account contract/override; a matching authorized account-specific source; a matching official public source; then an eligible reviewed public catalogue entry. Priority never overrides identity, expiry, or applicability checks. On conflicts, expose provenance and prefer a confirmed newer effective rate within the same scope. Never silently blend input from one tier with output from another.

### 8.2 Update procedure

1. Fetch the official source for each affected provider/model; check tier, unit, region, conditions, and effective date.
2. Update only verified fields. Preserve unknowns and record review evidence; do not infer missing model rates from nearby entries.
3. Run schema, decimal, identity, effective-date, and regression validation. Produce a human-readable old/new diff.
4. Require maintainer approval before public publication. Publish an immutable version and a trusted current-version pointer with rollback support.
5. The app validates authenticity, schema, scope, age, and version before atomically applying the update. Invalid updates keep the last usable snapshot.
6. Mark unsupported or expired entries visibly. A successful feed download must not renew each entry's human review date.

An assistant-assisted update is an explicit maintenance task: research current official rates, prepare a dated change, and have it reviewed through this process. It is **not** a promise that the assistant will keep prices updated indefinitely outside the conversation. Assign a release owner for the catalogue before shipping and document the review cadence. A weekly review target is proposed for active manually maintained entries; expiry handling remains mandatory when that target is missed.

### 8.3 Trust and publishing

Use HTTPS and a release-controlled source. A signed manifest is recommended for the remotely distributed catalogue: define key ownership, verification, rotation, and a monotonic version policy before enabling downloads. Do not copy unreviewed pull-request data into the active feed. Deliberate rollback uses a newly approved revision pointing to reviewed prior content, not unrestricted acceptance of older manifests.

Backups may include visibility preferences and explicitly exportable manual overrides. Recompute freshness after restore, never renew review dates, and do not include credentials. Cross-device restoration of account-scoped rates requires the corresponding connection to be resolved before use.

## 9. Advanced Settings and controls

Create a searchable **New Chat — Scores & Prices** group within Advanced Settings. Reuse existing polished cards, help icons, switches, and focus/highlight behaviour. Do not add a second independent settings store.

| Setting | Default | Behaviour |
|---|---|---|
| Show benchmark scores in New Chat | On | Controls benchmark badges and score-specific detail actions. |
| Show provider prices in New Chat | On | Controls monetary rates and pricing-specific detail actions. |
| Automatically refresh provider prices | On | Allows due foreground metadata refresh for visible pricing; never performs inference. |
| Use community scores when no personal score exists | Off | Enables read-only fallback from the existing public scoreboard. Never publishes personal results. |
| Refresh prices in the background | Off | Enables constrained periodic metadata refresh; depends on automatic refresh. |
| Refresh prices now | Action | Explicit one-off update with per-source success/failure results. |
| Manage manual prices | Action | Opens scoped entries, provenance, review dates, conflicts, and expiry. |

Add corresponding booleans to `AppFeatureSettings`, enum entries to `AppFeature`, and cases to `withFeature()`. Update `SettingsSearchRegistry` grouping, search keywords, focus IDs, string resources, setting ViewModel persistence, backup compatibility, and unit/UI tests. Non-boolean actions need deliberate custom rows; do not shoehorn them into an enum that only renders switches. [R10–R11]

Toggles take effect while the popup is open. Turning scores off removes badge space and score-specific work. Turning prices off removes monetary rows and refresh requests initiated only for this feature. Turning automatic refresh off retains eligible cached prices and permits explicit manual refresh. Community and background controls remain independent from public-source price display.

**Shared-consumer rule.** These display switches do not disable benchmarks, provider usage accounting, spend controls, or another explicitly enabled consumer's metadata access. Track refresh consumers so hiding New Chat pricing stops its work without breaking an active usage/budget feature. Conversely, a hidden popup cannot secretly remain a refresh consumer.

Migration defaults enable the two requested visual features without deleting previous settings. No publication permission, credential, community opt-in, or background permission is invented during migration. Disabling both features restores the existing compact selection experience, while retaining the selected-model description as basic identity information.

## 10. Architecture and data contracts

### 10.1 Separate identity, evidence, and presentation

Introduce `ObserveNewChatProfileInsightsUseCase` as the composition layer. It combines observed profiles, effective provider configuration, feature settings, benchmark projections, and pricing snapshots. The UI consumes immutable summaries; it does not perform HTTP, parse JSON, select benchmark runs, or format raw provider fields.

Keep three identities distinct: **profile UID** for selection/UI state; **benchmark identity** for comparable evidence; and **price context** for billing applicability. A generic model-name string cannot safely serve all three purposes.

Suggested contracts below are design types, not drop-in code. Keep money serializable as validated strings at persistence boundaries; calculations use exact decimals.

```kotlin
// Proposed contracts; not existing repository symbols.
data class ModelIdentity(
    val providerId: String,
    val requestedModelId: String,
    val resolvedModelId: String?,
    val deploymentScopeId: String?
)

data class PriceContext(
    val model: ModelIdentity,
    val accountScopeId: String?,
    val executionMode: String,
    val serviceTier: String?,
    val routingPolicyDigest: String?,
    val region: String?,
    val modality: String,
    val currency: String
)

data class ProfileInsightKey(
    val profileUid: String,
    val modelIdentity: ModelIdentity,
    val priceContextRevision: Long
)
```

Use typed enums or sealed types in production for execution mode, source, freshness, availability, and applicability; the strings above keep the conceptual contract readable. Account/deployment identifiers are opaque local IDs, not endpoint URLs or hashes of secrets.

`PriceSnapshot` should contain snapshot/revision IDs, provider context, source kind, source URL, adapter version, original unit/rates, conditional rules, source validation/review timestamps, effective/expiry fields, and an applicability qualifier. Provenance and freshness are separate: “manual” can be recent, and “API-sourced” can be stale.

`BenchmarkSummary` should contain source type, actual matching key, kind/plan/suite/formula, score and scale, source run ID, test date, completion, provenance quality, and configuration scope. `ProfileInsightUiState` contains independent score and price states; one failure cannot blank the other.

### 10.2 Storage and dependencies

Use the existing benchmark store as evidence, with an indexed lightweight projection rather than serializing entire run payloads into each row. Add an additive origin/provenance migration only where necessary. Inspect the actual schema version at implementation time; do not assume a database version from this document.

Persist price snapshots and manual overrides in a dedicated repository/cache with atomic replacement and bounded retention. A dedicated Room database is appropriate for scoped entries and history; this avoids changing the profile entity just to cache a price. Use migrations and exported schema fixtures; never use destructive fallback as the release path.

Put transport/parser adapters behind `ProviderPricingSource`. Start with `OpenRouterPricingSource`, `ReviewedCataloguePricingSource`, `ManualOverridePricingSource`, and `OnDevicePricingClassifier`. Reuse existing provider connection resolution and secret-vault boundaries; do not introduce raw-token fields into insight models.

Wire dependencies with the existing Hilt approach. Provide the clock, formatter, alias resolver, HTTP client, and repositories as replaceable dependencies for deterministic tests. Public price fetching should not load a local model, wake an NPU, run research tools, or call an AI to interpret a catalogue.

### 10.3 Runtime update sequence

1. Observe a stable list of profile UIDs and current model/provider configuration.
2. Resolve identities and publish matching cached summaries immediately; unmatched fields become Loading or Unknown, never the previous model's values.
3. Register only permitted visible-feature consumers and issue coalesced refreshes.
4. Join new evidence by its exact key and configuration revision, then publish immutable state.
5. On model/provider/context changes, cancel obsolete collection with `flatMapLatest` or equivalent and reject late results whose keys no longer match.
6. Recompute age/effective-time applicability while visible; release feature consumers when dismissed.

Avoid one network fetch or benchmark scan per row. A single provider catalogue can serve multiple profiles, but account-specific information must never leak across connections. When model A changes to B while A's fetch is running, A's completed response may update A's cache but cannot repopulate B's card.

## 11. File-level implementation map

All Kotlin paths below are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`. “New” marks proposed files rather than inspected existing symbols.

| Area | Existing files to change | Proposed additions / responsibility |
|---|---|---|
| Popup host | `presentation/ui/home/HomeScreen.kt`, `HomeViewModel.kt` | New `NewChatProfileInsights.kt`: compact rows and detail-host state. Keep confirmation semantics. |
| Shared row | `presentation/common/PlatformCheckBoxItem.kt` | Optional supporting-content slot or dedicated wrapper; independent accessible actions. |
| Composition | Existing home ViewModel and provider-resolution boundary | New `domain/usecase/ObserveNewChatProfileInsightsUseCase.kt`; stable UID joins and feature gating. |
| Score read model | `data/benchmark/BenchmarkScoreboard.kt`, `ScoreboardRepository.kt`; integrate existing `BenchmarkStore.kt` / `BenchmarkDatabase.kt` after schema review | New `ProfileBenchmarkResolver.kt`; cache-only hydration, provenance projection, no duplicate formula. |
| Price acquisition | `data/repository/OpenRouterModelRepository.kt`, `data/openrouter/OpenRouterModel.kt` | New `data/pricing/ProviderPricingRepository.kt`, `ProviderPricingSource.kt`, source adapters and exact-decimal parsing. |
| Model discovery | `data/repository/ProfileModelCatalog.kt` | Share known identity resolution, not assumptions that discovery implies price. Preserve its existing role. |
| Persistence | Existing settings persistence; new pricing persistence | New `data/pricing/PricingDatabase.kt`, snapshot/override entities and migration tests. |
| Controls | `data/model/AppFeatureSettings.kt`, `presentation/ui/setting/AdvancedSettingsScreen.kt`, `SettingsSearchRegistry.kt`, `SettingViewModelV2.kt` | New `NewChatInsightsSettingsPanel.kt`; boolean mappings, action rows, searchable help. |
| Design system | Existing theme, spacing, icon, and shape components | Add accessible price semantic tokens and preview states without a new theme framework. |
| Catalogue | New repository assets and maintenance tooling | `app/src/main/assets/pricing/catalog.v1.json`, `docs/pricing/maintenance.md`, validation script and optional trusted feed workflow. |
| Quality | Relevant unit, instrumentation, and screenshot test source sets | Parser, resolver, migration, settings, accessibility, failure, and model-switch regression tests. |

Do not overload the existing local downloadable-model `ModelCatalog` schema with remote billing data. It represents artifacts, capabilities, runtime requirements, and downloads, not provider rate contracts. [R13]

The existing accounting area includes `InvocationLedger`, `UsageAccounting`, `SpendBudget`, and model-token comparisons. Review their concrete integration before reuse; this document does not assume their full implementation. Any future cost calculation should reference the quote snapshot used for that request and keep estimates separate from provider-reported charges. Price updates must never retroactively rewrite historical bills. [R14]

## 12. Privacy, performance, and operational safeguards

**Metadata-only networking.** Public catalogues receive only the requests needed for metadata retrieval. Do not send profile names, conversations, prompts, benchmark answers, memory, custom endpoints, or account identifiers to public price/score services. Credentials are only used for an explicitly supported authorized account source and remain behind the existing credential boundary.

**No automatic sharing.** Reading the existing community feed does not publish results. Keep the repository's explicit benchmark submission action and permission checks intact. The picker must not create a GitHub issue, request new write permission, or upload a run. [R5]

**Untrusted payloads.** Validate JSON size, pagination, unit schema, rate ranges, effective-date syntax, and model identity before publishing snapshots. Restrict redirects and catalogue/source URLs to supported safe schemes and approved hosts. Do not follow arbitrary source URLs on refresh or fetch custom intranet endpoints merely because a feed lists them. Logs must redact credentials and account-specific pricing notes.

**Performance targets — to measure, not claimed results.** With 50 saved profiles and a warm metadata cache, target a first usable picker within 250 ms on a representative device; zero network waits on the selection path; one coalesced in-flight refresh per source/context; and no frame-blocking parser/database work. Keep expensive evaluation off the UI thread. Use a bounded lazy list if the enlarged rows make the existing scroll column costly.

**Battery and cancellation.** Do not start any model runtime for this feature. Use capped metadata concurrency, cancellation, and cooldowns. Closing the popup releases subscriptions. Persist successful snapshots atomically and trim older entries by a documented cache budget while preserving active manual overrides.

**Diagnostics.** Record source, adapter version, cache hit/miss, validation age, result category, duration, and coarse failure reason under existing diagnostics consent. Do not log secrets or full personal configuration. Provide a nonfatal status such as “2 providers updated; 1 unavailable” after explicit refresh.

**Historical integrity.** New rates affect new quotes, not old usage records. Old benchmark runs retain their original suite and provenance. A restore or migration must not upgrade either kind of evidence from unknown to verified without supporting data.

## 13. Implementation phases and exit gates

| Phase | Work package | Exit gate |
|---|---|---|
| 0 — Freeze contracts | Confirm current branch baseline, all New Chat entry points, effective provider resolution, benchmark schema, and shared refresh consumers. | File map and feature behaviour agreed; no unexpected mutation of existing chat selection. |
| 1 — Vertical score slice | Add stable-key insight state, selected-model line, personal benchmark resolver, badge, and benchmark display switch. | Correct scores follow the model; incomplete runs and unrelated models never appear. |
| 2 — Pricing foundation | Implement scoped contracts, decimals, persistent cache, manual catalogue/override, source selection, and honest unavailable states. | Restart/offline/migration tests pass before presenting any green monetary amount. |
| 3 — Official source refresh | Extend OpenRouter adapter and shared fetch; add validation timestamps, explicit refresh, conditions, and bounded failure handling. | Fresh/stale/expired and model-switch races pass with contract fixtures. |
| 4 — Full popup and settings | Finish detail views, Advanced Settings group, source controls, localization, accessibility, theme tokens, and preserved selection/navigation. | UI and accessibility acceptance passes at supported widths and text scales. |
| 5 — Optional network extensions | Add opt-in community fallback, optional background refresh, and trusted reviewed-catalogue feed distribution. | Disabled states produce no feature-specific work; no automatic submissions; feed trust and rollback validated. |
| 6 — Release qualification | Run repository checks, instrumentation, migrations, performance measurements, and physical-device acceptance. | All mandatory tests below pass; supported-provider coverage and limitations published. |

Each implementation pull request should include screenshots of fresh, stale, missing, local, and both-disabled states; test results; schema changes; and a provider coverage summary. Do not declare every provider live when only OpenRouter refresh and a manual fallback have been qualified.

The first usable release includes the two display switches, personal benchmark matching, automatic OpenRouter metadata refresh where applicable, dated manual fallback, and all unknown/failure states. Community and periodic background refresh may ship later, but their controls must not be exposed as functional until they actually work.

No Android build, emulator test, phone test, database migration execution, or live end-to-end app qualification was performed while preparing this document. The phases above are required future implementation work, not completed tasks.

## 14. Acceptance and regression test matrix

### 14.1 Identity and benchmarking

| Test | Expected result |
|---|---|
| B01 — Same profile, model A changed to B | A's score disappears immediately; only B evidence may repopulate the row. |
| B02 — Rename profile | The matching model score remains; display name is not a lookup key. |
| B03 — Two profiles, same provider/model | Both may show the same standardized model score with the correct provenance. |
| B04 — Same model label, different provider/endpoint | No cross-provider price or unsupported exact-profile benchmark match. |
| B05 — No run, canceled run, incomplete run | No fabricated score; incomplete data stays in history. |
| B06 — Personal Full and Quick results | Full is preferred when valid; best is selected only within that plan. |
| B07 — Community result higher than personal | Personal remains primary; fallback does not cherry-pick a higher score. |
| B08 — Delegation and local runtime results | They never substitute silently for a remote model capability score. |
| B09 — Old suite or ambiguous legacy provider | Show older/reference evidence only with explicit qualification. |
| B10 — Changed everyday creativity/custom prompt | Standardized score is not represented as a fresh test of that configuration. |

### 14.2 Pricing correctness and freshness

| Test | Expected result |
|---|---|
| P01 — Provider says per-token `0.000002` | Format as `US$2.00 / 1M input tokens`, with no second multiplication. |
| P02 — Missing/null versus explicit zero | Unknown is not Free; explicit eligible zero can show a free token rate. |
| P03 — Small positive, negative, malformed value | Positive stays visibly nonzero; invalid values are rejected safely. |
| P04 — Free/batch/routing suffixes | Match effective variant/context; do not borrow the base model's rate blindly. |
| P05 — Routed model without a fixed endpoint | Qualified range/from/variable state, never an unjustified exact quote. |
| P06 — Contract/account override | Applies only to the intended account and context; always labelled manual/account-specific. |
| P07 — Context threshold and time boundary | Evaluate documented boundary semantics exactly; handle UTC/day changes and unknown prompt length. |
| P08 — Unsupported conditional field | Do not apply it as unconditional pricing; mark exact applicability unavailable. |
| P09 — API age crosses 1 hour/72 hours | Fresh becomes stale/expired without another successful fetch. |
| P10 — Manual age crosses 7 days/30 days | Review labels age correctly; downloading the same catalogue does not renew review. |
| P11 — Future effective rate / withdrawn model | Future rate is not active; authoritative withdrawal removes the current quote. |
| P12 — Offline, timeout, 429, malformed or partial catalogue | Retain eligible last-known data with real timestamps; unrelated profiles still work. |
| P13 — A request completes after profile switches to B | A's result may update A cache, never B card; loading terminates. |
| P14 — On-device versus hosted compatible server | Only confirmed on-device execution gets “No API token charge.” |
| P15 — Restore, clock rollback, or timezone change | Do not fabricate freshness or move source effective boundaries incorrectly. |
| P16 — Source changes historical rates | Existing request/usage records and original quote references remain unchanged. |

### 14.3 Settings, UI, and release behaviour

| Test | Expected result |
|---|---|
| U01 — Scores off, prices on; then opposite | Only the requested metadata is hidden; cards collapse unused space. |
| U02 — Both displays off | Selection remains functional and compact; no feature-only network consumers remain. |
| U03 — Automatic refresh off | Cache still works; explicit refresh works; no automatic feature refresh occurs. |
| U04 — Community/background controls off | No associated feature traffic, scheduling, or publication occurs. |
| U05 — Open, recompose, scroll, close repeatedly | No duplicate catalogue storm, resource leak, paid inference, or accidental benchmark. |
| U06 — Tap details versus checkbox; long press | Correct action only; favourite and selection state are preserved. |
| U07 — Back from details; filter/favourite updates | Search, scroll, UID selection, and chat mode remain consistent. |
| U08 — Large text, RTL, themes, TalkBack | No clipping, ambiguous price units, colour-only states, or overlapping touch targets. |
| U09 — Combined selection changes from two to one | Existing eligibility/reset rule remains correct; no fake combined total is shown. |
| U10 — Settings migration and backup round trip | Defaults and saved choices survive; no new consent or review date is invented. |
| U11 — 50 profiles and a slow/failing provider | Cached picker remains usable; Start Chat does not await metadata. |
| U12 — Untrusted feed, redirect, oversized payload | Reject safely, retain usable cache, and redact sensitive diagnostics. |

**Definition of done.** Every requirement RQ-01 through RQ-08 has passing automated coverage and relevant device/UI evidence. No stale value is labelled current, no unknown value is labelled free, and no old model result survives a selection change. The manual maintenance owner, supported provider matrix, and real refresh behaviour are documented before release.

## 15. Manually checked reference rate and verification limits

As a concrete source-backed example, the official OpenAI GPT-4.1 model page was checked on **October 12, 2026**. It lists the following text-token prices per one million tokens: input **US$2.00**, cached input **US$0.50**, and output **US$8.00**. [W2]

| Field | Reference value / scope |
|---|---|
| Billing provider | Direct OpenAI, not an OpenRouter route or another reseller. |
| Model | `gpt-4.1` as listed on the official model page. |
| Price basis | Listed text-token rates; input/output separated. |
| Provenance | Official model page; manually checked for this document. |
| Review precision | Date only: October 12, 2026. No exact observation time is asserted. |
| Limits | Not an account-specific quote, not a full model catalogue, not an installed app update. |

A record prepared from this check should be marked `reviewed`, not `provider_api_live`, and must retain its source and date. Implementation must confirm the exact tier/application and any additional charges before publishing it as an applicable production rate. No complete inventory of the user's configured provider models or private account contracts was inspected.

All benchmark values in the mockup are fictional. No benchmark score was fabricated for the real GPT-4.1 rate example. The illustration and the checked price reference are intentionally separate.

## 16. Source register and reproducibility

Repository references are pinned to the inspected commit rather than mutable `main`. Public provider documentation was checked on October 12, 2026 and can change afterward. Proposed defaults, architecture, UI layout, and tests are recommendations, not claims that these behaviours are already implemented.

### Repository evidence

Base path: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/` in `tailscale-signin/GPT_Mobile_AI-improved` at commit `cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1`.

- **[R1] HomeScreen.kt — SelectPlatformDialog.** Profile rendering, `description = null`, search/labels, favourite callback, confirmation and Combined-mode rules. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt#L1660-L1785).
- **[R2] PlatformCheckBoxItem.kt.** Current row inputs and selection/long-press behaviour. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/PlatformCheckBoxItem.kt).
- **[R3] HomeViewModel.kt.** Platform and feature-setting flows and index-aligned selection. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeViewModel.kt).
- **[R4] BenchmarkScoreboard.kt.** Submission identity, validation, score formula, best-run policy, suite scope, and local exclusions. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/benchmark/BenchmarkScoreboard.kt).
- **[R5] ScoreboardRepository.kt.** Public-feed cache, hydration/refresh coupling, errors, and explicit sharing. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/benchmark/ScoreboardRepository.kt).
- **[R6] OpenRouterModelRepository.kt.** Existing one-hour in-memory cache, public catalogue request, cooldown, and bounds. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/OpenRouterModelRepository.kt).
- **[R7] OpenRouterModel.kt.** Prompt/completion price DTO and model metadata. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/openrouter/OpenRouterModel.kt).
- **[R8] ProfileModelCatalog.kt.** Provider/custom-endpoint model discovery and ID/name projection. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ProfileModelCatalog.kt).
- **[R9] PlatformV2.kt.** Stored model, provider-connection reference, endpoint, routing, batch, and runtime configuration. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/entity/PlatformV2.kt).
- **[R10] AdvancedSettingsScreen.kt.** Enum-driven switch cards, help icons, grouping, and search focus. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/AdvancedSettingsScreen.kt).
- **[R11] AppFeatureSettings.kt.** Serializable defaults, `AppFeature`, and exhaustive `withFeature()` mapping. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/model/AppFeatureSettings.kt).
- **[R12] BenchmarkProfile.kt.** Fixture settings and benchmark-only model/batch normalization. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/benchmark/BenchmarkProfile.kt).
- **[R13] ModelCatalog.kt.** Local artifact catalogue schema, distinct from remote prices. [Pinned source](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/catalog/ModelCatalog.kt).
- **[R14] Accounting directory.** File inventory only; concrete usage implementations require further review before integration. [Pinned directory](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/tree/cbd88545dbdd406c6e9f6d61f279ad62e4bd0cc1/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/accounting).

### Official external references

- **[W1] OpenRouter, Models.** Official metadata, price units, top-provider scope, variants, and conditional price schema. [Documentation](https://openrouter.ai/docs/guides/overview/models).
- **[W2] OpenAI, GPT-4.1 model page.** Source for the dated reference text-token rates only. [Documentation](https://developers.openai.com/api/docs/models/gpt-4.1).
- **[W3] Anthropic, Claude pricing.** Official source for provider-specific reviewed pricing and dimensions. [Documentation](https://platform.claude.com/docs/en/about-claude/pricing).
- **[W4] Google, Gemini Developer API pricing.** Official tier, modality, and effective-date pricing source. [Documentation](https://ai.google.dev/gemini-api/docs/pricing).
- **[W5] OpenAI, Models API reference.** Model-discovery schema; do not assume it is a pricing endpoint. [Documentation](https://developers.openai.com/api/reference/resources/models).
- **[W6] Android Developers, Compose accessibility defaults.** Minimum touch targets and default component accessibility behaviour. [Documentation](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

**Final implementation principle:** The popup should make choosing a model easier, not make its evidence look more certain than it is. Correct identity, visible provenance, honest freshness, and a responsive selection flow are part of the feature—not optional polish.
