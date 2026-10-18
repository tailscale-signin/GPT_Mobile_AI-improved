GPT Mobile Main Menu
and Local Assist Design
Implementation specification
Prepared for Emanuel Melo  |  17 October 2026  |  Specification 1.0
Repository baseline: main at a13b8b5a46ec
Application: 0.9.53.0, build 122  |  Android API 31 minimum  |  Room schema 40. [R24]
Design direction
Make the main menu visually consistent and make its actions dependable. Retire the Execution & Efficiency controls and their active policy system. Give Local Assist one simple owner, count only replies that the user can open, and make conversation selection and folder operations use stable identities.
This specification defines the intended product behaviour, source changes, migration rules and acceptance criteria. Values marked as proposed are starting design tokens for implementation; the 60% profile text opacity and 90% New Chat glass opacity are fixed requirements.
Item | Required result
1 | Remove Execution & Efficiency and its policy enforcement. Replace complex Local Assist controls with one switch, helper picker and actual status.
2 | Show plain AI profile names in a theme-derived colour at exactly 60% base opacity.
3 | Make New Chat a perfect circular glass button with 90% tint opacity and backdrop blur.
4 | Invert the new-reply control colours, use saturated red numerals and exclude Combined contributors from unread counts.
5 | Use one opaque main-menu background, a short sharp top fade and a long soft bottom fade over conversation content.
6 | Keep distinct Chat, Multi Chat and Combined icons. Seen replies use outlines; unopened replies use filled, stronger variants.
7 | Repair long-press selection, conditional Pin and Unpin, scoped Select All, and Move to folder with existing or new destinations.

Implement the persistence and action changes before connecting the new appearance. That sequence prevents a polished interface from displaying counts or operations backed by the current inconsistent state.

Proposed main menu states

Figure 1  Normal and selection states. Sample conversation names illustrate layout; the image is a design reference. Exact values and state behaviour are specified below.
The profile subtitle stays in place during a press. A draft, when present, uses a separate line. The title, folder controls, selection header and floating action remain fully legible while conversation content fades at the list edges.
Element | Design value
Profile subtitle | Theme-derived source colour, alpha 0.60, 13sp proposed; one line in compact rows and up to two lines otherwise.
New Chat | 72dp proposed diameter, 34dp glyph, CircleShape, one 0.90 tint fill, 16dp proposed backdrop blur.
List edge fades | 8dp top and 120dp bottom proposed, with responsive bounds and scroll clearance.
Unread numeral | Bold saturated red, starting at #D50032 on a pale badge; 99+ is display formatting only.


Current source findings
The inspected main revision already contains folders, row selection, completion navigation and Local Assist infrastructure. The implementation should extend these components while repairing the following concrete connections. [R1-R21]
Priority | Verified finding | Implementation consequence
P0 | ExecutionPolicy changes the effective Local Assist switch, task eligibility and timing. InvocationLedger reloads old per-turn policy snapshots. [R3-R5] | Remove both live overrides and resumed-turn enforcement; hiding the section is insufficient.
P0 | Every committed successful agent run enters the completion inbox, including Combined contributors and synthesis. [R12, R14-R15] | Publish durable visible-reply records at the message commit boundary.
P0 | Home marks unread when active runs disappear. A separate Boolean store drives the row style. [R2, R13] | Derive badge, row style and navigation from one reply/read repository.
P1 | Opening a chat clears unread before rendering; changes in ChatScreen clear the whole chat again. [R1-R2, R15-R16] | Acknowledge only the displayed reply revision on the visible screen and profile.
P1 | The normal row action map contains Move to folder, but visible menus use the selection map, where Move is missing. [R1] | Use one contextual action model for the actual displayed menu.
P1 | Selection uses positional Booleans and resets on folder/search/fetch emissions. Bulk and single deletion clean up differently. [R2] | Use selected chat IDs and one action pipeline with consistent cleanup.
P1 | Profile text uses onSurfaceVariant and can turn into label chips while pressed. The 88dp New Chat button has 28dp corners. [R1] | Introduce a stable plain-name subtitle and a separately rendered circular glass action.
P1 | All unread modes become the same bubble icon. Inbox storage caps at 32 and the navigator exposes eight entries. [R1, R12] | Retain mode identity; paginate the navigator without truncating unread data.

Source findings describe code paths and conditions. The acceptance plan requires runtime verification during implementation; no speed improvement or device-level fix is established by these source observations.
1 Remove Execution and simplify Local Assist
One editing destination
Place one Local Assist card under Settings > AI > Local Models. Keep the existing model library and marketplace flow. A settings-search result or profile Assign action opens and focuses this card. Remove the separate Assist destination, embedded Execution copy, trial-labelled Advanced switch, mode tabs and ordinary-user timing sliders. [R7, R9-R10]
The card contains one enabled switch, one selected helper profile and one status line. Use the supporting text: “Use a selected local model to help prepare research and source material.” If the selected helper needs attention, show one relevant action, such as Choose helper, Verify model or View details.
Status | Meaning | Available action
Off | The user disabled optional assistance. | Enable
Choose a helper | No stable, explicit helper UID is selected. | Choose helper
Setup required | The profile needs existing processing permission or preparation setup. | Review setup
Preparing | An authorized preparation job owns the runtime. | View details
Ready | The exact selected model and backend are admitted and available. | None required
Helping | A useful, permitted optional task is running. | View details
Paused | A local chat owns the runtime, device pressure is high, or a safe cooldown is active. | Explain the specific reason
Model unavailable | The selected profile or its installed artifact cannot be used. | Verify model or choose helper

Enabled records user intent; Ready records current capability. Selecting a different model invalidates the previous readiness result immediately. An unavailable or unapproved profile must remain named in the card, with its reason, until the user chooses a replacement.
Simple controls with one execution owner
Create a small LocalAssistConfig and one resolver for intent, selected UID, processing permission and preparation authorization. Keep one bounded helper queue and the existing native runtime owner. Engine placement, cancellation acknowledgement, memory reserve, thermal limits, installed-artifact validation and quarantine remain automatic protections. The separately named LocalExecutionPolicy governs engine qualification and belongs to Local Models; it is not the retired settings system. [R8, R26-R28]
The helper uses source-linked preparation and evidence selection. Original authorized tool results stay available. Validate helper output before inclusion, preserve source IDs and reject invalid or stale artifacts. The ordinary assistant, explicit research workflow and Combined writer retain their own configured roles. Turning optional assistance off must not break direct local chat, native tool calling or an explicitly selected research worker or reviewer. [R8, R27-R29]
Keep ordinary replies moving
An ordinary remote Send must not wait for a helper cold load, helper queue or native teardown. At a request boundary, include a ready, validated artifact if available. Otherwise continue with the original authorized evidence. Remove the repeated optional waits currently attached to individual wrapped tool results. Explicit research may await a bounded preparation stage when that stage is part of the requested workflow. [R27]
Use the current scheduler to prepare an explicitly enrolled helper while the app is idle and foregrounded. New setup explains this behaviour at the point where the helper is enabled. Upgrades preserve a previously disabled preload choice. Smart Readiness must no longer silently override the Local Assist switch; general provider and MCP readiness keep their separate responsibilities. [R6]
Detailed receipts belong in Diagnostics. Distinguish prepared, validated, included in request and final reply published. A finished helper is never itself a new-message event.
Execution system retirement map
Component | Required treatment
ExecutionEfficiencyScreen and ViewModel | Delete active screens, state, navigation callers, search rows, strings and obsolete UI tests.
OptimizationMode and ExecutionPolicy | Remove active policy state, resolver overlays and dispatch consumers. Keep only a legacy decoder where old backups require it.
ModelBudgetAdmission and ExecutionAllowanceReached | Remove Execution-specific request, concurrency, model-time and token-budget enforcement after migrating all callers.
InvocationLedger policy snapshots | Stop reading, combining or enforcing historical executionPolicyJson. Stop writing it for new invocations. Retain old receipts as inert history.
Statistics and usage retention | Move explicitly requested receipt retention to existing Statistics ownership; preserve minimal accounting required by independent limits.
Spend and total token limits | Keep their independent checks and existing permission boundaries. They share ledger code but are not the retired policy.
LocalAssistScreen and duplicate switch | Replace with the shared card and picker. Profile assignment and search use the same mutation path.
Local runtime protections | Retain qualification, memory/thermal admission, ownership fencing, cancellation, cooldown and installation checks.
Research and Model Delegation | Keep explicit worker/reviewer configuration and grants. Remove accidental dependency on optional Local Assist enablement.

The existing Execution modes are Off, Balanced, Maximum Savings, Fast Response and Custom. The inspected policy does not grant cloud, tool or memory permission. Removing it must not broaden any separate processing or sharing grant. [R4]
Upgrade and restore contract
Run one versioned, idempotent normalizer before publishing settings, on startup and on settings-only or complete-backup restore.
Preserve the explicit localAssist Boolean, a valid selected helper UID and all existing ALLOWED, DENIED and REVIEW_REQUIRED grants. Remove the old Execution overrides permanently.
Preserve historical preload-disabled intent. A missing or formerly automatic UID becomes a truthful setup state; do not silently choose a new model or grant it access.
Normalize incoming advanced_feature_settings_json in staging before committing restored preferences, because CompleteBackupPreferences bypasses normal settings mutations and publishes its DataStore edit immediately. Validate profile-only restore against the selected helper identity. [R11]
Ignore retired fields on later imports and leave historical policy JSON inert when continuing the same turn. Never silently replace malformed settings with a fresh default configuration.
Perform conversion atomically, retain recoverable original data on failure, and keep request dispatch from observing half-migrated settings.
A practical migration can retain a compact preparation-consent marker and legacy policy DTO without preserving any old optimization mode, tuning screen or runtime enforcement.
2 Plain themed profile names
Render only the conversation's AI profile names in the subtitle. Use the selected profile order and a plain comma-separated list for Multi Chat and Combined. Keep provider logos, server badges, label chips, decorative prefixes and emojis out of this text. The conversation mode remains visible through its dedicated leading icon. [R1]
Apply a theme-derived source colour with alpha 0.60 exactly once to the subtitle Text. The ordinary row and compact row must share the same renderer. Parent opacity is 1.0 in the fully visible list region. Edge fades are a separate spatial effect; they intentionally reduce visibility only near the boundaries.
Display cleanup without corrupting identities
Use a memoized display transformation keyed by profile UID and name revision. Remove recognized emoji and decorative icon sequences as complete grapheme clusters, then remove a specified set of standalone ASCII emoticons. Preserve ordinary digits, punctuation, non-Latin letters and combining characters. Emoji can include multi-code-point sequences, so a blanket symbol or character-category deletion is unsafe. [A4-A5]
Input condition | Required display
Decorative emoji around a profile name | The remaining plain name, with normalized spacing.
Qwen3.5-9B, GPT-4.1, C++ or Model #2 | Preserve the meaningful digits and punctuation.
Standalone smiley token beside the name | Remove the emoticon token without changing punctuation inside a name.
Emoji-only name or deleted profile | Use a sanitized stored model-name snapshot, then the plain fallback AI profile if necessary.
Long Multi or Combined profile list | One line in compact mode, up to two otherwise, ellipsis at the end; full names remain available to accessibility.
Draft conversation | Keep the profile subtitle and place the draft preview on a separate tertiary line.

Do not change saved profile names, profile IDs or backup data to satisfy this display rule. Preserve original names in their editing screen. Remove the current press-to-label crossfade; a long press should immediately enter selection and leave the subtitle recognizable. [R1]
Contrast at the requested opacity
Evaluate the composited text colour against the actual HomeBackground. Start from the theme accent and adjust the derived tone while keeping its hue relationship and 0.60 alpha. Extend PaletteContrast checks to this exact visible pair. Fixed 60% opacity and unrestricted custom mid-tone backgrounds cannot guarantee 4.5:1 for every palette; retain the requested alpha, validate supported presets and show accurate results in theme preview. [R22, A3]
3 Circular glass New Chat action
Replace NewChatButton's current 88dp rounded rectangle with a 72dp proposed circular control. Set equal width and height, CircleShape clipping and a circular ripple. Center the existing new-chat glyph at a proposed 34dp optical size and preserve the existing navigation action. The button stays icon-only, with the localized accessible name New chat. [R1, R23]
Token | Required or proposed value
Shape | Perfect circle with equal measured dimensions.
Diameter | 72dp proposed; grow only if a deliberate accessibility treatment requires it.
Glass tint opacity | 0.90 exactly, applied once to the background tint.
Tint source | Final resolved theme surfaceContainerHigh, with a theme accent edge; no extra opaque Material fill.
Backdrop blur | 16dp proposed, converted to native pixels and evaluated against the repository's pinned Compose APIs.
Glyph | 34dp proposed, centered, alpha 1.0, sharp.
Edge and elevation | 1dp subtle theme edge and one soft shadow; zero duplicate tonal overlay.
Placement | 16dp from the safe bottom and end; apply system insets once.

Rendering order
Draw the menu scene, obtain a correctly aligned sample of the content below the button, blur that sample, clip the result to the circle, paint one 90% theme tint, then draw the edge and sharp glyph. The sampled source excludes the button and its own blur output. Extend the sample beyond the final crop so blur edges remain correct.
Android effects operate on their source drawing. Applying a blur modifier to the complete button would blur its glyph and would not automatically sample the siblings behind it. Use a bounded same-window graphics implementation; check the pinned Compose version before adopting newer APIs. [A1-A2]
On a uniform background the blur will be subtle, especially because only 10% of the backdrop contributes through the tint. Validate real sampling with a controlled detailed background, then inspect the intended home screen. Keep the 0.90 value rather than reducing it to exaggerate the effect.
If the effect is unavailable or fails its performance gate, retain the 90% tint, circle, border and sharp icon through the shared glass fallback. Track that renderer state in Diagnostics. The New Chat component owns the 90% variant; the earlier popup specification continues to define its own 85% material. [P1]
4 New reply counter and unread meaning
Presentation
Invert the existing navigator's colour roles: use the resolved onPrimary as its container and primary as its glyph, with contrast-adjusted inverse roles if needed. Place a bold saturated red numeral on a small pale badge. Start with #D50032 for the numeral; do not change the app's global error colour to style unread activity.
Use a minimum 48dp action target, a proposed 24dp icon and 12sp bold scalable numeral. At zero unread replies, hide the badge and pending-reply shortcut. For one reply, open that reply. For more than one, open a scrollable or paginated unread list grouped by conversation, preserving every target. Display 99+ on the badge when needed and announce the exact number. [R1, R12, A6]
The numeric count is the number of published replies still waiting to be viewed. It is not the number of finished models, active profiles or conversations. A conversation can contain several unread Multi replies while appearing once in a grouped navigator.
Event | New unread replies | Reason
Single Chat publishes a useful final response | 1 | One new visible reply.
Multi Chat publishes two independent profile replies | 2 | Each is independently visible and readable.
Three Combined contributors finish | 0 | They are inputs to the answer.
Combined final answer is committed and published | 1 | One answer is delivered for that turn.
Combined runs with one participant and publishes a valid final fallback | 1 | Publication role, not participant count, determines eligibility.
Local Assist, tool, reviewer or debug contribution finishes | 0 | Internal work does not create a user reply.
Empty failure or cancellation with no published content | 0 | No new reply can be opened.
An explicit saved partial is published to the user | 1 | A visible partial counts once and is labelled Partial.
Same callback is replayed or the same unread reply becomes final | 0 additional | Update the existing logical reply instead of duplicating it.

Read acknowledgement
Mark a reply viewed after successful navigation and foreground rendering of that reply on the selected profile or Combined answer. The acknowledgement identifies the publication revision actually displayed. Opening the main menu, visiting another profile, viewing older history, or a failed navigation must not clear it.
Capture a bounded revision or commit sequence when acknowledging. A newer response arriving during that operation remains unread. In Multi Chat, opening profile A leaves profile B's pending response intact. A foreground chat already showing its newest response acknowledges it after publication and rendering without a lasting badge flash.
Archived conversations remain reachable from the global unread navigator and retain an Archived label there. Folder membership and pin state do not change unread meaning. Deleting a conversation removes its unread publications through the same durable deletion path.
Unread persistence and publication
Replace the two SharedPreferences unread stores with one Room-owned reply publication and acknowledgement model. Keep the UI as a consumer of this model. Active-run state drives progress only. Existing completed runs must not become unread merely because they disappear from activeRuns. [R2, R12-R14]
Proposed field | Purpose
logicalReplyKey | Stable identity for one visible output slot; Combined uses its user-turn identity, not each contributor run ID.
chatId and messageId | Own the reply, support navigation and cascade cleanup.
userTurnId and profileUid | Preserve turn and Multi profile channel; use a Combined channel for the merged answer.
displayRole | Persist DIRECT_REPLY, MULTI_REPLY, COMBINED_CONTRIBUTOR, COMBINED_FINAL or INTERNAL before dispatch.
ownerFence and committedRevision | Prevent stale workers or recovery callbacks from publishing or acknowledging the wrong revision.
publishedAt and publicationSequence | Order navigation and support race-safe read acknowledgement.
acknowledgedRevision | Track which published revision the user actually viewed.
deliveryKind | Distinguish Final and explicitly published Partial without using success status alone.

Names above are proposed domain fields. Choose the smallest schema that preserves these invariants, including uniqueness and referential integrity. Unread counts consider the latest unacknowledged revision for each logical reply, so replacing an already pending partial does not add a second count.
Commit boundary and Combined ownership
Snapshot displayRole when creating the durable run. Add or update the publication in the same Room transaction that successfully commits the owned message. Apply this to normal completion, gateway recovery and explicit partial publication. Rejected or stale ownership cannot produce a publication. [R14, R17]
Combined synthesis currently reuses the primary contributor message slot. A message ID alone therefore cannot identify the visible delivery. Persist the logical Combined reply identity and role through retries and recovery. Also handle the current one-participant path, which bypasses synthesis, by explicitly publishing its final fallback. Do not infer eligibility solely from a run-name prefix or from the current conversation mode. [R15]
For legacy Multi workspaces with hidden child chat rooms, group unread state under the visible root while retaining each original room/message target. The selected-row count refers to visible conversations. Workspace expansion remains an operation-specific domain rule.
Migration and backup
The inspected database is schema 40. Allocate the next free version on the implementation branch and add explicit migrations and schema exports. New unread tables belong to the CONVERSATIONS backup section, not an optional agent-history section. A restored conversation must remain valid when detailed execution history was excluded. [R20-R21]
Migrate only old inbox items whose visible delivery can be proven, coalescing duplicate contributor/final references. Boolean chat flags cannot reconstruct exact historical reply counts. Keep imported historical content read when acknowledgement evidence is absent, and prevent legacy preferences from reappearing after restore. Never mark every historical assistant row unread.
Remove the old 32-item storage cap. The database retains outstanding publications; the UI pages its results. Add indices for chat/channel and unread queries, with no network call needed to calculate a badge.
5 Uniform background and list edge fades
Resolve one opaque HomeBackground colour from the final theme. Paint it behind the system-inset areas, top bar, tabs, folder strip, list and archive access area. Make ordinary row containers transparent and remove scroll-elevation fills that create colour bands. Selection and transient menus retain their own intentional state surfaces. [R1, R22]
Apply one spatial fade to the scrollable conversation content, including titles, subtitles, leading icons, draft text and row bubbles. Keep the Chats heading, folder strip, Select All control, New Chat button, open menus and snackbar outside the faded region. The current sticky folder header must be moved outside that region or explicitly excluded. [R1]
Parameter | Proposed rule
Top fade | 8dp from the row viewport boundary, quickly reaching full visibility inward.
Bottom fade | 120dp on a normal portrait viewport, using a smooth easing curve to transparency at the bottom.
Compact viewport | Clamp the bottom fade to at most 25% of the available row viewport; keep a usable fully legible band.
Top resting position | Add clearance so the first row rests below the short fade.
Bottom resting position | Scroll padding at least max(bottom fade, FAB diameter + clearances), plus 16dp and any unconsumed system inset.
Short list | Reduce or disable only the overlapping edge fade needed to keep every available row readable.
Focus and selection | Bring focused rows into the fully legible band. Keep active checkbox and overflow control feedback crisp.
Pointer handling | The drawing layer has no click handler or accessibility node; it must not consume scrolling or intercept controls.

Use a steep linear top ramp and a smooth bottom ramp. If t is the normalized distance from the bottom edge, the smooth factor is t squared multiplied by three minus two t. Combine the edge factors by their minimum so the content is fully visible in the central band.
Choose one rendering path
For a true alpha mask, apply the gradient with DstIn inside a row-only offscreen graphics layer. Keep the solid background beneath it so masking cannot clear the window. Cache the gradient by viewport size. [A1]
Because this screen has one guaranteed opaque background, an overlay gradient of that exact same colour can produce the same visible fade with less buffering. Compare it with the mask using captured visual results and frame timing, then choose one path. Do not stack two fades or use a different colour at either edge.
The fade is spatial, not a long exit animation on each item. Preserve scroll position and existing drag/swipe hit areas. Add end padding so the final row can scroll entirely clear of both the fade and the floating button.
6 Conversation mode icon states
Use paired outline and filled vector assets with the same optical dimensions for Chat, Multi Chat and Combined. Render a 24dp proposed glyph in its existing leading slot. Keep that mode identity when unread; the current generic unread bubble replacement must be removed. [R1]
Mode | Seen | Unopened reply
Chat | Single bubble outline | Same bubble filled, stronger silhouette
Multi Chat | Two distinct overlapping bubble outlines | Same paired bubbles filled, stronger silhouette
Combined | Connected contributor nodes converging on one answer outline | Same combined structure with filled nodes and stronger strokes

Use a proposed 1.75dp outline weight and 2.5dp stronger treatment at the 24dp viewport, with the unread title bold. The theme accent supplies the tint. If the theme offers global icon styling, semantic read/unread state takes precedence for these assets.
Generation progress is separate. Keep the mode glyph and show a small adjacent or surrounding progress indicator. A Combined contributor finishing can update that progress without filling the icon. An older unread reply can remain indicated while a newer request runs.
Selection replaces the leading mode slot with a checkbox and retains unread state in the data. Leaving selection restores the correct mode and unread asset. Local or server runtime identity is available through the profile and details; it does not replace the requested mode with a fourth main-menu icon.
Use local vector assets and the existing icon wrapper with explicit tint, checking that it preserves inverse foreground colours. The inspected ThemeIcon only resolves tint and does not currently select a different shape. [R23]
7 Selection and conversation actions
Long press and stable selection
A stationary long press on the conversation body enters selection and selects that conversation. Subsequent taps toggle selection; they do not navigate. Keep swipes and drag-to-organize in normal mode. During selection, disable conflicting swipe and drag dispatch so the same gesture cannot mutate and select a row.
Replace selectedChats as an index-aligned Boolean list with selectedChatIds as a set of stable IDs. Preserve selection across title changes, completion updates, sorting and folder membership refreshes. Remove only IDs that are no longer eligible. Freeze the selected target IDs when an operation starts. [R2]
Support selection inside search results. Hold the current folder and search query as the selection scope. While an action sheet or move picker is open, its scope and targets stay fixed. Closing selection returns to the same folder, query and scroll position.
Select All below Chats
Keep a visible Chats heading in selection mode. Directly below it, place a tri-state checkbox labelled Select all, with the applicable result count and an adjacent selected count. This control belongs above the messages list and outside all three-dot menus.
Unchecked means none selected; indeterminate means some selected; checked means every eligible conversation in the current folder/search result is selected. Activating unchecked or indeterminate selects the entire result, including rows not composed on screen. Activating checked clears it. An empty result disables the control. Exclude archived and hidden workspace child rooms from this visible selection scope.
Contextual menu rules
Only selected rows show their three-dot button. The row menu and any retained header overflow consume one SelectionAction model and clearly state the scope, such as “3 conversations selected”. Remove the duplicate archive/delete toolbar buttons and remove Select All from all action menus.
Selection | Pin actions | Other actions
One unpinned conversation | Pin | Move to folder, Archive, Delete; Duplicate when eligible.
One pinned conversation | Unpin | Move to folder, Archive, Delete; Duplicate when eligible.
Several, all unpinned | Pin selected | Move to folder, Archive selected, Delete selected.
Several, all pinned | Unpin selected | Move to folder, Archive selected, Delete selected.
Mixed pinned state | Pin unpinned (N) and Unpin pinned (M) | Each pin action touches its matching subset; other actions use all selected IDs.
No selected conversations | None | No row overflow and no enabled bulk mutation.

Use explicit setPinned targets rather than a blind toggle. Revalidate state at execution and report the actual changed count. Keep pin order deterministic after recreation. When a single pinned conversation is eligible for reordering, expose Move pin up/down only where a neighbouring position exists.
Move to folder and complete action wiring
Reuse conversation_folders and conversation_folder_members. The existing schema has one membership per conversation and already supports atomic creation with one member. Extend the repository with moveMany and createAndMove for a set of visible conversation IDs. [R18]
Destination picker
The Move to folder action opens a searchable destination picker titled “Move 3 conversations” for a multi-selection. List existing folders, a clear No folder destination and a Create folder action. All chats remains an aggregate view, so it must not be presented as though it were a folder.
Selecting Create folder opens a compact name editor within the same flow. Preserve the selected IDs, query and target context across rotation and back navigation. Reuse the existing 1-40 character name validation and folder-colour choices. Normalize names and compare case-insensitively for this flow; if a same-name destination exists, offer that folder rather than silently creating an ambiguous duplicate. [R18]
Create the new folder and move all selected conversations in one Room transaction. Revalidate destination existence and target ownership there. Disable repeated confirmation while saving. Keep the picker open with an inline error when validation or persistence fails, and close it only after a confirmed commit.
A move changes membership only. It preserves conversation title, messages, attachments, profiles, pin state, unread state and running generation. For legacy grouped workspaces, move the visible root as a folder item while maintaining its child mapping. Do not expose hidden child rooms as extra selected conversations.
Undo and concurrent changes
After a move, show the committed count and destination with Undo. Retain each affected conversation's prior membership and the operation version. Undo restores only memberships still owned by that move, so it cannot overwrite a later move. If Create folder is undone, remove the created folder only when it is still empty and unchanged; otherwise retain it.
If a previous folder has since been deleted, Undo returns the affected conversations to No folder and reports that count. It does not recreate the deleted folder or fail the entire Undo operation.
If a destination is deleted while the picker is open, keep the selection and refresh the destination list. Deleting a folder removes memberships while preserving conversations, which then remain in All chats and No folder. [R18]
Action | Wiring and completion rule
Pin and Unpin | One use case sets the intended value and pin order for the matching selected subset; UI updates from committed state.
Select All | Checkbox invokes ID-based selection over the current scope; it performs no database mutation.
Move or Create and move | Selection action model -> picker -> repository transaction -> refreshed membership -> committed feedback and Undo.
Archive | Shared archive operation expands legacy workspaces as required, preserves folder/unread/pin data and offers one Undo path.
Delete | Shared deletion path gates targets, cancels and joins work, removes dependent data and unread publications, then updates UI.
Duplicate | Available for one eligible non-running conversation; copied history begins read and gets new IDs. Do not copy unread publications.
Pin ordering and drag | Use the same pin/folder domain commands as menus; preserve accessible alternatives without adding six-dot decoration.
Back and dismiss | Close the open menu or picker first, then selection. A dismissed unsaved operation changes nothing.

Use the same domain operations from selected-row menus, header actions, swipe confirmation and the archive drawer. Single and bulk deletion currently diverge in unread cleanup; consolidating them prevents late run callbacks from recreating ghost badges. Preserve the existing dependent-file and privacy cleanup. [R2, R30]
Implementation map
Keep HomeScreen focused on layout. Add small immutable UI state and action models so profile text, mode icons, unread count, selection and dialogs observe the same saved state. Proposed new names below identify responsibilities and may be adjusted to fit the repository.
Area | Existing entry points | Implementation work
Settings and Assist | SettingScreen, SettingsSearchRegistry, LocalAiHub, LocalAssistScreen, AppFeatureSettings | Delete Execution entry points; introduce one LocalAssistCard and canonical settings resolver.
Execution retirement | EffectiveAssistPolicy, ExecutionPolicy, ModelBudgetAdmission, InvocationLedger | Remove active overlays, persisted-turn reads and optional enforcement; preserve independent accounting.
Helper lifecycle | ChatReadinessCoordinator, AssistCoordinator, LocalAssistToolBinder, DirectResearchModelFactory | One optional owner, correct grants/status and no ordinary-send helper waits.
Home layout | HomeScreen, AppDesign, Theme, PaletteContrast | HomeUiTokens, ProfileNameText, ConversationModeIcon and circular glass button; shared row-edge effect.
Unread production | AgentRun drafts, ChatViewModel, AgentRunCoordinator, AgentPersistenceDao | Persist display roles and logical reply identity; publish atomically with the owned message.
Unread consumption | HomeViewModel, ChatScreen, GenerationCompletionStore, ConversationReadStateStore | ConversationUnreadRepository, exact revision acknowledgement and paginated navigator.
Selection and folders | HomeViewModel, ConversationFolderRepository, ConversationFolderDao | Selected ID set, SelectionAction resolver, bulk transactions, operation results and guarded Undo.
Migration and backup | ChatDatabaseV2, AppBackupModels, AppBackupManager, CompleteBackupDatabase, CompleteBackupPreferences | Next schema migration, settings normalizer, archived-folder coverage and unread round trips.

Backup coverage that must be corrected
Complete Backup already includes folder tables, but the legacy AppBackupManager database export reads a query that excludes archived conversations. Exporting all folders without all archived members cannot preserve complete organization. Define and test full-database replacement semantics for this path, including archived chats, folders and new unread records. Older backups without these fields must decode with safe defaults. [R19-R20]
A settings-only restore must not recreate old unread preference truth. A conversations restore brings its publication/read state with it; a profile-only restore revalidates the helper without silently enabling or replacing it. Restore failures must preserve the prior usable state.
Delivery sequence
Implement this design on a fresh branch from the then-current main revision. Reconcile overlapping work before changing the schema number or shared settings structures. Suggested repository document destination is docs/design/Main_Menu_Local_Assist_Design_Implementation_2026-10-17.md.
Step | Scope | Exit evidence
1 | Introduce canonical Assist settings and normalization; remove live Execution enforcement and duplicate routes. | Legacy modes/restore/resume matrix passes; independent limits and permissions remain functional.
2 | Add reply publication/read persistence and role metadata at normal, recovery and partial commit boundaries. | Combined contributor=0/final=1, replay and stale-owner tests pass.
3 | Connect Home and chat acknowledgement to the new repository; migrate old stores and paginate unread navigation. | Badge, icon and target agree; profile-specific read and >99 cases pass.
4 | Replace positional selection, unify menu actions and extend bulk folder/create/Undo operations. | Real menu interactions mutate the intended IDs; refresh and failure cases pass.
5 | Apply plain 60% profile text, state-preserving icons, circular 90% glass and uniform-background fades. | Visual states pass across themes, sizes, insets and short/long lists.
6 | Complete backup coverage, remove obsolete code/resources and verify the integrated build on device. | Required CI checks, explicit migration tests, device flow and paired performance evidence are complete.

Packages may become separate pull requests where dependencies allow. Keep the visible unread UI change behind the completed persistence migration so released builds never run two independent unread systems.
Completion evidence
The implementation report should record the merged source revision, actual schema version, changed entry points, migration fixtures, automated results and device observations. Screenshots establish visual appearance; the publication/action tests establish wiring. A successful screenshot alone does not demonstrate correct counts or persisted operations.
Acceptance criteria
These are implementation gates. Reuse and extend the existing conversation-selection, folder, ownership and backup tests rather than writing broad tests that only mirror visual constants. [R25]
ID | Scenario | Required result
LA1 | Upgrade every legacy Execution mode and budget combination. | Explicit Assist intent and grants survive; retired controls and enforcement never reactivate.
LA2 | Restore settings and a same-turn invocation containing old executionPolicyJson. | One normalization path runs before dispatch; old policy is inert; independent spend/token limits still work.
LA3 | Helper missing, disabled, denied, busy or uninstalled. | Truthful status; no helper substitution; ordinary remote response continues.
LA4 | Slow cold load, blocked queue, multi-tool batch and model switch during work. | No accumulated helper waits on ordinary Send; no stale artifacts or native ownership races.
LA5 | Assist off with direct local chat and an explicitly configured research reviewer. | Both follow their own valid configuration; no accidental feature disablement.
UR1 | Three Combined contributors finish, then the final publishes. | Count stays 0 until final; then becomes 1. Contributor debug views never increase it.
UR2 | One-participant Combined, failure without content and explicit saved partial. | Valid fallback=1; empty failure=0; published partial=1 and final replacement adds no second pending item.
UR3 | Multi A and B each publish; only A is opened. | Total starts at 2, then becomes 1; B stays unread and the conversation icon remains filled.
UR4 | Reply arrives during acknowledgement or navigation fails. | Only the displayed revision is acknowledged; new or unopened content remains pending.
UR5 | Process death, recovery replay, duplicate callback and stale fence. | Committed visible reply is retained once; rejected owner cannot publish.
UR6 | More than 99 unread replies, including archived conversations. | Exact stored count and accessible count; badge 99+; every target remains reachable.

ID | Scenario | Required result
AC1 | Long press, row tap, then completion and folder refresh. | Correct stable IDs remain selected; no accidental navigation, swipe or drag mutation.
AC2 | Single, homogeneous and mixed pinned selection. | Only valid labelled pin actions appear; each changes the intended subset and preserves deterministic order.
AC3 | Select All in a filtered folder with offscreen rows. | Tri-state checkbox selects the full current result; archived and hidden child rooms are excluded.
AC4 | Move selected chats to existing/new/no folder; repeat tap or force save failure. | One atomic operation; no partial move or false success; error retains picker and selection.
AC5 | Undo after another move or after the new folder is reused. | No later operation is overwritten; reused/nonempty folder is retained.
AC6 | Bulk, swipe and drawer deletion while generation runs. | Worker ownership is joined; dependent data and unread records disappear; late callbacks cannot resurrect state.
AC7 | Archive/Undo, folder deletion and duplication. | Organization/read state follows defined rules; deleting a folder keeps chats; duplicate begins read.
UI1 | Standard/compact/draft rows, emoji sequences and model punctuation. | Plain names at base alpha 0.60; meaningful text intact; long press does not reveal label chips.
UI2 | Each conversation mode with read, unread, busy and selected states. | Correct stable glyph, fill/weight and title emphasis; progress is independent.
UI3 | New Chat over a detailed test layer and the real home background. | True circular sample/clip, one 0.90 tint and a sharp centered icon; correct fallback when needed.
UI4 | Short, long and empty lists; 320dp width, landscape, large text, RTL and both navigation modes. | Uniform base colour; correct fades; first/last/focused rows and all controls remain reachable.
BK1 | Old/new settings and conversation backups, including archived folder members. | Round trips preserve valid data; no ghost unread preferences, lost folder members or resurrected Execution enforcement.

Visual quality and performance verification
Use paired captures in light, dark, a saturated preset and a difficult custom palette. Check profile text after compositing, badge numerals against the actual badge surface, and mode/control boundaries. Ordinary text targets 4.5:1 and meaningful non-text controls target 3:1; respect the explicit 60% subtitle rule and its custom-theme limitation. Preserve scalable text, meaningful accessibility labels and minimum 48dp targets. [A3, A6-A7]
Measure main-menu scroll and unread updates with glass enabled and disabled on the same build, device, refresh rate and data set. Use a long conversation list and background generation so the effect is evaluated under relevant load. Compare frame duration percentiles, missed frame deadlines, graphics allocation and memory. Native blur and offscreen layers must not be described as free.
For Local Assist, record Send-to-dispatch, first visible answer, helper queue/load time, primary success, helper skip reason and actual artifact inclusion. Pair the same prompts with Assist on/off and cold/warm helper states. The release gate is that an unavailable optional helper does not block the primary response and that original evidence remains usable.
Run the repository's required CI checks and relevant Android build and instrumentation gates. The source baseline does not establish device performance, so publish measured deltas only after this work. Stop expanding test scope once the identified migration, unread, action and rendering risks are resolved.
Source references
Repository links below are pinned to the inspected commit and display their filenames. Implementation names marked proposed elsewhere are new responsibilities, not claims that those files already exist.
[R1] Home layout, profile text, completion navigator, icons, selection menus and New Chat. HomeScreen.kt
[R2] Unread observation, positional selection, folder publication, opening, deletion and bulk actions. HomeViewModel.kt
[R3] Execution overlays and existing processing grants. EffectiveAssistPolicy.kt
[R4] Optimization modes and retiring enforcement. ExecutionPolicy.kt; ModelBudgetAdmission.kt
[R5] Persisted policy replay, shared budgets and independent accounting. InvocationLedger.kt
[R6] Readiness and helper-preload gates. ChatReadinessCoordinator.kt
[R7] Current Assist controls and model navigation. LocalAssistScreen.kt; LocalAiHub.kt
[R8] Task configuration and binding conditions. LocalAssistPolicy.kt; LocalAssistToolBinder.kt
[R9] Execution, Local Assist and separate runtime settings. AppFeatureSettings.kt
[R10] Duplicate editing surfaces and removal targets. SettingScreen.kt; SettingsSearchRegistry.kt; ExecutionEfficiencyScreen.kt; ExecutionEfficiencyViewModel.kt
[R11] Raw settings restore and serialization. CompleteBackupPreferences.kt; SettingDataSourceImpl.kt; SettingRepositoryImpl.kt
[R12] Per-run completion inbox and 32-item limit. GenerationCompletionStore.kt
[R13] Separate Boolean unread state. ConversationReadStateStore.kt
[R14] Normal completion and gateway-recovery recording. AgentRunCoordinator.kt
[R15] Combined synthesis ownership, one-participant bypass and read clearing. ChatViewModel.kt
[R16] Read acknowledgement trigger on message/run updates. ChatScreen.kt
[R17] Owned terminal-message commit boundary. AgentPersistenceDao.kt
[R18] Folder schema, validation and transactional creation. ConversationFolder.kt; ConversationFolderDao.kt; ConversationFolderRepository.kt
[R19] Legacy export/restore and archived-chat filtering. AppBackupManager.kt; AppBackupModels.kt; ChatRoomV2Dao.kt
[R20] Conversation-section table coverage. CompleteBackupDatabase.kt
[R21] Actual schema version and database migration registration. ChatDatabaseV2.kt
[R22] Final theme resolution and composite contrast checks. Theme.kt; PaletteContrast.kt
[R23] Shared geometry, tint wrapper and existing action glyph. AppDesign.kt; ThemeIcon.kt; ic_new_chat.xml
[R24] Application version, SDK baseline and repository conventions. build.gradle.kts; AGENTS.md
[R25] Existing focused selection, folder and backup test entry points. ConversationSelectionGestureTest.kt; ConversationFolderInstrumentedTest.kt; ConversationFolderBackupTest.kt
[R26] Separate local runtime placement and qualification policy. LocalExecutionPlan.kt
[R27] Optional waits, shared work, permissions and source-preserving handoff. AssistCoordinator.kt; ChatRepositoryImpl.kt
[R28] Model identity, runtime ownership and preparation admission. LocalAssistPreparation.kt
[R29] Research helper and reviewer integration. DirectResearchModelFactory.kt
[R30] Existing dependent-record and privacy cleanup. ConversationDeletion.kt
Platform references
[A1] Android Developers. Compose graphics modifiers and compositing. Accessed 17 October 2026 Toronto time.
[A2] Android Developers. RenderEffect API. Accessed 17 October 2026 Toronto time.
[A3] W3C WAI. Understanding contrast minimum. Accessed 17 October 2026 Toronto time.
[A4] Unicode Consortium. UTS 51 Unicode Emoji. Accessed 17 October 2026 Toronto time.
[A5] Unicode Consortium. UAX 29 Text Segmentation. Accessed 17 October 2026 Toronto time.
[A6] Android Developers. Compose accessibility API defaults. Accessed 17 October 2026 Toronto time.
[A7] W3C WAI. Understanding non-text contrast. Accessed 17 October 2026 Toronto time.
[P1] GPT Mobile UI and Glass Theme Implementation. Design specification dated 17 October 2026, pages 1 and 4-6. Shared popup glass alpha 0.85 with separate sharp foreground and renderer fallback.