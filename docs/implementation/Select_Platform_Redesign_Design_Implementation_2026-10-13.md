# Source design transcription

ENGINEERING DESIGN  /  ANDROID
Select PlatformRedesign
Design & Implementation Specification
Compact profile cards. Clear model comparisons.Preserved selection, pricing and benchmark behaviour.
REPOSITORY
tailscale-signin/GPT_Mobile_AI-improved
VERIFIED BASE
main • 1d4dcd8698356abddc5f1be946bb0811017b7d7a
SOURCE VERSION
0.9.51.0 • version code 120
DOCUMENT
Version 1.0 • October 13, 2026
SUGGESTED BRANCH
feat/select-platform-ui-redesign-2026-10-13

STATUS  •  DESIGN READY / IMPLEMENTATION PENDING
No application files have been changed, built, tested, committed or published by this document. Proposed code and validation commands are implementation guidance, not completion evidence.
Repository-ready Markdown is the canonical companion. This edition presents the same specification in a paginated review format.

Contents
A complete implementation handoff, from the visible card layout to validation and rollback.
01   Purpose and approved direction
02   Requirements and acceptance mapping
03   Current implementation audit
04   Target layout and information hierarchy
05   Interaction specification
06   Architecture and state ownership
07   Proposed component and data contracts
08   Benchmark display policy
09   Pricing policy and detail disclosure
10   Label and button redesign
11   Async, lifecycle, performance and privacy
12   Accessibility, localization and motion
13   File-level implementation map
14   Implementation sequence and commit boundaries
15   Detailed test matrix
16   Build and validation commands
17   Additional repository improvements
18   Rollout, compatibility and rollback
19   Definition of done and implementation handoff
20   Sources and verification references
Reading guide
Sections 1–5 define the experience. Sections 6–10 define the engineering contracts. Sections 11–16 define quality gates and delivery work. Sections 17–20 cover related improvements, rollout and evidence.

1. Purpose and approved direction
Rebuild the Select Platform dialog as a compact profile picker. A person should be able to identify an AI profile, read its configured model, compare its benchmark and token rates, and select it without scanning diagnostic paragraphs or oversized label controls.
The default profile card contains exactly four text elements: the AI profile name, the model name underneath in the active theme colour, a large benchmark score at the upper end, and a small theme-coloured rate underneath the score. For left-to-right layouts, the score and rate are on the right. Selection and favourite icons are permitted; provider descriptions, attached label rows, provenance paragraphs and dates are not.
This specification replaces the earlier high-level plan where necessary. In particular, the implementation should introduce a picker-specific card rather than force every PlatformCheckBoxItem caller into the new layout. The score and price form one accessible detail action, not two vertically stacked undersized buttons. Existing selection, conversation modes, data resolution and privacy boundaries remain authoritative.
1.1 Design decisions
Decision
Required outcome
Compact identity
Name first; configured model second; no transport/provider prefix.
Metrics at the end
Benchmark is visually dominant; rate is smaller and themed.
Progressive disclosure
Provenance, dates, caveats and methodology move to an insight detail panel.
Rounded labels
One shared rounded design for interactive label controls, with a compatibility wrapper for existing callers.
No attached label row
Profile labels remain usable as filters and in label-management views, but do not make picker cards taller.
Preserve data integrity
Missing prices are not zero; missing scores are not poor scores; local speed is not a remote benchmark score.
Preserve behaviour
Favourite ordering, hidden selections, UID identity and Separate/Combined semantics do not change.
Narrow scope
No inference, delegation, memory, research, native-runtime or release redesign is included.

1.2 Evidence boundaries
Repository observations below refer to the pinned commit, not to every branch or a particular installed APK. The build file establishes the source version, but no device screenshot or runtime trace was supplied for this task. Visual measurements, performance budgets and proposed APIs are implementation requirements, not measurements of a completed build. Sources are listed in Section 20.
Before implementation, refresh the intended integration branch and compare the named files with this base. Do not overwrite concurrent changes to pricing, OpenRouter, local AI or diagnostics. Do not downgrade a newer branch to the version printed above.
2. Requirements and acceptance mapping
ID
Requirement
Acceptance evidence
SP-01
Render only the profile display name on the first identity line.
UI assertion excludes the client-type prefix.
SP-02
Render the configured model beneath it using the active theme accent.
Light/dark/theme-switch screenshots and text assertion.
SP-03
Show a large benchmark at the upper right in LTR.
Layout bounds and typography assertions.
SP-04
Place small, themed token rates beneath the benchmark.
Bounds assertion and rate-state fixtures.
SP-05
Remove the top-provider/list-route paragraph from the picker.
Screen-scoped absence assertion.
SP-06
Remove provider-check timestamps from the picker.
A dated fixture does not render its date on a card.
SP-07
Remove “Each selected AI keeps its own response tab.” from the picker.
String absence assertion in Separate mode.
SP-08
Redesign all profile-label buttons using a shared rounded component.
Call-site inventory and label editor/filter screenshots.
SP-09
Preserve benchmark and price detail access.
Detail action opens the correct profile context.
SP-10
Preserve Advanced Settings visibility and refresh controls.
All score/price visibility combinations tested.
SP-11
Keep selections stable through filtering, favourites and data updates.
UID-based state tests and UI integration tests.
SP-12
Keep combined mode available only with at least two enabled selections.
Mode transition tests.
SP-13
Keep disabled profiles hidden, as in the existing picker.
Existing ordering test plus new integration assertion.
SP-14
Avoid waiting for pricing or benchmarks before showing profiles.
Delayed and failed repository fixtures.
SP-15
Support large text, small windows, RTL, keyboard and TalkBack.
Device/emulator acceptance matrix.
SP-16
Make absence, stale data and local execution truthful.
Formatter, freshness and local-profile tests.

The phrase “only the AI profile name” describes the identity area. It does not remove the explicitly requested benchmark and price, the selection control or a compact favourite indicator. No profile label badges are displayed inside the new picker cards.
3. Current implementation audit
3.1 Verified observations
Source
Observed implementation
Design consequence
HomeScreen.kt
SelectPlatformDialog owns the header, mode chips, search, label filters, scrollable profile column and confirmation. It includes both general and mode-specific descriptions. [R2]
Extract the picker presentation without changing chat creation. Remove descriptions only from this surface.
HomeScreen.kt
The description passed to a card is ${platform.compatibleType.name} · ${platform.model}. [R2]
Pass the configured model alone to the new identity component.
PlatformCheckBoxItem.kt
A shared row stacks description, supporting content and attached labels. The checkbox and row both have click callbacks. [R3]
Introduce a dedicated picker card; define one selection action and a separate sibling insight action. Do not assume the current callbacks prove double activation.
NewChatProfileInsights.kt
Benchmark and rates are separate text buttons. Routing, conditional-price and freshness text appears below; prices use fixed green values. [R4]
Move compact values into the trailing column and move metadata into details. Use theme colours instead of hard-coded green.
ProfileLabelComponents.kt
BeveledProfileLabel uses cut corners. Its compact style multiplies font size and line height by 0.5f; large labels use substantial padding. [R6]
Replace the shape and sizing through compatible shared components. Do not halve text to make a control fit.
NewChatInsightsViewModel.kt
It combines profiles, feature settings, benchmark history, scoreboard data, prices and a one-minute clock; insights are mapped by profile UID. [R5]
Retain the data sources and introduce explicit display states, including first-load status.
NewChatInsightsViewModel.kt
The visible refresh routine awaits scoreboard refresh before entering its pricing loop. [R5]
Isolate refresh jobs so a slow or failed scoreboard operation cannot hold up price refresh. This is a code-level dependency, not a measured outage.
ProfileBenchmarkResolver.kt
Personal results take precedence in the caller; community is a fallback. Resolution is based on billing provider and configured model, with Full preferred over Quick among eligible entries. [R5, R9]
Reuse the resolver; do not sort arbitrary cached results or match on profile name.
ProviderPricingRepository.kt
Resolution honours context-scoped manual overrides, billing identity, validity and expiry. The inspected API refresh path is OpenRouter. [R8]
Do not describe every provider rate as live or bypass existing rules.
HomeViewModel.kt
Profile updates already preserve selections by UID while publishing a positional boolean list. [R11]
Preserve this protection; add UID-based events at the new picker boundary rather than declaring selection persistence missing.
ProfileDiscovery.kt
Search matches all query tokens against profile name, model, client type and serialized labels. [R12]
Preserve search behaviour initially; a parsed-label search cleanup can be a separately tested change.
ChatProfileOrderTest.kt
The existing test checks original indices, favourites and hidden disabled profiles. [R13]
Keep it and extend it; do not replace it with screenshot-only coverage.

3.2 Risks requiring tests, not assumed failures
Long text can compete with a trailing metrics column; independent refreshes can return after a model edit; reusable label changes can affect other screens; and a lazy list inserted into an intrinsic-size dialog can be measured incorrectly. These are design risks to prevent. This review does not claim that each has produced a device crash.
The palette helper in PlatformCheckBoxItem.kt uses abs(hashCode()) % palette.size. The Int.MIN_VALUE edge case can leave a negative index. Replace that operation with a non-negative modulo and add a deterministic regression test when consolidating the palette helpers. [R3]
4. Target layout and information hierarchy
4.1 Default arrangement
The following is a structural wireframe, not a screenshot. Names, models, scores and prices are illustrative fixtures, not verified provider quotations or benchmark results.
Select Platform                                  (?)

[       Separate       |       Combined       ]

[ Search profiles and models                   x ]
[ All ]  [ Coding ]  [ Research ]  [ Writing ] ...

                         Input / output per 1M tokens
+--------------------------------------------------+
| [x]  Daily assistant                         87  |
|      example/model-a                USD 1 / 3*  |
+--------------------------------------------------+
+--------------------------------------------------+
| [x]  Coding assistant                        92  |
|      example/model-b                USD 2 / 8   |
+--------------------------------------------------+
+--------------------------------------------------+
| [ ]  On-device assistant                      -  |
|      example/local-model             On-device   |
+--------------------------------------------------+

2 selected                       [Cancel] [Confirm]

The trailing score and price are right-aligned with each other. Profile name and score occupy the upper band; model and rate occupy the lower band. The global rate legend avoids repeating units in every row. Use an em dash for a missing score in the rendered UI; the ASCII dash above is wireframe notation.
A superscript-style information mark on a rate indicates that details include a qualification such as a routing reference, conditional charge or retained stale quote. It is not a new paragraph or badge row. The details button's accessible description must explain the specific qualification. The details panel explains the mark in ordinary language.
4.2 Dialog structure
Use a bounded dialog surface with a header, body and footer. At normal text scale, the title, mode control and footer stay visible while the profile list scrolls. Search and filters sit above the list. The list uses stable UID keys and must receive finite height constraints. Lazy layouts are appropriate for collections of unknown size and should not be nested in an unbounded same-axis scroll parent. [A2]
Do not blindly place LazyColumn inside the current AlertDialog text slot. Locate the implementation behind the existing FadingAlertDialog alias first; that wrapper's source was not resolved in this review. If its intrinsic measurement contract is unsuitable, use Dialog plus a bounded themed Surface for this picker only. Preserve app motion conventions without modifying a shared wrapper globally.
At constrained height, move mode/search/filter controls into the same lazy body as the cards, while retaining the title and confirmation area. Do not introduce a second vertically scrolling container. Apply IME and safe-area handling once; do not subtract system insets twice.
4.3 Visual tokens
Element
Proposed token
Behaviour
Dialog
24 dp corners; 20 dp normal inset; maximum 560 dp width
Reduce outer gutters when the usable window is narrow.
Profile surface
16 dp corners; 12 dp internal padding
Minimum content-driven height, not a fixed clipping height.
Card separation
8 dp
No thick divider between every card.
Profile name
16 sp, semibold, onSurface
One line normally; wrap at accessibility scales when needed.
Model name
13 sp, primary
One line normally, with ellipsis and full text in details.
Benchmark
24 sp, bold, onSurface
Right-aligned; use stable numeral widths where available.
Price
12 sp, primary
Right-aligned; no hard-coded green or smaller forced text.
Selected card
secondaryContainer or validated primary-tinted surface
1 dp primary border plus checked selection indicator.
Unselected card
surfaceContainerLow and subtle outline
No glowing borders or stacked shadows.
Favourite icon
14–16 dp, themed
Decorative indication; favourite action remains accessible.
Label control
32–36 dp visual pill; 48 dp interaction slot
Label text remains readable at normal and enlarged scales.
Motion
Approximately 120–180 ms state transitions
No list-height animation during price refresh.

These are design tokens to validate in previews, not mandatory replacements for the app's complete theme system. Resolve actual colours inside composition so an active theme change updates an already-open dialog. Test normal text contrast against each actual surface; target at least 4.5:1 for the small model and rate text.
4.4 Responsive priority rules
Allocate the identity column using weight and the metrics column from measured content, with a bounded maximum. Test usable card widths around 280, 320 and 360 dp rather than assuming a single phone resolution. At ordinary widths the trailing group should not consume more than approximately 45% of the content width.
When content does not fit, first ellipsize identity text, then permit the rate to wrap within the trailing group. At large font scales, allow the profile name and model additional lines and increase card height. The benchmark remains at the upper end and price below it. Do not hide currency, truncate significant rate digits without an approximation marker, or scale text down below the design tokens just to preserve a two-line height.
Use start/end alignment and directional isolation for model IDs and numeric rate strings. In RTL locales, mirror the overall layout while keeping each model identifier and rate readable. Keep the information hierarchy rather than enforcing physical right alignment in every writing direction.
5. Interaction specification
5.1 Selection and favourites
The profile identity region toggles selection once. A non-interactive checkbox reflects its state; it must not add a second click handler. The trailing insight region is a separate sibling action. Pressing it never toggles the profile. Pressing the identity area never opens pricing.
Long-pressing the identity region retains the existing favourite-toggle behaviour. Provide an equivalent localized accessibility custom action and a favourite action inside the detail panel. Favourite changes preserve selected UIDs and filter state. The star is not a tiny standalone tap target.
Use one gesture strategy for the identity region: combinedClickable with checkbox role and explicit toggle-state semantics when retaining long press, or a toggleable pattern with an equivalent accessible favourite action. Do not stack independent clickable, toggleable and combinedClickable handlers on the same node. Android's Compose guidance supports moving selection behaviour to the parent while leaving its checkbox non-interactive. [A1]
5.2 Mode selection
Replace the independent Separate and Combined filter chips with a single-selection segmented control, using the repository's installed Material 3 API. Android supplies single-selection segmented button containers; confirm the exact available version before using one. [A3]
The enabled selected-profile count determines availability, not the count of visible search results. Combined is disabled below two selected profiles. If a profile is removed or disabled and the count drops below two, return to Separate, as the existing implementation does. Do not automatically restore Combined when the count later increases.
Remove the persistent mode descriptions. A header help action can explain Separate and Combined on demand, including why Combined is unavailable. Do not place the removed “Each selected AI keeps its own response tab.” sentence back into the default dialog under another component.
5.3 Search and labels
Search changes only visibility. Selecting a label applies a single label filter, matching current behaviour. Selecting All clears the label filter but does not clear the search field or profile selection. A search-clear icon clears only the query. An empty-results action clears both query and label filter without deselecting profiles.
Keep selections hidden by search or labels. The footer shows total enabled selections. When useful, append a small “1 hidden by filters” count; this is conditional state information, not permanent help copy. A removed label resets to All rather than leaving an invisible stale filter active.
Retain query, label and scroll position when opening and closing insight details. Preserve them across configuration recreation within the current dialog session. A new dialog session resets query and filter; selection seeding follows existing app behaviour. Never persist these temporary filters as new global preferences without a separate product decision.
5.4 Confirmation and cancellation
Confirm is enabled only with at least one enabled selection. On confirmation, revalidate against the latest complete profile list, preserve its canonical order and send UIDs to the existing callback. Sorting the displayed favourites first must not silently change conversation participant ordering.
Block repeated confirmation while handling the initial action. Cancel, back and outside-dismiss must not create a chat or issue model requests. They preserve the current app's rules for remembering selection. Dismissing an insight overlay returns to the picker; it does not dismiss both layers at once.
5.5 Empty and failure states
Condition
Main picker behaviour
Available recovery
No profiles configured
“No AI profiles yet.”
Use an existing profile-setup route if one is already supplied; otherwise show a clear instruction.
Profiles exist but none enabled
Preserve the existing enable-profile warning.
Existing AI-profile settings action where available.
Search/filter has no match
“No matching profiles.”
Clear filters; selection remains intact.
Insights loading
Show profiles immediately; keep metrics space stable.
No user action needed.
No benchmark
Em dash, not zero.
Details explain that no eligible result exists.
Price unknown/expired
“Price unavailable.”
Details provide refresh when supported.
Offline with usable cached price
Retain qualified cached value.
Details show original date and refresh result.
Refresh fails
Do not replace valid retained data with zero or a spinner forever.
Retry in details; selection remains available.
Selected profile is disabled/deleted
Remove it from valid selections and update mode.
Concise status announcement.

6. Architecture and state ownership
6.1 Component decomposition
HomeScreen
  SelectPlatformDialog (existing entry-point adapter)
    SelectPlatformPickerContent
      PickerHeader + Help
      PickerModeSelector
      PickerSearchAndFilters
      LazyColumn<PickerProfileRow>
        SelectPlatformProfileCard
          ProfileIdentitySelection
          CompactProfileInsightsButton
      PickerFooter
    ProfileInsightDetailsDialog
      Benchmark section
      Pricing section
      Favourite action

The shared label design lives under presentation/common, not inside the picker package. Price and benchmark acquisition remain in the existing data layer. A composable must not download prices, load a model, run a benchmark or construct its own billing identity.
6.2 Owners and derived state
State
Owner
Lifetime
Profiles and selected identities
HomeViewModel / existing home state
Existing home and dialog selection lifecycle.
Price and benchmark data
Existing repositories via NewChatInsightsViewModel
Cached repository state; collected while appropriate.
Query, selected label, list scroll
Picker saveable UI state
Current picker session and configuration recreation.
Combined/Separate
Picker saveable state with validity reducer
Same semantics as current dialog.
Open details
Picker-level UID and insight-context key
Not stored in a lazy row's local state.
Compact text and indicators
Pure presenter from resolved values plus locale
Recomputed only when relevant inputs change.
Theme colours
Composable theme resolution
Recomposition on theme change.

The new UI should receive selected UIDs, not presentation indices. Keep a compatibility adapter for the existing List<Boolean> contract during integration. Translate from the original complete list once, never from the filtered or favourite-sorted list. Resolve an outgoing UID against the latest ViewModel snapshot before toggling.
Do not create a second independent source of truth for selection in the dialog. If the ViewModel gains canonical UID state, derive its legacy boolean projection from that state in one place. Existing consumers keep working until intentionally migrated.
6.3 Update and invalidation rules
A name or label edit updates presentation without invalidating valid model insights. A model, endpoint, provider connection, billing/routing configuration or applicable benchmark configuration change invalidates the affected insight context immediately. A late result for the old context must not appear on the newly configured profile merely because the UID is unchanged.
Use the existing priceContextKey(profile) for local pricing context; do not publish it because it contains endpoint and connection information. Use the benchmark resolver's authoritative identity rules for scores. Keep a context key alongside each display item so detail panels and asynchronous results can be checked against current inputs. [R7, R9]
The benchmark resolver intentionally supports eligible provider/model records shared across profile names. Do not change that into strict display-name matching or pretend the compact number measures every custom profile setting. Explain its actual scope in details.
7. Proposed component and data contracts
The following contracts describe the implementation boundary. They are design-level Kotlin examples, not an applied or compiled patch. Align imports, existing app types and helper names with the integration branch.
internal data class PickerProfileRow(
    val uid: String,
    val profileId: Int,
    val displayName: String,
    val configuredModel: String,
    val selected: Boolean,
    val favorite: Boolean,
    val enabled: Boolean,
    val insightContextKey: String,
    val insights: CompactInsightState,
)

internal data class CompactInsightState(
    val benchmark: BenchmarkDisplay,
    val price: PriceDisplay,
    val refreshing: Boolean,
)

internal sealed interface BenchmarkDisplay {
    data object Hidden : BenchmarkDisplay
    data object Loading : BenchmarkDisplay
    data object Missing : BenchmarkDisplay
    data class DeviceOnly(val hasRecordedRun: Boolean) : BenchmarkDisplay
    data class Available(
        val value: Double,
        val source: BenchmarkOrigin,
        val plan: String,
        val suiteVersion: Int,
    ) : BenchmarkDisplay
}

internal enum class BenchmarkOrigin { PERSONAL, COMMUNITY }

internal sealed interface PriceDisplay {
    data object Hidden : PriceDisplay
    data object Loading : PriceDisplay
    data object Unavailable : PriceDisplay
    data object OnDevice : PriceDisplay
    data class Available(
        val currency: String,
        val inputPerMillion: String,
        val outputPerMillion: String,
        val qualifiers: Set<PriceQualifier>,
    ) : PriceDisplay
}

internal enum class PriceQualifier {
    ROUTING_REFERENCE, CONDITIONAL, STALE, MANUAL, APPROXIMATED
}

@Composable
internal fun SelectPlatformProfileCard(
    row: PickerProfileRow,
    onToggle: (String) -> Unit,
    onFavorite: (String) -> Unit,
    onOpenInsights: (String, String) -> Unit,
    modifier: Modifier = Modifier,
)

Add explicit initial-load completion to the presentation state. ProfileInsight.score == null alone cannot distinguish loading from a genuinely absent benchmark. Likewise, priceRefreshing is not proof that initial hydration completed. Track completion or add a small adapter state around the existing streams, rather than inventing a placeholder timer.
Keep exact rate strings or BigDecimal values through formatting; never convert money through Float or Double. Do not persist the display DTO, serialize provider credentials into it or pass the complete profile object into remote analytics.
7.1 Atomic selection projection
internal fun selectedUids(
    profiles: List<PlatformV2>,
    selectedByOriginalIndex: List<Boolean>,
): Set<String> = profiles.mapIndexedNotNull { index, profile ->
    profile.uid.takeIf {
        profile.enabled && selectedByOriginalIndex.getOrElse(index) { false }
    }
}.toSet()

internal fun confirmedUids(
    profiles: List<PlatformV2>,
    selected: Set<String>,
): List<String> = profiles
    .filter { it.enabled && it.uid in selected }
    .map { it.uid }

Use these rules at the adapter boundary; do not recompute selection from each visible row's index. Test mismatched boolean-list lengths and profile updates between display and confirmation. UI changes and confirmation should be processed on the existing serialized UI/ViewModel path.
8. Benchmark display policy
8.1 Eligible remote scores
Use ProfileBenchmarkResolver.personal(...) first. Only when no eligible personal score exists and newChatCommunity is enabled, use the eligible community result. Preserve Full-over-Quick selection, supported suite validation, billing-provider checks and exact configured model matching. BenchmarkScoreboard excludes local runs from ordinary remote model submissions and validates the supported suite before evaluation. [R9, R10]
Render the evaluated overall model score as a large whole number, retaining full precision in details. Do not recompute it from displayed metrics or introduce a new weighted formula. The inspected score is a capability score based on weighted task outcomes and completion; timing remains separate. Do not turn the number into tokens per second or label it as a universal model ranking. [R10]
If community provenance must be indicated without adding text, use one compact source glyph next to the number, with a complete accessible label. The detail panel clearly says Personal or Community, Quick or Full, suite version, run date and model/provider scope. Do not represent community data as the user's own benchmark.
8.2 Missing and local results
An absent or ineligible score renders an em dash. A local profile with a recorded runtime benchmark also renders an em dash in the remote capability-score position until a separately defined compatible local score exists. Its details retain device, backend, accelerator, throughput, latency and memory information already supported by NewChatProfileInsights. [R4]
Use the existing local classification in this path (ClientType.LITERT_LM) or an already-established canonical classifier on the integration branch. Do not invent a new GenieX client type. A runtime-specific build does not itself establish a comparable scoring formula.
Do not automatically run benchmarks or load local models when the picker opens. An unbenchmarked profile is still selectable. A score change must not reorder cards or reset scroll position.
9. Pricing policy and detail disclosure
9.1 Main-card formatting
Display the currency and paired input/output rate, with the unit legend shown once above the list: Input / output per 1M tokens. These are rates, not the predicted cost of the user's next conversation. Never add input and output rates together as though that were a bill.
Examples such as USD 1 / 3 are fixture values only. Use the returned currency rather than an ambiguous dollar symbol. Do not convert USD to CAD or infer exchange rates. A verified zero can display zero; a missing rate cannot. For local inference use On-device, not Free, because the UI is communicating the absence of a provider token rate rather than a claim that hardware and energy are free.
Use formatRate or a compatible decimal formatter with tests. Normal values should remain exact where space permits. If shortening is necessary, use an explicit approximation marker, never round a nonzero rate silently to zero, and retain the exact values in details. Prefer wrapping before reducing the font. Formatting rules must be locale-aware without altering model IDs or their case. [R7]
9.2 Resolution and freshness
Retain ProviderPricingRepository.resolve and ModelPrice.freshness. At the inspected base, API quotes are fresh for up to one hour and expire after the allowed 72-hour window; reviewed/account-source freshness follows review dates with seven-day fresh and thirty-day expiry windows. Explicit expiry and invalid/future observation timestamps can invalidate a quote sooner. These are existing repository rules, not a new recommendation to lengthen caching. [R7, R8]
The resolver checks valid context-scoped manual overrides before ordinary provider/model matches and does not provide ordinary catalogue rates for unsupported batch contexts. A UI refactor must not bypass those checks just to fill every card. [R8]
Data condition
Compact display
Detail treatment
Fresh applicable rate
Numeric rate
Source and original verification/review date.
Routing-reference quote
Rate plus information mark
Explain that selected route/account charges can differ.
Conditional pricing
Rate plus information mark
Explain the condition and expose available dimensions.
Stale, not expired
Retained rate plus qualification
Explicit Stale label and original date.
Expired or invalid
Price unavailable
Explain absence; refresh where supported.
Manual override
Valid applicable rate with qualification
Manual origin, scope and review date.
Local profile
On-device
No provider token quotation; retain local details.
Failed refresh
Keep eligible cached value or unavailable state
Error/retry; timestamps remain unchanged.

Do not use the word Live for a reviewed file, manual value or stale cache. The inspected repository only implements the shown API refresh path for OpenRouter. This redesign does not promise API-backed live prices for all providers. [R8]
9.3 Unified insight details
The entire score/price group is one button with at least a 48 dp interaction area. Either visible value opens a single profile insight dialog with labelled Benchmark and Pricing sections. This deliberately avoids two overlapping 48 dp targets inside a compact two-line card. Both sections can be visible in a scrollable panel; collapsed methodology and extra-price dimensions prevent a second wall of text.
The panel includes full profile name and model, permitted provider identity, benchmark provenance and dimensions, exact rates and unit basis, cached-input or additional charges when available, freshness, review/validation date, routing/conditional caveats, source link and supported refresh action. Never show an API key, authorization header, secret query string or unredacted custom endpoint. Date formatting uses the current locale and timezone; it does not change the stored timestamp.
When one metric is disabled in Advanced Settings, hide that section and its action semantics. When both are disabled, remove the insight button and reclaim its width. Do not display disabled metrics through a hidden accessibility node.
Open detail state is keyed by UID plus insight context. A deleted profile closes the panel safely; a changed context updates or closes it rather than displaying the old model's rates under a new title.
10. Label and button redesign
10.1 Shared label components
Add ProfileLabelChip for interactive filters and assignment controls, and ProfileLabelBadge for non-interactive display. Keep BeveledProfileLabel temporarily as a wrapper that delegates to the appropriate component. Preserve its existing parameters and call-site compatibility; map large and compact to sensible size variants rather than keeping half-size text. [R6]
Use fully rounded corners, consistent horizontal padding, a soft themed container and a restrained border. A selected chip gains a check icon and selected state, not just another colour. Preserve the user's stored label colour as a swatch or accent; use readable theme text rather than arbitrary label-coloured small text. Long labels ellipsize with their complete name available to accessibility and editing.
Interactive controls reserve 48 dp touch slots even when the visible pill is shorter. Ensure adjacent expanded touch regions do not overlap. Disabled controls have genuine disabled semantics and no active gesture callback. Passive badges do not need to pretend to be buttons. Android's accessibility defaults explain both minimum targets and the danger of undersized overlapping hit regions. [A1]
10.2 Call-site migration
Inventory every BeveledProfileLabel and related legacy label-control call before editing. Known inspected consumers include the picker filters, label editor, shared checkbox rows and conversation label display in HomeScreen. There may be additional consumers; the inventory is an implementation gate, not a claim that these are the complete set. [R2, R3, R6]
The wrapper ensures every migrated label control receives the common shape and readable typography. Remove attached label badges only from the new picker card. Do not remove stored labels or suppress useful labels on conversation and settings screens.
Retain label serialization, key matching, colours, assignment and removal logic. Check assigned-label rows, reusable-label rows, colour choices and save/cancel controls with large text. If making these rows scrollable, bound the editor height and preserve access to its actions.
10.3 Other picker controls
Use a filled primary Confirm button and a quiet Cancel button with matched heights. Keep the search field and clear action visually consistent with label chips. The rate legend uses small secondary text, not another button. Avoid decorative provider logos, benchmark trophy badges, auto-sorting controls or extra filter categories in this release.
11. Async, lifecycle, performance and privacy
The profile list must render independently of insight hydration. Continue collecting repository-backed state; do not add row-level LaunchedEffect requests. Use a visibility-scoped effect at the picker route and cancel work when its session ends. Compose effects should be keyed to the relevant lifecycle inputs and cancel when they leave composition. [A4]
Within the existing visible-refresh routine, isolate scoreboard and pricing work into sibling supervised jobs with their own error handling. Scoreboard failure must not prevent price refresh. Retain cancellation propagation, repository mutexes and freshness gating. A cosmetic name edit should not trigger a new remote fetch; use a distinct set of relevant identities and feature flags as refresh inputs.
Manual refresh respects repository support and in-flight state. Cache hydration and refresh failures must not cancel the profile stream. Preserve original validation dates; showing a spinner, reopening the dialog or reading a cache must never mark a quote newly verified.
Build compact display values and normalized search/label data away from repeated row composition. Keep theme and localized presentation at an appropriate UI boundary. Do not run model inference, network calls, disk reads or expensive benchmark evaluation from each card's composable body. Preserve the existing off-main repository work. [R5, R8]
11.1 Proposed performance gates
These are test targets, not benchmark claims: zero remote calls caused by tapping a profile or label; zero automatic benchmark/inference starts; no main-thread disk/network work from the picker; stable row height during refresh; and no growing job count after ten open/close cycles. With fixture sets of 10, 100 and 500 profiles, record opening and scroll performance on the same device/build as the baseline. Investigate repeatable regressions above 10%; do not claim a pass without comparable measurements.
No new analytics service, remote event collection or dependency is required. Existing debug diagnostics may record redacted error categories during testing, but must not expose names, keys, endpoint paths or billing context strings. Do not publish benchmark results merely because the picker displays community data.
12. Accessibility, localization and motion
Use two focusable actions per ordinary card when insights are visible: selection and insight details. The selection node announces profile name, configured model, checked state and favourite state. The insight node announces the score's origin and the input/output rate basis, including qualifications. Decorative icons do not become duplicate focus stops.
Provide localized action names for selecting, deselecting, viewing details, adding/removing a favourite, clearing search, choosing a label, refreshing and closing. Use plural resources for selected and hidden counts. Keep rates and identifiers directionally isolated in RTL. Do not hard-code the Separate label in Kotlin as the existing call currently does. [R2]
The removal contract includes the user-reported phrases “Top-provider list reference”, “actual route may vary”, “Provider API checked …”, and “Each selected AI keeps its own response tab.” The reported Oct 13, 2026 1:28:00 AM value is an example of a formatted timestamp, not a literal to search-and-delete from source. Remove the default-screen rendering path; retain applicable route/source/date information inside details. Assertions must distinguish the picker from an intentionally opened detail panel.
All new strings belong in Android resources. Do not remove shared resource definitions until repository-wide reference checks prove they are unused. Specifically remove the calls rendering select_platform_description, chat_mode_standard_description and the persistent combined-mode description from this picker. Move insights_route_reference, conditional-price copy and freshness to the detail path; retain their informational function. [R2, R4]
Respect system text scale at 1.0, 1.3, 1.5 and 2.0. Validate dark, light and at least one custom accent theme. Selection remains understandable without colour. Preserve visible focus indication for keyboard/D-pad input and allow scroll-to-focused controls.
Use short colour/selection transitions and normal press feedback. Disable nonessential animation when the app/system motion preference requires it. Do not animate a one-second cascade of all profiles, shimmer entire cards indefinitely or move the list when numbers refresh.
13. File-level implementation map
Kotlin paths below are relative to app/src/main/kotlin/dev/chungjungsoo/gptmobile/ unless another root is shown. NEW means proposed; it does not assert the file already exists.
File
Action
Responsibility and limits
presentation/ui/home/HomeScreen.kt
Modify
Replace embedded layout with the extracted picker; preserve route callbacks and lifecycle ownership.
presentation/ui/home/SelectPlatformDialog.kt
NEW
Bounded dialog, header/footer, filters, lazy body and detail ownership. Preserve the existing entry signature through an adapter where needed.
presentation/ui/home/SelectPlatformProfileCard.kt
NEW
Two-region compact card, theme tokens, focus/gesture semantics.
presentation/ui/home/SelectPlatformUiState.kt
NEW
Display-state types, selection projection and pure presentation helpers.
presentation/ui/home/NewChatProfileInsights.kt
Refactor
Compact insight button plus reusable detail content; remove fixed green from this surface. Retain old entry point while callers need it.
presentation/ui/home/NewChatInsightsViewModel.kt
Modify
Explicit initial-load state, context validation and isolated refresh jobs. Reuse existing repositories.
presentation/ui/home/HomeViewModel.kt
Targeted change
Add safe UID-based selection entry point or adapter; preserve legacy boolean projection and chat creation.
presentation/ui/home/ChatProfileOrder.kt
Preserve / extend only if needed
Retain favourite grouping and original-list semantics.
presentation/common/PlatformCheckBoxItem.kt
Compatibility audit
Do not globally replace with the picker card. Consolidate palette edge-case fix if included.
presentation/common/ProfileLabelComponents.kt
Modify
Rounded chip/badge design and wrapper compatibility; label storage unchanged.
presentation/common/ProfileDiscovery.kt
Small compatible extension
Optional clear action styling; keep existing search semantics unless separately tested.
data/pricing/ModelPrice.kt
Preserve contracts
Only targeted formatting/helper tests or isolated bug fixes; no freshness-policy rewrite.
data/pricing/ProviderPricingRepository.kt
Prefer unchanged
Preserve source resolution and refresh gating. Any cache fix gets separate tests.
data/pricing/ProfileBenchmarkResolver.kt
Prefer unchanged
Preserve provider/model/suite selection.
app/src/main/res/values/strings.xml and applicable locales
Modify
New picker/detail/action strings and plural resources.
app/src/test/kotlin/...
Add / extend
State, formatter, resolver, lifecycle and palette regression tests.
app/src/androidTest/...
Add
Follow the repository's existing Kotlin test source-root convention after inventory. Compose interaction and device tests.
docs/Select_Platform_Redesign_Design_Implementation_2026-10-13.md
Add
This specification and eventual acceptance evidence links.

No Room migration, database schema change, permission change, provider credential migration or native packaging edit is required by this UI design. Do not modify SDK, Java, application ID or release versions merely to implement these components. The inspected build uses Java 21, SDK 37 and minimum SDK 31; its separate GenieX property controls a different application ID and runtime packaging. [R14]
14. Implementation sequence and commit boundaries
Phase 0 — Establish the integration baseline
Confirm the target base and record its SHA. Read applicable repository instructions and locate all picker, insight and label callers. Capture baseline screenshots with deterministic sample data and record existing build/test failures separately. Create the proposed branch only when implementation is actually authorized. Do not treat this document as evidence that a branch already exists.
Exit evidence: file inventory; baseline screenshots; source version; applicable Gradle tasks; known pre-existing failures.
Phase 1 — Contracts and regression fixtures
Add display states, UID projection helpers, context keys and pure formatting tests. Freeze current benchmark selection and pricing resolution with fixture tests before touching visuals. Introduce explicit loading completion without making profile display depend on insights.
Suggested commit: refactor: define stable select-platform display state.
Phase 2 — Cards and shared controls
Implement the picker-specific card, shared insight action, rounded chip/badge components and compatibility wrapper. Add previews for selected/unselected, favourite, loading, missing, stale, local and both-metrics-hidden states. Remove attached labels from the picker card only.
Suggested commit: feat: add compact themed profile picker components.
Phase 3 — Dialog integration
Extract the dialog, supply finite list constraints, integrate search and filters, use stable UID events and add the segmented mode control. Remove obsolete on-screen paragraphs. Keep confirmation order and validity rules. Test search/favourite changes before proceeding.
Suggested commit: feat: integrate streamlined select-platform dialog.
Phase 4 — Insight details and refresh isolation
Move routing, pricing dates and benchmark provenance into details. Wire exact currency/rates and compact qualifications. Isolate the visible scoreboard/pricing jobs. Verify stale-result rejection after model changes and cancellation on dismissal. Keep source refresh support truthful.
Suggested commit: fix: preserve insight provenance and isolate picker refreshes.
Phase 5 — Accessibility and cross-screen label sweep
Migrate remaining label-control callers through the compatible style, fix palette indexing, localize strings and verify expanded text/RTL/keyboard behaviour. Do not delete old component entry points until references are gone. Capture affected settings/editor/conversation surfaces, not just the picker.
Suggested commit: fix: complete accessible label and picker interactions.
Phase 6 — Validation and handoff
Run unit, Compose, build, lint and device gates. Produce before/after screenshots, record commands and outcomes, and open a draft PR with the exact change list. Merge or publication is a separate authorized operation; neither is performed by generating this document.
Suggested commit: test: cover select-platform redesign regressions.
15. Detailed test matrix
Every test below is proposed unless explicitly described as an existing test. Avoid live paid APIs in deterministic tests. Use fake repositories, fixed clocks and synthetic models/rates. Compose provides semantics-based finders, actions and assertions; test both the merged accessibility tree and relevant unmerged child nodes. [A5]
15.1 Unit and state tests
ID
Scenario
Required result
U01
Filter or favourite-sort a selected profile
UID remains selected; original list indices are not reused as visible indices.
U02
Profile list arrives with changed order
Selections survive by UID.
U03
Same profile name on two different UIDs
Independent selection; no name collision.
U04
Same model under different providers/endpoints
No cross-provider price or score leakage.
U05
Model changes while a refresh is pending
Old-context result is rejected.
U06
Label removed while active
Filter resets to All; selection remains.
U07
Disabled/deleted selected profile
Selection and Combined validity update.
U08
Selected profiles hidden by filters
Total count and confirmation still include valid hidden selections.
U09
Confirmation after list change
Revalidates UIDs and preserves complete-list order.
U10
Boolean selection list shorter than profiles
Safe false fallback; no crash.
U11
Eligible personal and community scores
Personal wins; disabling community prevents fallback.
U12
Eligible Full and Quick results
Current Full preference is preserved.
U13
Invalid/cancelled/local/incompatible-suite score
No fabricated remote score.
U14
Missing benchmark versus valid zero
Distinct display states.
U15
Fresh, stale, expired and future-dated price
Existing freshness boundaries respected with a fixed clock.
U16
Manual override with matching/mismatching context
Applies only in its authorized context.
U17
Free, unknown, very small and very large rate
No false zero; exact details preserved; approximation explicit.
U18
Unsupported batch/custom billing context
Price unavailable rather than guessed from transport type.
U19
Scoreboard refresh fails or is delayed
Pricing and profile display continue independently.
U20
Dismiss/reopen repeatedly
No orphan refresh job or growing collector count.
U21
Both visibility flags off
No metric placeholder, detail action or width reservation.
U22
Palette hash at Int.MIN_VALUE
Valid deterministic non-negative palette index.

Extend the existing ChatProfileOrderTest rather than deleting it. Add proposed test classes such as SelectPlatformSelectionTest, CompactProfileInsightsTest, PickerInsightContextTest and PickerRefreshLifecycleTest in the actual established test package structure. [R13]
15.2 Compose integration and screenshot tests
ID
Scenario
Required result
C01
Default populated picker
Name/model on left; large score/small rate on right; no attached labels.
C02
Dates and routing-reference fixture
Requested removed text absent in picker, present in details where appropriate.
C03
Separate and Combined views
No persistent response-tab paragraph.
C04
Tap checkbox/identity region
Exactly one selection change.
C05
Tap metrics region
Opens correct details; does not select/deselect.
C06
Long-press and accessibility favourite action
Same persisted favourite result; selection stable.
C07
Zero, one and two enabled selections
Correct Confirm and Combined availability.
C08
Search, filter, clear, empty-results recovery
Correct rows and unchanged hidden selections.
C09
Query/filter details round trip and rotation
State and list anchor restored for the current session.
C10
Theme change while picker is open
Model, rate, selection and chips recolour immediately.
C11
Long names/IDs and 2.0 text scale
No clipped actions or inaccessible values; card grows.
C12
RTL, keyboard, TalkBack and D-pad
Correct order, identifiers, focus and action announcements.
C13
Open keyboard in a short window
Footer remains reachable; no unbounded lazy-list exception.
C14
Score-only, price-only and neither
Unused metric controls and space disappear.
C15
Slow/offline refresh
Profile selection remains usable; no destructive date update.
C16
Label editor and other shared consumers
Rounded styling without lost assignment, removal or save actions.
C17
Fast repeated Confirm and back
No duplicate chat; topmost overlay dismisses first.
C18
Baseline versus 500-profile fixture
Stable scroll keys, no unwanted jumps or excessive repeated work.

Suggested stable tags include select_platform_dialog, profile_card_<uid>, profile_select_<uid>, profile_insights_<uid>, profile_label_<key>, picker_mode_separate, picker_mode_combined, picker_search and picker_confirm. Do not derive tags from mutable display names.
15.3 Device matrix
Validate at minimum: a narrow phone-sized window, the user's target Android phone when available, landscape with keyboard, a larger tablet/window, light and dark themes, a custom accent, and the four stated font scales. Test the normal runtime build and a compile/smoke pass for the separate GenieX build where its dependencies and compatible device are available. Lack of a suitable native device must be recorded as untested, not reported as passed.
16. Build and validation commands
Run these during implementation from the repository root, not from the documentation output folder. They are proposed validation commands and have not been executed for an application patch in this task. The repository's checked-in wrapper and dependency catalogue remain the source of truth.
# Baseline and caller inventory
git status --short
git rev-parse HEAD
git grep -n 'SelectPlatformDialog'
git grep -n 'PlatformCheckBoxItem'
git grep -n 'BeveledProfileLabel'
git grep -n 'fun FadingAlertDialog'
git grep -n 'NewChatProfileInsights'

# Confirm tasks before running the gate
./gradlew :app:tasks --all

# Main validation gate
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:lintDebug

# Requires a configured emulator or device
./gradlew --no-daemon :app:connectedDebugAndroidTest

# Separate runtime build; use its supported dependencies/device
./gradlew --no-daemon -PgeniexRuntime=true :app:assembleDebug

Use gradlew.bat on Windows. Keep GenieX outputs and test sessions clearly distinguished from the normal package. Do not use native-library pickFirst changes, disable lint or delete failing tests to make a UI PR green. Repository instructions specifically warn against weakening JNI packaging constraints. [R14, R15]
For each command, record the actual integration SHA, task, device/build variant, outcome, report/artifact path and any pre-existing failure. A compile pass is not a screenshot review; a screenshot review is not a device accessibility pass.
17. Additional repository improvements
These are deliberately classified so the redesign does not turn into an unrelated application rewrite.
Priority
Improvement
Scope decision
P0
Replace visible positional events with safe UID-based events
Required for the new picker boundary; preserve existing ViewModel semantics.
P0
Separate compact display from provenance detail content
Required; prevents future metadata additions from re-expanding cards.
P1
Isolate scoreboard and pricing refresh jobs
Include with targeted lifecycle tests.
P1
Remove half-size label text and fix palette modulo edge case
Include in the shared label migration.
P1
Explicit loading/missing/hidden insight states
Include; prevents misleading empty values and layout flicker.
P1
Screen-scoped empty-results recovery and localized actions
Include; no new navigation architecture required.
P2
Prefer parsed label names over raw serialized label text for search
Optional follow-up; test compatibility before changing matching behaviour.
P2
Verify catalogue hydration precedence for duplicate provider/model entries
Optional isolated fix: current hydration concatenates reviewed before cached data and keeps the first duplicate. Test whether a fresher eligible quote is displaced before changing precedence. [R8]
P2
Broader shared profile-row unification
Defer until other screens' requirements are audited; do not force picker minimalism globally.
Out of scope
Universal live pricing adapters, new benchmark formulas, release publishing
Separate work with explicit requirements and evidence.

No price catalogue values are manually updated as part of this design. No broader repository audit is claimed. The additional items above arise from the inspected picker-related paths.
18. Rollout, compatibility and rollback
Land changes in reviewable commits and keep the shared legacy entry points until their callers are verified. Prefer a reversible presentation change over a new persistent feature flag. No new user-facing toggle is needed to choose between old and new picker layouts; the existing score/price settings continue to apply.
Rollback should restore the previous picker presentation and, if necessary, the label wrapper visuals without reverting compatible selection protections or corrupting profile data. Avoid mixing unrelated pricing migrations, database changes or version bumps into the same commit. Keep bug fixes isolated enough to retain them if the presentation is rolled back.
A draft PR should include the verified base, final head SHA, before/after screenshots with fixture labels, the list of removed default-screen copy, notes on remaining detail disclosure, test outcomes and limitations. Missing device validation stays visible as a blocker or explicit release exception; it must not be silently converted into a pass.
19. Definition of done and implementation handoff
The redesign is ready for merge only when the requested information hierarchy is implemented, obsolete text is absent from the default picker, rounded label controls are consistent, selection and mode behaviour are unchanged, benchmark/pricing provenance is still accessible, and the agreed validation gates pass on the integration commit.
Acceptance record to complete in the PR
Gate
Required evidence
Current status
Requested visual layout
Light/dark before-and-after screenshots
Not implemented / not run.
No metadata clutter
Screen-scoped tests for removed strings and dates
Not run.
Correct selection and mode logic
Unit and Compose test reports
Not run.
Correct metric origin and rates
Resolver/formatter/context fixtures
Not run.
Shared label compatibility
Call-site inventory and affected-screen screenshots
Not run.
Accessible controls
TalkBack, focus, hit-target and font-scale review
Not run.
Lifecycle and performance
Cancellation tests and comparable fixture measurements
Not run.
Android validation
Unit, build, lint and applicable device reports
Not run.
Documentation
This design specification
Prepared.

Coding-agent handoff
Implement the specified Select Platform redesign against the latest authorized integration branch. Start by comparing the files in Section 13 with the pinned base and recording conflicts. Create picker-specific presentation components, preserve UID selection and resolver contracts, and migrate shared label visuals through a compatibility layer. Do not delete useful provenance, fabricate scores or prices, alter native packaging, change persisted profile data or publish a release. Complete the tests and report exact results, screenshots and remaining limitations. Use this document's proposed APIs as design guidance, not as a claim that uncompiled snippets are drop-in code.
20. Sources and verification references
All repository links below are pinned to 1d4dcd8698356abddc5f1be946bb0811017b7d7a. Source files were inspected through the connected GitHub repository on October 13, 2026. Existing code observations in this document are separated from proposed changes; Android links support the referenced platform patterns rather than implying a dependency upgrade.
[R1] Verified integration commit
[R2] HomeScreen.kt — SelectPlatformDialog
[R3] PlatformCheckBoxItem.kt — shared selection row and palette
[R4] NewChatProfileInsights.kt — current metrics and detail content
[R5] NewChatInsightsViewModel.kt — insight streams and visible refresh
[R6] ProfileLabelComponents.kt — shared labels and editor
[R7] ModelPrice.kt — rates, identity, validity and freshness
[R8] ProviderPricingRepository.kt — hydration, resolution and API refresh
[R9] ProfileBenchmarkResolver.kt — eligible personal/community selection
[R10] BenchmarkScoreboard.kt — score scope and validation
[R11] HomeViewModel.kt — existing UID selection preservation
[R12] ProfileDiscovery.kt — search matching and search field
[R13] ChatProfileOrderTest.kt — existing ordering regression test
[R14] app/build.gradle.kts — source version, toolchain and runtime variant
[R15] Repository coding guidance — architecture and native packaging constraints
[A1] Android Developers — accessibility defaults, selection semantics and touch targets
[A2] Android Developers — lazy lists, keys and scroll constraints
[A3] Android Developers — segmented buttons
[A4] Android Developers — Compose side effects and lifecycle
[A5] Android Developers — Compose testing APIs