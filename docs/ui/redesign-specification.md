GPT Mobile AI UI Redesign

Design and implementation specification

Prepared for Emanuel Melo  |  11 October 2026

A complete visual renovation of the Android app: clearer navigation, quieter surfaces, richer theme colours and a consistent system for every screen, editor, sheet and popup. All existing settings and capabilities remain available.


Proposed design mockups. Screen content and metrics are illustrative; these are not screenshots of an implemented build. The implementation specification follows the research and visual design.

# 1 Scope and design decision

Adopt four main destinations: Conversations, AI, Tools and Settings. Use a compact navigation bar at the root level, a navigation rail when space allows, and focused detail screens for reading, editing and setup. Give every capability one canonical home, with contextual shortcuts that open the same state and settings.

The visual direction combines warm neutral backgrounds, softly separated surfaces, generous spacing and a controlled accent palette. Colour should clarify action, selection and status. Most text remains neutral. Expressive shapes and motion are concentrated around important interactions, while long conversations, configuration forms and diagnostic records remain calm.

## Repository baseline

| Attribute | Verified baseline |
| --- | --- |
| Repository | tailscale-signin/GPT_Mobile_AI-improved |
| Branch and commit | main at 33002673dd5c55f252ee69717af4b124bebe9785 |
| Commit timestamp | 11 October 2026 at 05:53:19 UTC |
| App version in build file | 0.9.41.0, versionCode 110 |
| UI stack | Kotlin, Jetpack Compose, Material 3, Navigation Compose, MVVM and Hilt |
| Declared dependencies | Compose BOM 2026.09.00; Material 3 1.4.0; Navigation 2.10.2; Kotlin 2.4.20 |
| SDK settings | minSdk 31; targetSdk 37 |


The audit examined the navigation graph, UI source and supporting theme, settings and build files at this commit. It indexed 168 presentation files, then inspected supporting settings, manifest, compatibility UI and brand assets. Source inspection establishes structure and implementation opportunities; no APK was run and no on-device usability or frame-time measurements were performed. Recheck the diff from this commit before implementation begins.

## Delivery boundary

This document is the design and engineering handoff. It does not change the repository, publish an APK or claim that proposed behaviour is already implemented. Preserve inference, tool execution, permissions, memory, storage and benchmark logic behind the redesigned presentation. Backend enhancements are separate work unless a UI requirement cannot be fulfilled without them.

## How to use this document

Sections 2 to 5 establish the research, audit, navigation and visual system. Section 6 shows the design boards. Sections 7 to 11 specify every screen family. Sections 12 and 13 cover popups and feature preservation. Sections 14 to 16 define integration, delivery and testing. Section 17 links the sources.

# 2 Research before visual design

Research was completed before choosing the navigation and visual direction. The following guidance is the basis for the proposal. The exact layouts, colours, spacing and task groupings that follow are product design decisions for this app, not claims that a guideline prescribes one universal layout.

| Evidence | Design implication for this app |
| --- | --- |
| Material colour roles [S1] | Separate primary actions, selected states and background surfaces. Use paired foreground roles rather than making every icon and label the accent colour. |
| Google expressive design research [S2] | Use shape, size and colour to make important actions easier to notice. Apply expression selectively in a dense AI application; validate the result with its actual tasks. |
| Material app bars [S3] | Make the page title clear and keep the top bar focused on one or two essential actions. Avoid duplicated large titles and decorative hero blocks on utility screens. |
| NN/g progressive disclosure [S4] | Show frequent controls first. Put specialised settings in named sections with search and direct links; do not hide them in an unlabelled overflow maze. |
| NN/g usability heuristics [S5] | Keep system status visible, actions reversible where supported, labels familiar and recovery specific. Preserve the context of a running task when navigating. |
| Android adaptive layouts [S6] | Respond to available window size and fold posture. Use list and detail panes where they fit, and preserve the selected item as the window changes. |
| Android accessibility [S7, S8] | Maintain at least 48 dp touch targets, meaningful semantics, focus order and readable state descriptions. Announce stage changes without reading every streamed token. |
| W3C contrast guidance [S9, S10] | Adopt 4.5:1 for ordinary text and 3:1 for essential non-text indicators as design targets. Test actual composed colours, including alpha and surfaces. |
| Compose performance [S11] | Keep expensive work out of recomposition and use stable lazy-list identities. Profile before adding effects to streams or large model catalogues. |
| Android predictive back [S12] | Use supported Navigation and Material behaviour; one owner handles Back at each level. A dismissed popup must return focus to its trigger. |
| Material bottom sheets [S13] | Use sheets for secondary choices. Long configuration flows need enough space, visible actions and reliable state restoration. |


## Research interpretation

The strongest fit is a restrained Material 3 system with selective expressive detail. Copying every visual flourish from consumer apps would compete with long answers and dense settings. The proposal uses a small set of repeatable layouts, then makes the interaction details precise: a strong primary action, a clear selected state, a readable result and a predictable way back.

Android is the primary platform reference. W3C values are used as explicit contrast benchmarks for this native app; this document does not claim WCAG certification. Search results that merely republished Material guidance were not treated as primary technical evidence.

# 3 Findings from the current UI source

Several items in earlier redesign discussions are already present on this baseline. The renovation should build on them and test their behaviour, rather than reintroduce parallel implementations. File paths below are relative to presentation/ unless stated otherwise.

| Finding | Evidence and redesign response |
| --- | --- |
| Shared foundations exist | designsystem/AppDesign.kt defines spacing, shapes and an empty state. Expand this owner into a complete component system; do not create a competing token layer. |
| Settings navigation is already more structured | SettingScreen.kt has category rows and a search registry. Reorganise its categories and deep links; preserve control-level focus and dynamic profile entries. |
| Composer fixes are present | ChatScreen.kt already uses onSurfaceVariant for the placeholder and shows Stop separately from Send while running. Retain these behaviours in the new composer. |
| Appearance already has a route | NavigationGraph.kt opens ThemeSettingsScreen with asDialog=false. Other dialog entry points remain; migrate wrappers, not a supposedly missing route. |
| Theme accent mapping can flatten hierarchy | Theme.kt maps a custom primary to primary, secondary and tertiary. Full custom palettes derive several surfaces from the same value. Introduce tonal roles while retaining legacy palette fidelity. |
| Custom palette contrast is partially checked | PaletteContrast.kt exists. Extend its checks to effective foreground/background pairs, boundaries, pressed states and debug colours. |
| Large screens have an existing implementation | AdaptiveConversationLayout.kt supports a conversation pane at 840 dp and handles fold bounds. Preserve state continuity while integrating the new app shell. |
| Screen ownership is concentrated | ChatScreen.kt has 2,006 lines; HomeScreen.kt 1,952; ToolConnectionsScreen.kt 1,483; PlatformSettingScreen.kt 1,256; PlatformSettingDialogs.kt 2,322. These counts indicate extraction opportunities, not defects by themselves. |
| Navigation uses long return fades | NavigationGraph.kt specifies 500 ms pop fades. Introduce a coherent motion policy and reduced-motion handling, with measured interaction latency. |
| Popup wrappers are shared but content varies | FadingPopups.kt is used broadly; callers still mix editors, catalogues and critical decisions. Introduce consistent shells and workflow-specific content. |
| Workspace carries nine unrelated tabs | WorkspaceScreen.kt includes Tasks, Context, Evidence, Branches, Recipes, Models, Budgets, GitHub and Plugins. Redistribute these capabilities into task-oriented homes with compatibility links. |
| Permission choices have materially different scopes | ToolPermissionDialogs.kt exposes allow once, allow tool for all models and allow provider. The conversation callback is unused in this UI. Do not invent a narrower working scope during a visual rewrite. |
| Compatibility implementations coexist | common/McpMarketplaceDialog.kt delegates to the marketplace screen; another package has backup and platform UI. Verify callers before deletion; wrappers are not proof of duplicate active routes. |
| Diagnostics and benchmark foundations exist | DebugDiagnosticsScreen.kt already embeds Debug, Statistics and Benchmark workspaces. Benchmarks have cohort tables and a global execution banner. Unify presentation while keeping run ownership and results. |


These findings are confirmed from source. Visual crowding, discoverability and navigation improvements are design assessments that need validation with the running app. No accessibility failure or performance regression is asserted solely from a composable name or file length.

# 4 Navigation and information architecture

| Main destination | Canonical content | Contextual shortcuts |
| --- | --- | --- |
| Conversations | All chats, favourites, folders, archive, new conversation and conversation search | Activity; selected chat profiles; queue; sources |
| AI | Profiles, provider connections, local models, delegation and reusable recipes | Benchmarks; model details; local runtime; budgets |
| Tools | Installed plugins, connections, marketplace, permissions and GitHub workspace | Research configuration; resources and prompts; native service settings |
| Settings | Appearance, Conversations, Memory, Privacy & data, Activity & diagnostics, About | Search every setting; restore backup; theme preview |


## Navigation rules

Show the four labelled destinations on root screens. Use Chats as the short navigation label and Conversations as the page title. Do not show root navigation under a compact conversation composer or a full-screen editor.

Use one back stack per root destination. Restore search, filters, scroll position and selected detail on return. Re-selecting the current root may scroll to its start only when no editor or selection is active.

Activity is a global utility opened from a labelled accessible icon, the persistent running-task strip or Settings. It is not a fifth primary destination. It contains Runs, Usage, Benchmarks and Diagnostics.

Add keyboard shortcuts only where they do not conflict with editing: new chat, search, back and focus composer. Show supported shortcuts in help and menus.

Keep legacy Route strings and inbound notification/share arguments working through adapters. Preserve chatRoomId, enabledPlatforms, conversationMode, targetMessageId, regenerate, incomingShare, profileUid and runId.

## Redistribution of the current workspace

| Current workspace tab | New home | Data and behaviour retained |
| --- | --- | --- |
| Tasks | Activity → Runs | Queue, approvals, saved remote tasks, cancellation and return to conversation |
| Context | Conversation → Context | Inspect source context tied to that conversation |
| Evidence | Conversation → Evidence | Evidence inspection/editing and source provenance |
| Branches | Conversation → Branches | Conversation branches retain their original meaning |
| Recipes | AI → Recipes | Create, edit and reuse existing recipes |
| Models | AI → Compare | Existing model comparison data |
| Budgets | AI → Budgets | Spend and token limits, estimates and warnings |
| GitHub | Tools → GitHub workspace | Repository files, branches, drafts, diffs and review actions |
| Plugins | Tools → Configuration | Existing plugin configuration, validation and export |


Do not silently discard a gated or legacy capability. Every current workspace tab receives an alias and a parity test before the old navigation is retired. GitHub workspace and conversation branches remain distinct concepts.

## Adaptive layout

Compact width below 600 dp uses a single pane. From 600 to 839 dp use a navigation rail and one content pane unless two panes can satisfy minimum readable widths. At 840 dp and above, use a list/detail layout where appropriate. These are product breakpoints aligned to the existing app and Android conventions, not a requirement to split every screen. Height, hinges, font scale and the keyboard can override the split. Keep the conversation reading column near 680 dp, with a flexible upper limit around 760 dp.

# 5 Visual system and theme behaviour

## Colour roles

The illustrated Forest palette is the reference art direction. Existing presets remain available. Add new paired light and dark presets such as Forest, Ocean, Iris, Amber and Graphite only after their full role sets pass contrast checks. Wallpaper colour remains an explicit source. Changing a theme must never change model configuration, permissions or feature defaults.

| Role | Light reference | Dark reference | Use |
| --- | --- | --- | --- |
| Background | #F6F8F7 | #111A17 | Main canvas |
| Surface | #FFFFFF | #1A2621 | Cards and focused content |
| Surface low | #EDF2F0 | #202F28 | Search and quiet secondary panels |
| Surface high | #E3EBE7 | #293D33 | Elevated controls and nested selection |
| On surface | #17231F | #E7F0EB | Main text and essential icons |
| On surface variant | #52635B | #B6C7BD | Supporting text |
| Primary | #176B54 | #98D9B6 | Primary action, focus and links |
| On primary | #FFFFFF | #083824 | Content on primary action |
| Primary container | #D2EBDD | #224C38 | Selected item and low emphasis action |
| Outline variant | #C7D3CD | #465B50 | Decorative dividers, not sole control boundaries |
| Error | #B3261E | #FFB4AB | Errors and destructive actions |


The reference swatches specify the art direction, not a complete ColorScheme implementation. Generate and test the remaining Material roles. Essential outlined controls need a separate tested outline role. Do not assume the soft divider colour meets the contrast needed to identify an input.

## Accent discipline

Use primary for the most important action, focused field, progress emphasis and links. Use containers for selection. Keep ordinary setting titles and most toolbar icons neutral.

Allow success, warning, error and information to retain semantic identity independent of the user accent. Pair each with an icon and text. Blue, green or pink user themes must not make errors indistinguishable.

Give charts a separate accessible categorical palette with direct labels and patterns where useful. Preserve the established debug roles for delegate, reviewer and recalled memory in their diagnostic context, with tested light and dark variants.

Preserve ic_app_emblem artwork and ThemedAppIcon proportions. Its background circle follows theme colour; its internal reference artwork does not receive a blanket tint.

## Theme precedence and migration

Model appearance preferences as theme mode plus colour source plus optional saved profile. Colour source is Wallpaper, Preset or Custom. Selecting Wallpaper explicitly activates dynamic colour; selecting a preset or custom colour switches away from Wallpaper. Keep the previous custom profile so returning to it is lossless. Show which source is active instead of disabling controls without explanation.

Treat existing custom palettes as Legacy custom until the user chooses a tonal conversion. Preserve their raw ARGB values and current mode. A preview offers the proposed tonal mapping with Apply and Cancel. Never silently recolour a saved preset. Add a theme schema version only for new preference data, not a Room migration merely for restyling.

## Reference palette contrast

Calculated sRGB ratios for the reference palette: primary-action text is 6.43:1 in light mode and 8.08:1 in dark mode; supporting text on the main canvas is 5.98:1 and 10.05:1; accent text on its selected container is 5.10:1 and 5.99:1. Soft divider colours are only 1.54:1 and 2.14:1 against the card surface, so they must not identify an essential control by themselves. Use a stronger outline token for those controls. These checks cover the stated pairs, not every production state.

## Palette safety and preview

Use a draft ThemePreviewState local to Appearance; render a small conversation, a settings row, a button and an error example through the same theme adapter as production. Apply writes atomically; Cancel restores the original. Keep a clearly reachable reset. Contrast failures should identify the affected pair and offer a correction, while retaining access to advanced custom values and a safe fallback preview.

# 5A Geometry typography and components

| Token | Specification | Notes |
| --- | --- | --- |
| Spacing | 4, 8, 12, 16, 20, 24, 32, 40 dp | 20 dp standard screen padding; 16 dp when narrow; 24 dp in spacious panes |
| Touch target | 48 × 48 dp minimum | A 20 to 24 dp glyph sits inside the target; help icons receive the same target |
| Rows | 56 dp single line; 72 dp supporting line; content may grow | Use heightIn, wrapping and measured content; no fixed-height clipping |
| Shapes | 12 dp small control; 16 dp input; 20 dp card; 28 dp sheet | Pills only for short selections and compact status labels |
| Typography | Page 28/34 sp; section 20/26; title 16/22; body 16/24; support 14/20; label 12/16 | Use weight 400, 500, 600 or available family equivalents; essential descriptions are not tiny metadata |
| Icons | One rounded family at 24 dp; inline 18 to 20 dp | Filled selected variant only when available; icon and label agree |
| Elevation | 0 dp content; subtle 1 to 3 dp where separation needs it | Avoid stacked shadows, blurred glass and permanent glow |
| Motion | 120 to 180 ms feedback; 220 to 300 ms transitions as starting values | Tune by device; do not animate every streamed token or force a one-second wait |


Keep the bundled Proxima Nova family initially, with the existing font resources and a verified fallback. Confirm its redistribution rights before adding weights or replacing files. System font scaling must remain effective. The visual boards use a rendering substitute; implement the specified scale in the app’s actual family and review line wrapping.

## Component contract

| Component | Required behaviour |
| --- | --- |
| AppScreenScaffold | One title, back or root navigation, insets applied once, scroll content and optional bottom action region |
| SettingRow | Title, optional supporting value, leading icon, one clear control; separate accessible help action |
| SelectionRow | Radio or checkbox semantics; selected container and check mark; disabled reason remains readable |
| EntityRow | Name, one supporting line, one status, optional overflow; used by profiles, tools and models |
| StatusIndicator | Typed status mapped to icon, text, semantic colour and accessible announcement |
| AppSearchField and FilterSheet | Query restoration, active-filter count, Apply/Reset where staged, visible result count |
| AppChoiceSheet | Title, search when needed, scrolling content, pinned actions and safe-area padding |
| AppEditorScaffold | Draft state, validation, Save/Cancel, unsaved-change handling and keyboard-safe actions |
| AppDecisionDialog | Specific consequence, primary and dismiss actions, safe default focus, operation progress |
| AppEmptyState and AppErrorState | Explain what happened and the next useful action; distinguish empty, filtered and failed states |


## Accessibility and motion contract

Every interactive icon needs a meaningful accessible name. Expose selection, expanded state, validation errors and progress. Merge a row’s semantics only when its child controls are not independently actionable. Use polite announcements for stage changes and completion; never a live region for every token or countdown tick. A sheet has a pane title, traps focus while open and returns focus when closed. [S7, S8]

Respect the system animation scale. Add a reduced-motion preference without removing existing response animation and edge-fade options; effective motion is the stricter of system and app settings. Provide non-drag ways to reorder, move and acknowledge. Verify predictive back for sheets, editors, nested routes and a cancelled back gesture. [S12]

# 6A Conversations and reading

A content-led conversation list and a focused dark reading surface. The root bar belongs to the list; the conversation has a dedicated composer. Source and contribution access remain visible below the answer.


Design proposal • Representative state • Exact responsive and large-text behaviour is specified in the following sections.

# 6B Profiles and local models

Profiles share one identity pattern. Local model eligibility, installation and actual execution are separate facts. The illustrated GPU/NPU status is an example of truthful presentation, not a measurement from the user’s phone.


Design proposal • Representative state • Exact responsive and large-text behaviour is specified in the following sections.

# 6C Tools and marketplace

The Tools hub focuses on what is installed and usable. Marketplace discovery uses one clear action per item, restrained metadata and a compact filter entry.


Design proposal • Representative state • Exact responsive and large-text behaviour is specified in the following sections.

# 6D Settings and theme preview

Settings uses readable rows grouped by purpose. Appearance previews colours through real component shapes before Apply. Existing custom palettes and presets remain supported.


Design proposal • Representative state • Exact responsive and large-text behaviour is specified in the following sections.

# 6E Memory and benchmark results

Memory emphasises saved content, source history and review status. Benchmark results use a compact comparison table with raw units. All values shown in this board are demonstration data.


Design proposal • Representative state • Exact responsive and large-text behaviour is specified in the following sections.

# 6F Profile selection and permissions

Model selection preserves separate and combined reply modes. Permission review defaults to a one-time grant and exposes broader scopes deliberately. The final permission layout must bind to the verified backend scopes.


Design proposal • Representative state • Exact responsive and large-text behaviour is specified in the following sections.

# 6G GitHub workspace on a larger window

A rail, file list and detail pane organise repository work. The repository, branch, permission level and pending changes remain visible. The example shows an inspection state with no mutation pending.


Design proposal • Representative state • Exact responsive and large-text behaviour is specified in the following sections.

# 7 Conversations and first use

## UI01 Welcome and setup

Replace the decorative tour with a short introduction, an existing-account or provider setup action, a local-model path and Restore backup. Use one focused question per setup step and a clear Back action. Keep optional education available after setup.

Behaviour and preservation. Preserve provider type selection, endpoint, credentials, model selection, local downloads and setup completion. Resume drafts after rotation and external authentication. Do not require a model download before the user understands its size and access requirements.

Acceptance. A new user can reach a usable configured profile or a truthful setup-required state. Cancelling a picker or authentication does not erase earlier input.

Repository: ui/startscreen/StartScreen.kt; ui/setup/SetupPlatformListScreen.kt; SetupPlatformTypeScreen.kt; SetupPlatformWizardScreen.kt; SetupCompleteScreen.kt; LocalModelPicker.kt; common/NavigationGraph.kt

## UI02 Migration and application lock

Use a quiet progress screen with a clear title, actual stages and an explanation of what is being migrated. Authentication uses the system biometric/device credential surface, framed by a simple app lock screen.

Behaviour and preservation. Retain existing migration operations, item status and retry. Do not present a decorative percentage when total work is unknown. Preserve protection and screenshot policy, including safe return from background and incoming shares.

Acceptance. Interrupted migration reports recoverable state. The protected app never briefly exposes a previous conversation before authentication.

Repository: ui/migrate/MigrateScreen.kt; ui/main/ProtectedActivity.kt; ui/main/MainActivity.kt; AndroidManifest.xml

## UI03 Conversation list and search

Use a large root title that becomes a compact bar on scroll, a stable search field, All and Favourites views, and a Folders entry. Pinned and Recent are simple section labels. Rows show title, short preview, recency and a meaningful run or unread status.

Behaviour and preservation. Preserve pin order, drag organisation, labels, favourites, folders, multiselect, archive, delete confirmation, unread entry and draft indicators. Add visible menu equivalents to gestures. Keep a stable New chat action above root navigation.

Acceptance. Long titles, 200 percent text, empty folders and 1,000 fixture conversations remain usable. Search results identify their destination and return to the previous query and scroll position.

Repository: ui/home/HomeScreen.kt; ConversationFolders.kt; ConversationPinOrder.kt; ConversationPinFeedback.kt; ChatProfileOrder.kt

## UI04 Favourites folders and archive

Favourites use the same row geometry and an optional group filter. Folder editing is a compact form with name and any supported appearance controls. Archive opens as a half-height sheet when space allows, with expand and full-screen affordances for long lists.

Behaviour and preservation. Keep favourite response targets, group creation, pinning, folder move/reorder and archive restore. Distinguish archived conversations from collapsed older assistant replies. Archive Undo is only offered when the underlying action supports it; permanent delete remains confirmed.

Acceptance. Opening a favourite reaches the exact response. Archive restores return to the correct list location. Every drag and swipe action has a menu or accessibility action.

Repository: ui/home/HomeScreen.kt; ui/archive/ArchivedConversationsBar.kt; ui/chat/ChatScreen.kt

## UI05 New conversation and profile selection

Use a full-screen selection flow on compact windows when multi-profile selection and reply mode are both needed; use a sheet for a simple single-model switch. Show search, source filters, a selection summary and a sticky Start action.

Behaviour and preservation. Keep profile labels, free/local/remote eligibility, separate and combined modes, unavailable reasons and last-used suggestions. Suggest the previous choice only if still enabled and permitted. Free-provider tool consent remains separate from profile selection.

Acceptance. Selected IDs and mode match the created conversation. Disabled profiles cannot become active through a visual shortcut. Large selections remain inspectable before Start.

Repository: ui/home/HomeScreen.kt SelectPlatformDialog; unified/UnifiedModelPickerScreen.kt; common/ProfileDiscovery.kt; common/FreeProviderPicker.kt

# 7A Conversation interaction

## UI06 Conversation reading surface

Remove unnecessary outer cards around every assistant paragraph. Use the neutral canvas for answers, a soft container for user prompts, a modest author line and a compact action row. Keep rich headings, lists and code readable without excess decoration.

Behaviour and preservation. Preserve response revisions, copy, edit, select text, retry, favourite, export, automatic stable titles, optional timestamps, older reply expansion and original source links. Existing scrolling and entry-position logic remains the authority during refactoring.

Acceptance. Streaming, unread entry, favourite jumps, manual scroll-up and keyboard opening do not steal position. Long answers and attachments keep stable message keys.

Repository: ui/chat/ChatScreen.kt; ChatBubble.kt; ChatMarkdown.kt; AssistantChronologicalContent.kt; StableChatResponseViewport.kt; ConversationEntryPosition.kt

## UI07 Composer and attachments

Use a single rounded input surface with attachment control, expandable text area and a distinct Send action. The active profile/mode appears near the header. Research and tool selection are compact contextual controls, not a permanent row of every feature.

Behaviour and preservation. Keep Stop independently reachable while typing a follow-up. Keep queued count, three-second follow-up feedback where supported, edit/cancel controls, attachment name/size/state and retry/remove. Do not make pending or running content unreadable by reducing all text opacity.

Acceptance. Send never acts as Stop. A failed attachment is visible before send. At narrow width and large text, auxiliary controls wrap or move into a labelled sheet while Stop and Send remain usable.

Repository: ui/chat/ChatScreen.kt ChatInputBox; ChatAttachmentDraft.kt; QueuedPromptBubble.kt; MessageQueueIndicatorBanner.kt; PromptQueuePanel.kt

## UI08 Separate and combined responses

For Separate mode, provide clear profile sections and quick profile navigation. For Combined mode, lead with the organised final response and a compact Contributions entry. Show each profile’s Waiting, Running, Complete, Failed or Excluded state with text and icon.

Behaviour and preservation. Retain every stored contribution and source. The redesign must not change synthesis into a short summary or remove unique information. Contribution inclusion claims require recorded evidence; unknown inclusion is labelled unknown. Reviewer and delegate debug views remain reachable.

Acceptance. Partial failures do not hide successful output. Users can inspect every original contribution and understand which result is final. UI state never marks a failed profile complete.

Repository: ui/chat/CombinedProfileBubbles.kt; CombinedResponseState.kt; CombinedContributionQuality.kt; ReviewerDebugText.kt; DebugActivityContent.kt

## UI09 Progress queue and recovery

Use one expandable run-status row for current stage, elapsed work time and essential action. Details open a timeline. Queue opens a list of editable pending items with pause, resume, reorder and remove only where supported.

Behaviour and preservation. Bind progress to actual run events. Display interrupted, cancellation requested and cancelled as different states. Retain delegation recovery and saved remote job continuation. Background navigation does not cancel work or restart a job.

Acceptance. After background/resume the UI reflects the authoritative run, not a local spinner. A recovery action states its scope and never automatically replays a mutation with an unknown outcome.

Repository: ui/chat/AgentRunStatusBlock.kt; ResearchProgressCard.kt; AgentFlightRecorderCard.kt; DelegationRecoveryDialog.kt; unified/MessageQueueScreen.kt; common/BenchmarkExecutionStatus.kt

## UI10 Sources evidence and rich content

Show a small source strip with count and a Sources sheet listing title, domain, citation number and available supporting context. Use a full-screen inspector for long evidence, code, wide tables, math, maps and media.

Behaviour and preservation. Preserve source provenance and unique evidence during deduplication. Keep original links, tool result ownership and evidence edits. Provide failure fallbacks for missing images, invalid links, unavailable maps and unsupported content.

Acceptance. Thirty or more sources remain navigable without a huge row of tiny icons. Links and citations resolve to recorded evidence; raw metadata is never fabricated to fill a design slot.

Repository: ui/chat/ChatSources.kt; ChatSourcePicker.kt; AnswerSourcePresentation.kt; ChatSourceBrand.kt; SelectTextBottomSheet.kt; LocationToolMapPreview.kt; AmazonProductResults.kt; AirbnbListingResults.kt

# 8 AI profiles local models and research

## UI11 AI hub and provider connections

Profiles are the initial AI view. Connections and Local are adjacent destinations; Delegation, Recipes, Compare and Budgets are clear secondary entries. A profile row has name, model/source, health and overflow; a provider connection shows shared ownership.

Behaviour and preservation. Retain profile creation, enable/disable, labels, favourites, ordering and existing actions. Editing shared credentials shows affected profiles. Connection health is time-stamped observed state, not an inference from having saved an API key.

Acceptance. All configured profiles are reachable, including disabled and unavailable ones. A connection change does not silently recreate profile IDs or erase per-profile tool assignments.

Repository: ui/setting/AiPlatformsScreen.kt; ProviderConnectionSettingsScreen.kt; AddPlatformScreen.kt; common/ProfileLabelComponents.kt

## UI12 Profile editor

Organise the editor into Overview, Behaviour, Tools and Advanced. Overview holds identity, provider and model. Behaviour holds prompts, response options and supported reasoning. Tools holds assignments and restrictions. Advanced contains provider-specific sampler, limits and runtime controls.

Behaviour and preservation. Keep current UID, enabled state, labels, endpoint/credential ownership, system prompt, temperature, top-p, top-k, token limits, timeout, search backend, tool-call limits, provider safety and local accelerator settings. Hide unsupported inputs only with a clear applicability explanation and lossless saved values.

Acceptance. A save changes only edited fields. Cancel discards the draft. Editing a profile with provider-specific advanced settings round-trips without dropping unknown or currently hidden options.

Repository: ui/setting/PlatformSettingScreen.kt; PlatformSettingDialogs.kt; ProfileToolsPanel.kt; CreativitySlider.kt; MaxToolCallsSetting.kt; common/SystemPromptEditor.kt

## UI13 Model picker family

Use one row, search, sorting and capability vocabulary across cloud, OpenRouter, Ollama, llama server and local pickers. Preserve each workflow’s selection contract rather than forcing all providers into one implementation.

Behaviour and preservation. A single-select model switch can apply directly only when clearly stated; multi-select uses Apply. Keep discovery refresh, manual model IDs, server changes and free models. Distinguish unsupported from unknown capability; show measured performance only when available and comparable.

Acceptance. A selected model remains selected through filtering. Provider-specific model metadata and endpoints are retained. A failed catalogue request leaves manual entry and the current model available.

Repository: ui/chat/CloudModelPickerDialog.kt; ui/setting/OpenRouterModelPickerDialog.kt; LlamaModelPickerDialog.kt; DelegateModelDropdown.kt; ui/llama/LlamaModelDropdown.kt; unified/UnifiedModelPickerScreen.kt

## UI14 Local model library and discovery

Split Local models into Installed, Discover and Runtime. Installed prioritises usability and active state. Discover has search and a filter sheet for architecture, runtime, quantisation and hardware compatibility. Model details show size, licence/access, package identity and exact requirements before Download.

Behaviour and preservation. Preserve local import, download progress, cancellation, retry, removal, Hugging Face access, gated licences, MTP and supported format selection. Preserve all runtime adapters and flags. Eligibility, file presence, loaded runtime and successful inference remain separate indicators.

Acceptance. A QNN-compatible catalogue entry is never labelled NPU running without runtime evidence. An incompatible package has a reason and a relevant alternative; interrupted downloads have a truthful recoverable state.

Repository: ui/setting/LocalModelsScreen.kt; LocalModelCatalogUi.kt; ui/localmodel/LocalModelDownloadDialogs.kt; LocalModelDownloadStatus.kt; LocalModelDownloadActions.kt

## UI15 Local runtime and device health

Give Runtime a compact device summary, current engine/accelerator, fallback explanation and last verification. Put tuning under Performance and resource management under Storage and thermal controls. A details section exposes runtime package versions and diagnostics.

Behaviour and preservation. Retain CPU threads, compiled cache, speculative decoding mode, Local Assist, native metrics, idle release and automatic QNN fallback. Preserve GenieX build gating and per-profile accelerator/context ownership. Do not promise CPU plus GPU plus NPU parallelism from a new visual control.

Acceptance. Changing runtime tuning while busy respects the current locking rules. Backend failures show requested versus actual execution and a next action. Benchmarking remains the basis for speed claims.

Repository: ui/setting/LocalRuntimeSettingsCard.kt; ui/setting/PlatformSettingDialogs.kt AcceleratorDialog; data/model/AppFeatureSettings.kt

## UI16 Delegation and reviewer

Move delegation from a large nested configuration popup to AI → Delegation. Use Overview, Helper, Reviewer and Limits sections. A compact summary states enabled status, selected helper, reviewer and research depth. Keep explanatory help beside ambiguous controls.

Behaviour and preservation. Retain ownership slider semantics, strategies, cloud helpers, cloud recall, budgets, tool limits, retry/recovery and reviewer exclusions. The displayed delegation amount is derived from the stored processingOwnership mapping; never reverse it accidentally. Keep global, profile and conversation scopes visible.

Acceptance. Existing settings survive byte-equivalent serialisation where no field changed. Helper and reviewer eligibility matches current policy. Disabled cloud recall is never enabled by choosing a helper or applying a theme.

Repository: ui/setting/ModelDelegationSettingsPanel.kt; LocalToolsSettingsPanel.kt; ConversationDelegationCard.kt; data/model/ModelDelegationSettings.kt; ConversationDelegationSettings.kt

## UI17 Research tools recipes comparison and budgets

Research is a contextual composer control opening depth, selected sources and related tool settings. AI also hosts existing Recipes, model comparison and Budgets as focused screens rather than unrelated Workspace tabs.

Behaviour and preservation. Preserve multi-search controls, deduplication, cache/reuse choices, DeepResearch settings if available at the implementation baseline, recipe editing, spend budget and token budget. Keep real policy values and limits; a decorative depth slider cannot imply unlimited tool calls or access.

Acceptance. Existing recipes and budget values migrate without loss. Search results link to the canonical setting. Any feature from a newer branch must be verified before its control is presented as available.

Repository: ui/setting/DeepResearchSettingsCard.kt; PluginConfigurationDialog.kt; ui/workspace/WorkspaceScreen.kt RecipeEditor and BudgetPanel; common/AdvancedOptions.kt

# 9 Tools marketplace and workspaces

## UI18 Tools hub

Use Installed and Connections views with a single Marketplace entry. Group by user task where useful, but keep search global within Tools. Cards show name, purpose, actual status and one next action; long technical descriptions live in details.

Behaviour and preservation. Preserve all native and remote tool families, opt-in defaults, execution ownership, enabled state and per-profile restrictions. A service may be installed yet not connected, or connected yet not permitted. Keep these dimensions explicit in details.

Acceptance. A disabled global service cannot appear usable merely because a profile has selected it. Native tools remain available without requiring a remote MCP connection.

Repository: ui/setting/ToolConnectionsScreen.kt; ToolServiceComponents.kt; ui/mcp/NativePluginSettings.kt

## UI19 Marketplace and plugin detail

Use Plugins and Tools as distinct views when they reflect current catalogue types. Keep search at the top and put compound filters in a sheet. Detail presents purpose, publisher/source, access requirements, execution location, permissions and installation state.

Behaviour and preservation. Retain install/download/integrity checking, setup, export package, remove connection, uninstall and registry repair. Render actions from the real package type. Do not label a documentation-only or externally hosted item as installed software or verified publisher without evidence.

Acceptance. Each item offers the correct primary action for its state. Removing a package accurately lists affected generated connections while preserving manually created connections according to existing rules.

Repository: ui/mcp/McpMarketplaceDialog.kt; ui/mcp/MarketplaceViewModel.kt; common/McpMarketplaceDialog.kt wrapper

## UI20 Connection setup and editing

Use a focused editor for simple name/endpoint changes and a step flow for provider, authentication and tool discovery. Validation is inline. Keep a sticky Next or Save region above the keyboard, and preserve draft values during OAuth or pairing.

Behaviour and preservation. Retain connection type, stable alias, endpoint, bearer/API-key/OAuth modes, credentials, cleartext private-network option, tool policy and connectivity probe. Saving does not mean connected. Mask stored secrets and use the current secure storage adapter.

Acceptance. A cancelled external flow resumes the same draft. Editing a connection does not erase an unchanged credential. Duplicate aliases and incompatible endpoint types show precise errors.

Repository: ui/setting/ToolConnectionsScreen.kt ToolConnectionEditorScreen; ToolConnectionSetupFlow.kt; ServerPairingActivity.kt; ConnectionDoctorDialog.kt

## UI21 Tool assignment permissions and resources

Use searchable tool lists with selection counts and a permission summary. A resource/prompt browser gets its own screen when content is long. Provide a clear Permissions destination listing saved grants by actual scope and source.

Behaviour and preservation. Preserve per-profile selection, connection policy, discovery, resources, prompts and structured MCP input. For tool approval, keep Once, Always this tool for all models and Always this provider where supported. A narrower conversation scope is shown only after the backend contract is implemented and tested.

Acceptance. A UI selection does not grant permission until the relevant action succeeds. Revocation is reflected in profile and conversation state. Dismissing approval never authorises a tool.

Repository: ui/setting/McpToolsSelectionScreen.kt; McpServerPickerDialog.kt; ToolPolicyDialog.kt; McpBrowserDialog.kt; ui/chat/ChatToolSelectionBottomSheet.kt; McpInputDialog.kt; ToolPermissionDialogs.kt

## UI22 GitHub workspace

Create a dedicated Tools → GitHub workspace with repository and branch context, files, changes and review. Compact mode navigates list to detail. Expanded mode uses a file pane and a readable diff/detail pane with a stable action region.

Behaviour and preservation. Retain repository selection, branch selection, file reading, drafts, inspect/validate, save/discard and explicit commit/review actions. Conversation branches remain elsewhere. Preserve permission gates and scope descriptions; visual cleanup cannot bypass write restrictions.

Acceptance. Before a mutation, the user can identify target repository, branch and files. Returning from a file keeps the selected branch and draft. Diff colours have text or line markers as well as colour.

Repository: ui/setting/GitHubWorkspaceScreen.kt; ui/workspace/GitHubReviewPanel.kt; ProjectWorkspacePanel.kt; common/Route.kt

## UI23 Native service configuration and rich results

Apply the same detail layout to Amazon, news, location, places, Airbnb and other service-specific settings. Product/listing results use a shared visual shell while retaining their service-specific fields. Images open an accessible media viewer.

Behaviour and preservation. Keep product detail, media gallery, observed price history, watch controls, maps, listing links and data deletion where present. Clearly distinguish observed history from guaranteed historical prices and saved data from live data. Native service enablement stays opt-in where currently defined.

Acceptance. A missing image or blocked source does not break the list. Result actions operate on the correct item. Wide plots and maps provide an inspectable full-screen state.

Repository: ui/amazon/AmazonDataScreen.kt; AmazonProductDetailDialog.kt; AmazonProductGallery.kt; ui/chat/AmazonProductResults.kt; AirbnbListingResults.kt; LocationToolMapPreview.kt; ui/setting/McpMediaActivity.kt

# 10 Settings memory and data

## UI24 Settings root and global settings search

Use six clear rows: Appearance, Conversations, Memory, Privacy & data, Activity & diagnostics and About. Search appears before categories and replaces them with results while active. Results show title, breadcrumb and optional current value.

Behaviour and preservation. Retain all existing search keys and add aliases for old labels such as Platforms, Fact vault and Advanced. Deep links select the correct section, scroll to the control and briefly highlight it. Dynamic profile controls use stable IDs.

Acceptance. Every persisted user-facing setting has a canonical location or an explicit legacy/gated disposition. No result points to a category without focusing the intended setting when focus is supported.

Repository: ui/setting/SettingScreen.kt; SettingsSearchRegistry.kt; SettingsDiscoveryPanel.kt; common/SettingSearchFocus.kt

## UI25 Appearance and display

Use Theme, Colour and Display sections with a live preview. Presets show an actual miniature surface, label and selected check. Custom editing supports the current fields, saved profiles, contrast feedback and reset. Display holds mode, density and motion-related presentation choices.

Behaviour and preservation. Preserve System/Light/Dark, wallpaper colours, custom primary, full custom palette, saved profile names and delete/reset actions. Existing edge fades and response animation remain controllable. New reduced-motion and density options are additive and explicitly persisted.

Acceptance. Apply is atomic and Cancel does not change the active theme. Wallpaper switching preserves the prior custom theme. Extreme custom colours remain recoverable through a visible safe reset.

Repository: ui/setting/ThemeSettingsScreen.kt; CustomPaletteEditor.kt; theme/Theme.kt; PaletteContrast.kt; data/dto/ThemeSetting.kt

## UI26 Conversation preferences and advanced settings

Move reading, composer behaviour, background generation and notifications to named sections under Conversations. Retain specialised engine and research settings in their owning destination. Keep an All advanced controls index for power users, linked to those same values.

Behaviour and preservation. Preserve existing AppFeature fields and defaults. The current parallel-search feature is effectively on and its control is disabled; display its actual policy instead of presenting a working off switch. Do not duplicate persistence when the same control has a shortcut.

Acceptance. Changing a setting from either entry point immediately updates the other. Existing backup imports preserve the value, and obsolete names still find the new location.

Repository: ui/setting/AdvancedSettingsScreen.kt; SettingsSearchRegistry.kt; data/model/AppFeatureSettings.kt; data/datastore/SettingDataSource.kt

## UI27 Memory library and review

Use Saved, Documents and Review as primary sections. Topics is a saved-content filter or linked detail; Overview becomes a compact summary. A memory row shows text, source and relevant state. Editing, source/history and conflict review open focused detail surfaces.

Behaviour and preservation. Retain add/edit/delete, enable/disable, pin, merge/split, bulk review, replacement proposals, keep existing/keep both, original message links, prior values and wrong-topic removal. Confidence is extraction confidence, not a guarantee of truth.

Acceptance. A proposed replacement does not silently replace an active memory. Source links reach the original message. Bulk operations show selection count and protect manual or pinned content according to existing behaviour.

Repository: ui/setting/FactVaultScreen.kt; ui/setting/FactVaultViewModel.kt; common/Route.kt FACT_VAULT

## UI28 Memory controls and documents

Group controls into Learning, Recall, Privacy, Capacity and Connected memory. Documents has import, indexing progress, failure/retry and remove. Use current values with concise help rather than repeated explanatory cards.

Behaviour and preservation. Retain sensitivity, preferences/relationships/topics learning, repetition threshold, local-model learning, learning rotation, semantic recall/index rebuild, pinned priority, cloud recall, original-chat restriction, review-before-use, ranking, token/count budgets, capacity, retention and external MCP selection. Preserve all cross-setting restrictions.

Acceptance. No control implies that on-device storage means cloud requests cannot include recalled content. Connected-memory state explains why recall is paused. Search can focus each control without exposing credentials.

Repository: ui/setting/FactVaultScreen.kt; KnowledgeSourceActivity.kt; LocalToolsSettingsPanel.kt; data model and ViewModel owning memory controls

## UI29 Privacy storage backup and restore

Privacy & data contains app protection, screen privacy, storage cleanup and Backup & restore. Use a full-screen backup flow with explicit Create and Restore routes, section selection, credential/recovery handling, preview and progress.

Behaviour and preservation. Retain current complete backup workflow and recovery-key handling, optional data sections, encrypted secrets, conversations, settings, profiles, plugins and benchmark results. Legacy BackupRestoreScreen is migrated only after caller and format checks; it does not replace the current host.

Acceptance. A restore preview states the actual operation and counts. Cancellation leaves existing data intact where supported. A round-trip fixture preserves benchmark tables, saved themes, IDs and permission data under current security rules.

Repository: ui/setting/PrivacyStoragePanel.kt; BackupRestoreHost.kt; CompleteBackupDialog.kt; compatibility ui/screens/BackupRestoreScreen.kt

## UI30 About licences and external handoffs

Use a compact About screen with app name, exact version, update/release links where supported, credits and licences. Licence search uses a readable list and detail page. External browser, document and system sharing surfaces remain native.

Behaviour and preservation. Keep all existing legal text, credits and links. ShareIncomingActivity validates incoming content and routes to a clear destination without losing attachments. The redesign should not imitate system permission or file-picker UI.

Acceptance. Back returns from external surfaces correctly. Licence content remains readable with large text. Incoming shares never silently target the wrong conversation.

Repository: ui/setting/AboutScreen.kt; LicenseScreen.kt; ui/main/ShareIncomingActivity.kt; common/CreditsViewModel.kt

# 11 Activity diagnostics and benchmarks

## UI31 Activity and running work

Unify long-running work in Activity with Runs, Usage, Benchmarks and Diagnostics. The root icon has an active indicator only when needed. A compact persistent task strip shows actual progress and opens the appropriate run without blocking navigation.

Behaviour and preservation. Retain chat runs, queue, remote tasks, pending approvals, saved task continuation, benchmark progress and interruption states. Do not let composable disposal own cancellation. Stop and dismiss remain different actions.

Acceptance. A benchmark started on any screen continues according to the existing background service policy. The strip never covers the composer or keyboard action row, and back navigation does not create a second run.

Repository: common/BenchmarkExecutionStatus.kt; ui/workspace/WorkspaceScreen.kt Tasks; ui/setting/DebugDiagnosticsScreen.kt; service/BenchmarkForegroundService.kt

## UI32 Debug live runs logs and health

Diagnostics opens a summary of device and connection health with direct entries to Live, Runs, Logs and Display. Use dense readable records with expandable detail rather than a full card for every metric. Log filters and export remain anchored.

Behaviour and preservation. Retain all debug display toggles, request/tool traces, redaction, runtime/backend, memory, thermal/network observations and explicit health checks. Keep current sampling semantics; opening another tab must not imply a new successful connection test.

Acceptance. Unknown, stale, disconnected and failed are distinct. Diagnostic export stays redacted and tied to the chosen run. Long values can be selected without blowing out layout.

Repository: ui/setting/DebugDiagnosticsScreen.kt; LiveDiagnostics.kt; AppLogPanel.kt; ConnectionDoctorDialog.kt; ui/chat/DebugActivityCard.kt

## UI33 Usage statistics

Show time range and profile/provider filter above a small overview: requests, tokens, latency and cost when known. Use compact charts followed by the corresponding accessible table. Move attribution and estimate explanations into adjacent help.

Behaviour and preservation. Retain current accounting sources, trends, outcomes, provider grouping, profile/model distinctions and token-comparison data. Unknown cost is not zero. Estimated and provider-reported tokens remain distinguishable.

Acceptance. Every chart has units, date range and an equivalent readable data representation. Filtering changes all visible summaries consistently and never compares different time windows without labelling them.

Repository: ui/setting/UsageStatisticsScreen.kt; BenchmarkCharts.kt; ProfilePerformance.kt; ModelPerformance.kt

## UI34 Benchmark results and comparison

Lead with Results and a clearly selected cohort. Display profile, overall score, speed score, measured tokens per second, reliability and freshness with adjustable visible columns. On compact windows show key columns and an expandable detail; expanded windows can show the full table.

Behaviour and preservation. Retain dynamic score scaling within eligible cohorts, separate local and remote interpretation, provisional runs, snapshots, raw metrics and historical results. Sort labels describe direction and metric. Do not re-score or rewrite historical measurements as a visual side effect.

Acceptance. Unknown metrics display Not measured. Local and remote rankings do not share a misleading speed normalisation. The current score snapshot and measurement date are inspectable.

Repository: ui/setting/ProfileBenchmarkScreen.kt; BenchmarkCharts.kt; ProfileBenchmarkViewModel.kt

## UI35 Benchmark run delegation and history

Use a focused Run form for plan, selected profiles and constraints, with a review summary before starting. Delegation is a mode within Run and a result filter. History lists run time, configuration, completion and comparison eligibility.

Behaviour and preservation. Keep sequential execution, selected delegate lists, primary model, reviewer score, ownership, output/handoff budgets, test fixtures, diagnostic events and save-after-test behaviour. Preserve background service and backup integration. Cancellation does not masquerade as failure or completed success.

Acceptance. Navigate away, rotate and resume without losing results or starting duplicate work. Restore a backup and verify raw runs, snapshots, score tables and current settings. All failures remain inspectable.

Repository: ui/setting/ProfileBenchmarkScreen.kt; ProfileBenchmarkViewModel.kt; common/BenchmarkExecutionStatus.kt; service/BenchmarkForegroundService.kt

# 12 Popup and editor renovation register

Every popup is assigned by task, not by the name of its existing Kotlin file. Reuse shells, while retaining each workflow’s selection rules, validation and permission semantics. Critical decisions use a decision dialog or a deliberately modal review sheet; long editors use a route. Only one modal owns focus at a time.

| Pattern | Use and contract |
| --- | --- |
| Menu | Up to a short set of immediate actions. Neutral labels; destructive action separated. Dismiss on action, preserve the underlying selection. |
| Choice sheet | Searchable choices, filters, queue or source inspection. Content-driven height, explicit close, accessible expand where useful. Confirm only when selection is staged. |
| Decision dialog | Delete, overwrite, reset, irreversible action or short permission choice. State object and consequence; use specific verbs rather than Yes/No. |
| Review sheet | Permission or access review with arguments and scope. Default to the narrowest existing permission; broader choices require explicit selection. |
| Editor route | Long text, multi-field credentials, prompts, configuration, backup and complex model settings. Draft, validation, Save, Cancel and unsaved-change handling. |
| Fullscreen viewer | Media, wide tables/code, map, long source or diagnostic record. Selection, zoom where appropriate, external open and Back. |


| ID | Existing surface | New presentation | Preservation contract |
| --- | --- | --- | --- |
| P01 | New-chat profile selection | Full-screen selector / wide-window panel | HomeScreen SelectPlatformDialog; shared picker rows; Keep multi-select and response mode; explicit Start. |
| P02 | Cloud and local model switch | Choice sheet | CloudModelPickerDialog; ChatModelDialog; LocalModelDialog; Retain provider/model contract and unavailable reasons. |
| P03 | OpenRouter Ollama llama pickers | Choice sheet with detail route | OpenRouterModelPickerDialog; LlamaModelPickerDialog; LlamaModelDropdown; Keep manual IDs, server choice, refresh and metadata. |
| P04 | Chat title and profile rename | Small editor dialog | ChatTitleDialog; PlatformNameDialog; Trim/validate with current limits; preserve draft on validation error. |
| P05 | User and assistant message edit | Editor route | UserMessageEditDialog; AssistantMessageEditDialog; Keep revision/attachment semantics; explain effect before save. |
| P06 | Profile management in chat | Choice sheet | ChatProfileManagementDialog; Keep enable/pause constraints and current run safety. |
| P07 | Message action overflow | Anchored menu | ChatBubbleDropdownMenu; ChatDropdownMenu; Correct message target, role and capability-specific actions. |
| P08 | Select text | Fullscreen selection surface | SelectTextBottomSheet; Keep selectable content and copy; do not capture hidden records. |
| P09 | Export conversation | Choice sheet with format details | ChatExportDialog; Plain text, Markdown and debug-gated JSON; preserve exact export scope. |
| P10 | Sources and evidence | Choice sheet to detail viewer | ChatSourcePicker; ChatSources; Workspace evidenceEdit; Actual evidence, original links, owner and source context. |
| P11 | Prompt queue | Choice sheet / Activity route | PromptQueuePanel; MessageQueueScreen; Count, edit, pause, resume, remove and supported ordering. |
| P12 | Archive and favourite detail | Choice sheet / detail route | ArchivedConversationsBar; FavoriteDetailDialog; Keep precise response targets, groups, restore and delete protection. |
| P13 | Folder and favourite group edit | Small editor dialog | ConversationFolders; AddFavoriteGroupDialog; Name and supported style fields; explicit move/reorder alternatives. |
| P14 | Delete chats profiles or records | Decision dialog | DeleteWarningDialog; DeletePlatformDialog; memory delete; Show count and consequence; offer Undo only if actually recoverable. |
| P15 | Tool approval | Modal review sheet | ToolApprovalDialog; Once, all-model tool grant and provider grant mapped to real callbacks. |
| P16 | Free-provider data consent | Modal review sheet | FreeToolConsentDialog; Preserve explicit acknowledgement and scope; slider gets equivalent accessible action. |
| P17 | MCP structured input | Form sheet or editor route | McpInputDialog; Generate fields from schema; validation; cancellation responds correctly. |
| P18 | Tool selection and policy | Choice sheet / policy detail | ChatToolSelectionBottomSheet; McpToolsSelectionScreen; ToolPolicyDialog; Assignments and permission grants remain separate. |
| P19 | MCP server resources prompts | Detail route | McpServerPickerDialog; McpBrowserDialog; Discovery, resource reading and prompt inputs; no accidental execution. |
| P20 | Plugin install and uninstall | Detail route plus decision dialog | McpMarketplaceScreen; McpPresetConfigureDialog; Integrity state, package/connection scope and cancellation. |
| P21 | Connection and plugin configuration | Editor route | PluginConfigurationDialog; ToolConnectionEditorScreen; NativePluginSettings; Keep credentials, execution controls and all service-specific fields. |
| P22 | Connection doctor and pairing | Status sheet / pairing route | ConnectionDoctorDialog; ServerPairingActivity; pairingLink dialog; Observed results, copyable errors and retry without losing configuration. |
| P23 | API key URL timeout | Small editor / connection route | PlatformSettingDialogs APIKey/APIUrl/Timeout; Preserve secure storage and unchanged secret semantics. |
| P24 | Sampling and limits | Numeric editor sheet | Temperature/TopP/TopK/MaxTokens dialogs; Exact ranges, units, default/reset and keyboard input. |
| P25 | System prompt and provider advanced | Editor route | SystemPromptDialog; GeminiSafety; OpenRouter/Ollama/LlamaAdvancedSettings; Preserve every provider field and existing constraints. |
| P26 | Accelerator and runtime choice | Choice sheet plus explanation | AcceleratorDialog; local-model forms; Requested versus actual backend; unavailable reason and fallback policy. |
| P27 | Local download access flows | Status sheet / secure auth route | HuggingFaceSignInSheet; LicenseSheet; AccessTokenDialog; Action/Message/ConfirmDialog; Keep terms, gating, account choice, retry and cancellation. |
| P28 | Memory add edit merge split review | Editor route / decision dialog | FactVaultScreen edit, restructure and inspection states; Retain original values, proposals, source links and bulk selection. |
| P29 | Themes custom colour saved profile | Appearance route / small name dialog | ThemeSettingDialog; CustomPaletteEditor; Draft preview, ARGB preservation, reset and saved-profile deletion. |
| P30 | Backup restore and section selection | Full-screen flow plus final decision | BackupRestoreHost; CompleteBackupDialog; BackupSelectionDialog; RestorePreviewDialog; Retain current backup format, recovery key and restore preview. |
| P31 | Delegation settings and recovery | AI route / recovery sheet | LocalToolConfigurationDialog; ModelDelegationSettingsPanel; DelegationRecoveryDialog; Retain all budgets and eligibility; recovery uses actual job state. |
| P32 | Product gallery history and watch | Detail viewer / small editor | AmazonProductDetailDialog; AmazonProductGallery; AmazonWatchDialog; Correct item identity, observed values and watch settings. |
| P33 | Help and diagnostic inspection | Help sheet / record detail | SettingsHelpIcon; DebugActivityContent; AppLogPanel; Accessible title; copy where useful; redacted technical details. |
| P34 | Generic error reset and unsaved changes | Inline error then decision if needed | FadingAlertDialog callers and screen wrappers; Preserve user input; scoped reset; safe Cancel; one modal focus owner. |


## Export is a content contract

The current plain-text and Markdown exporters save selected response revisions without prompts, thinking or tool activity. The debug format includes broader stored conversation and diagnostic data with credentials redacted, but not attachment contents or unrecorded network payloads. The redesigned choice sheet must explain this rather than imply every format is a full backup. Do not silently change export contents as a styling change. [R6]

## Dialog lifecycle

Hoist editor drafts and pending selections to a state holder with stable IDs. Preserve them across rotation and process recreation where appropriate, excluding raw secrets from SavedStateHandle. Dismissal of a choice sheet discards unapplied selection; dismissal of an editor with changes asks whether to discard. Opening a nested detail replaces the sheet or navigates forward instead of stacking multiple independent dialogs. Returning restores the parent state and focus.

# 13 Feature and settings preservation

Feature parity is an implementation gate. The tables below map the current feature registry and important multi-field editors into the new structure. Keep persisted keys and value semantics stable. A layout move is not permission to reset defaults, merge distinct settings or remove an advanced option.

| Existing AppFeature key | Canonical destination | Preservation rule |
| --- | --- | --- |
| COMPACT_CHAT_LIST | Appearance → Display | Existing compact mode; new density choices must map losslessly |
| SMOOTH_STREAMING | Conversations → Reading | Retain follow-until-user-scrolls behaviour |
| CENTER_UNREAD | Conversations → Reading | Retain unread entry policy |
| RESPONSE_ANIMATION | Appearance → Motion | Keep user preference; system reduced motion wins |
| EDGE_FADES | Appearance → Display | Keep option; do not obscure interactive content |
| MESSAGE_TIMESTAMPS | Conversations → Reading | Preserve current value |
| QUEUED_FOLLOW_UPS | Conversations → Composer | Keep safe-boundary queue behaviour |
| PARALLEL_SEARCH | Tools → Research → Execution | Current policy is on and toggle disabled; show truthfully |
| DEDUPLICATE_SEARCH | Tools → Research → Results | Keep URL merging semantics |
| REUSE_SEARCH | Tools → Research → Efficiency | Preserve authorised reuse rules |
| DEDUPLICATE_SEARCH_CONTENT | Tools → Research → Results | Retain trial status and alternative sources |
| GITHUB_CONDITIONAL_READS | Tools → GitHub → Efficiency | Preserve ETag validation |
| GITHUB_BLOB_CACHE | Tools → GitHub → Efficiency | Preserve immutable content cache |
| LOCAL_MODEL_CACHE | AI → Local → Runtime | Preserve compiled-model cache |
| LOCAL_ASSIST | AI → Local → Runtime | Retain trial status, limits and default |
| LOCAL_NATIVE_METRICS | AI → Local → Runtime | Preserve metrics overhead explanation |
| BACKGROUND_GENERATION | Conversations → Background | Retain service behaviour |
| RESPONSE_NOTIFICATIONS | Conversations → Notifications | Retain OS permission and app preference |
| AUTOMATIC_TITLES | Conversations → Organisation | Preserve one-time stable naming |
| ARCHIVE_OLDER_REPLIES | Conversations → Reading | Keep separate from archived conversations |
| SHOW_REASONING | Conversations → Reading | Show only model-provided reasoning |
| SMART_SUGGESTIONS | Conversations → Composer | Retain optional follow-up suggestions |
| REMOTE_MCP | Tools → Connections | Global gate remains global |
| SHARED_TOOL_CALLS | Tools → Execution | Keep safe result-sharing rules |
| DEVICE_LOCATION | Tools → Location | App setting does not grant OS location access |
| MODEL_DISCOVERY | AI → Connections | Preserve manual model entry fallback |
| DIAGNOSTICS | Activity → Diagnostics → Collection | Preserve opt-in/default and storage rules |
| OPENROUTER_BATCH | AI → Connections → OpenRouter | Retain supported asynchronous workloads |
| QNN_AUTO_FALLBACK | AI → Local → Runtime | Keep global value and requested/actual labels |


## Debug metric controls

All eleven DebugMetric switches remain in Activity → Diagnostics → Display: TOOL_CALLS, TOTAL_TOKENS, TOKEN_SPEED, TIME_TO_FIRST_TOKEN, RUNTIME, HARDWARE, NETWORK, DELEGATION_TRACE, REVIEWER_TRACE, MEMORY_RECALL and TOKEN_COMPARISON. Preserve the existing conversation debug-mode gate separately from each visibility switch. Theme changes alter presentation, not collection or display consent.

## Other persisted and dynamic settings

| Owner | Fields and behaviours to preserve | Destination |
| --- | --- | --- |
| AppFeatureSettings | lastChatProfileUids, lastChatMode, localCpuThreads, localSpeculativeDecoding, localIdleMinutes, pluginExecution, toolPluginStates, delegation, profileBehavior, conversationReasoning, conversationDelegation, spendBudget, tokenBudget | Owning AI, Tools, Conversations or Activity screen; scope shown explicitly |
| ThemeSetting | themeMode, dynamicTheme, customPrimaryArgb, customPalette, savedProfiles and any existing profile metadata | Appearance; legacy values retained |
| Profile and provider editors | Identity, provider UID, model, endpoint, credentials, enabled state, labels, system prompt, sampling, token limits, timeout, reasoning, safety, accelerator, search and tool policy | AI → Profile or Connection editor |
| Memory controls | Learning and recall switches, capture sensitivity, topic threshold, extraction choices, semantic index, cloud/original-chat/review restrictions, pin priority, ranking, capture/recall budgets, capacity, retention and connected memory | Settings → Memory → Controls |
| Tool connections and plugins | Connection UID and alias, auth mode, encrypted secrets, endpoint, transport, cleartext option, tool policy, grants, per-profile tool selection and service configuration | Tools → Connections, Configuration or Permissions |
| Provider advanced panels | OpenRouter provider controls and credits, Ollama options, llama gateway settings, model lists, tool/output/time budgets and supported batch choices | AI → Connection or Profile → Advanced |
| Workspace state | Tasks, Context, Evidence, conversation Branches, Recipes, Models, Budgets, GitHub and Plugins records | Destinations in section 4; existing record identity retained |
| Benchmark data | Plans, selected profiles/delegates, results, raw metrics, cohort/scoring snapshots, dates, history and backup sections | Activity → Benchmarks |
| Privacy and storage | App protection, screenshot behaviour, cleanup, backup sections, encrypted credentials and recovery material | Settings → Privacy & data |


## Parity ledger and release proof

Before editing, generate ui_surface_inventory.json and setting_parity.csv from the pinned baseline and then the implementation baseline. Each row records stable ID, current file/function, route or trigger, feature gate, persistence key, current default, new home, new component, migration status and test. Include inline popups and Android Activity entry points, not only Route constants. Scan and then manually review dynamic controls; a text search alone is not proof of complete coverage.

Require 100 percent of baseline items to have a disposition: migrated, unchanged logic with new presentation, alias retained, or proven unreachable compatibility code. Removal requires evidence that the entry point is unused and that the underlying feature still has a supported home. Do not count a settings search result as preservation unless its actual control is reachable and functional.

# 14 Repository integration design

## Evolve the current architecture

Retain Kotlin, Compose, MVVM, Hilt, repositories and the existing navigation stack. Extract presentational composables out of large files into the current presentation package. Do not combine the UI renovation with a new inference engine, a navigation framework replacement or a database redesign. Treat declared dependency versions as the baseline; confirm resolved versions and stable API support before adding adaptive components. [R1, R2]

| Current owner | Proposed change | Must remain stable |
| --- | --- | --- |
| presentation/designsystem/AppDesign.kt | Expand into tokens and components for spacing, shapes, type, semantic states, rows, search, sheets and editor scaffolds | One source of visual truth; wrappers during migration |
| presentation/theme/Theme.kt, Color.kt, Type.kt, PaletteContrast.kt | Add a single theme adapter, full role mapping, preview state and contrast coverage | Legacy palettes, dynamic colour and bundled artwork |
| presentation/common/NavigationGraph.kt and Route.kt | Add root shell and route adapters; preserve parameter decoding and settings focus | Existing deep links, notification/share entry and back stack |
| ui/home/HomeScreen.kt | Extract list, row, favourites, folder actions and new-chat flow | HomeViewModel state and stable conversation/profile IDs |
| ui/chat/ChatScreen.kt and ChatBubble.kt | Extract composer, header, message actions and details surfaces | Scrolling, streaming, revision, queue and run state |
| ui/setting/PlatformSettingScreen.kt and dialogs | Introduce draft editor sections and shared field components | Provider-specific models and save semantics |
| ui/setting/ToolConnectionsScreen.kt and ui/mcp | Separate hub, detail, connection editor and permission review | Connection, installation and permission state ownership |
| ui/setting/FactVaultScreen.kt | Split library, review, documents and controls presentation | Memory provenance, policy and conflict handling |
| ui/setting/DebugDiagnosticsScreen.kt and ProfileBenchmarkScreen.kt | Introduce Activity routes using existing state owners | Background work, accounting, raw results and cohorts |
| ui/workspace/WorkspaceScreen.kt | Replace broad tab container with adapters to canonical destinations | Existing records, task handles and inbound run links |
| common/FadingPopups.kt and SettingsDesign.kt | Keep compatibility wrappers while migrating callers to shared shells | Dismiss callbacks and animation accessibility |
| Legacy Java package UI and Android activities | Trace all callers, migrate reachable surfaces and retain compatibility adapters until verified | Backup formats, secure handoffs and feature gates |


## Proposed source organisation

Use presentation/designsystem/tokens for geometry, motion and semantic colours; presentation/designsystem/components for stateless UI primitives; presentation/navigation for the root shell and legacy route adapters; and feature-local components beneath the existing ui directories. These are proposed paths, not files already present. Keep feature-specific logic in its existing ViewModel or state holder.

## State and event contracts

Read authoritative domain state through collectAsStateWithLifecycle where appropriate. Compose emits intents such as SelectProfile, SaveDraft and RequestStop. It does not own runtime jobs, credential persistence or benchmark scoring.

Use rememberSaveable for lightweight local selection and scroll where appropriate; use SavedStateHandle or an explicit draft repository for editors that must survive process recreation. Never place raw API keys in saved-instance state or log output.

Give every overlay an explicit owner and target ID. Use a sealed overlay state per feature instead of many unrelated booleans that can open two modals at once. Preserve typed result/cancel semantics.

Scope updates: global policy, provider connection, profile, conversation and one-time permission are different. UI labels show the effective value and its source, and never overwrite an inherited parent value when editing a child.

Keep loading, empty, ready, partial, failed, offline, interrupted and permission-required states distinct. Show last checked time where a status can go stale.

## Theme and accessibility implementation detail

Use MaterialTheme.colorScheme and semantic extension tokens, not new literal colours at each call site. Full custom palettes need an explicit migration adapter; primary-only overrides should produce coordinated containers rather than assigning the same accent to every role. Calculate contrast against the final composited surface, including alpha. Keep controls independently accessible when a row also contains a help button.

Do not assume Material 3 Expressive or Adaptive APIs are available because a design guide mentions them. Verify the project’s resolved libraries and opt-in requirements. If a desired component requires a dependency change, isolate it in the foundation PR and keep a stable equivalent until compatibility and performance are verified.

# 15 Implementation work packages

Use a dedicated renovation branch created from the implementation-time main branch. Reconcile changes since the audited commit first, especially recent runtime, benchmark, marketplace and permission work. The complete visual renovation is delivered in reviewable stages; the stages do not reduce the final scope.

| ID | Package and work | Depends on | Exit criterion |
| --- | --- | --- | --- |
| W01 | Baseline and parity; Capture current route, setting and overlay inventory; record screenshot fixtures and existing task behaviour. Identify reachable legacy surfaces. | Baseline | Every baseline item has an owner and migration destination. |
| W02 | Theme and component foundation; Extend AppDesign, theme roles, typography, semantic states, accessible rows, editor and popup shells. Add legacy palette adapter and preview. | W01 | Reference palettes pass checks; old saved themes render without loss. |
| W03 | Navigation shell; Build four roots, state restoration, Activity entry and route aliases. Integrate expanded-window layout without duplicating panes. | W02 | All deep links, shares, notifications and Back tests pass. |
| W04 | Conversation experience; Renovate home, folders, favourites, archive, profile selection, reading, composer, attachments, queue, progress and sources. | W03 | Chat feature and scroll regression suite passes. |
| W05 | AI and local models; Renovate profile/connection editors, model pickers, installed/discover/runtime, downloads and access flows. | W02 W03 | All profile fields and runtime states round-trip correctly. |
| W06 | Delegation research and budgets; Move delegation, reviewer, research settings, recipes, comparison and budgets to canonical homes. | W05 | Existing field semantics and scope gates preserved. |
| W07 | Tools and GitHub; Renovate tools, marketplace, connection setup, assignment, permissions, resources/prompts and repository workspace. | W02 W03 | Install/connect/permit states and mutation confirmations remain correct. |
| W08 | Settings memory and data; Renovate search, Appearance, conversation preferences, memory library/review/documents/controls, privacy, backup and About. | W02 W03 | All feature registry entries reachable; backup round-trip verified. |
| W09 | Activity and measurements; Renovate Runs, diagnostics, usage, benchmarks and persistent work strip. Redistribute Workspace task records. | W03 W06 | Background/resume/cancel and benchmark history preserve truth. |
| W10 | Auxiliary and system surfaces; Finish every popup register item, service detail, media/map/table viewer, setup, migration and Android Activity handoff. | W04 to W09 | No unclassified reachable surface remains. |
| W11 | Adaptive and accessibility review; Review all component states at compact/expanded width, large text, TalkBack, keyboard, reduced motion and theme variants. | W04 to W10 | No blocking clipping, inaccessible action or focus loss. |
| W12 | Performance and release review; Run release-like benchmarks, complete parity ledger, remove proven unused wrappers and prepare release notes. | W11 | All release gates met; rollback path and data compatibility verified. |


## Review and rollout

Use an internal presentation flag for side-by-side QA while the new system is incomplete. The flag must select UI only, never fork permission or data storage logic. Roll out coherent destinations behind it, then enable the complete system only after feature parity is signed off. Keep old route aliases and compatibility readers for the supported upgrade window. Do not ship a patchwork where the same action opens two different designs.

Keep each pull request concrete: before/after screenshots in light and dark, changed surface IDs, state diagrams where behaviour changed, parity ledger updates, and targeted verification evidence. Runtime upgrades and unrelated model changes should remain separate commits or PRs so UI regressions can be isolated.

## Effort and uncertainty

This is a substantial renovation, not a palette-only change. A planning range is roughly 30 to 50 engineering days plus 8 to 12 design and QA days for one experienced contributor, assuming the existing runtime and data contracts stay stable. This is a scope estimate, not a delivery promise. Re-estimate after W01 using the actual reachable surface count, test coverage and implementation-time branch diff. Visual prototype work is already represented in this document; production Compose implementation and device validation remain.

# 16 Acceptance and validation plan

## Non-negotiable release gates

| Gate | Evidence required |
| --- | --- |
| Complete coverage | Every screen specification UI01 to UI35, every popup family P01 to P34, all 29 AppFeature keys and all 11 DebugMetric keys mapped and exercised, plus dynamic and legacy controls discovered in W01. |
| Feature parity | All baseline capabilities reachable; no silent default, scope, credential, permission, model, memory or benchmark data changes. |
| Theme correctness | Light, dark, wallpaper, every preset and representative custom extremes reviewed; legacy palette upgrade fixture preserved. |
| Accessibility | TalkBack focus/labels, switch/keyboard action paths, 200 percent font scale, reduced motion, RTL and touch target review completed. |
| Interaction reliability | Send and Stop independent; queue edits correct; sources resolve; dismiss and Back safe; restore and deep links correct. |
| Runtime truth | Installed, eligible, requested, actual and verified execution distinguished; unknown remains unknown. |
| Background continuity | Chat and benchmark work survives navigation, rotation and background/resume according to existing service policy. |
| Measured performance | Release-like frame and startup measurements show no material regression beyond agreed device-specific tolerance. |
| Visual polish | No clipping, overlap, repeated headers, arbitrary accent text, inconsistent gutters or unreadable disabled explanations. |
| Data compatibility | Upgrade and backup/restore fixtures retain IDs, records, settings, grants, themes, results and historical score data. |


## Required scenario matrix

| Scenario | What must be checked |
| --- | --- |
| Small phone, 320 and 360 dp | Long names, keyboard, bottom actions and narrow popup layout |
| Typical phone, 390 to 412 dp | Every primary flow in light and dark; one-handed actions |
| Landscape and short height | Sheets and editors become scrollable without hiding confirmation |
| 600 to 839 dp | Rail behaviour, state restoration and no cramped two-pane split |
| 840 dp and wider, fold hinge | Readable list/detail, preserved detail identity and no hinge obstruction |
| Font scale 1.0, 1.3, 2.0 | All controls wrap; touch targets and primary actions remain usable |
| RTL and long translations | Start/end alignment, mirrored directional icons, no forced English title casing |
| TalkBack and keyboard | Row semantics, focus order, actionable help, modal focus return |
| 1000 chats, 1000 catalogue items | Stable lazy keys, filter responsiveness and scroll performance |
| Long stream with rich content | Scroll-up holds position; code/table/math/media remain usable |
| Partial and failed work | Retry/cancel/recovery labels match actual state and affected task |
| Process recreation and restore | Drafts and selected IDs restored appropriately; secrets not exposed |


## Performance targets to validate

Establish a release-like baseline on the user’s Snapdragon 8 Elite device and at least one lower-performance Android device. Use macrobenchmarks for start, scrolling, opening pickers and switching themes; inspect jank and recompositions during streaming. Aim for no more than a 10 percent p95 frame-duration regression on identical fixtures, and no statistically meaningful startup regression. These are proposed acceptance tolerances, not measured results.

At 60 Hz the nominal frame interval is about 16.7 ms, and at 120 Hz about 8.3 ms. Report missed-frame/jank metrics rather than claiming every operation meets those values. Avoid full-list recomposition, repeated Markdown reparsing, large uncached image decoding and blur during generation. Background UI subscriptions must not add continuous high-frequency sampling. [S11]

## Usability validation

Run two short formative rounds with approximately five participants per round, including an experienced power user and someone unfamiliar with the app. Tasks: start a Combined chat, stop a run while preparing a follow-up, switch a local model, find cloud-memory recall, revoke a tool grant, change a theme, run a benchmark and restore a backup preview. Track completion, wrong turns, hesitation and comprehension. Small samples uncover problems; they do not establish population-wide statistical proof.

Proposed task targets: create a chat from a configured profile in at most three intentional selections; reach the active model picker in one action from a conversation; find a named setting through search and one result activation; identify a failed run and its recovery in one detail view. Revise the design if these targets compromise accuracy, consent or preservation of advanced functionality.

## Final review checklist

Confirm the implementation commit and the exact test/build variant in the handoff.

Compare the full parity ledger against current main again; incorporate newly merged surfaces.

Review all empty, loading, error, disabled, selected and completed states, not just ideal screenshots.

Confirm themes and colour changes leave all stored feature and permission values untouched.

Verify the actual APK on the target phone before describing the redesign as shipped or fully validated.

# 17 Research references and source map

All web references were researched on 11 October 2026. The summaries in section 2 are concise interpretations. Product-specific layouts and acceptance criteria are original recommendations derived from the repository audit and these principles.

S1  Material Design 3 colour roles

S2  Google Design expressive Material research

S3  Material Design 3 app bars

S4  Nielsen Norman Group progressive disclosure

S5  Nielsen Norman Group usability heuristics

S6  Android adaptive application guidance

S7  Compose accessibility defaults and touch targets

S8  Compose semantics and accessibility state

S9  W3C contrast minimum

S10  W3C non-text contrast

S11  Compose performance best practices

S12  Compose predictive back setup

S13  Material Components bottom sheets

## Pinned repository references

R1  Project agent guidance

R2  Dependency versions

R3  Navigation routes

R4  Navigation graph

R5  Theme role mapping

R6  Conversation export choices

R7  Feature and debug registry

R8  Settings search registry

R9  Conversation composer

R10  Tool approval scopes

R11  Workspace tab inventory

R12  Benchmark presentation

R13  Local model presentation

R14  Memory presentation

R15  Brand icon contract

R16  App build configuration

## Audit coverage and limits

The source inventory includes common components, theme and design system, all principal presentation screens and their inline popup definitions, setup/migration/main activities, unified model/queue screens, service-specific result surfaces and compatibility UI inspected for migration. The full repository tree was available without truncation. Function and route scans were combined with targeted source reads; this is not a claim that every backend file was reviewed.

The 35 screen specifications and 34 popup families cover the identified UI by user task, so several Kotlin files intentionally map to one family. W01 must confirm runtime reachability, build flags and any surfaces added after the pinned commit. The colour references and design boards were visually reviewed as document artifacts. Production accessibility, runtime functionality and device performance remain implementation acceptance work.

