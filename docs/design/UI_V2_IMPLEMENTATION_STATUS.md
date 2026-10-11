# UI/UX V2 draft implementation status

Source baseline: `a5a782a24fdebe26475598b10ebfe46ce92d1570` on main. Branch: `feat/ui-ux-v2-2026-10-10`. Draft PR: #650.

This is a review build spanning the safety fixes and several core experience work packages. It is **not the completed 16-PR release program** in the source design. No requirement is marked verified merely because code exists. Nothing has been merged or released.

`implemented-unverified` means code was added or an existing implementation was retained, with the stated remaining scope and acceptance checks. It does not mean every sub-requirement is implemented. `not-started` identifies known substantial work absent from this draft; it is not an approved deferral.

The most material unfinished implementation is the complete settings inventory for nested provider/delegation/runtime forms, app-wide navigation/scaffold conversion, indexed home filters, guided MCP setup, guided backup conflict review, full Active Work integration and the adaptive pane expansion. Review these before treating this branch as a complete redesign.

## Evidence and review build

- `UI Review APK` builds a debuggable APK on draft PRs, compiles instrumentation fixtures and checks packaged runtime libraries and telemetry policy. This is a review APK, not a signed production release.
- PR Validation runs the full existing unit suite, resource/gateway contracts and Android lint. Kotlin Lint and CodeQL remain required checks.
- New regressions cover search keys/synonyms, custom-palette contrast, last-profile UI pause policy, workload preset invariants, one-message queue selection, grant revocation, benchmark service rejection/cancellation and independent composer Stop/Send.
- Physical device screenshots, TalkBack, rotation, IME/insets, foldables, map gesture/memory behavior and before/after macrobenchmark results are **unverified** in this environment. `.github/workflows/device-validation.yml` remains the dedicated hardware workflow and now collects visual review fixtures.
- Screenshot fixture output is review evidence, not an automatically approved golden. Do not accept screenshots generated from real accounts or secrets.
- Existing benchmark scoring, memory/vector storage, provider ownership, backups and durable queues are reused. No new task/grant/index database was introduced.

## Requirement traceability

| ID | Status | Implementation / remaining acceptance |
|---|---|---|
| 001 | implemented-unverified | ChatProfileManagementDialog; menu targets conversation membership and pause state. UI preserves one unpaused profile; legacy queue can still pause all. |
| 002 | implemented-unverified | SettingsSearchRegistry plus owning memory/privacy/profile descriptors; route/tab and control highlighting. Further nested provider and delegation fields still need coverage. |
| 003 | implemented-unverified | Visible-card lifecycle cooldown clock; one scheduled daily-limit expiry; root timer removed. Physical trace pending. |
| 004 | not-started | A new app-wide adaptive shell is not implemented; existing adaptive conversation layout is retained. |
| 005 | implemented-unverified | Existing implementation retained in ui/home/HomeScreen.kt:1780–1855; `ChatsTitle` usage. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 006 | not-started | Shared AppScreenScaffold migration across all roots remains pending. |
| 007 | implemented-unverified | Appearance and focused settings routes; benchmark Open deep link; chat profile menu. |
| 008 | implemented-unverified | ProfileDiscovery search primitive shared by library and home picker; other picker workflow consolidation pending. |
| 009 | implemented-unverified | Existing implementation retained in `common/FadingPopups.kt`; `ui/mcp/McpMarketplaceDialog.kt`; `ui/setting/ThemeSettingsScreen.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 010 | implemented-unverified | AppSpacing tokens adopted in settings/design primitives; remaining surfaces retain existing padding. |
| 011 | implemented-unverified | AppShapes card/panel/input tokens introduced; full-app rollout pending. |
| 012 | implemented-unverified | SettingsDesign uses surface layering instead of content gradient. |
| 013 | implemented-unverified | Existing implementation retained in `common/ThemeIcon.kt`; new `designsystem/components/AppIcon.kt`; `res/drawable`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 014 | implemented-unverified | Existing implementation retained in `ui/home/HomeScreen.kt` (`ChatListItem`/`FancySwipeChatCard`). V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 015 | implemented-unverified | Compact chat-list preference in existing feature store; archived density and device performance still pending. |
| 016 | implemented-unverified | Existing implementation retained in `ui/home/HomeScreen.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 017 | implemented-unverified | Existing implementation retained in `ui/home/ConversationFolders.kt`; `ui/home/HomeScreen.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 018 | implemented-unverified | Home row overflow: pin/unpin, move folder, reorder pinned conversations. |
| 019 | implemented-unverified | Home overflow plus custom accessibility actions; archived restore/delete menu. |
| 020 | implemented-unverified | Archived deletion now uses confirmation; home archive retains Undo. |
| 021 | implemented-unverified | Existing implementation retained in `ui/home/HomeScreen.kt` (`NewChatButton`). V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 022 | implemented-unverified | Last enabled profile/mode suggested via existing feature store; unavailable selections excluded. |
| 023 | not-started | Indexed folder/profile/time/favorite/archive home search expansion is not implemented. |
| 024 | implemented-unverified | Existing implementation retained in `ui/home/HomeScreen.kt`; generation status store. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 025 | implemented-unverified | Composer uses onSurfaceVariant placeholder instead of primary at 20 percent alpha. |
| 026 | implemented-unverified | Independent Stop and Send actions and stable tags; interaction regression added. |
| 027 | implemented-unverified | Visible next-message chip plus profile-management menu; default selection remains available through existing controls. |
| 028 | implemented-unverified | Existing implementation retained in `ui/home/HomeScreen.kt` (`SelectPlatformDialog`); `ui/chat/ChatScreen.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 029 | implemented-unverified | Existing implementation retained in `ui/chat/PromptQueuePanel.kt`; `MessageQueueIndicatorBanner.kt`; `QueuedPromptBubble.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 030 | implemented-unverified | Image/PDF/other document-picker entries; no unsupported source implied. |
| 031 | implemented-unverified | Draft size and preparation/failure labels; retry reuses preprocessing; remove preserved. |
| 032 | implemented-unverified | Existing implementation retained in `ui/chat/ChatBubble.kt`; `SuggestionHighlightManager.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 033 | implemented-unverified | Existing implementation retained in `ui/chat/ChatBubble.kt`; `ui/chat/ChatScreen.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 034 | implemented-unverified | Existing implementation retained in `ui/chat/ChatBubble.kt`; `DebugActivityContent.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 035 | implemented-unverified | Existing implementation retained in `ui/chat/ThinkingBlock.kt`; `ToolTraceBlock.kt`; `ui/thinking/ThinkingAccordion.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 036 | implemented-unverified | Existing implementation retained in `ui/chat/AnswerSourcePresentation.kt`; `ChatSources.kt`; `ChatSourcePicker.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 037 | implemented-unverified | Existing implementation retained in `ui/chat/ChatSourcePicker.kt`; `AnswerSourcePresentation.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 038 | implemented-unverified | Existing implementation retained in `ui/chat/AgentRunStatusBlock.kt`; `ResearchProgressCard.kt`; `ToolTraceBlock.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 039 | implemented-unverified | Existing implementation retained in `ui/chat/CombinedProfileBubbles.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 040 | implemented-unverified | Existing implementation retained in `ui/chat/CombinedProfileBubbles.kt`; `CombinedResponseState.kt`; `CombinedResponseMerger.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 041 | implemented-unverified | Existing entry/boundary/auto-scroll logic preserved; outline operates on loaded stable turn keys. Existing unit regressions retained. |
| 042 | implemented-unverified | Map fullscreen fallback added; existing code/table/image/fullscreen behavior retained; device QA required. |
| 043 | implemented-unverified | Profile library search and favorites added; provider grouping preserved. Remote/local/free filter polish pending. |
| 044 | implemented-unverified | Existing implementation retained in `ui/setting/AiPlatformsScreen.kt`; `ProviderConnectionSettingsScreen.kt`; `PlatformSettingScreen.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 045 | implemented-unverified | Name/model/provider/labels token search and favorites; capability/performance sorting remains future work. |
| 046 | not-started | Shared metadata-aware capability indicator rollout remains pending. |
| 047 | implemented-unverified | Existing implementation retained in `ui/setting/AiPlatformsScreen.kt`; `LocalModelsScreen.kt`; health ViewModels. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 048 | not-started | Evidence-backed task recommendation shortcuts remain pending. |
| 049 | implemented-unverified | Existing Profile/Tools/Advanced tabs retained; control search navigates to owning tab. |
| 050 | not-started | Dedicated side-by-side model comparison remains pending. |
| 051 | implemented-unverified | Existing implementation retained in `ui/setting/AiPlatformsScreen.kt`; profile ViewModels. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 052 | implemented-unverified | Fixed 64dp option rows changed to heightIn(min=64dp). |
| 053 | implemented-unverified | Existing implementation retained in ui/setting/LocalModelsScreen.kt; `LocalModelCatalogUi.kt`; runtime backend. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 054 | implemented-unverified | Two-line marketplace summaries; technical description in details. |
| 055 | implemented-unverified | Hero collapses with list position; filters remain available. |
| 056 | implemented-unverified | Card keeps one primary CTA; secondary remove/export/configure in details. |
| 057 | implemented-unverified | Labels/status state from installation and connection health retained. |
| 058 | implemented-unverified | Filter sheet with active count and reset; stable existing filter state retained. |
| 059 | implemented-unverified | Existing implementation retained in `data/catalog/GitHubMarketplaceCatalog.kt`; `ui/mcp/McpMarketplaceDialog.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 060 | implemented-unverified | Existing implementation retained in Marketplace/Connections screens; string resources. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 061 | not-started | Guided resumable full-screen MCP setup is not implemented; existing setup retained. |
| 062 | implemented-unverified | Existing explicit grant dialogs retained. Added read-only audit and explicit per-grant revoke without silently granting permission. |
| 063 | implemented-unverified | Existing implementation retained in `ui/mcp/McpMarketplaceDialog.kt`; catalog metadata. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 064 | implemented-unverified | Existing implementation retained in `ui/mcp/MarketplaceViewModel.kt`; `NativePluginSettings.kt`; package store. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 065 | implemented-unverified | Existing bounded service groups retained; no unmeasured large-list rewrite. |
| 066 | implemented-unverified | Memory and Research category separates memory from tool connections. |
| 067 | implemented-unverified | Owners supply memory/privacy and gated dynamic profile controls; AppFeature/DebugMetric exact keyed targets. Nested controls still need a complete inventory. |
| 068 | implemented-unverified | Breadcrumbs and stable target keys; hide categories during search; route selects tab and highlights/scrolls supported controls. |
| 069 | implemented-unverified | Settings text uses content roles, minimum row height and navigation chevrons. |
| 070 | implemented-unverified | Appearance is a normal navigation Scaffold; legacy dialog invocation remains compatible. |
| 071 | implemented-unverified | Graphite retained; saved palette and default Cyan not rewritten. |
| 072 | implemented-unverified | Contrast evaluator uses actual custom color-role mapping and alpha compositing. Suggested repairs preview without changing saved values. |
| 073 | implemented-unverified | Shared tokens/empty states and calmer settings panels introduced; app-wide conversion still pending. |
| 074 | implemented-unverified | Existing implementation retained in `theme/Motion.kt`; `common/NavigationGraph.kt`; main animations. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 075 | implemented-unverified | Memory Overview, Saved Facts, Documents, Review, Controls and Topics; tabs/filters saveable. |
| 076 | implemented-unverified | Memory provenance dialog shows stored source/scope/confidence/history and Not recorded for absent dates. |
| 077 | implemented-unverified | Existing conflict choices and correction history retained; Review tab surfaces pending replacements. |
| 078 | implemented-unverified | Existing document indexing/error/retry/remove workflow retained; separated Documents target. |
| 079 | not-started | New step-by-step backup wizard/conflict review is not implemented; existing selection/import/export retained. |
| 080 | implemented-unverified | Current main already fixes stats/settings benchmark restore and native plugin archive coverage. Existing serializer/crypto/selection tests retained; fresh-device restore matrix pending. |
| 081 | implemented-unverified | Focused/Balanced/Thorough workload presets call existing strategy setter and preserve unrelated permissions; regression added. |
| 082 | implemented-unverified | Existing implementation retained in `ui/setting/DebugDiagnosticsScreen.kt`; `ProfileBenchmarkScreen.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 083 | implemented-unverified | Existing implementation retained in `ui/setting/ProfileBenchmarkScreen.kt`; benchmark registry/score engine. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 084 | implemented-unverified | Existing cohort isolation, dynamic anchors and explanation retained; DynamicScoreEngineTest covers leader rescaling and invalid/provisional runs. |
| 085 | implemented-unverified | Singleton coordinator progress retained; notification and app banner share it; session/interruption state added. |
| 086 | implemented-unverified | Health tab shows actual local backend/accelerator/fallback and explicit plugin checks; existing redaction/recovery retained. |
| 087 | implemented-unverified | Existing implementation retained in `ui/setting/BenchmarkCharts.kt`; `ProfileBenchmarkScreen.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 088 | implemented-unverified | 48dp help/back targets and wrapping profile rows; all custom surfaces still require automated/device hit-target audit. |
| 089 | implemented-unverified | Home row custom actions, labeled help/stop/send; full TalkBack/modal/map ordering pending. |
| 090 | implemented-unverified | Fixed profile row heights removed; large-font RTL composer fixture added; app-wide device matrix pending. |
| 091 | implemented-unverified | Existing implementation retained in theme/Motion.kt; `ChatScreen.kt`; `HomeScreen.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 092 | implemented-unverified | Explicit organization menu and custom accessibility actions mirror home swipe/drag operations. |
| 093 | implemented-unverified | Marketplace clock narrowed; physical recomposition/performance evidence not available. |
| 094 | implemented-unverified | Marketplace visual expiry updates run only while visible and resumed; all other owners still require lifecycle audit. |
| 095 | implemented-unverified | Existing macrobenchmark extended with production settings-discovery fixture; device workflow collects review images. Map/marketplace/under-inference paired traces pending. |
| 096 | implemented-unverified | Existing implementation retained in `ui/setup/SetupPlatformTypeScreen.kt`; `SetupPlatformWizardScreen.kt`; `SetupPlatformListScreen.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 097 | implemented-unverified | Existing implementation retained in `ui/setup/*`; `ui/setting/PlatformSettingScreen.kt`; tools dialogs. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 098 | implemented-unverified | AppEmptyState used for discovery; existing empty/error states retained elsewhere; full rollout pending. |
| 099 | implemented-unverified | Existing implementation retained in `ui/chat/AmazonProductResults.kt`; `AirbnbListingResults.kt`; product gallery. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 100 | implemented-unverified | Same MapView reused for inline/fullscreen; saveable camera and initial-fit guard. Existing gesture/lifecycle handling retained; real map gestures and memory QA pending. |
| 101 | implemented-unverified | Existing implementation retained in `ui/workspace/GitHubReviewPanel.kt`; `ui/setting/GitHubWorkspaceScreen.kt`. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 102 | not-started | Folders/search/unread expansion of adaptive conversation pane remains pending. |
| 103 | implemented-unverified | Existing implementation retained in `common/SettingItem.kt`; existing `SettingsHelpIcon`; screens. V2 acceptance still needs source/fixture and physical-device review; code presence is not verification. |
| 104 | implemented-unverified | Vector geometry unchanged; resource preflight confirms reference SVG. Human image comparison unavailable. |
| 105 | implemented-unverified | Composer light/dark/custom/200-percent-font RTL screenshots captured by device tests and uploaded as human review evidence; no goldens approved or device tests executed here. |
| 106 | implemented-unverified | Backup UI now states that passwords are excluded, separate recovery keys must be retained, and unencrypted exports expose credentials. |
| 107 | implemented-unverified | Main already has app-scoped coordinator. Added session/status, service-start rejection handling and completion cleanup; lifecycle/cancellation tests plus real-device QA pending. |
| 108 | implemented-unverified | Benchmark app-wide banner Open/Stop surface implemented. Unified download/research Active Work surface remains not started. |
| 109 | implemented-unverified | Optional next-message override snapshots queue choice, clears on acceptance, never alters membership; Combined and unavailable profiles rejected. |
| 110 | implemented-unverified | Loaded-turn outline/jump using existing stable message-pair keys; load older and return-to-latest remain available; no second message index. |
| 111 | implemented-unverified | Diagnostics Health uses actual runtime state, actual plugin probe status/date and historical provider request evidence explicitly labeled as historical. |
| 112 | implemented-unverified | Benchmark foreground notification has real progress and Open/Stop actions; service failures cancel honestly. Existing download/research services retained; common presentation still pending. |
| 113 | implemented-unverified | ToolTrustStore and free-model acknowledgements enumerated with actual scopes, legacy unknown labels and per-grant revoke; connection policy and saved restrictions shown separately. |
| 114 | implemented-unverified | New strings and 66 existing literal labels externalized; locale-aware memory dates and RTL composer fixture. Full screen-string extraction/translations and RTL device pass pending. |
| 115 | implemented-unverified | Appearance standard Scaffold, benchmark banner outside NavHost overlay, expanded map system-bar padding. Full keyboard/inset sweep pending. |

## Device review checklist

1. Install the latest successful draft review APK artifact linked from PR #650.
2. Reproduce Stop while typing, pause/remove/resume profiles, queued one-message override, and long-history jump/return without losing the draft.
3. Review settings route/tab/control focus, Appearance back behavior, long profile labels, custom palette warnings, and Home menu equivalents for every swipe/drag action.
4. Run benchmarks while navigating, background/resume and cancel from notifications; reject notification permission; kill the process and confirm interrupted status and previously completed samples remain truthful.
5. Verify encrypted/passwordless/unencrypted backups on a fresh install with SETTINGS/STATS/TOOLS category combinations, missing key and legacy import.
6. Pan/zoom/fullscreen/rotate the same map while chat scroll and on-device inference are active; inspect disposal and memory.
7. Run dedicated physical-device tests and macrobenchmarks; inspect `ui-review.tar`, Android test reports and traces. Compare before/after on the same hardware/configuration. Approve goldens explicitly.
8. Complete the outstanding implementation and full localization/accessibility matrix before marking ready for merge.
