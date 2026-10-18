# GPT Mobile UI Glass Design Implementation 2026-10-17

Text requirements imported from the supplied design document. Original diagrams and visual references remain in the DOCX. This specification is not an implementation-completion claim.

GPT Mobile UI and Glass Theme Implementation

Design specification and development plan

Prepared for Emanuel Melo  \|  17 October 2026  \|  Specification 1.0

Repository baseline: main at 7060f3245cb4

Application declaration: version 0.9.53.0, build 122. Android minimum API 31 and target API 37. [R30]

1 Design direction

Introduce one shared glass material for transient menus, dialogs and bottom sheets. Its background must be 85% opaque and tinted by the active theme. Keep enabled labels, icons, brand artwork and controls crisp and fully opaque. The visual character should come from consistent spacing, rounded shapes, quiet borders and restrained depth.

The first release should apply this material across the existing UI architecture. It should preserve the current theme catalogue, custom colours, navigation, profile identities and consent actions while bringing their foreground surfaces into one coherent system. Add backdrop blur where the renderer supports the required bounds and meets performance checks.

| Decision | Development contract |
| --- | --- |
| Panel opacity | 0.85 background alpha in Glass mode, applied once. |
| Theme relationship | Derive the fill from the final resolved theme surface. Use theme accent for selected actions and subtle edges. |
| Blur | Auto when a verified renderer is available; graceful 85% tinted fallback when it is not. |
| Icons | Consistent rounded action family; explicit semantic colours; original brand and app emblem artwork. |
| User controls | Display offers Glass or Solid and background blur Auto or Off. These are global appearance settings. |
| Delivery scope | Shared material and wrappers, direct migration of custom overlays, truthful previews, persistence and verification. |

All geometry, blur, motion and colour treatment values below are proposed design tokens. The fixed 85% Glass opacity is the product requirement. Source references identify current behaviour; implementation sections specify the intended changes.

2 Visual references

Figure 1  Proposed menu, dialog and archive sheet treatments. These are illustrative design references. The same material follows dark, light and alternate theme colours.

Glass should read as a calm foreground panel. The 15% background contribution gives context without competing with the task. A light theme uses a pale tinted surface and dark text; a dark theme uses a deep tinted surface and light text. Avoid a bright saturated accent covering the entire panel. Use the accent to guide attention to selection, focus and the primary action.

Figure 2  Rendering order. Blur belongs to the sampled background layer; the single tinted fill and sharp foreground are separate drawing operations.

3 Current system and implementation gaps

Theme and layout foundations

The current catalogue contains 32 built-in choices: 22 base presets plus 10 paired renovation presets. Cyan remains the default. The current editor tabs are Gallery, Customize, Display and My Themes. The October 12 design document describes an older catalogue and tab arrangement; extend the current implementation. [R1] [R2] [R3] [R40]

AppearanceEditorViewModel already manages an isolated draft, Apply, Discard, Save, Undo, restored state and conflicts. This is the correct integration point. AppDesign already centralizes 8, 12, 16 and 20dp spacing, a 48dp minimum touch target, and card, panel and input corner radii. Add overlay tokens to this foundation. [R4] [R7]

| Area | Confirmed current behaviour | Design implication |
| --- | --- | --- |
| Theme resolution | Dynamic wallpaper colours take precedence over a custom palette, then custom primary, then defaults. Neutral surfaces receive a 16% accent mix. [R5] | Resolve glass after the theme. Reuse existing tint; avoid an additional blanket accent wash. |
| Foreground contrast | Neutral surface tinting runs after foreground roles are assigned. Current contrast checks use theme surfaces. [R5] [R19] | Validate the final glass composite and derive readable overlay foreground roles. |
| Preview fidelity | Gallery rows paint raw palette values; the app uses the full resolver. [R3] [R5] | Use the same pure resolver and material tokens in gallery, preview and applied UI. |
| Shared overlays | Fading wrappers provide dialogs, alerts, menus and sheets, with a 500ms dismissal fade. Styling parameters are limited. [R6] | Extend the wrappers with shared material and lifecycle contracts. |
| Custom overlays | Bubble actions, sources, profile selection, archive and MCP UI also have raw or custom windows and solid plates. [R10] [R11] [R12] [R13] [R14] | Migrate those plates directly; wrapper changes alone cannot complete the feature. |
| Icon defaults | ThemeIcon promotes an unspecified inherited surface icon colour to primary. Multicolour artwork needs a separate path. [R8] | Introduce explicit action and brand rendering roles, with compatibility for existing callers. |

Icon and surface inventory

The pinned recursive repository tree contains 285 presentation Kotlin files, 148 drawable and drawable-nodpi resources, 62 chat_source assets, 52 mcp_brand assets and seven custom presentation icon files. These counts describe files, not visible icon instances or verified use. The component inventory in Section 6 identifies confirmed migration points.

ThemedAppIcon already preserves the emblem artwork while tinting its circular badge. SourceLogo also distinguishes original multicolour marks from monochrome artwork. MCP service rendering currently applies primary tint to all mapped assets, including multicolour resources; add equivalent rendering metadata there. [R9] [R11] [R10] [R34]

Classify a surface by its on-screen role. The marketplace compatibility function named McpMarketplaceDialog opens a full-screen workspace, and FavoriteDetailDialog is a full-screen reading view. Their nested transient controls are glass candidates. [R38] [R16]

4 Glass material specification

Opacity and colour

At rest, the unobstructed panel background has alpha 0.85. Its foreground content uses alpha 1.0. Apply alpha to the fill colour, never to the entire popup subtree. The existing dismissal animation may animate the group while it exits; that animation is separate from the resting material opacity.

Take the opaque RGB value of the final resolved surfaceContainerHigh as the initial glass base. The current resolver already tints neutral surfaces, so the first implementation should use this colour directly. If visual review requires a different tint, adjust the derived RGB once in the token resolver and validate it across every preset. Do not paint an extra accent rectangle over the 85% fill. [R5]

Modal scrims are independent of the glass fill. The proposed starting scrim opacity is 0.28 in dark themes and 0.20 in light themes, with one scrim owner. Anchored menus use no added full-screen dim by default. Stronger focus treatment, if needed, should be tuned separately and tested for readable content behind the modal.

| Token | Proposed value | Scope |
| --- | --- | --- |
| glassBackgroundAlpha | 0.85 | Exactly one full-area background fill in Glass mode. |
| solidBackgroundAlpha | 1.00 | Explicit Solid style only. |
| foregroundAlpha | 1.00 | Enabled text, action icons, focus cues and brand art. |
| menuCornerRadius | 20dp | Transient anchored menus and bubble action menu. |
| dialogCornerRadius | 28dp | Floating dialogs and modal pickers. |
| sheetTopCornerRadius | 28dp | Top corners of modal sheets. |
| inputCornerRadius | 16dp | Reuse existing AppShapes input token. |
| decorativeBorderWidth | 1dp | Subtle edge, starting at outlineVariant alpha 0.35. |
| focusIndicatorWidth | 2dp | Visible keyboard and accessibility focus, contrast at least 3:1. |
| tonalElevation | 0dp | Prevent an extra Material tonal fill on the glass plate. |
| shadowElevation | Menu 8dp; dialog 12dp; sheet 8dp | One soft shadow owner; tune in device visual review. |
| blurRadius | Start at 16dp; cap native radius at 80px | Renderer-specific tuning; convert dp to px explicitly. |
| contentPadding | Dialog and sheet 24dp; menu 16dp horizontally | Menu list vertical padding 8dp; item gap tokens stay shared. |

The border is decorative and may be subtle. Focus rings, informative icons and control cues must meet their own contrast target. Preserve full opacity on controls even when their fill is a quieter tone. An inset argument block or product image may use a solid local surface for readability; it must not silently replace the entire glass plate.

Layer ownership

Draw eligible background content, then its correctly positioned and clipped blurred sample when supported.

Paint the single theme-tinted panel background at alpha 0.85.

Draw border, text, icons, controls and imagery above that fill.

Assign the fill to either the native window background or the Compose surface. Every child that covers the same whole panel must be transparent.

Disable duplicate automatic tonal overlays and duplicate scrims. Keep intentional child controls distinct from the root plate.

Two stacked 85% full-panel fills produce 97.75% effective opacity. A new glass background hidden beneath an old opaque Surface produces no visible transparency. Both defects need a compositing check, because checking one colour constant cannot detect them.

Readability across themes

Use overlay content colours derived from the visible composite. Start with the theme foreground and adjust its lightness when necessary. If no foreground passes against the required backdrop range, shift the derived panel base toward a lighter or darker tone while preserving its theme relationship, then test again. Keep the stored user palette unchanged and the Glass alpha at 0.85. Provide a clear Solid choice and an accurate preview. A blur capability change must not silently persist a different appearance preference.

Test ordinary text at 4.5:1 and large text at 3:1 using the applicable size definition. Informative icons and essential control or focus cues should reach 3:1 against adjacent colours. These are acceptance benchmarks for this design; opacity alone cannot establish contrast. Test dark, light, saturated, patterned and image backdrops after compositing. [A9] [A10]

5 Icon typography and control system

Icon roles

Use a rounded action icon family throughout the menus and controls. Keep existing Rounded icons through the material migration, then replace required action assets with locally imported Material Symbols Rounded in a separate measured cleanup. Android now recommends imported Symbols rather than expanding the older Material Icons artifact. Centralize the selected assets so unrelated screens do not acquire different weights and silhouettes. [A7]

| Role | Colour and artwork rule | Geometry and semantics |
| --- | --- | --- |
| Ordinary action | Explicit overlay content colour. Theme accent for selected or primary actions. | 24dp glyph in a minimum 48dp target; 12dp icon-to-label gap. |
| Destructive action | Theme error role with verified composite contrast. | Same geometry as neighbouring rows. Retain a clear Delete label. |
| Disabled action | Purposeful disabled foreground and disabled semantics. | No second inherited 0.85 multiplier; touch action disabled. |
| Brand or provider | Original multicolour art, or an explicitly approved monochrome treatment. | Preserve aspect ratio; typically 28dp artwork in a 40dp visual badge and 48dp target. |
| App emblem | Retain ThemedAppIcon artwork and theme-coloured circular badge. [R9] | Keep centred square fitting; no stretched circle or global colour filter. |
| Local and gateway | Retain meaningful server and tool identities. [R33] | Use explicit category metadata where available, with a stable fallback. |
| Status | Theme state or existing profile identity colour plus icon and label. | Check, error, progress or paused shape makes state understandable without colour alone. |

Introduce AppActionIcon with explicit Normal, Accent, Selected, Destructive and Disabled roles, plus BrandMark with Original or Monochrome render modes. Keep ThemeIcon as a compatibility adapter during migration. Its current unspecified-tint policy is relied on by existing screens, so changing its default globally would create unreviewed recolouring. [R8]

Reuse the SourceLogo rendering distinction in MCP assets and preserve the source and brand provenance records. A theme-tinted badge can relate a brand to the UI while the artwork keeps its original colours. Preserve product image colours and neutral image isolation surfaces in Amazon details. [R11] [R34] [R35] [R36] [R15]

Text and interaction density

Reuse the app typography and existing user font settings. Default menu labels should use the equivalent of bodyLarge, typically 16sp, with secondary information at bodyMedium, typically 14sp. Dialog titles use titleLarge, typically 22sp. These describe roles; they must still respect the app font scale and system font size. Avoid adding a second font or decorative title icon to every dialog.

Use 52dp as the normal single-line menu row starting height, expanding for wrapped text and large fonts. Maintain at least 48 by 48dp interactive targets. Icon-only actions need localized purpose labels, while decorative icons beside a spoken label should avoid duplicate announcements. Directional icons must mirror correctly for right-to-left layouts. [A8]

6 Component migration inventory

Migrate the outer plate and its rendering ownership while retaining each component's behaviour. Review inner full-size surfaces as part of every migration. The following are confirmed source-level entry points; a discovery pass must register remaining call sites before completion.

| Priority and component | Current entry point | Implementation requirement |
| --- | --- | --- |
| P1 Shared alerts | FadingAlertDialog in common/FadingPopups.kt [R6] | Expose container/content roles and shared shape. Preserve confirm, dismiss, focus and semantics. |
| P1 Shared menus | FadingDropdownMenu [R6] | Apply one fill and edge. Retain anchoring, scrolling, properties and menu dismissal. |
| P1 Shared dialogs | FadingDialog [R6] | Provide a material container contract; migrate caller-owned plates explicitly. |
| P1 Shared sheets | FadingModalBottomSheet [R6] | Set container material and one scrim owner; preserve drag, insets and back behaviour. |
| P1 Home overflow | HomeScreen selection menu [R16] | Glass plate with Pin, Unpin, Archive and Delete. Overflow stays selection-only. |
| P1 Bubble actions | BubbleActionMenu raw Popup [R12] | Replace custom plate; preserve touch origin, viewport clamping, stable identity and focus. |
| P1 Tool consent | ToolPermissionDialogs [R27] | Shared alert material; sharp permission wording, icons, arguments and existing allow/deny actions. |
| P1 Chat settings | ChatDialogs [R28] | Migrate root wrappers and inspect opaque tab/body layers and nested controls. |
| P1 Profile picker | SelectPlatformDialog and help dialog [R13] | Migrate raw windows and plates; retain model selection, search, labels and keyboard handling. |
| P1 Sources | SourceListDialog in ChatSourcePicker [R11] | Migrate the animated plate; preserve source origin, groups, brand art and dismissal. |
| P2 Archive | ArchivedConversationsBar [R14] | Replace raw sheet plate; retain icon-only trigger and half-height behaviour. |
| P2 MCP overlays | McpMarketplaceDialog implementation [R10] | Migrate filters, menus, alerts and configuration. Classify full-screen content separately. |
| P2 Product details | AmazonProductDetailDialog [R15] | Replace root Surface; preserve gallery, product colours, paging and primary action. |

Remaining surface families

The inventory pass should trace ChatExportDialog, ChatProfileManagementDialog, CloudModelPickerDialog, LocalModelDownloadDialogs, LlamaModelPickerDialog, NvidiaModelPickerDialog, OpenRouterModelPickerDialog, McpServerPickerDialog, PluginConfigurationDialog, ConnectionDoctorDialog, CompleteBackupDialog, ResearchArticleDialog and ResearchComparisonDialog. Their filenames locate candidates; window type, root plate, inset handling and migration reach must be confirmed at each call site.

Keep long-reading workspaces, the main screen, settings pages and chat history on normal theme surfaces. Glass applies to their transient foreground controls. Preserve the icon-only New Chat action, the profile dock below the composer, conversation selection behaviour, and profile/status colours. Combined-answer attribution details retain their existing debug-only policy. [R16] [R17] [R18]

7 Compose implementation architecture

Shared material and host contracts

Separate appearance intent from drawing capability. The persisted choice describes Glass or Solid and Auto or Off blur. Runtime policy chooses a renderer for the currently opened surface. Colours and geometry come from a pure immutable token resolver after GPTMobileTheme resolves the active colour scheme.

| Module or contract | Responsibility |
| --- | --- |
| OverlayAppearance in data/model | Stable string values for surface style and blur preference; tolerant parsing with backward-compatible defaults. |
| OverlayMaterialTokens in presentation/theme | Opaque base RGB, single fill alpha, readable content roles, outline and scrim values. |
| AppDesign overlay tokens | Extend existing spacing and shape definitions with menu, dialog, sheet and icon roles. [R7] |
| OverlayRenderPolicy | Select Tinted, NativeWindowBlur or validated SharedBackdrop from capability, preferences and surface type. |
| AppGlassSurface | Own one fill, shape, edge and content colour. Accept already prepared background rendering. |
| Fading popup adapters | Retain public entry points while forwarding material, properties and lifecycle to the new implementation. |
| Optional GlassBackdropHost | Record eligible app background once and supply correctly clipped samples for supported overlays. |
| AppActionIcon and BrandMark | Explicit action semantics and original versus monochrome artwork. Keep ThemedAppIcon dedicated. |

Keep ChatAlphaTokens for chat-specific elements. The requested 85% panel opacity must not replace chat bubble, trace, metadata or profile pending-state alpha values. [R39]

Illustrative surface contract

The following sketch defines ownership rather than a complete implementation. Resolve the token object once for the active scheme. Confirm concrete component signatures against the resolved dependencies before coding.

@Composablefun AppGlassSurface(    tokens: OverlayMaterialTokens,    shape: Shape,    content: @Composable () -> Unit) {    Surface(        color = tokens.opaqueBase.copy(alpha = tokens.fillAlpha),        contentColor = tokens.content.copy(alpha = 1f),        shape = shape,        border = BorderStroke(1.dp, tokens.edge),        tonalElevation = 0.dp,        shadowElevation = tokens.shadowElevation    ) {        content()    }}

When a native window owns the fill, the Compose container instead uses a transparent colour. Do not call this tinted surface inside a second tinted native background. The backdrop renderer must draw beneath the content rather than applying an effect to this entire Surface. Keep tint in Compose unless the native path's entry, exit and movement have been validated; a Compose group alpha or transform does not automatically affect the native background or blur.

Renderer compatibility

| Surface type | Baseline rendering | Blur implementation and constraint |
| --- | --- | --- |
| Bounded floating dialog | Single 85% Compose fill and existing dialog semantics. | Native Window background blur is suitable when the floating translucent window bounds and drawable match the panel. [A2] |
| Anchored popup or dropdown | Single 85% fill with the existing position provider. | A Compose popup uses a separate WindowManager root. Do not assume a dialog Window or treat whole-screen blur-behind as bounded glass. [A12] |
| Material bottom sheet | 85% container with existing sheet state and scrim. | The Android sheet uses a full-screen dialog root. Native window blur is not automatically bounded to the sheet. [A13] |
| Same-window custom overlay | 85% fill above the existing content. | Requires an explicitly sampled, translated and clipped backdrop. Blur on the overlay itself affects its own content. [A6] |

Native blur integration

The app minimum SDK is already 31. Window background blur is available from that API level, but availability also depends on device and runtime state. Use WindowManager.isCrossWindowBlurEnabled and a listener that is removed when the owning window is disposed. Convert dp tokens to pixels and match the window background drawable to the intended rounded outline. [R30] [A2] [A3]

The AOSP guidance distinguishes bounded background blur from blur behind the whole window. Its illustrative frosted radius is 80px, and it warns that large radii increase GPU work. Start with the capped design token and tune on the target device. Battery saving and other system states can disable blur while a window is open. Preserve the tint fallback and keep the capability change out of saved preferences. [A1]

Bounded backdrop proof of concept

Before promising identical true blur on menus and sheets, implement a small spike covering one anchored menu, one sheet and one dialog. Compare a shared Compose graphics layer approach against the existing window architecture. A shared layer can record eligible background drawing once; each overlay then redraws the correctly translated region, blurs it, clips it, paints its one tint and draws sharp foreground content. This is a project renderer proposal built on drawing primitives. [A5]

Exclude the overlay itself from the recorded source so it cannot sample itself recursively.

Verify offsets after scroll, IME appearance, rotation, split-screen and changes in density or window insets.

Define which surfaces can be sampled. SurfaceView, video, embedded views or protected surfaces require explicit validation and may use the tinted fallback.

Avoid CPU screenshots and toImageBitmap capture on every frame. Release layers and listeners after dismissal.

Retain one active expensive backdrop operation where possible. For stacked overlays, define source ownership and avoid accidental repeated blur.

If the spike cannot preserve existing focus, anchoring or gesture behaviour, keep that component on the 85% tinted fallback until a measured renderer is ready.

Dependency boundary

The repository declares Compose BOM 2026.09.00 and a Material 3 version entry of 1.4.0. These declarations do not establish the final resolved version because dependency constraints can affect selection. Check dependencyInsight before choosing APIs. [R29]

The current Compose UI release notes place the newer DialogProperties blur/window-shape conveniences and PopupProperties blur-behind controls in the 1.13 prerelease line. Do not assume they are present in this app or upgrade the entire UI stack solely to obtain them. Isolate any stable platform bridge and make a dependency upgrade a separately validated change. [A4]

./gradlew :app:dependencyInsight \  --configuration debugRuntimeClasspath \  --dependency androidx.compose.ui./gradlew :app:dependencyInsight \  --configuration debugRuntimeClasspath \  --dependency androidx.compose.material3

8 Theme editor persistence and backup

Simple user controls

Under Display, add a Menus and Popups section with Background style options Glass and Solid. Glass is the default and its supporting text states 85% opacity. Add Background blur with Auto and Off. Keep radius, tint strength and rendering diagnostics out of the normal settings flow. A local preview should show a menu and dialog using the selected theme and material.

These settings are global in the first release. Selecting another colour preset retains the Glass/Solid and Auto/Off choices. Save a theme retains the existing palette and mode semantics. Apply commits the appearance draft; Save does not activate it. The UI can explain once that popup style applies to all themes. [R4]

Draft and identity handling

Add defaulted material and blur fields to ThemeSetting and AppearanceDraft. Include them in from, applyTo, dirty-state checks, SavedState restoration, conflict checks and Undo.

Change activeProfileId to custom only when palette, mode, dynamic selection or other profile identity fields change. A glass-only edit must keep Cyan or the current preset name.

Update select(profile) to copy palette fields into the existing draft and retain global material preferences. It currently constructs a fresh draft. [R4]

Reset appearance defaults explicitly to Cyan, dark mode, dynamic OFF, Glass and blur Auto, preserving the saved profile collection.

Use the pure colour resolver in the preview and gallery. Do not call the full Activity-affecting GPTMobileTheme inside the preview; it also updates system-bar state. [R5] [R3]

Provide the selected material through ThemeSettingProvider and derive overlay tokens at the app theme host. [R31] [R32]

Persistence wiring

Store stable string keys such as overlay_material and overlay_blur in the existing DataStore mutation. Parse unknown field values independently so a malformed material value does not discard a valid palette. Fresh installs and older appearance payloads use Glass and Auto defaults for missing new fields. Do not persist runtime blur capability or device performance state. [R20]

| Path | Required update |
| --- | --- |
| ThemeSetting and SettingDataSourceImpl | Add defaulted fields and atomic read/write through the existing theme mutation. |
| ThemeBackupDto and SettingRepositoryImpl | Round-trip both fields through direct configuration JSON export and import. [R21] [R22] |
| UiPreferencesBackupDto and AppBackupManager | Map both fields through theme and UI-preference payloads. The entire UI-preference appearance wins when present. [R23] [R24] |
| CompleteBackupPreferences | Generic key capture already includes new DataStore keys; verify Settings-section restoration. [R26] |
| LegacyBackupConverter | Extend material mapping and preserve savedProfiles and activeProfileId, which the current legacy mapping omits. [R25] |

For explicit backup DTOs, use the entire uiPreferences appearance payload when present, including explicit null values; otherwise use theme. Align legacy conversion with this rule instead of mixing nullable fields from both sources. Apply the chosen appearance atomically. Test disagreeing payloads and an explicit null custom palette. If neither appearance representation exists, retain the current appearance.

Full Settings snapshots have different semantics: if Settings is absent, retain current settings; if Settings is restored, replace the complete settings snapshot. Missing new overlay keys in an older snapshot then resolve to Glass and Auto. This preserves the existing full-backup replacement contract. No new Room table is required for these appearance preferences. [R26]

9 Interaction responsive layout and motion

Window and layout behaviour

A menu begins near its trigger or touch point and stays inside the usable window. Retain BubbleActionMenu's existing offset and clamping logic. Start with its current 224dp width, allow a preferred 240dp where available, and cap expansion at the usable width with 16dp margins. Large text should wrap and increase row height; the menu scrolls when its content exceeds the available height. [R12]

Dialogs use at least 16dp safe horizontal margins and a 560dp maximum content width, matching the existing profile picker cap. Dense content scrolls within the modal while its essential actions remain reachable above the IME. Sheets use the available safe width on phones and a bounded width on larger windows. Preserve the archive's half-height presentation and its own scrolling and dismissal behaviour. [R13] [R14]

The material follows the actual clipped panel bounds during movement. Test edge-to-edge layout, gesture navigation, three-button navigation, display cutouts, landscape and split-screen. Preserve the profile dock below the composer and allow keyboard changes to follow the existing inset policy. [R17]

Dismissal and focus

Use a shared Hidden, Entering, Visible and Exiting lifecycle. New openings reset opacity and animation state. Dismiss callbacks run once, and the window is removed when the exit finishes. Preserve each surface's current back, outside-tap, close-button and swipe permissions. Returning focus to the invoking control should not scroll the conversation unexpectedly.

The existing wrappers fade dismissal over 500ms. Preserve the established back and swipe-back experience during the first migration and centralize its token. Use a proposed 220ms entry for new glass presentation, while keeping existing source-origin and bubble-menu transforms where appropriate. Respect disabled or reduced animation settings. The glass resting opacity must remain independent of the animated group alpha. [R6] [R11] [R12] [R37]

For a native window renderer, synchronize the background, blur visibility and rounded outline with the overlay lifecycle and geometry. The native plate must not remain on screen while only its Compose content fades. Verify entry, dismissal and any origin transform as one coherent transition.

Confirm actions should commit at the existing semantic event, once. A fade must not duplicate tool permission approval, delay a network operation unnecessarily, or allow a second tap to submit twice. Where buttons currently bypass wrapper dismissal, route them deliberately through the shared lifecycle rather than adding a competing animation.

Accessibility semantics

Retain dialog pane titles, focusable menu behaviour, TalkBack order, selected/disabled state semantics and clear button names. Trap focus appropriately in a modal; return it on close. Test keyboard and directional-pad use as well as touch. Original brand artwork may be decorative when the adjacent provider label already communicates identity. [A8]

10 Development work packages

Implement in the following dependency order. Each package has a concrete completion condition so the work can be reviewed in small changes without losing the app-wide goal.

| Package | Work | Completion condition |
| --- | --- | --- |
| 1 Inventory and baseline | Pin a development commit; enumerate shared and raw windows, root plates, child covers, focus and gesture owners. Capture reference screens and resolved dependencies. | Every in-scope overlay has an owner and migration record. Representative baseline captures and timing data exist. |
| 2 Material and icon contracts | Extend AppDesign; add pure OverlayMaterialTokens, Glass/Solid and blur preferences, AppGlassSurface and semantic icon adapters. | 0.85 and 1.0 states pass compositing/contrast checks; no inherited content fade or default logo recolouring. |
| 3 Shared and custom overlays | Migrate Fading wrappers, then P1 direct bypasses and P2 components. Remove competing full-panel fills and scrims. | All registered overlays use the material or a documented full-screen classification. Core interactions pass. |
| 4 Blur renderer | Prove bounded dialog, menu and sheet paths; add capability observation and fallback; measure during chat streaming. | Blur bounds, sharp content, disposal and performance pass on supported targets; fallback stays usable and at 85%. |
| 5 Editor and backups | Wire global controls through draft, preview, theme locals, DataStore and every explicit backup mapping. Correct legacy profile mapping. | Preset identity, Apply/Save, Undo, old payloads, unknown values and all restore routes pass. |
| 6 Visual and release review | Review theme matrix, text sizes, icons, states, motion, gesture navigation, slow/unsupported blur and release build size. | Acceptance matrix is complete; remaining platform limitations are represented by tested fallbacks. |

Suggested code ownership

Paths in this table are relative to app/src/main/kotlin/dev/chungjungsoo/gptmobile. Screen-specific migration files are listed in Section 6.

| Existing area | Files or additions |
| --- | --- |
| Design foundation | presentation/designsystem/AppDesign.kt; presentation/theme/OverlayMaterialTokens.kt, PaletteContrast.kt and Motion.kt. |
| Rendering | presentation/common/FadingPopups.kt; new common/AppGlassSurface, OverlayRenderPolicy and optional GlassBackdropHost under presentation. |
| Icons | presentation/common/ThemeIcon.kt compatibility; new AppActionIcon and BrandMark beside it; presentation/ui/mcp/McpBrandAssets.kt; existing SourceLogo and ThemedAppIcon. |
| Appearance state | data/model/OverlayAppearance.kt; data/dto/ThemeSetting.kt; presentation/ui/setting/appearance/AppearanceEditorViewModel.kt. |
| Appearance UI and host | presentation/ui/setting/ThemeSettingsScreen.kt; presentation/common/ThemeSettingProvider.kt; presentation/theme/Theme.kt; presentation/ui/main/MainActivity.kt. |
| Persistence and recovery | data/datastore/SettingDataSourceImpl.kt; data/dto/ConfigBackupDto.kt; data/repository/SettingRepositoryImpl.kt; data/backup/AppBackupModels.kt, AppBackupManager.kt and LegacyBackupConverter.kt. |
| Screen migrations | The P1/P2 entry points in Section 6 and additional registered dialog, menu and sheet call sites. |

Keep broad dependency upgrades, unrelated screen rearrangements and icon-library removal in separately reviewable changes. The first material rollout can reuse the current action assets. Measure any later APK size reduction rather than promising savings from an import change alone.

11 Verification and release criteria

Visual and functional acceptance matrix

| Case | Expected result |
| --- | --- |
| Opacity ownership | In Glass mode, a blank panel region composites one 0.85 fill over known dark and light fixtures. No 0.9775 double fill or opaque cover. |
| Foreground sharpness | Enabled glyphs, labels and imagery retain full opacity and sharp edges. Blur samples only eligible background. |
| Preset coverage | All 32 presets remain selectable with stable IDs. Test every preset numerically; capture representative dark, light, neutral and saturated themes. |
| Custom and wallpaper colours | Glass, gallery and preview follow the final resolver after dynamic, custom or system-mode changes. Stored palette values remain intact. |
| Contrast | Composite checks meet 4.5:1 for ordinary text and 3:1 for informative controls; adverse image and patterned backgrounds remain readable. |
| Editor behaviour | Glass-only edits preserve preset labels. Selecting a preset preserves global material. Apply, Save, Discard, Undo and restored drafts retain their contracts. |
| Backup and migration | Direct JSON, theme DTO, UI preferences, full Settings backup and legacy converter preserve appearance and saved profile identities. |
| Unknown and missing values | Bad fields fall back independently. Absent DTO appearance or full-backup Settings sections retain current values; restored old Settings snapshots use defaults for new keys. |
| Icons and artwork | Rounded actions align consistently; error/disabled/selected states remain distinct. App emblem and original brands preserve colours and aspect ratio. |
| Menu and modal behaviour | Back, outside tap, close, swipe, focus restoration and action callbacks behave once. Menus remain on-screen and correctly anchored. |
| Reading and input | At 1.0, 1.3, 1.5 and 2.0 font scales, labels wrap, controls remain reachable, content scrolls and the IME does not cover actions. |
| Capability change | While a panel is open, blur on/off, battery saving and recreation preserve a readable 85% Glass fallback and saved preference. |
| Lifecycle and stacking | Rapid open/close/reopen, nested controls and navigation leave no transparent stuck window, extra scrim, retained layer or listener. |
| Existing navigation | New Chat stays icon-only; overflow stays selection-only; Pin/Unpin/Archive/Delete work; profile dock stays below composer. |

Tests to extend

Extend PaletteContrastTest, ThemeAndBackupDefaultsTest, AppearanceContractsTest and SettingRepositoryConfigBackupTest with the material and migration cases. Add focused overlay compositing snapshots, BubbleActionMenu instrumentation and backup-converter regression coverage. Use high-contrast fixtures and known RGB values so the visual tests catch an accidentally opaque child Surface instead of merely repeating implementation constants.

For the alpha fixture, disable blur and scrim and render a blank panel region over two known opaque backdrops. Compare both captures with the expected 85% composite calculated in the renderer's defined colour space, allowing a documented 8-bit rounding tolerance. Test blur bounds and content sharpness separately.

Performance acceptance

Measure the baseline and candidate in the same release-like benchmark configuration on the target ROG Phone 9 Pro and at least one API 31 or lower-capability test device. Exercise opening and closing menus, dragging sheets, keyboard transitions and live local/remote chat output behind the overlay. Include the device's actual supported refresh modes rather than using a single 60Hz assumption.

Use FrameTimingMetric percentiles, particularly frameOverrunMs on API 31 and above. Positive overrun indicates a missed deadline. Proposed acceptance is no repeatable p95 regression beyond the measured baseline variance, no new sustained stutter, and no continuing render work after dismissal. Compare multiple matched runs before attributing a difference to blur. [A11]

Check retained layers, listeners and memory after repeated dismissal. Inspect CPU/GPU work during streaming; local inference may compete for device resources. If a particular renderer fails the measured gate, route that surface to the tinted fallback. Do not continuously capture the full screen or animate blur radius for decoration.

Completion and rollback

The feature is complete when the registered component inventory is migrated, appearance and backup checks pass, representative screenshots have been reviewed, and supported renderer paths meet the performance criteria. Keep renderer selection separate from stored material so blur can be disabled without losing colour themes, saved profiles or the 85% glass treatment. Solid remains an explicit user selection.

Record the implementation commit, resolved UI dependencies, tested device/API/refresh modes, image captures and performance comparison in the development review. Execute the repository's relevant build, unit, instrumentation and lint gates before release. This document defines those requirements and does not represent their execution.

12 Source register

Repository references are pinned to 7060f3245cb4012ed71043a09aa5595f9629563b. Android and accessibility references were reviewed on 17 October 2026. References in the body link directly to the relevant source; the register provides their titles for review.

Repository sources

[R1] ThemeSetting and the base preset catalogue

[R2] The ten paired renovation presets

[R3] Theme editor tabs and gallery preview

[R4] Appearance draft and Apply Save Undo contracts

[R5] Theme resolution and neutral surface tinting

[R6] Shared fading popup wrappers

[R7] Existing spacing and shape tokens

[R8] ThemeIcon default tint policy

[R9] App emblem and circular theme badge

[R10] MCP overlay and service logo rendering

[R11] Source logo rendering and animated source dialog

[R12] Bubble menu positioning and surface

[R13] Profile picker dialog and help dialog

[R14] Archive trigger and half height sheet

[R15] Amazon product detail dialog

[R16] Home selection menus and New Chat action

[R17] Composer and bottom profile dock placement

[R18] Profile dock state and attribution colours

[R19] Existing palette contrast checks

[R20] Theme DataStore keys and atomic mutations

[R21] Direct configuration export and import

[R22] Backup theme payload

[R23] Full backup manager theme and UI preference mappings

[R24] Full backup UI preferences model

[R25] Legacy backup conversion

[R26] Generic DataStore backup encoding

[R27] Tool permission dialogs

[R28] Chat configuration dialogs

[R29] Declared Compose and Material dependency versions

[R30] Android SDK and application version declarations

[R31] Theme composition locals

[R32] Activity theme host

[R33] Tool category icons

[R34] MCP asset mapping

[R35] MCP asset provenance

[R36] Source icon provenance

[R37] Current theme motion module

[R38] Marketplace compatibility adapter

[R39] Existing chat alpha tokens

[R40] October 12 theme design document

Platform and accessibility sources

[A1] Android Open Source Project window blur guidance

[A2] Android Window background blur API

[A3] Android WindowManager blur capability and listener API

[A4] Compose UI release notes and new window properties

[A5] Compose graphics layer and drawing modifiers

[A6] Compose image customization and content blur

[A7] Compose icon guidance and Material Symbols

[A8] Compose accessibility defaults

[A9] WCAG 2.2 minimum text contrast

[A10] WCAG 2.2 non text contrast

[A11] Android Macrobenchmark FrameTimingMetric

[A12] AndroidX popup window implementation

[A13] AndroidX Material bottom sheet window implementation
