# Queued prompts and model controls: 17-feature integration audit

Branch: `feat/queued-prompts-model-controls-debug-marketplace`  
Review: [PR #493](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/pull/493)  
Date: 2026-09-25

All implementation paths below are present on this branch. Device rendering, touch/GPS behavior and authenticated third-party services still require the manual checks below; source/build verification is not a substitute for those checks.

Paths in this table are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/`.

| # | Requested behavior | Implementation and integration evidence |
| --- | --- | --- |
| 1 | Queue prompts while generating; typing changes Stop to Send | `presentation/ui/chat/ChatScreen.kt` keeps the composer enabled and chooses Send when text/attachments exist. `ChatViewModel.kt` captures a FIFO queue, reserves persistence/dispatch work synchronously and waits for all profile runs and combined synthesis. Later prompts use the completed conversation as follow-up context. Unsent text survives queue draining. Queues also wait when all profiles are paused. |
| 2 | Remove retry warning | Retry actions no longer display the warning; obsolete translations were removed from resources. |
| 3 | Hide revision arrows for a single response | `ChatScreen.kt` supplies a revision label only when stored revisions exist; `ChatBubble.kt` renders the arrow/count group only with that label. |
| 4 | Models popup with search, tools and creativity | `ChatDialogs.kt`, `CloudModelPickerDialog.kt` and `data/repository/ProfileModelCatalog.kt` connect provider model discovery/search, chat model overrides, MCP connection switches, permission-aware phone location, web search, creativity and profile membership. Local downloaded models retain their picker; Ollama queries `/api/tags`. |
| 5 | One-second pause/resume and combined membership | `ChatBubble.PlatformButton` handles a 1,000 ms press with cancellation on scrolling and accessibility actions. Profiles and separate responses use 50% opacity while paused. `ChatViewModel` excludes paused profiles from new runs/retries/synthesis. Models controls add/remove combined members. |
| 6 | Main settings titles without descriptions | `presentation/ui/setting/SettingScreen.kt` uses title-only destinations grouped by category. |
| 7 | Debug statistics page | `UsageStatisticsScreen.kt` / `UsageStatisticsViewModel.kt` have a dedicated Debug navigation route, model/profile generated-token rankings, daily charts, run counts, input/output usage, completion/failure/cancellation, duration and tool statistics. Views identify missing token usage and the 10,000-record history limit. |
| 8 | Optional backup sections off by default | `data/backup/CompleteBackupSelection.kt` and `BackupUiState` default tools, agent history, attachments and local models off. Options can enable them. Password encryption remains off by default. Full-backup APIs still support all sections explicitly. |
| 9 | Custom themes; retain current/default theme | Theme controls initialize from the current palette, support editable primary/secondary/background/surface colors with preview, and restore the stock palette. Theme settings and both backup paths persist custom colors. Existing themes are preserved until changed. |
| 10 | Research at least ten compatible MCP addons | Twelve hosted, GitHub-backed candidates are catalogued with real endpoints/auth recipes. See [MCP compatibility audit](mcp-marketplace-compatibility.md) for source links, live discovery results and excluded process-host/OAuth candidates. Installation saves a remote connection and then opens profile/tool assignment. |
| 11 | Clean settings background and category headings | Active settings layout has plain section headings, no decorative chat bubbles, and destination icons only on clickable rows. |
| 12 | Edit/delete default favorite groups; remember selection | `presentation/ui/home/HomeViewModel.kt` allows Starred/Work/Personal to be renamed or deleted, preserves ungrouped favorites, stores the last category and records initialization so deleted defaults do not reappear. `HomeScreen.kt` wires long-press label actions. The aggregate All view remains. |
| 13 | Enabled-only platform picker; Separate/Combined controls | `HomeScreen.SelectPlatformDialog` filters enabled profiles while preserving original selection indices; selected count and confirmation also exclude disabled profiles. Only Separate and Combined mode chips remain. |
| 14 | Plain preparing indicator; lighter timestamps | The actual `CompactAgentActivityBar` call path and gateway indicator use no background bubble. Status text is larger with a left icon and progress bar. Both message timestamp styles use light weight and reduced opacity. |
| 15 | Real document attachments | `util/DocumentTextExtractor.kt` reads PDF, DOC/DOCX, XLS/XLSX, PPT/PPTX and text/CSV/TSV/JSON/Markdown/XML. Bounded extraction occurs during attachment preparation. `ChatRepositoryImpl` inserts excerpts before context budgeting, uses native PDFs where supported and preserves original attachment metadata in storage. Unsupported/unreadable files get an error rather than silently disappearing. |
| 16 | Embedded maps from location tools | `ChatBubble.LocationToolMapPreview` renders successful coordinate-bearing results with a MapLibre street map and marker, lifecycle cleanup, coordinate-keyed view replacement and an external map action. |
| 17 | Immediate red/cyan swipe feedback | `HomeScreen.FancySwipeChatRow` immediately reveals red for delete and cyan for archive, including an early foreground tint and visible action icons. |

## Regression coverage

- `ChatPromptQueueTest`: FIFO order, retained drafts, coordinator/database completion ordering, combined synthesis handoff and pause/resume.
- `DocumentTextExtractorTest`: PDF, legacy Excel, Office XML/shared strings, extraction size limit and external-entity rejection.
- `ProfileModelCatalogTest`: provider catalog parsing and Google pagination/capabilities.
- `UsageStatisticsTest` / `ProviderEventAssemblerTest`: reporting/range coverage and actual provider usage payloads.
- `ThemeAndBackupDefaultsTest` / `CompleteBackupViewModelTest`: optional-section defaults, encryption default and backward-compatible theme serialization.
- MCP catalog/config/resolver tests: curated remote entries, alias/connection allowlists, individual deny precedence and search toggle propagation.
- Existing request, context, backup, local-runtime and resource checks run with the feature suite.

## Validation

Validation results will be recorded after the final build completes.

## Manual acceptance on an Android device

1. During a separate and combined generation, type/send two prompts and then leave a new unsent draft. Confirm each queued turn waits for all required work and the draft remains.
2. Hold a profile for one second; verify 50% opacity and no later generation for that profile. Pause all profiles and resume one to release queued prompts. Scroll over the chip row without accidentally pausing a profile.
3. Open Models on cloud, Ollama and local profiles. Search/select a model, change creativity, assign an MCP addon and toggle location/web search. Check a denied Android location permission and an Advanced Settings tool restriction.
4. Attach a real PDF, DOC, DOCX, XLS and XLSX. Ask a question about content inside each. Verify a scanned PDF can use a native PDF-capable provider; text extraction is not OCR and does not claim to read image-only Office pages.
5. Invoke device location with permission, pan/zoom the embedded map, rotate/background/resume and generate a second location. Verify the new marker and external Open action.
6. Check settings, Debug statistics, themes, favorite long-press actions, first-pixel swipe feedback and revision controls at large font size on a narrow screen.
7. Back up and restore with default selections, then explicitly enable all optional sections; test a custom theme and password-protected backup.

Queue state and pause toggles belong to the active chat ViewModel: this change does not implement a durable unattended queue across leaving the chat or process death. Provider-side live steering is not assumed; queued follow-ups are submitted after completion using the app's conversation history. Token charts cannot recover usage that an older run/provider did not record. Marketplace entries require account access where noted in the compatibility audit.
