# OpenRouter Provider Upgrade
## Design and implementation specification

**Project:** GPT Mobile AI Improved\
**Repository:** `tailscale-signin/GPT_Mobile_AI-improved`\
**Requested baseline:** v0.9.50.0\
**Resolved branch:** `version-v0.9.50.0`\
**Branch head reported during review:** `8b7c27ef1ee65b6fa38b6f43ac3a8d1b9c5639e5`\
**Document date:** October 12, 2026\
**Status:** Implementation proposal; repository inspection and documentation research completed. No application changes, authenticated account tests, Android builds, or device acceptance tests were performed for this document.

> **Design decision:** Replace the single scrolling OpenRouter settings page with three persistent top tabs: **Setup · Routing · Account**. Routing and Account are neutral gray, exactly 50% opaque, and non-interactive until the selected connection has a server-validated inference API key. A valid key unlocks the interface even when spending is exhausted; authentication, permission, availability, and billing are separate states.

This specification combines a clean first-use experience with progressively disclosed provider controls. It preserves the existing profile system, theme language, vault storage, batch services, and cache options while correcting ambiguous account ownership and credit presentation. All proposed components, defaults, refresh intervals, and implementation phases below are design recommendations, not claims that these features already exist.

---

## 1. Product contract and scope

The screen must answer four practical questions: **Am I connected? Which route will my model use? What might it cost? How much have I used or have left?** It must not require users to understand management credentials, token accounting, or provider routing before they can chat.

| Requirement | Acceptance contract |
|---|---|
| Three top tabs | Setup first; Routing second; Account third. Labels remain stable, with a meaningful icon per tab. |
| Exact locked appearance | Tabs two and three use neutral colors and one 0.50 opacity layer. They have disabled accessibility semantics and cannot be opened by taps, keyboard, deep links, restored selection, or gestures. |
| Real validation | A successful, schema-valid current-key response for the active credential and endpoint establishes connection identity. Length or prefix checks never establish validity. |
| Useful monetary information | Separate key allowance, effective budget, account wallet, price estimate, and recorded request cost. Every amount identifies currency, scope, and freshness. |
| Exclusive controls | Expose routing, provider filters, price ceilings, privacy restrictions, sticky routing, response caching, and batch controls with precise request mappings. |
| Actual integration | A setting is complete only when persisted, resolved into the execution policy, serialized in the correct transport, and covered by an execution-path test. |
| Preserve profiles | Model selection, sampling, prompts, reasoning choices, and tool permissions stay with child AI profiles. Provider defaults are inherited, not silently copied over profile choices. |
| Clean presentation | Primary information first; advanced sections collapsed; explanatory details in the existing question-mark help pattern. |

**Delivery boundary.** This is a provider-settings overhaul, not a replacement for the chat engine, a billing processor, or a generic administrator console. Account mutations, purchases, refunds, organization membership changes, and BYOK secret management should open OpenRouter's authenticated website rather than be embedded in the first release. Optional account-wide read access is specified separately in section 8.

**Implementation branch proposal:** `feat/openrouter-provider-workspace-v0.9.50.0`. Create it from the reviewed baseline only when implementation is requested. Re-check the branch head and reconcile concurrent changes before editing.

## 2. Verified baseline and priority findings

The inspected implementation lives under `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`, not a speculative new Java source tree. Repository references R1–R14 at the end identify the files and review scope.

| Priority | Finding in the reviewed code | Required change |
|---|---|---|
| P0 | `OpenRouterSettingsRepositoryImpl.findPlatform()` uses the first matching OpenRouter profile, including a name/URL heuristic for OpenAI-compatible profiles. [R5] | Make `providerConnectionUid` explicit in navigation, repositories, caches, validation, and usage queries. Never select an account by list order or display name. |
| P0 | The screen saves the API key and base URL on every text edit. Whole-settings writes launch asynchronously. [R2, R3] | Keep a private draft; validate and commit credentials deliberately. Use atomic field updates and revision checks to prevent stale writes. |
| P0 | `validateApiKey()` checks only nonblank text and length of at least 32. [R3] | Replace authentication decisions with `/key`; classify invalid, expired, management-only, network, billing, and permission outcomes independently. |
| P0 | Startup refresh calls `/credits` with the regular provider key; the repository explicitly treats 403 as requiring a management key. [R3, R6] | Use `/key` for ordinary account information. Make wallet access optional, capability-aware, and separate from connection health. |
| P1 | Credits cache identity uses a 32-bit `String.hashCode()`. Refresh completion is not bound to a credential revision. [R3, R6] | Use a scoped credential reference plus revision; cancel obsolete work and reject late responses. Do not retain another account's visible totals. |
| P1 | Overview formats money with locale currency defaults, without setting USD. [R2] | Set currency explicitly; localized separators must never relabel USD as CAD or another currency. |
| P1 | The model DTO retains only input/output price strings and limited model metadata. [R7] | Add nullable supported-parameter, modality, canonical identity, and applicable pricing metadata without treating omitted fields as zero. |
| P1 | The model picker constructs its own repository; the catalog is public, cached for one hour, and not filtered for the active user. [R8, R9] | Inject the shared catalog, add user-filtered results, and expose observed freshness. Preserve existing bounds and cancellation handling. |
| P1 | The routing DTO covers some fields but lacks several useful controls; `z_weight` is present but not in the reviewed current provider reference. [R10, S6] | Evolve a typed policy and a current-schema serializer. Quarantine unqualified legacy fields rather than advertise unsupported controls. |
| P1 | The page is one long list; its batch-size slider permits 1–50 while the ViewModel accepts 1–100. [R2, R3] | Introduce tabs and one validation specification shared by UI, persistence, and transport. |

**Preserve what already works.** Settings persistence already uses `SecretVault` and strips the API key from ordinary preferences. Do not replace this with plaintext storage. The catalog already has a mutex, bounded response reading, a failure cooldown, and cancellation checks. Extend these rather than regress them. [R5, R8]

## 3. Information architecture and navigation

Entry points remain **Settings → AI → OpenRouter provider connection**, with a secondary route from any linked OpenRouter profile. The header identifies the specific connection, for example “OpenRouter · Personal”; a connection switcher is shown only when there is more than one.

| Tab | Icon concept | Main job | Primary content |
|---|---|---|---|
| Setup | Key / verified connection | Connect safely | Credential card, validation, endpoint selection, linked profiles, connection diagnostics. |
| Routing | Route / branching arrows | Control delivery and price | Strategy, model-price preview, provider preferences, privacy, performance, cache and batch sections. |
| Account | Wallet / usage chart | Understand allowance and spend | Key facts, effective budget, usage periods, optional wallet, local receipts, external account actions. |

The tab row stays immediately beneath the app bar while content scrolls. Use a single scroll container per tab, preserving each tab's position. Do not add a second row of top-level tabs. Period filters, routing presets, and profile chips are controls inside cards, not competing navigation.

**Navigation ownership.** Change `OPENROUTER_SETTINGS = "openrouter_settings"` to a connection-aware destination, for example `openrouter_settings/{connectionUid}?tab={tab}`. The existing `provider_settings/{connectionUid}` route provides the connection identity to forward. Keep the old route as a compatibility entry: resolve exactly one eligible connection or show a chooser; never silently choose the first. [R13]

**State transitions.** New connections open Setup. Successful validation enables the other tabs but does not unexpectedly navigate away from Setup. Removing or invalidating the active key returns to Setup, clears protected content, and announces the change. After a process restart, a stored last-selected tab is only restored after the authentication gate permits it.

**Protected-route behavior.** A direct Account link while locked redirects to Setup with “Connect OpenRouter to open Account.” The help explaining the lock remains fully readable beneath the tab row; the disabled tab itself does not become a hidden action button.

## 4. Credential validation and the 50% lock

Use a state machine, not `apiKey.isNotBlank()`. Keep `AuthenticationState`, `CapabilityState`, `BillingState`, and `FetchState` separate so a failure in one panel cannot disable unrelated features.

| State | Setup | Routing / Account | Required behavior |
|---|---|---|---|
| Missing or unvalidated key | Enabled | Disabled, alpha 0.50 | Show connection action and short explanation. |
| Validating a new key | Enabled; prevent duplicate submission | Disabled, alpha 0.50 | Cancel action available; no arbitrary unlock timer. |
| Valid inference key | Enabled | Enabled, alpha 1.00 | Show verified timestamp and capabilities. |
| Valid key, no allowance | Enabled | Enabled, alpha 1.00 | Show budget warning and relevant website action; not “invalid key.” |
| Explicit 401 / known expired key | Enabled | Disabled, alpha 0.50 | Invalidate only the matching active credential revision. |
| Management/provisioning-only key in inference slot | Enabled | Disabled, alpha 0.50 | Explain role mismatch; do not send a chat test automatically. |
| Network failure before first validation | Enabled | Disabled, alpha 0.50 | “Could not verify. Check your connection and retry.” |
| Network failure after validation in this session | Enabled | Unlocked shell; cached panels labeled stale | Keep last verified identity; disable operations requiring fresh permission data. |
| Permission failure on optional account resource | Enabled | Stay enabled | Only that resource shows restricted/unavailable. |

**Validation transaction.** Normalize outer whitespace; reject embedded control characters and malformed endpoint URLs locally. Send the draft credential to the approved endpoint's `/key`, then validate the response shape and credential role. Commit the credential to the existing vault and the connection configuration only after success. Bind the result to `(connectionUid, endpointOrigin, credentialRevision, attemptId)`. A cancelled or old result must never activate a replacement credential.

**Replacing a working key.** The old committed connection remains active while the user edits a replacement; show “Editing replacement — current connection still active.” Existing tabs continue to represent the old account. Only a successful atomic replacement changes identity, clears cached account panels, and starts scoped refreshes. Removing the active key is a separate confirmed action.

**Compose implementation contract.** Use a tab model containing `enabled`, `selected`, icon, and label. Disabled colors are opaque neutral tokens, with a single parent alpha of 0.50; do not multiply the default Material disabled alpha by another 0.50. Use `Role.Tab`, selected/disabled semantics, and no executable click action when locked. Guard the navigation reducer as well as the composable. Do not use a swipe pager that can bypass disabled tabs.

## 5. Setup tab specification

**First viewport.** A compact branded hero reads “Connect OpenRouter” with “One connection, many AI profiles.” Below it is one prominent credential card, not a wall of toggles. Use the existing theme's surface hierarchy; do not force a separate OpenRouter color scheme.

| Component | Specification |
|---|---|
| Connection name | Local label; editable without altering account identity. Explain that it is not an OpenRouter username. |
| API key field | Masked by default; paste and reveal actions; no automatic clipboard inspection; never place secret text in saved navigation state. |
| Primary action | **Validate & Connect** for first setup; **Validate Replacement** for changes. Enter/IME submit uses the same reducer. |
| Secondary actions | **Get an API Key**, **Open OpenRouter**, and **Connection Help** open approved external destinations without credentials in URLs. |
| Status card | Connected, checking, offline, invalid, or restricted; last successful verification time; no raw error dumps. |
| Endpoint selector | Global default, plus EU/US options when account capabilities permit. Advanced custom gateway mode is explicitly separate. |
| Linked profiles | Count, names, model IDs, and **Manage Profiles**. Creating another profile does not duplicate credentials. |
| Diagnostics sheet | Endpoint origin, status category, duration, retry-after when provided, and sanitized request ID. **Copy Diagnostics** excludes secrets and private account identifiers. |

**Endpoint rules.** Official selections use `https://openrouter.ai/api/v1`, `https://eu.openrouter.ai/api/v1`, or `https://us.openrouter.ai/api/v1`. Regional availability and guardrails are not inferred from the phone's location. Respect server-reported allowed regions and verify regional access; never fall back from a restricted region to global automatically. [S14]

Custom gateways retain their existing compatibility role but cannot masquerade as a directly verified OpenRouter account. Require an explicit trust confirmation before transmitting a credential to a new host. Prefer a separate gateway credential. Never probe OpenRouter's account APIs through an arbitrary custom URL, append credentials to links, accept userinfo/query secrets, or follow an authenticated redirect to a different origin.

**Saving and cancellation.** Credentials require explicit validation and commit. Nonsecret Setup edits use a clear Save/Discard state. Navigating back with an unsaved draft asks whether to discard it; scrolling or switching cards never saves it implicitly. Failed validation does not overwrite the previously working connection.

## 6. Routing tab: useful exclusive controls

The first card is an **Effective Route Summary**: current profile, exact model ID, inherited strategy, price information, and the strongest active restrictions. A profile selector previews each linked profile without changing which model that profile uses. A provider-default view makes inheritance explicit.

**Routing presets.** Offer Balanced, Lowest Price, Fast Response, High Throughput, and Custom. These are local convenience presets, not claims of official OpenRouter named modes. Show a small change preview before applying a preset so it cannot erase existing restrictions. Balanced omits explicit sort; the existing price-sorted configuration remains unchanged during migration.

| Control | Wire mapping | Default / behavior |
|---|---|---|
| Routing strategy | `provider.sort` | Inherit; mapped values `price`, `latency`, or `throughput`. |
| Preferred providers | `provider.order` | Ordered chips with accessible Move Up/Down actions; preference, not an allowlist. |
| Allowed providers | `provider.only` | Strict selection; empty means unset, not “deny all.” Explicit no-match state prevents sending. |
| Excluded providers | `provider.ignore` | Searchable selection; conflict with allowed providers blocks Apply. |
| Provider fallback | `provider.allow_fallbacks` | Preserve existing value; affects provider fallback, not model switching. |
| Require supported parameters | `provider.require_parameters` | Optional stricter routing; warn when a model has no known compatible endpoints. |
| Data collection restriction | `provider.data_collection` | Inherit or deny; UI must not claim it changes account-wide settings. |
| ZDR endpoints only | `provider.zdr` | Inherit or true; separate from account-level ZDR and local storage policy. |
| Input/output price ceiling | `provider.max_price.prompt/completion` | Decimal USD per million tokens; blank is unset, zero is an intentional ceiling. |
| Image/request price ceiling | `provider.max_price.image/request` | Advanced, applicable units only; never converted as token prices. |
| Preferred throughput / latency | `provider.preferred_min_throughput`, `preferred_max_latency` | Optional targets, not guarantees; simple scalar first, percentile editor advanced. |
| Quantization filter | `provider.quantizations` | Advanced dynamic options; explain reduced provider choice without promising quality. |
| Distillable text only | `provider.enforce_distillable_text` | Expert option with terms explanation; hidden from the first viewport. |

The mapping follows the provider-selection reference. Price caps are hard routing restrictions; performance targets are preferences. A provider price ceiling is **not** a whole-request or monthly spending limit. [S6]

**Provider selector.** Populate identities from provider/endpoint metadata, retaining exact slugs including endpoint suffixes. Display names are labels only. Show provider privacy/terms/status links in a detail sheet. A status link is not proof of the particular endpoint's availability. [S7]

**Model fallback separation.** Keep model fallback chains in profile settings with distinct labels. Never change the selected model merely because a cheaper provider is available. If model fallback is enabled, store requested and actually resolved model identities in receipts and show “Model may change” in the estimate. Model fallback must not be confused with the app's combined-model responses or delegation.

**Profile override behavior.** Each editable field supports **Inherit**, **Set value**, and, only where safe, **Clear override**. Display the source of the effective value. Existing profile values win over new provider convenience defaults; account/server restrictions and explicit app safety restrictions are never weakened by this precedence.

## 7. Routing details: prices, caches, batches, and advanced options

### Live model-price panel

For the selected profile, show exact model ID, input and output rates, currency/unit, and last successful retrieval time. Rates appear in an accessible semantic green; the words “Input” and “Output” carry meaning independently of color. Add **Compare Providers**, **Refresh Prices**, and **Open Model Details**.

Fetch the user-filtered catalog after validation. Its eligibility respects account preferences and guardrails; it is not proof that a specific request will succeed. Request model endpoint details lazily, only when a model is viewed. Support capability chips such as tools, reasoning, modalities, and structured output only when returned metadata supports them. [S4, S5, S8]

A flexible router or multi-model profile does not have one guaranteed fixed price. Show **Variable — depends on route**, an explicitly labeled range where defensible, and actual receipts after execution. Never display the lowest catalog rate as “your active price” when the route is unresolved.

### Keep three cache systems distinct

| System | User-facing controls | Guardrails |
|---|---|---|
| Provider prompt cache | Sticky routing toggle; cache-read/write counters in receipts | Best effort, model dependent; no guaranteed savings. Explicit provider order takes precedence over sticky routing. [S10] |
| OpenRouter response cache | Enable, TTL, and **Fresh Response for Next Request** | `X-OpenRouter-Cache`, TTL header, and single-request clear header. No “clear all server cache” claim. [S11] |
| Local queue-result cache | Enable, TTL, size, clear local cache | App-owned, scoped by connection and complete request identity; distinguish local hits from remote generation. |

Use stable, opaque session IDs, not conversation titles or user identities. Keep separate sessions per connection, conversation, and execution lane so combined-mode workers do not accidentally share unrelated routing state. Response caching can replay tool calls; do not automatically execute cached mutating tool actions without the existing permission and idempotency checks.

Account-level ZDR disables OpenRouter response caching. Per-request `provider.zdr` does not itself disable it. Explain both precisely; an optional app **No Remote Response Storage** policy should explicitly turn response caching off and reject incompatible batch submission. That additional policy is an app decision, not a claimed API equivalence. [S11]

### Batch workspace

Keep batch size, flush timeout, retry controls, and local queue controls under a collapsed Batch section. Add job status, counts, progress, last update, result export, and deliberate cancel/delete actions. Never route an interactive chat into a background batch simply because batching is enabled.

The current batch interface is `/api/v1/batches`; it accepts a limited provider policy, unlike synchronous chat. Serialize only supported batch controls, including `provider.only` and `provider.allow_fallbacks`, before `requests` as required by the batch format. If a user's hard policy cannot be enforced, block batch submission and offer synchronous execution; do not silently drop price/privacy restrictions. Preserve the existing serializer's ordered envelope while extending it. [R11, S12]

Display retention information before submission: OpenRouter documents a 30-day input/result retention window, with upstream retention varying by provider. Do not promise complete upstream deletion from a local clear-cache action. Polling, cancellation, and download resume must use the original connection identity. [S12]

**Shared numeric specification.** Preserve existing stored values within validated bounds. Use a numeric field plus stepper for batch size (1–100, default 10), flush delay (1,000–30,000 ms, default 5,000), and retries (0–10, default 3). The local queue-cache editor supports 0 seconds as disabled reuse and 1–86,400 seconds as an app-chosen TTL range, preserving the existing 300-second default. OpenRouter response-cache TTL is a separate field with its documented 1–86,400-second range and 300-second default. Do not bind the two TTLs to the same preference. [R4, S11]

### Other exclusive options without clutter

Offer opt-in app attribution with the official title/referer headers and a concise privacy explanation. Do not expose “hide my account” as a toggle: the documented app visibility header concerns app registration, not user identity. Preserve existing reasoning and plugin configuration in profile settings and link to them from **Profile Extras** rather than duplicating sliders here. [S13, R10]

## 8. Account tab: allowance, usage, and optional wallet

Start with a compact summary that identifies the scope: **This API Key**. Do not call it “Account Balance” unless a wallet endpoint has actually supplied account-wide data.

| Card | Fields to render when returned | Presentation rule |
|---|---|---|
| Key identity | Label, credential role, expiry, free-tier indicator | Mask secret-like labels; do not infer a person's name, email, paid plan, or subscription. |
| Key allowance | Limit, remaining allowance, reset interval, BYOK inclusion | Null limit means no key-specific cap, not unlimited account funds. |
| Effective budget | Limit, spend, remaining amount, scope, reset time | Prefer the returned effective budget; do not add multiple overlapping budgets. |
| Budget details | Individual budget records and scope | Collapsed details; distinguish member/workspace/key restrictions where reported. |
| Usage | Lifetime, daily, weekly, monthly key usage; separate BYOK usage | Source and period labels visible; keep overlapping periods out of additive charts. |
| Free-model requests | Returned used, remaining, and limit | Show only when supplied; do not invent a counter from deprecated rate-limit metadata. |
| Workspace / organization | Returned identifiers and permitted data regions | Collapsed; IDs are not friendly account names unless separately resolved. |
| This-device activity | Recorded requests, failures, latency, tokens, charged/estimated spend | Never label local totals as the complete OpenRouter account history. |

These conditional panels map to the current-key response; all optional fields must tolerate null, omission, and newly added values. The deprecated `rate_limit` object is not a reliable live quota display. [S1]

**Primary actions:** Refresh, Hide Amounts, Open Usage Dashboard, Add Credits, Manage API Key, Manage BYOK, and Privacy / Account Settings. Browser actions clearly show an external-link icon; app settings must not imply they changed server account settings. Validate official destinations during implementation and avoid undocumented deep links.

**Optional local spending alerts.** Offer off-by-default allowance reminders, a user-set USD threshold, and per-device daily/monthly spending warnings. Label them “App reminders — not a server spending limit.” Request notification permission only when enabling notifications. Do not show a predictive number of remaining chats as fact; a planning estimate needs an explicit token/model assumption.

### Optional account-wide read access

Ordinary inference keys do not provide the documented wallet/analytics access. `/credits` requires a management key, and `/activity` is a management-key analytics resource. These permissions are independent of unlocking Routing and Account. [S2, S3, S9]

The default experience should offer **Open Wallet in Browser**. For users who explicitly want native wallet/analytics, implement an advanced **Connect Account Read Access** sheet with an optional session-only management credential. Explain that the credential itself has broad management authority even though this app uses only an allowlisted set of GET requests; it is not a scoped read-only key.

Do not save that management key in settings, profiles, backups, telemetry, or normal credential autofill. Keep it in a dedicated short-lived in-memory session and clear it on disconnect, backgrounding, or process death. Never use it for inference. A future secured broker can replace this method without changing the UI data contract.

Verify management role separately and compare returned organization/workspace/principal context when available. Do not assume two supplied keys belong to the same account. If identity cannot be established, show an isolated **Separate Account Access** panel; do not combine its wallet with the active inference key's allowance or estimated remaining capacity.

The optional analytics chart must identify its account/workspace scope and the last completed UTC reporting day. The reviewed activity endpoint covers the last 30 completed UTC days; it is not an intraday live feed. Offer workspace filtering when available and do not splice this delayed series into today's local activity without visible source boundaries. [S9]

A restricted resource produces a restrained card: “Account-wide balance needs additional access. Your chat connection is still working.” It must not generate a red provider-offline indicator or repeated prompts to supply an administrator key.

## 9. Money, pricing, and activity correctness

Maintain distinct types: `KeyAllowance`, `EffectiveBudget`, `WalletSnapshot`, `CatalogRate`, `PriceEstimate`, and `GenerationReceipt`. A generic `balance: Double` must not represent all six.

**Decimal handling.** Decode monetary strings or JSON decimals without binary floating-point conversion. Store normalized decimal strings or a suitable exact decimal representation; use `BigDecimal` for calculations. Explicitly set `Currency.getInstance("USD")`. Localized grouping and decimal separators are welcome; conversion to CAD is an optional separately sourced estimate, not a relabeling.

**Rate conversion.** Catalog input/output rates are per token; display them per million tokens by multiplying by 1,000,000. In contrast, the routing `max_price` input/output controls are already per million tokens. Image and per-request units remain their native units. Preserve applicable cache, modality, and tier metadata without flattening everything into one text-token rate. [S4, S6, S8]

**Worked example — illustrative only.** A synthetic model charging USD 0.0000015/input token and USD 0.000006/output token displays USD 1.50/M input and USD 6.00/M output. An 8,000-input / 2,000-output text estimate is USD 0.024 before any unmodeled fees. This is not a price quote for any named model.

**Freshness model.** Every snapshot stores fetched-at, source, scope, endpoint origin, credential revision when applicable, and status. Show “Updated just now,” “Cached · 18 min ago,” “Stale,” or “Unavailable.” Only a successful response updates freshness. A refresh failure preserves the last value with a stale label; it never becomes zero, free, or fresh. Avoid hardcoded named-model price tables in the production UI.

**Proposed refresh policy.** Coalesce requests per connection. Refresh `/key` on validation and Account entry when older than 60 seconds; refresh active-user catalog/prices when older than 15 minutes; refresh visible endpoint detail when older than 5 minutes. Apply a 10-second manual-refresh cooldown and server retry guidance. These are tunable app defaults, not OpenRouter quotas. Never poll while a tab is hidden or the app is backgrounded merely to keep a badge green.

**Actual accounting.** Consume the final streaming usage message and nonstreaming response usage. Full usage is now included automatically; do not add a legacy “Enable usage reporting” switch or rely on deprecated include-usage flags. Persist returned cost, token details, generation ID, model, provider when supplied, and cache status. [S15]

**Missing receipts.** An interrupted stream with unknown cost displays “Cost pending,” not $0. Reconcile by generation ID when available and authorized; never resend the completion merely to recover billing information. Upsert by connection-scoped generation identity. A client retry may create another billable generation; the UI groups attempts but does not erase them. BYOK provider inference estimates and OpenRouter charges remain separate to prevent double-counting. [S16]

## 10. Visual system and interaction specification

Reuse `SettingsHero`, `SettingsPanel`, `SettingsHelpIcon`, `AppShapes.panel`, and `AppSpacing.screen`. The existing `SettingsTabs` helper currently accepts labels and selection only; add an overload with typed tab items and enabled state, or introduce `OpenRouterTabRow` without changing other settings screens' behavior. [R12]

| Element | Proposed specification |
|---|---|
| Page surface | Theme surface background; no nested full-page gradients. One restrained tonal hero may carry identity. |
| Tabs | Three equal visual slots at normal font scale, icon plus short label; 48dp minimum interactive target. Adaptive wrapping/scrolling at large type rather than shrinking labels. |
| Cards | Existing panel shape; 16dp content padding, 12–16dp vertical gaps, 8dp related-control spacing. |
| Hierarchy | Headline for one primary amount or status; title for each section; body for values; label text for scope and freshness. |
| Icons | Rounded family; Key, Route, Wallet, Verified, Refresh, Lock, Info, OpenInNew. Decorative icons have no duplicate spoken label. |
| Monetary values | Semantic green with tested contrast; neutral unit labels; amber for stale/near-limit; red for actual error, not “has spent money.” |
| Advanced controls | Collapsed sections with a short effective summary and changed-setting count. No hidden critical warnings. |
| Motion | Subtle tab indicator and 160–220ms content transition; respect reduced motion; no continuous decorative glow or shimmer. |
| Saving | Sticky bottom Apply/Discard bar only when nonsecret edits are dirty; respect IME and navigation insets. |
| Empty/error states | Localized sentence, one recovery action, optional technical details; preserve unaffected cards. |

**Screen reading order.** Header → tabs → gate explanation/status → active tab heading → primary card → secondary cards → advanced controls → action bar. Announce validation results and changed budgets without repeatedly interrupting TalkBack during polling.

**Accessibility.** Use proper tab, switch, button, and progress semantics. Disabled tabs must remain understandable as unavailable destinations, not disappear. Question-mark buttons need contextual names such as “About remaining key allowance.” Maintain 48dp touch targets, support 200% font scaling, and make graphs available as a text/table view. Test light, dark, dynamic/custom palettes, RTL, keyboard input, and display cutouts. [S17]

**Representative first-view layouts:**

```text
SETUP                             ROUTING                           ACCOUNT
OpenRouter · Personal             OpenRouter · Personal             OpenRouter · Personal
[Setup] [Routing locked] [Account locked]  [Setup] [Routing] [Account]  [Setup] [Routing] [Account]
Connect OpenRouter                Effective route                    This API Key
[Masked key ..........]           [Profile selector]                 [Remaining allowance]
[Validate & Connect]              Model ID + freshness               Budget / reset / scope
Get a key · Connection help       Input price | Output price         Today | Week | Month
Endpoint: Global                  [Compare] [Refresh]                [Refresh] [Hide Amounts]
Linked profiles                   Routing strategy                   This-device activity
                                  Provider / privacy sections        Account access (optional)
```

The text layout is a structural wireframe, not a pixel-accurate rendering or a real account screenshot. Locked tabs in production must meet the exact 0.50 opacity contract, not merely include a lock label.

## 11. Data architecture and state ownership

Use the existing Kotlin, Compose, Hilt, repository, and vault architecture. Avoid introducing a second preference store or an independently constructed HTTP client for every card. [R1, R5, R8]

**Proposed flow:** UI intent → `OpenRouterSettingsViewModel` → connection-scoped repositories → immutable state. At execution time, the active connection and profile feed an `OpenRouterPolicyResolver`, whose result is passed to the existing transport's request serialization boundary. Response usage feeds a receipt repository and the current statistics views.

| New type / service | Responsibility |
|---|---|
| `OpenRouterWorkspaceState` | Selected connection/tab, authentication, capability states, draft settings, resource states. Never includes a revealable key in exportable state. |
| `OpenRouterCredentialSession` | Active secret reference, credential revision, validation context, private draft handling. |
| `OpenRouterAccountRepository` | `/key` snapshots, account-read session capabilities, scoped refresh/cancellation. |
| `OpenRouterCatalogRepository` | Shared catalog, authenticated eligible models, endpoint detail, price provenance. Prefer extending/wrapping the existing model repository. |
| `OpenRouterProviderPolicy` | Typed tri-state inherited controls and supported extension fields; no unvalidated raw JSON editor. |
| `OpenRouterPolicyResolver` | Provider defaults + profile overrides + mandatory restrictions + execution-mode capability checks. |
| `OpenRouterRequestDecorator` | Adds supported routing/body/header fields once, before sending; never modifies other providers' requests. |
| `OpenRouterUsageRepository` | Idempotent receipts, source labels, retry grouping, reconciliation status, local aggregates. |

**Identity keys.** Use `(connectionUid, origin, credentialRevision)` for account resources and `(origin, modelId, routingPolicyVersion)` for route-sensitive price previews, adding credential scope for user-filtered data. An account's public label is never a primary key. Where a fingerprint is needed, use a nonexported keyed digest or vault identifier, not `String.hashCode()`.

**HTTP layer.** Inject the existing approved network stack and one consistently configured JSON parser. Keep TLS verification enabled, bound response sizes, redact Authorization and secret-looking error text, and use typed errors. Use `CancellationException` propagation rather than turning cancellation into a failed-provider event. Close response bodies and bind each result to the request context that produced it.

**API resource contract.** All paths are relative to an explicitly approved `/api/v1` origin; authorization roles and regional eligibility remain resource-specific.

| Resource | Method / path | Use and access treatment |
|---|---|---|
| Current key | `GET /key` | Validate inference credential; load optional key/budget metadata. [S1] |
| Eligible models | `GET /models/user` | Authenticated catalog; a failure never proves all public models are eligible. [S5] |
| General catalog | `GET /models` | Catalog fallback clearly labeled as not user-qualified; retain authoritative request-time checks. [S4] |
| Model endpoints | `GET /models/{author}/{slug}/endpoints` | On-demand route/price details; encode segments and preserve model variants. [S8] |
| Providers | `GET /providers` | Provider identity and documented external policy/status links. [S7] |
| Generation metadata | `GET /generation?id={id}` | Receipt reconciliation only; no prompt-content retrieval required. [S16] |
| Wallet | `GET /credits` | Optional management session; never used to validate ordinary chat access. [S2] |
| Account activity | `GET /activity` | Optional management session; completed-day, scoped reporting. [S9] |
| Batch lifecycle | `POST /batches`; `GET /batches/{id}` | Existing queued execution; explicit mode/capability checks. [S12] |

**Capabilities are per resource.** Current-key success does not prove wallet access, entitlement to every regional endpoint, or support for every model parameter. A capability record may be Available, Restricted, Unsupported, Unknown, or TemporarilyUnavailable. Optional failures must not erase a previously valid identity.

## 12. Request-policy composition and execution wiring

Keep request configuration and account administration separate. The app can change its outgoing `provider` object; that does not mean it has updated the user's OpenRouter website preferences or budget rules.

**Resolution rules.** Preserve the explicit selected profile model. Begin with provider defaults, apply profile overrides, then enforce non-overridable restrictions. For restrictions, intersect allowed-provider sets, union excluded sets, choose the tighter numeric ceiling, and retain denial/ZDR requirements. Empty intersections fail with an actionable error. Do not accidentally union allowlists or interpret missing fields as explicit false.

**Illustrative synchronous policy envelope:**

```json
{
  "model": "<exact-model-id-from-selected-profile>",
  "messages": "<existing-serialized-message-array>",
  "provider": {
    "sort": "price",
    "allow_fallbacks": true,
    "require_parameters": true,
    "data_collection": "deny",
    "max_price": { "prompt": 2.0, "completion": 8.0 }
  },
  "session_id": "<opaque-conversation-lane-id>",
  "stream": true
}
```

This is a mapping example, not a runnable payload: preserve the existing actual message array, tool definitions, attachments, reasoning fields, and response-format configuration. The illustrative numeric ceilings are not recommended prices. Include only explicitly resolved controls and qualified headers. Omitted/default values must remain semantically distinct.

**Mandatory execution-path coverage.** Trace each call site using `OpenRouterProviderRouting` or constructing an OpenRouter-compatible request. Integrate the resolver into ordinary chat, streaming and nonstreaming generation, regenerated responses, combined-mode contributors, final synthesis, delegate/reviewer/helper calls, benchmarks, and queued/batch submission where compatible. This call graph was not exhaustively audited for this document; completing it is an explicit implementation gate, not an assumed existing capability.

Each delegate uses its own selected connection and profile, not the parent's API key or routing policy by accident. Share price metadata where safe, but not mutable account state or inference budgets between unrelated connections. Combined-mode estimates sum distinct planned calls with an uncertainty label; actual receipts retain each contribution.

**Benchmarks.** Bypass local and remote identical-response caching for meaningful fresh measurements. Record model, serving provider when known, policy version, cache status, execution role, and timestamp alongside the result. Do not treat a cached result as a new model-performance score. Reuse this catalog/pricing source in a future New Chat price panel rather than creating another hardcoded pricing implementation.

**Batch policy gate.** A batch transport must not serialize the entire synchronous provider object. Supported policy belongs at the batch envelope level; model and endpoint are also explicit. When mandatory restrictions cannot be represented and cannot be verified as server-enforced, require synchronous execution or refuse submission with a clear reason. Never silently “best effort” a hard privacy or price requirement.

## 13. Repository change map

All paths below are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/` unless stated otherwise. **Existing — read** means source was inspected. **Integration checkpoint** means the path was discovered or identified by a verified route but its full implementation still needs tracing. **New** means a proposed file, not an existing one.

| Path / location | Status | Planned change |
|---|---|---|
| `presentation/ui/setting/OpenRouterSettingsScreen.kt` | Existing — read | Replace long list with host, tab row, gate, and three tab bodies. |
| `presentation/viewmodel/OpenRouterSettingsViewModel.kt` | Existing — read | Single immutable StateFlow; typed resource states; draft/commit/revision reducer. |
| `presentation/ui/setting/SettingsDesign.kt` | Existing — read | Reuse hero/panel/help styling; add enabled/icon-aware tab overload without breaking callers. |
| `presentation/common/Route.kt` | Existing — read | Add connection-aware route and backwards-compatible resolver. |
| `presentation/common/NavigationGraph.kt` | Integration checkpoint | Forward exact connection ID; gate deep links and restored selection. |
| `presentation/ui/setting/ProviderConnectionSettingsScreen.kt` | Integration checkpoint | Open the correct OpenRouter connection workspace. |
| `data/repository/OpenRouterSettingsRepository.kt` | Existing — read | Explicit connection arguments and field updates instead of implicit global ownership. |
| `data/repository/OpenRouterSettingsRepositoryImpl.kt` | Existing — read | Remove first-profile lookup; migrate preferences per connection; preserve SecretVault. |
| `domain/model/OpenRouterSettings.kt` | Existing — read | Version settings, add policy references, remove secrets from portable UI/config models. |
| `data/repository/OpenRouterCreditsRepository.kt` | Existing — read | Isolate optional account-read access; typed restriction state, exact decimal values, scoped cache. |
| `data/openrouter/OpenRouterModel.kt` | Existing — read | Extend tolerant catalog/pricing/capability DTOs. |
| `data/repository/OpenRouterModelRepository.kt` | Existing — read | Add scoped user catalog, endpoint details, freshness, and shared injection. |
| `presentation/ui/setting/OpenRouterModelPickerDialog.kt` | Existing — read | Consume shared catalog; display prices/capabilities; preserve selection callback. |
| `data/openrouter/OpenRouterAdvancedOptions.kt` | Existing — read | Typed routing extensions, compatibility decoder, validated serializer. |
| `data/openrouter/OpenRouterBatchClient.kt` | Existing — read, lines 1–150 | Extend supported envelope policy; reuse safe transport; scope jobs and sanitize errors. |
| `presentation/ui/setting/FancyOpenRouterCreditsCard.kt` and `presentation/ui/common/CreditsViewModel.kt` | Integration checkpoint | Reconcile duplicate credit UI with shared account repository; avoid contradictory balances. |
| `presentation/ui/setting/UsageStatisticsViewModel.kt` | Integration checkpoint | Consume shared local receipts with scope labels and no duplicate counting. |
| `presentation/ui/setting/openrouter/` | New | Setup/Routing/Account tab components, stateful cards, dialogs, previews, accessibility tests. |
| `domain/openrouter/` | New | Policy resolver, money types, capability rules, validation-state contracts. |
| `data/openrouter/account/` and `data/openrouter/usage/` | New | Account DTOs/client, optional session access, generation receipts and reconciliation. |
| Hilt modules, resources, backup serializer, Room schema | Integration checkpoints | Bind services, localize strings, migrate portable data, add receipts with verified schema version. |

Do not assign a database migration number from memory. Inspect the current `ChatDatabaseV2` schema and all outstanding migrations on the actual implementation branch before adding a receipt entity. Keep settings storage in DataStore unless structured historical data warrants Room. [R1]

## 14. Persistence, migration, and safety

**Migration sequence.** Read the legacy `openrouter_batch_settings` object and enumerate actual provider connections. Build a deterministic mapping using existing connection UIDs and linked profiles. With exactly one eligible connection, migrate the old provider defaults to that connection. With multiple ambiguous connections, do not copy a secret or infer ownership: retain legacy data read-only and ask the user to choose which connection inherits the defaults. Preserve explicit per-profile routing settings. [R5]

Keep existing batch size, flush timeout, retries, cache flags, TTL, sticky routing, and response-cache preference where valid. Invalid numeric values receive the documented shared clamp and an audit note. Newly introduced expensive or privacy-sensitive capabilities default off. Existing saved credentials start Unvalidated after upgrade; cached validation is not a transferable capability.

Use schema-versioned, idempotent migrations and a settings snapshot for rollback. The new UI may be feature-flagged, but rollback must not restore unsafe first-profile ownership or plaintext secrets. Backups contain portable settings and optional historical receipts, not active validation state, secret references usable on another installation, draft keys, or management sessions. Restores require reconnecting credentials.

**Local-cache redesign.** Canonical identity includes connection scope, endpoint, full model ID/variant, request body, effective policy, tools, attachments/content digests, and cache version. Avoid caching operations dependent on mutable external state, sensitive attachments, or mutating tool calls by default. Never use a hash of only prompt text, temperature, and max tokens for general agent requests. [R4]

**Policy changes during runs.** A submitted call keeps its recorded immutable request policy. Apply changed settings to future calls. Stronger restrictions may pause queued-but-not-submitted work for revalidation; weakening policies needs deliberate Apply. A key rotation cannot silently resume another account's job.

**Error and retry taxonomy.** Distinguish authentication, forbidden resource, insufficient allowance, rate limit, no eligible route, unsupported parameter, regional restriction, malformed response, transport outage, and cancellation. Honor retry-after and bounded jittered retry rules. Do not retry invalid credentials or silently broaden provider filters. Avoid replaying a generation after partial output unless the user or existing recovery workflow explicitly permits another charge.

**Security acceptance.** Existing vault semantics are mandatory. Raw request/response logging is off for authentication/account APIs. Sanitize error bodies before display and export. Sensitive values do not enter analytics, crash breadcrumbs, screenshots used in tests, public scoreboards, or repository fixtures. No TLS bypass or authenticated cross-origin redirect is permitted.

## 15. Phased implementation plan

Each phase should be a reviewable commit group on the implementation branch. Do not publish a release merely because a draft UI renders.

| Phase | Work | Exit evidence |
|---|---|---|
| 0. Pin and trace | Re-resolve branch head; inspect transport call graph, provider ownership, Hilt bindings, schema, backups, and duplicate credits UI. Freeze captured API fixtures. | Verified file map and explicit execution-path checklist; no guessed migration number. |
| 1. Connection ownership | Connection-aware route/repositories, secure drafts, revision-scoped validation, locked-tab state machine. | Unit tests for key races, restore/deep links, multiple accounts, and migration. |
| 2. Tabbed interface | Setup/Routing/Account scaffolding, shared cards/help, loading/errors, localization, adaptive layouts. | Screenshot and semantics tests for exact locked state and large-font layouts. |
| 3. Account and pricing | Current-key data, effective budgets, eligible catalog, endpoint detail, decimal formatting, refresh/provenance. | Contract fixtures and price/allowance tests, no management key required for normal setup. |
| 4. Runtime policy | Typed routing controls, profile inheritance, actual request decorator, caches and batch capability gate. | Recorded test requests prove policies reach every supported execution path. |
| 5. Receipts and optional access | Terminal usage capture, reconciliation, local activity, optional management-session read access. | Idempotent accounting, account isolation, background credential clearing, restricted-resource tests. |
| 6. Qualification | Full Android build/tests, targeted live smoke test with a disposable limited key, device UX and performance. | Passing checks, measured UI evidence, no unauthorized charges or key leaks. |

Suggested Gradle verification begins with `./gradlew test`, `./gradlew assembleDebug`, and `./gradlew connectedAndroidTest`, using the repository's supported Java/toolchain. Discover the applicable lint task rather than assume a task name. Instrumented/network smoke tests require suitable devices and explicit test credentials; public documentation inspection is not a substitute. [R1]

**Rollout flags.** Separate `openRouterWorkspaceV2`, `openRouterPolicyV2`, and optional `openRouterAccountReadSession` so UI rollout cannot inadvertently turn on new network behavior. Unsupported controls should not appear enabled merely because the screen flag is on. Never use rollout flags to bypass authentication or security fixes.

## 16. Test matrix and definition of done

| Area | Required cases |
|---|---|
| Authentication | Empty, whitespace, malformed, valid, expired, revoked, management-only, provisioning-only, schema-invalid 200, rate-limited, and offline. |
| Gate | Tabs two/three exactly 0.50 alpha while locked; no tap/keyboard/accessibility/deep-link/restore bypass; zero allowance still unlocks. |
| Races | Validate A then B; A finishes last; rotate during refresh; disconnect during retry; change connection with a draft; process recreation. |
| Account isolation | Multiple keys and connections; null/overlapping budgets; organization versus personal context; mismatched optional management account. |
| Pricing | Per-token/M-token conversion; very small values; numeric/string decimals; missing/zero/negative-invalid fields; USD under Canadian locale; router/endpoint/tier changes. |
| Freshness | Offline cache, failed refresh, clock changes, manual-refresh spam, hidden tab, stale user eligibility, server retry-after. |
| Policy | Inherit/set/clear; order/only/ignore conflicts; price ceiling zero; unsupported parameter; no eligible endpoint; strict region; no weakening during fallback. |
| Streaming | Final usage-only chunk, empty choices, partial output, cancellation, duplicate terminal event, reconciliation failure, generation IDs from cache hits. |
| Execution paths | Chat, regenerate, nonstream, combined contributor, synthesis, delegation, reviewer, helper, benchmark, queue, and batch. |
| Cache / batch | Local identity separation; tool side effects; fresh benchmark; batch limited policy; 410 expired result; result export; retention warning. |
| Interface | 360dp width, landscape/tablet, 200% text, TalkBack, RTL, dark/light/custom themes, IME and navigation insets, reduced motion. |
| Security / restore | Vault preservation; secret-free export/logs; untrusted endpoint; redirects; cancelled drafts; management session cleared in background; backup reconnect. |

**Release acceptance.** All three tabs and their lock states work on a physical Android device. Every visible control has a persisted value, effect, and test, or is clearly an external website action. Money labels identify scope and source. User-specific data never flashes from another connection. No current-key failure is inferred from a restricted wallet endpoint. No hard routing restriction is silently dropped. No account-wide balance is fabricated from key limits. No cached benchmark is represented as fresh performance.

**Performance targets, to measure rather than assume:** show local screen shell without waiting on network; no network call on each keypress; no endpoint-detail request for every catalog row; no redundant refresh on recomposition; one coalesced account refresh per connection; no regular background polling. Record frame timing and scroll behavior on the user's target Android hardware during acceptance.

## 17. Open questions resolved conservatively

**Account amount information:** ordinary keys get meaningful allowance, budget, and usage panels. True account wallet data is optional and permission-gated, not faked. **Many useful settings:** advanced routing and account sections are available without overwhelming Setup. **Fancy UI:** use existing theme-aware surfaces, icon-led cards, restrained motion, and clear data hierarchy, not decoration that competes with controls.

**Unknown capabilities:** schema fields absent from a response remain Unknown/Not provided. A restricted optional endpoint stays restricted; the app does not solicit broader credentials automatically. **Current API evolution:** freeze fixtures and revalidate schemas at implementation time; documentation examples are not proof of access for the user's particular account. **Legacy routing extras:** do not introduce a new `z_weight` control based solely on its presence in an old DTO; qualify it against the current wire contract before allowing transmission.

**Out of first-release scope:** charging a payment method, purchasing credits in-app, full key-management CRUD, BYOK secret upload, organization administrator controls, editing server guardrails, and OAuth/native callback support without a dedicated security review. These are deliberate boundaries, not missing placeholder buttons. Website shortcuts keep those account functions reachable.

## 18. Sources and review provenance

Official documentation was reviewed on October 12, 2026. Examples and schema fields may evolve; implementations must tolerate optional fields and re-check contracts before release. The documents below support API facts; the UI structure, refresh schedule, security defaults, and engineering plan are this specification's recommendations.

### OpenRouter and Android sources

- **S1 — Current API key:** https://openrouter.ai/docs/api/api-reference/api-keys/get-current-api-key
- **S2 — Account credits:** https://openrouter.ai/docs/api/api-reference/credits/get-remaining-credits
- **S3 — Management API keys:** https://openrouter.ai/docs/guides/overview/auth/management-api-keys
- **S4 — Model catalog:** https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties
- **S5 — User-filtered model catalog:** https://openrouter.ai/docs/api/api-reference/models/list-models-filtered-by-user-provider-preferences-privacy-settings-and-guardrails
- **S6 — Provider routing:** https://openrouter.ai/docs/guides/routing/provider-selection
- **S7 — Providers:** https://openrouter.ai/docs/api/api-reference/providers/list-all-providers
- **S8 — Model endpoints:** https://openrouter.ai/docs/api/api-reference/endpoints/list-all-endpoints-for-a-model
- **S9 — User activity analytics:** https://openrouter.ai/docs/api/api-reference/analytics/get-user-activity
- **S10 — Prompt caching and sticky routing:** https://openrouter.ai/docs/guides/best-practices/prompt-caching
- **S11 — Response caching:** https://openrouter.ai/docs/guides/features/response-caching
- **S12 — Batch API:** https://openrouter.ai/docs/batch-quickstart
- **S13 — App attribution:** https://openrouter.ai/docs/app-attribution
- **S14 — In-region routing:** https://openrouter.ai/docs/guides/features/in-region-routing
- **S15 — Usage accounting:** https://openrouter.ai/docs/cookbook/administration/usage-accounting
- **S16 — Generation lookup:** https://openrouter.ai/docs/api/api-reference/generations/get-generation
- **S17 — Android Compose accessibility:** https://developer.android.com/develop/ui/compose/accessibility/api-defaults

### Repository evidence register

Repository root: `https://github.com/tailscale-signin/GPT_Mobile_AI-improved`. All source paths below use prefix `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`, except `AGENTS.md`. Files were read from `version-v0.9.50.0`; R10 was also read at the exact reported commit. Blob hashes record the reviewed content independently of later branch movement. A range denotes a partial read, not a full-file audit.

| ID | Reviewed file | Blob hash / scope |
|---|---|---|
| R1 | `AGENTS.md` | Repository development instructions; full returned content. |
| R2 | `presentation/ui/setting/OpenRouterSettingsScreen.kt` | `e0ae7e6fe3f1b5fa6296313f46e7b6a3dec0d1d7`; lines 1–290. |
| R3 | `presentation/viewmodel/OpenRouterSettingsViewModel.kt` | `2ce0d8d5f97bcee8d4b57227512f0173759ac1be`; full file. |
| R4 | `domain/model/OpenRouterSettings.kt` | `51f72e25fff70147a9172e447a9a0a671c2319b2`; full file. |
| R5 | `data/repository/OpenRouterSettingsRepositoryImpl.kt` | `9c5c315c5d34a74b3222a17c01e2cab78dad1bee`; full file. |
| R6 | `data/repository/OpenRouterCreditsRepository.kt` | `85b0962f669888eeb2fefd8b2c3dce0742a2ecdf`; full file. |
| R7 | `data/openrouter/OpenRouterModel.kt` | `bf6bcc9e3789c930823640ab737b2c98bd48e756`; full file. |
| R8 | `data/repository/OpenRouterModelRepository.kt` | `fc7880d4e868cf686fd267f2953be51184991b1e`; full file. |
| R9 | `presentation/ui/setting/OpenRouterModelPickerDialog.kt` | `5c91b13fd51a82f367f2f1affef8c4631767471f`; lines 1–180. |
| R10 | `data/openrouter/OpenRouterAdvancedOptions.kt` | `933e998736d37def4bb74fbbb98091376745a7ed`; full file, commit-pinned. |
| R11 | `data/openrouter/OpenRouterBatchClient.kt` | `aec37e5f2371095b31e7054208acef5715e7790b`; lines 1–150. |
| R12 | `presentation/ui/setting/SettingsDesign.kt` | `0b1f23970226f893a1ce0b947ce89d711b943fbf`; full file. |
| R13 | `presentation/common/Route.kt` | `8e603022c9c0e612b190a5967667bba04ee641dd`; full file. |
| R14 | `data/repository/OpenRouterSettingsRepository.kt` | `10facf292bcbb3c934a9f1be948c01e602f313ff`; full interface. |

**Handoff note:** Source-based findings and proposed architecture are intentionally distinguished. Neither the target transport call graph nor real account permissions were fully exercised. Implementation phase 0 closes those verification gaps before the request policy or database schema changes are treated as ready to ship.
