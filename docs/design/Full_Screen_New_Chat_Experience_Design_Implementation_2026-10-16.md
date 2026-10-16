# GPT Mobile AI — Full-Screen New Chat Experience 2.0
## Source-grounded design and implementation plan

**Date:** October 16, 2026  
**Status:** DESIGN ONLY — no application functionality implemented, no build or device test claimed.  
**Repository:** tailscale-signin/GPT_Mobile_AI-improved  
**Baseline:** main at fba9959e3b04675f579ffab89760944737122c91  
**Design branch:** design/fullscreen-new-chat-experience-2026-10-16  
**Product intention:** Tap New Chat on the main screen; choose Research, Single Chat, Multi-chat, or Combined Mode on a fully themed screen; proceed to another full screen for selecting one or more local/remote AI profiles using clear labels and sorting. Research preselects Deep Research and defaults to Combined for two or more profiles.

---

## 1. Product contract and decisions

1. Always open the full-screen chooser when New Chat is tapped. The current one-enabled-profile automatic chat bypass must be removed. Never initiate generation just by entering this flow.
2. Screen 1 shows four distinctive options: Research Chat, Single Chat, Multi-chat, Combined Mode. Screen 2 is a full-screen profile picker specific to the chosen option. Start Chat opens the configured conversation, without a mandatory third confirmation screen.
3. Research accepts one or more local and/or remote profiles. Deep Research is switched on in the **new conversation's intent**, subject to existing independent permission gates. With two or more profiles, the execution mode defaults to Combined; with one, use a single-profile Research conversation without pretending Combined synthesis ran.
4. Single requires exactly one profile. Multi-chat requires at least two and is a **workspace of independent conversations**, not a new label for multiple response revisions of one shared history. Combined requires at least two and produces a synthesized comprehensive answer without dropping unique findings.
5. The separate AI profile picker has sticky search, type filters, user-created label chips at the top, an independent Sort control, profile details, a selected count, and a pinned Start Chat button.
6. Reuse current theme engine, motion guidelines, the existing icon wrapper, profile label colors, favorite ordering, scores/pricing insights, research controls, and complete Combined attribution and synthesis logic.
7. Preserve old chats, existing profile UIDs, all active/historical slot behavior, archived chats, and current backup/restore semantics. Do not silently convert existing STANDARD conversations to new Multi-chat workspaces.
8. All network/model checks during browsing and selection are lightweight, permission-aware, and non-blocking. No inference or paid research is initiated before Send.

### Chat-type matrix

| Type | Profiles | Conversation model | Research default | Destination |
|---|---:|---|---|---|
| Research | 1+ | 1 profile: STANDARD; 2+: COMBINED by default | ON; depth from a valid saved research preference, otherwise 50%; web-only until paper consent allows more | Research chat |
| Single | Exactly 1 | STANDARD | Unchanged/off unless separately configured | Standard chat |
| Multi-chat | 2+ | Independent per-profile child STANDARD chats, linked to a workspace | Unchanged/off | Multi-chat workspace |
| Combined | 2+ | Existing COMBINED pipeline | Unchanged/off | Combined chat |

Research and Combined are conceptually different: Research is the **purpose and tool policy**, while Combined is the **response execution/display mode**. Do not overload ConversationMode with RESEARCH merely to drive a badge. An explicit Research purpose must survive process death/reopen/restart.

---

## 2. Source review (baseline verified from main)

| Actual file | Verified current behavior | Required change |
|---|---|---|
| presentation/ui/home/HomeScreen.kt | New Chat directly navigates if one profile is enabled; otherwise opens SelectPlatformDialog | Always navigate to chooser, never skip |
| presentation/ui/home/SelectPlatformDialog.kt | Nearly full-width bounded-height Dialog, embedded STANDARD/COMBINED toggle, search, label chips, favorites and selected count | Extract reusable profile-list content, then host in full-screen route; replace mode toggle with selected chat intent |
| presentation/ui/home/SelectPlatformProfileCard.kt | Already supports selecting/favoriting, model name, insights | Reuse in full-screen picker, add readiness/local/remote cues without losing pricing or benchmarks |
| presentation/ui/home/ChatProfileOrder.kt | Filters enabled and puts favorites first | Preserve stable user order and selected set; sort separately |
| presentation/common/ProfileLabelComponents.kt | Reusable colored labels and chips exist | Reuse rather than inventing a label storage model |
| presentation/common/ProfileDiscovery.kt | Profile search helper exists | Extend query/filter semantics without breaking matching |
| presentation/common/Route.kt and NavigationGraph.kt | Chat creation navigates to chat_room/0 with UIDs and mode in URL | Add explicit new-chat routes and a draft-scoped ViewModel, then create durable session before chat navigation |
| data/database/entity/ChatRoomV2.kt | Stores STANDARD/COMBINED, participant slot history and active slots; normalize recognizes only COMBINED | Keep existing values stable; add purpose/workspace metadata compatibly |
| presentation/ui/chat/ChatViewModel.kt | Hydrates per-conversation Research from AppFeatureSettings.conversationResearch[chatId]; new rooms are created around first send, and Research is persisted afterward | Eliminate first-send setup race by persisting the chat + initial research config before first request |
| data/model/MultiSearchResearchSelection.kt | Research starts off with depth 0 by default; Web/Papers scope, toggle and independent readiness gates are implemented | Construct an explicit Research draft selection; do not silently change global plugin/profile consents |
| data/model/ChatMcpToolConfig.kt | Per-chat tool/delegation/research configuration exists | Seed it from stored chat session before Send |
| data/model/AppFeatureSettings.kt | Remembers last chat profile UIDs/mode; stores conversation Research settings and opt-in plugins | Add backward-compatible new chat preferences, preserving every existing setting |
| data/agent/tool/DeepResearchTool.kt | Deep Research includes independent tool grants, source restrictions, quotas, and gateway/native routes | Use existing engine; only fix wiring into newly created chat |
| presentation/theme/Theme.kt, common/ThemeIcon.kt, designsystem/AppDesign.kt | Material 3 palettes, custom/dynamic themes, themed icons, spacing and shapes exist | Reuse as the sole visual tokens |
| presentation/ui/chat/ChatScreen.kt | Combined response presentation, profile coloring, research chip and composer already exist | Keep behaviors; show Research state persistently and appropriately |
| docs/design/New_Chat_Benchmark_Pricing_Design_Implementation_2026-10-12.md | Previous proposal targeted an enriched modal profile picker | New full-screen screen supersedes only the surface/layout choice; preserve its benchmark and pricing data contracts |

**Current technical caveat:** the old picker permits multiple profiles under STANDARD, which behaves differently from the requested independent Multi-chat workspace. Do not relabel that behavior as Multi-chat without implementing independent message histories and composers.

**Current Research caveat:** MultiSearchResearchSelection defaults to researchToggle=false and depth=0. Research intent must be explicitly constructed and persisted for a Research chat, and gates such as global Deep Research enable, per-profile grant, network permission and academic content consent must remain enforceable.

---

## 3. Navigation and UI hierarchy

### Flow

- Chats home -> New Chat -> full-screen Chat Type -> full-screen Select Profiles -> Chat or Multi-chat Workspace.
- Back from Select Profiles returns to Chat Type with draft selections retained.
- Back from Chat Type returns to Chats home, discarding only this unsaved draft.
- Switching type keeps compatible selected profiles when possible; incompatible selections are pruned with a visible explanation rather than silently discarded. Existing chats and settings remain untouched.
- Existing chat navigation, notification deep links, favorites, archives and assistant-source jumps remain unchanged.
- Navigation bar for root destinations is hidden during New Chat; system navigation bars/IME insets remain respected.
- No extra review screen: the selection footer is the review area. Optional expandable Research details do not add a mandatory stage.

### Screen 1 — Chat Type

- Full-bleed themed Scaffold with edge-to-edge safe-area handling.
- Header: back button, title "New Chat", small step label "1 of 2" and optional short subtitle.
- Four large tappable cards in order: Research, Single Chat, Multi-chat, Combined. Each uses the shared ThemeIcon treatment, 20–24 dp corners, semantic accent from MaterialTheme.colorScheme, 48 dp+ touch controls, and a short description.
- Suggested labels:
  - Research: "Deep Research with one or more AI models" (default Research on, Combined with multiple).
  - Single: "A focused conversation with one AI".
  - Multi-chat: "Independent conversations together; send to one or all".
  - Combined: "Multiple AIs, one comprehensive answer".
- Selected card gets high-contrast outline + check, not only a hue change.
- One direct tap can advance; keyboard/D-pad/TalkBack expose the same state.
- Card icon treatment follows app theme and does not require new bundled large illustrations.

### Screen 2 — Select Profiles

- Header: back, title, context subtitle such as "Research · Step 2 of 2", and information/settings icon.
- Context banner: e.g. "Deep Research ON · Depth 50% · Combined when 2+ selected", or "Multi-chat · independent histories".
- Sticky area at the top: search field, fast filter row (All / Favorites / Local / Remote / Research-ready), horizontal colored user-created profile label chips, Sort control.
- Distinguish **filtering** from **sorting**. Toggling a label never reorders profiles except via stable filter; a sort command changes only displayed order.
- Sort choices: Favorites then Saved Order (default), Name A–Z/Z–A, Provider, Model, Recent, Benchmark and Listed Price when exact trustworthy data is available. Unknown score/price sort last and remain marked unknown; never infer Free.
- Profile cards: checkbox, profile name, actual selected model, provider, local/remote icon, favorite, matching benchmark score and price/freshness metadata when enabled, labels, optional readiness status (Ready/Needs setup/Unavailable), and an expandable detail/info entry. Selection by stable UID, not row index.
- Keep disabled/offline entries discoverable as non-selectable with reason, support going to AI/Local Models setup and returning with draft preserved.
- Pinned bottom tray: selected count, compact selected chips (removable), mode badge, per-mode validation hint, prominent theme-based "Start Chat" action. Show hidden-by-filter selection count clearly.
- Selection should never be lost because the user searches, sorts, scrolls, changes font size or rotates.
- Single: enforce exactly one selected. Selecting a different profile replaces the previous selection, with clear feedback.
- Multi-chat/Combined: require >=2 eligible profiles; no arbitrary model cap unless resource limits demand a documented bound.
- Research: require >=1; when count rises to 2, show "Combined Research" by default and allow the user to choose a pinned primary for the existing Combined synthesis engine; at count 1, show "Single-profile Research".
- Include an optional accessible "Research settings" disclosure: depth, Web/Papers scope, request budget/policy summary, consent and tool-readiness reasons. Do not bury required permission steps.
- Keep controls responsive with LazyColumn, stable keys, remembered UI state and lightweight enrichment.

### Multi-chat workspace

- One logical workspace with named independent child ChatRoomV2 rows, one per selected model/profile.
- Each child owns its own message and draft history, tool policy, send/stop/retry and generation status.
- On compact screens use a scrollable tab strip for each model; swipe changes conversation. One composer controls the active tab; an explicit "Send to all" action optionally fans the identical prompt to all children.
- On expanded screens use adaptive two/three-pane layout and adjustable focus; no mandatory horizontal scrolling on narrow phones.
- Preserve per-child position and drafts. One failing/paused model must not block other lanes. Local concurrency constraints should queue visibly rather than hanging requests.
- Home list represents the workspace as one entry with an accurate model count. Entering it restores the same active lane.
- Do NOT reuse ChatRoomV2.parentChatId for workspace membership: this field already represents chat branching. Use a separate workspace-membership entity and foreign key.

### Combined chat

- Reuse existing Combined evidence inventory, complete coverage gate, frozen sources and pinned primary logic; no lossy "best answer" selection or silent substitution.
- Keep all unique claims in the resulting answer. Show legible theme-contrast profile attribution colors in answer text without "From [profile]" labels or AI-profile source footers in the normal user view.
- Detailed text-match excerpts and profile source diagnostics stay inside Debug Mode and follow the dedicated debug visibility toggle.
- Failure states show which input model failed and whether synthesis can be recovered; no silently completed answer when contributors are still pending.

---

## 4. Research defaults, permissions and persistence

1. Entering Research constructs NewChatDraft with purpose=RESEARCH and selection researchToggle=true; depth defaults to the user's last valid Research preference or 50, not 0. A saved setting that explicitly opted out must not be presented as granted.
2. Default source scope is WEB_ONLY until the user has specifically consented to Papers and Studies; honor previously saved and still-valid explicit consent. Web/Papers selection always describes whether it may invoke external academic sources.
3. With >=2 selected, start as COMBINED and let the user set/pin a primary. For one model use STANDARD while retaining the Research purpose, tool-enabled draft and research-oriented UI.
4. Research being **selected by default** is not equivalent to bypassing the global Deep Research opt-in, profile tool access, disabled network tools, source restrictions or consent. If a permission is missing, show one-step resolution with a clear confirmation instead of silently mutating unrelated profile/global settings.
5. Use researchStartBlockReason/researchReadinessReason and the existing Deep Research/Research delegation engine. Distinguish "configured" from "actually ready". If a selected profile cannot access required tools, either offer a permitted alternative or show a reason and block execution of that research path, not the entire app.
6. Create and persist chat session + chosen profiles + mode/purpose + research configuration **before enabling Send**. The current chat_room/0 lazy room creation makes first-turn Research config vulnerable to late hydration. Do not rely only on calling setConversationResearch after the first turn has begun.
7. Research settings changed from within chat must update that chat, not the global Research defaults, unless user explicitly chooses "Save as my default".
8. One Research chat's permission/depth/scope cannot leak into later Single/Multi/Combined chats. Preserve saved research sessions, evidence provenance, quotas and cancellation semantics.

### Proposed data contract (illustrative Kotlin model, not production code)

- NewChatType: RESEARCH, SINGLE, MULTI_CHAT, COMBINED.
- NewChatDraft: type, ordered selectedProfileUids, filters/sort (ephemeral), researchSelection, primaryProfileUid, selected defaults version.
- ChatPurpose: GENERAL, RESEARCH; persisted separately from existing ConversationMode.
- ChatWorkspace: stable workspace ID, presentation=INDEPENDENT_MULTI, title, created/updated, optional lastFocusedLane.
- ChatWorkspaceMember: workspaceId, chatId, profileUid, position, active.
- Per-chat ResearchSettings storage: versioned chat-owned configuration available transactionally before first Send; support compatibility fallback to AppFeatureSettings.conversationResearch[chatId] during migration.
- ConversationMode.STANDARD and .COMBINED remain valid historical values. MULTI_CHAT is workspace metadata, not a fake COMBINED or a mass rename of STANDARD.

Avoid storing a giant serialized draft in route query strings. Share a Hilt graph-scoped NewChatViewModel via SavedStateHandle, and pass a stable saved chat/workspace ID when navigating after Start. Persist the finalized creation configuration using a repository operation with Room transactions. If a setting is held in DataStore, synchronize it before exposing the composer and repair/rollback partial creation on failure.

### Compatibility and migration

- Add database entities/columns only after concrete schema design, create an explicit migration from schema v40, export the schema and run migration verification.
- Preserve all existing ChatRoomV2 fields, mode constants and index ordering contracts for old chats.
- Handle backup/export/import and deleting/archiving a workspace with children intentionally. Deleting a parent must not silently delete user data without a confirmation and a tested cascade policy.
- If profile is deleted or disabled, retain existing chat history and show an actionable missing-profile state. Do not lose historical slots or replace pinned primary.
- Use old mode preferences as a suggested starting point without skipping the chat-type chooser.
- Rollout behind a reversible feature flag until navigation, persistence and first-message research tests pass.

---

## 5. Implementation work breakdown

### Phase A — Architecture and contract (P0)

- New NewChatViewModel using immutable StateFlow state, SavedStateHandle restoration, request validation and debounced/cancel-safe start action.
- NewChatType, ChatPurpose, creation request / result sealed state, eligibility resolver and profile sorting/filtering helpers with deterministic test fixtures.
- Domain-level CreateChatSessionUseCase. Move creation ownership out of presentation clicks; establish chat ID and tool config before dispatch.
- Unchanged existing chats opening through current NavigationGraph routes.
- End-to-end gate on selected profile enable status, readiness and tool policies.

### Phase B — The two full-screen Compose screens (P1)

- NewChatTypeScreen.kt with four cards and explanatory iconography.
- NewChatProfileScreen.kt with sticky controls, profile row reuse, selected tray and Start action.
- NewChatViewModel.kt, NewChatDraft.kt and NewChatSort.kt.
- Shared ProfileSelectionContent extracted from SelectPlatformDialog, so pricing/benchmarks/favorites/labels are not duplicated.
- Update HomeScreen.kt / NavigationGraph.kt / Route.kt; disable the one-profile bypass, retaining existing notification deep links and root navigation.
- Remove the modal route only after no other caller depends on it; old SelectPlatformDialog may remain for specialized legacy flows.
- Theme alignment from AppDesign.kt, ThemeIcon.kt and MaterialTheme.colorScheme; no hard-coded accent colors.

### Phase C — Research creation wiring (P0)

- Add Research intent persistence and creation-time Deep Research tool config seeding.
- Reuse MultiSearchResearchSelection, ChatMcpToolConfig, AppFeatureSettings.conversationResearch and current DeepResearchTool permission checks.
- Implement consent/setup redirects and return-to-selection.
- Ensure Research entry reflects purpose and selected depth in chat screen. Every first send must see committed config.
- Tests: one-profile Research; mixed remote/local profiles; multi-profile Combined Research; globally disabled tool; denied academic consent; process restart before send; existing non-Research unaffected.

### Phase D — Multi-chat workspace (P1, higher effort)

- New workspace/member Room entities and DAO; migration; repository and ViewModel for independent child sessions and prompts.
- New MultiChatWorkspaceScreen.kt with phone tabs, wide-layout panes, per-lane composer and optional send-to-all.
- Failure isolation, cancellation, archived/deleted child handling, notifications, resumes and backups.
- Keep existing STANDARD-with-multiple-profiles behavior operational for legacy chats; do not relabel it until new workspace is complete.

### Phase E — UX polish and integration (P1/P2)

- Animate card selection, 120–220 ms transitions, gentle shared-container treatment where appropriate. Respect reduced motion.
- Theme-derived icon accent treatment, accessible pressed/focus/selected state, high contrast at all font scales.
- Explanatory hints for tool requirements, pinned primary, local readiness and optional remote price/benchmark details.
- No network/inference call on opening the chooser or typing in search. Reuse Readiness scheduler/cache for existing passive checks only.
- Tests + benchmarks and a staged release plan.

---

## 6. Source ownership / target-file map

| Existing / new file | Required responsibility |
|---|---|
| HomeScreen.kt | Navigate to new chooser; remove auto-direct-one-profile |
| NavigationGraph.kt and Route.kt | New graph destinations and saved-ID navigation |
| presentation/ui/home/newchat/NewChatTypeScreen.kt (new) | Full-screen entry cards |
| presentation/ui/home/newchat/NewChatProfileScreen.kt (new) | Full-screen profile selection/filter/sort |
| presentation/ui/home/newchat/NewChatViewModel.kt (new) | Immutable draft state, validation, create action |
| presentation/ui/home/SelectPlatformDialog.kt | Split reusable content from modal shell |
| SelectPlatformProfileCard.kt, NewChatProfileInsights.kt | Theme/eligibility details; preserve insights |
| ProfileLabelComponents.kt and ProfileDiscovery.kt | Reuse labels/search |
| ChatRoomV2.kt / ChatRoomV2Dao.kt / ChatDatabaseV2.kt / migrations | Purpose/workspace persistence and verified migration |
| data/chat or data/conversation new creation use case | Atomic session creation and ID mapping |
| AppFeatureSettings.kt / ChatMcpToolConfig.kt | Backward compatible purpose/defaults/readiness |
| ChatViewModel.kt and ChatScreen.kt | First-run hydration, Research chip and Combined safety |
| MultiChatWorkspaceScreen.kt (new) | Independent session workspace |
| app/src/test and app/src/androidTest | Contracts, Compose navigation and Research/Combined regression coverage |

Never put the full implementation in HomeScreen.kt or ChatViewModel.kt. Keep existing 1,819-line and 2,765-line files from growing further without necessity.

---

## 7. Visual design system

- Work only with actual user-selected light/dark/dynamic/custom palette. Surfaces: background/surfaceContainerLow; selected: primaryContainer/onPrimaryContainer; dividers: outlineVariant; readable text: onSurface/onSurfaceVariant.
- Use ThemeIcon (already wraps Material3 Icon) and current saved label colors. Avoid a second independent icon style. Research/multi/combined icons can be Lucide-equivalent Material icons that are already packaged, or small vectors if required.
- Keep title hierarchy calm: strong heading, medium card labels, compact secondary metadata. Let model/profile title win over price or score.
- Recommended dimensions: 20 dp horizontal content padding; 16 dp profile/card inner spacing; 8–12 dp vertical gaps; 20–24 dp large card corners; 48 dp minimum tap targets.
- Compose adaptive layouts: 1 column in compact portrait, 2-column type card grid on roomy portrait/landscape/tablet where content fits, full-width footer within safe insets. On phone, chips horizontally scroll with accessible end affordance.
- Scroll position and selected state survive back, IME, rotate, and process recreation. Animation must not auto-scroll away from user selection.
- Meet at least 4.5:1 normal text contrast and 3:1 visible UI boundary/focus contrast. Never distinguish modes only by hue. For profile attribution, select colors from actual per-profile IDs and test contrast against both light and dark message surfaces.
- Honor accessibility screen reader role/state/selection labels and touch target sizes; support font scaling and reduced animation.
- Product aesthetic: premium, calm, theme-driven, dense enough for many profiles, no expensive blurred backgrounds or always-running animated decorations.

---

## 8. Performance and reliability budgets (targets, not observed measurements)

- Enter chooser without synchronous database/network work that delays rendering.
- Profile list should display cached names/selection immediately and enrich scores/prices asynchronously without reordering or stealing scroll focus.
- Navigation and state transformations must be O(n) over profiles; no per-frame label parsing / recomputation in each visible row.
- Use LazyColumn stable keys, derivedStateOf/memoization where appropriate, stable lambdas and immutable UID selection; avoid loading all prices by starting separate network calls per item.
- Target smooth 60 Hz scrolling on a representative phone, negligible CPU when idle, no local-model load or provider inference from mere wizard interaction; measure with existing macrobenchmark/app jank instrumentation.
- Starting a multi-model chat should share safe connection/readiness prewarming but respect per-provider throttles and local model memory limits. Do not duplicate tools or make extra fan-out due to the UI redesign.
- Repeated Start tapping must yield one chat or workspace, not duplicate chat rows.
- Any creation or permission failure stays in the picker with draft retained and an actionable inline message.
- Log high-level navigation/performance events only, without prompt text, private endpoint URLs, auth tokens, API keys or research content.

---

## 9. Test and acceptance matrix

| Test case | Required result |
|---|---|
| Tap New Chat with 0, 1, or 10 enabled profiles | Always see full-screen four-type chooser |
| Choose Single and tap profile A, then B | Exactly one selected; Start routes to B's normal chat |
| Choose Research and select one model | Research ON and depth >0; standalone Research chat without Combined synthesis claim |
| Choose Research and select three mixed local/remote profiles | Default Combined; user can choose pinned primary; all eligible profiles participate |
| Research plugin globally off / profile denied / papers denied | Explicit gate and consent/setup, no silent permission change; no unauthorized tool call |
| Research first prompt, immediate Send, crash/relaunch | Initial Research policy is committed before model dispatch and reloaded on reopen |
| Choose Multi-chat with three models | Three independent histories and drafts, send-to-one and optional send-to-all; one failure leaves others usable |
| Choose Combined with two-plus profiles | Full unique coverage, color source attribution, no diagnostic text-match rows outside Debug |
| Filter label/search so selected rows are hidden | Selected UIDs retained; footer shows hidden count |
| Change sorting or inspect price/benchmark | Selected UIDs and start order stable; unknown data stays unknown |
| Back from profiles and return | Draft and permissible selections retained; cancel does not create chat |
| Disable/delete profile after selection | Validation blocks unavailable target with clear repair action; never silently route to a different model |
| Rotate/IME/font-scale/TalkBack/back gestures | Usable screens; safe insets; accessible labels and no clipped Start |
| Existing STANDARD and COMBINED chats | Open and generate as before; original chat histories and source links intact |
| Backup, restore, archive, delete workspace | Membership and child message histories preserved or explicitly confirmed for deletion |
| Start double-tap / process restart during creation | Idempotent creation, no duplicate sessions or orphaned research settings |
| Long list of profiles with score/price refresh | Smooth lazy list, stable scroll order, no inference/paid calls from picker |

Tests to add: NewChatDraftReducerTest, NewChatSelectionRulesTest, ProfileFilterSortTest, ResearchCreationPolicyTest, ChatCreationIdempotenceTest, MultiChatWorkspaceRepositoryTest, NewChatFlowInstrumentedTest, ChatDatabaseV2MigrationsTest and macrobenchmark NewChatFlowBaseline. Names illustrative; follow repository conventions.

### Engineering verification before merge

1. Unit tests for reducers, ordering, permission gates, idempotency and migrations.
2. Build and lint using repository Gradle/ktlint CI.
3. Compose UI tests for all four flows, narrow phones and expanded screens, search/labels/filter/sort/back/restore.
4. Instrumented persistence tests including process death and first-send Research.
5. Regression runs on legacy STANDARD/COMBINED, sources/debug-only text matches and per-profile attribution colors.
6. Manual accessibility and high-contrast theme checks, then real-device jank/time-to-first-token comparison.
7. Feature flag rollout, crash-free observation, rollback path, then remove old dialog only when telemetry and tests justify it.

---

## 10. Release sequencing / boundaries

- **Deliverable 1:** full-screen chooser + full-screen profile screen and Single/Combined sessions; no large backend refactor. Retain modal as internal compatibility path where still used.
- **Deliverable 2:** Research chat creation, chat-persisted purpose and safe first-turn plugin activation; permission UX.
- **Deliverable 3:** independent Multi-chat workspace, adaptive layouts, background behavior, migration/backup.
- **Deliverable 4:** visual polish, advanced sort/readiness insight, performance validation and flag retirement.

One product feature can be staged without misrepresenting unimplemented screens as complete. If only partial modes have shipped behind the feature flag, show explicit unavailable state or keep the new chooser gated until all four options function end-to-end.

### Definition of Done

A newly created chat is always intentional, themed, backed by correct saved AI profiles, chat-mode semantics and permitted tools. Research defaults to on in Research conversations, and Combined Research runs with two or more profiles without sacrificing evidence coverage. Multi-chat has genuinely independent histories. Existing chats and settings work unchanged, and the app exhibits no meaningful latency, crashes, lost draft selections, or extra inference cost from the new creation flow.

**No implementation is asserted by this document.** Source changes, CI results, emulator tests and release validation must be provided by a future implementation PR.
