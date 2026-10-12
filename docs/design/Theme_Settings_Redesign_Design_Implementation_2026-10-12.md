# Theme Settings Redesign
## Design and Implementation Specification

**Repository:** `tailscale-signin/GPT_Mobile_AI-improved`  
**Date:** October 12, 2026  
**Audited baseline:** `main` at `7553e4ff14316f0d28639d5cc0ed24cb59511066`  
**Status:** Proposed implementation design. This change adds documentation only.  
**Scope:** The Appearance / Theme Settings experience, its editors and dialogs, theme state, saved themes, search navigation, and backup compatibility.

> Create a calm, polished theme workspace with four meaningful tabs: **Style**, **Colours**, **Display**, and **Saved**. Keep every existing theme capability. Separate browsing from applying, editing from saving, and saved-theme management from the built-in collection.

This specification is based on a static review of the repository files cited in Section 21. It does not claim that the redesigned screen has been implemented, rendered, benchmarked, or tested on a device. Recheck the audited files against the implementation branch before making code changes. This is a focused supplement to the broader UI design documents already under `docs/design`, not an instruction to replace unrelated app screens.

---

## Contents

1. [Product direction and scope](#1-product-direction-and-scope)
2. [Current implementation audit](#2-current-implementation-audit)
3. [Research-backed design decisions](#3-research-backed-design-decisions)
4. [Information architecture and tabs](#4-information-architecture-and-tabs)
5. [Visual system and screen shell](#5-visual-system-and-screen-shell)
6. [Style tab](#6-style-tab)
7. [Colours tab and colour picker](#7-colours-tab-and-colour-picker)
8. [Display tab](#8-display-tab)
9. [Saved tab](#9-saved-tab)
10. [Adaptive layout](#10-adaptive-layout)
11. [Interaction and action contracts](#11-interaction-and-action-contracts)
12. [State and preview architecture](#12-state-and-preview-architecture)
13. [Persistence, migration, and backup](#13-persistence-migration-and-backup)
14. [Navigation and search compatibility](#14-navigation-and-search-compatibility)
15. [Repository implementation map](#15-repository-implementation-map)
16. [Accessibility and readable colours](#16-accessibility-and-readable-colours)
17. [Performance and reliability](#17-performance-and-reliability)
18. [Acceptance tests](#18-acceptance-tests)
19. [Implementation sequence](#19-implementation-sequence)
20. [Definition of done and exclusions](#20-definition-of-done-and-exclusions)
21. [Evidence and references](#21-evidence-and-references)

---

## 1. Product direction and scope

### 1.1 The intended experience

The page should feel like one deliberately designed workspace rather than several settings forms assembled together. The user should immediately understand the current appearance, see the result of a proposed change, and find the correct controls without scrolling through unrelated settings.

The visual direction is restrained: consistent rounded surfaces, generous spacing, a clear type hierarchy, thin selection indicators, and theme colour used primarily for important actions and selected states. Avoid large decorative gradients, repeated hero headings, coloured text everywhere, or a separate border around every setting. The colour wheel is functional colour content, not decoration.

Preserve the current Cyan default and the user's chosen palette. Do not reset existing installations or replace their colours merely to demonstrate the new layout. The default palette is currently cyan `#00FFDE`, violet `#CC00FF`, background `#001219`, and card surface `#0B2028` in `ThemePresets.defaultProfile`. These values are existing product identity, not a new proposal. [R4]

### 1.2 Required outcomes

- A stable, visible tab strip with four clearly differentiated purposes.
- One consistent preview component that uses the same resolved colours as the app.
- Clear distinction between **Active**, **Previewing**, and **Saved** states.
- No loss of presets, custom colours, colour-wheel controls, display modes, wallpaper colours, saved themes, or backup data.
- No accidental application, overwrite, or deletion while browsing or editing.
- A layout that remains usable with large text, keyboard input, TalkBack, landscape, and multi-window operation.

### 1.3 Boundaries

This is not a redesign of model settings, research controls, benchmarks, or the complete app navigation. Do not populate the Display tab with unrelated settings merely to make it longer. Existing reading and motion controls remain owned by Advanced Settings unless a later, separately scoped change deliberately relocates them with search compatibility.

No new account, remote theme marketplace, online palette generator, wallpaper-reading permission, or paid dependency is required. This design uses the app's existing local theme functionality and Android/Compose components.

## 2. Current implementation audit

The following are source-level observations at the audited commit. Consequences described as risks require tests; they are not claims of reproduced device failures.

| Priority | Observed implementation | Redesign response |
|---|---|---|
| P1 | `ThemeSettingsScreen` has Presets, Custom, and Display, but `SettingsTabs` renders horizontally scrolling `FilterChip` controls. [R1, R7] | Use actual tab semantics and a dedicated theme tab component. Keep filter chips for filtering content, not navigating the workspace. |
| P1 | The tab controls are inside the vertically scrolling page column. [R1] | Place tabs in the persistent screen shell, outside tab content scrolling. |
| P1 | Built-in presets and `savedProfiles` are concatenated into the same collection. [R1] | Style contains built-in themes; Saved contains user-created themes. |
| P1 | Preset cards call `applyProfile` immediately. Saved cards contain an immediate Delete action. [R1] | Selecting a card previews a draft. Applying is explicit. Deletion moves to an overflow menu and confirmation dialog. |
| P1 | The custom editor contains colour selection, wheel/sliders, checks, preview, Apply, a name field, Save theme, and Restore default in one long form. [R2] | Keep colour roles on the page, move detailed picking into a focused editor, and move naming into a small save dialog. |
| P1 | `saveProfile` both saves and applies the palette; names matching case-insensitively replace previous entries. [R3] | Separate save from apply and require explicit replacement confirmation. |
| P1 | `CustomPaletteEditor` owns its editing state inside a conditionally composed tab. [R1, R2] | Hoist the draft above tab content and verify tab-switch/process-restoration behaviour. |
| P1 | Active-card detection relies on palette/mode comparisons, and saved-theme actions use names. [R1, R3] | Separate identity from visual equivalence. Introduce stable profile identifiers through a compatible migration. |
| P1 | `GPTMobileTheme` resolves colours and performs window-related side effects in the same composable. [R5] | Extract a side-effect-free resolver for rich previews. Keep window updates only in the actual app host. The existing small editor preview is local; this is a requirement for the proposed expanded preview. |
| P2 | The custom editor already uses the shared colour-role mapping and contrast utilities. [R2, R5, R6] | Retain and extend this working foundation rather than introducing a second colour engine. |
| P2 | Search targets Appearance tabs with the strings `0`, `1`, and `2`. [R8] | Preserve those aliases while introducing stable tab IDs and a Saved target. |
| P2 | `ThemeBackupDto` already includes mode, dynamic colours, custom accent, full palette, and saved profiles. [R11] | Preserve every existing field and add migration tests before changing profile identity. |

The current built-in collection has 16 themes: Cyan, Arctic, Orchid, Forest, Ember, Midnight, Rose, Ocean, Amber, Graphite, Neon, Lavender, Sand, Mint, Sky, and Daylight. The last five have explicit Light mode; preserve the complete collection and its mode metadata. [R4]

## 3. Research-backed design decisions

| Guidance | Decision for this screen |
|---|---|
| Android's Compose guidance distinguishes primary content tabs from secondary tab hierarchies. [E1] | Use one primary tab row below the app bar. Do not introduce nested tab rows. |
| Material 3 uses a coherent system of colour roles, typography, and shapes. [E2] | Reuse the repository's Material theme and shared design-system tokens; do not hard-code an unrelated settings palette. |
| Compose's accessibility defaults recommend minimum 48 dp interactive targets and appropriate component semantics. [E3] | Use real `Tab`, selectable rows, buttons, and switches; provide sufficiently large swatch and overflow hit targets. |
| Android adaptive guidance bases layouts on available window size, not a device label. [E4] | Adapt the workspace and preview to the actual window, including split-screen. |
| W3C contrast guidance provides explicit text-contrast thresholds. [E5] | Use measurable contrast targets for text rather than calling a palette accessible based on appearance alone. |
| Compose semantics expose selection, headings, state, and actions to accessibility services and tests. [E6] | Give tabs, colour controls, preview status, and destructive actions meaningful semantics. |

The exact tab names, layout dimensions, motion timings, and interaction rules below are design recommendations for this repository, not claims that a guideline mandates those exact choices. Use the Compose APIs compatible with the dependency versions on the implementation branch; do not upgrade the app's dependency stack solely for a tab component.

## 4. Information architecture and tabs

Retain the page title **Appearance** and the existing Settings entry point.

| Order | Label | Stable ID | Question answered | Contents |
|---|---|---|---|---|
| 1 | Style | `style` | Which built-in look should I start with? | Built-in theme gallery, light/dark filters, selection preview. |
| 2 | Colours | `colours` | Which exact colours should my theme use? | Accent, Secondary, Background, Cards; colour picker; contrast checks; save entry point. |
| 3 | Display | `display` | How should light/dark mode and wallpaper colours behave? | System/Light/Dark mode, wallpaper colour source, concise compatibility information. |
| 4 | Saved | `saved` | Where are the themes I created? | User theme library, preview, rename, duplicate, delete, and backup navigation. |

Style and Saved deliberately separate built-in discovery from personal-library management. Colours is a focused editing workspace, not a second theme gallery. Display owns behaviour, not palette CRUD.

**Tab rules:** fixed order; labels always visible; no badges or counts in the tab strip; no automatic tab changes when a theme is applied. Open Style by default. Restore the current tab on configuration change and within the existing navigation entry. Honour an explicit search target over a restored default. Switching tabs must not apply, discard, or save a draft.

Use a fixed, equal-width primary tab row when all labels fit. Switch to a scrollable primary tab row when measured text, localization, or window width requires it. Never shrink the text to force the labels to fit, and never replace labels with unexplained icons. Horizontal page swiping is not required: it can conflict with the colour wheel and sliders. Tab taps and accessibility actions are sufficient.

## 5. Visual system and screen shell

### 5.1 Persistent structure

The shell contains the app bar, a compact appearance-status row, the primary tabs, the current tab body, and a conditional bottom action bar. The app bar has Back, the title Appearance, and a single overflow menu. Put **Restore defaults** in that menu instead of repeating it at the bottom of multiple tabs.

The status row identifies the applied state, for example `Cyan · Dark` or `Wallpaper colours · System`. During editing, show `Previewing changes` as a distinct state. The words Active and Previewing must not be interchangeable.

Use a compact, expandable preview near the top of the current content. On sufficiently wide windows, place the same preview beside the controls. Do not pin a large preview above the tabs on a phone: navigation and editing space take priority. On short windows, use a Preview button that opens the larger sample in a sheet/dialog.

### 5.2 Proposed tokens

Reuse existing `AppSpacing`, `AppShapes`, and Material typography where equivalent. Add theme-specific tokens only when a shared equivalent is absent.

| Element | Proposed value or role |
|---|---|
| Compact-screen outer padding | 16 dp; increase to 24 dp on wider layouts. |
| Section separation | 24 dp. |
| Related-control gap | 12 dp. |
| Label/value gap | 4–8 dp. |
| Card/content padding | 16 dp. |
| Main panel radius | 20–24 dp, consistently selected from the shared shape scale. |
| Input/control radius | 12–16 dp. |
| Interactive target | At least 48 × 48 dp. |
| Settings row | Minimum 56 dp; grow with text rather than fixing the height. |
| Page title | Material `titleLarge`. |
| Section title | `titleMedium`, medium or semibold. |
| Main setting label | `bodyLarge`. |
| Secondary value/help | `bodyMedium`; avoid low-opacity small text for necessary information. |
| Page background | Resolved `background` or the established app scaffold role. |
| Group surface | `surfaceContainerLow`. |
| Raised preview/sheet | `surfaceContainer` / `surfaceContainerHigh`. |
| Primary action/selection | `primary` with its corresponding foreground role. |
| Destructive action | `error`, used only for the actual destructive action or error. |
| Separators | Sparse `outlineVariant`; not a border around every row. |

Use low elevation and consistent boundaries. Built-in theme cards may display their own palette inside a miniature sample, while card names and management chrome remain readable in the surrounding applied theme. This avoids every tile becoming a competing coloured panel.

### 5.3 Preview content

Use fixed, local sample content: a short user message, an assistant response with one link, an inline code sample, a source chip, and a composer/send button. A larger preview may include a settings row and code block. Do not load real conversations, call a model, run tools, or show an animated generation state.

The preview must show the effective draft, including wallpaper precedence and mode. Palette tiles are miniature palette samples, not independent app-theme hosts. Preview controls must not send messages or navigate to actual app destinations.

## 6. Style tab

### 6.1 Layout and behaviour

Place a restrained preview, then an optional single-row filter `All / Dark / Light`, then a lazy theme grid. These are content filters, not another navigation level. No explanatory paragraph is needed above the grid.

Each tile contains a miniature palette sample, the theme name, a short Light/Dark label, and an explicit state indicator. A thin accent outline plus checkmark identifies the draft selection. An Active label identifies the applied profile independently. Colour alone is insufficient.

Selecting a tile updates the draft and preview. It does not write to DataStore. The bottom action bar then offers **Apply theme**. This is an intentional improvement over immediate application and must be covered by interaction tests.

Preserve the current behaviour that choosing a preset includes its saved mode and turns off wallpaper colours when applied. Show the proposed mode in the preview so this is not a hidden side effect. A saved-profile selection follows the same rule.

Do not generate different palette values just because the user filters Light or Dark. Filtering changes which presets are visible, not what a preset means. Preserve collection order; Cyan remains the first default entry. Do not silently rename presets to match a new design vocabulary.

### 6.2 Compact wireframe

```text
┌─────────────────────────────────────┐
│ ‹  Appearance                    ⋮  │
│ Active: Cyan · Dark                 │
│ Style   Colours   Display   Saved   │
│ ━━━━━                               │
│                                     │
│ ┌ Preview ────────────────────────┐ │
│ │ Short conversation sample        │ │
│ │ Link · source chip · input       │ │
│ └─────────────────────────────────┘ │
│ All       Dark       Light          │
│ ┌───────────────┐ ┌───────────────┐ │
│ │ mini sample   │ │ mini sample   │ │
│ │ Cyan          │ │ Arctic        │ │
│ │ Dark · Active │ │ Dark          │ │
│ └───────────────┘ └───────────────┘ │
│ ... remaining built-in styles ...   │
│                                     │
│ Discard              Apply theme    │ ← only when dirty
└─────────────────────────────────────┘
```

Wireframes specify hierarchy, not a rendered or approved screenshot. In an unchanged state, do not show the dirty-state bottom bar illustrated above.

## 7. Colours tab and colour picker

### 7.1 Main editing page

Show the shared preview followed by four large, aligned colour rows. Each row has a role name, a small optional usage hint, the current HEX value, and a swatch with a proper touch target. Reuse the existing four-field `CustomThemePalette` model. [R4]

| UI label | Existing field | Purpose |
|---|---|---|
| Accent | `primary` | Primary actions, active controls, links. |
| Secondary | `secondary` | Supporting emphasis. |
| Background | `background` | Main page canvas. |
| Cards | `surface` | Main card/content surfaces. |

Do not expose every Material colour role as a new editable field. The existing resolver derives foreground and container roles; preserve that understandable four-colour model.

Below the rows, show a compact **Readability** row: `Checks passed` or `3 checks need attention`, with an accessible icon and expandable details. Do not describe a limited set of checks as proof of complete accessibility compliance. Keep detailed ratios out of the main page unless requested or a warning requires attention.

A secondary **Save theme** action opens the naming dialog. It saves the draft palette and mode to the personal library without applying them. The primary bottom action remains Apply theme when the active appearance would change.

```text
┌─────────────────────────────────────┐
│ ‹  Appearance                    ⋮  │
│ Previewing changes                  │
│ Style   Colours   Display   Saved   │
│         ━━━━━━━                     │
│ [ Local live preview             ]  │
│                                     │
│ Accent        #00FFDE       [swatch] │
│ Secondary     #CC00FF       [swatch] │
│ Background    #001219       [swatch] │
│ Cards         #0B2028       [swatch] │
│                                     │
│ ✓ Readability: checks passed     ›  │
│ Save theme                          │
│                                     │
│ Discard              Apply theme    │
└─────────────────────────────────────┘
```

### 7.2 Focused colour editor

Open one colour editor at a time. Use a bottom sheet where it fits; on short windows or with a keyboard consuming space, use a full-height editor/dialog with its own scroll container. The title names the role, for example **Accent colour**.

Order the content as: current/new swatches, HEX input, colour wheel, Hue/Saturation/Brightness controls, quick swatches, optional per-colour reset, then Cancel and **Use colour**. The existing wheel and HSB adjustment functionality must remain available. Preserve Cyan, Violet, Coral, and Mint quick choices as swatches with accessible names rather than a loose row of coloured text buttons. [R2]

The picker has its own temporary value. **Use colour** commits that value to the page draft, not to persisted app settings. Cancel, outside dismissal, and Back leave the page draft unchanged. A dirty picker requests discard confirmation when dismissal would lose an edit. Dismissing the keyboard first must not also dismiss the editor.

HEX entry accepts six hexadecimal digits, with or without a leading `#`, trims surrounding whitespace on validation, and normalizes valid values to uppercase `#RRGGBB`. Invalid text stays visible with a specific message; it must not become black, truncate into another valid colour, or enable Use colour. Preserve opaque 32-bit ARGB semantics at the storage boundary, including legacy signed representations where supported by existing imports.

Provide labelled numeric equivalents for HSB sliders, with Hue 0–360 degrees and Saturation/Brightness 0–100%. Normalize 360 degrees consistently to 0 for colour conversion. Preserve the last meaningful hue when saturation reaches zero so returning from grey does not jump to an unrelated hue. These are proposed refinements; test them against the existing picker implementation.

### 7.3 Readability corrections

Retain `paletteContrastChecks` and `suggestPaletteAccent`. A suggested correction changes only the draft after explicit selection and offers Undo. Show what changed; never silently rewrite the user's requested palette. Re-run checks after correction, since not every combination of background and surface can be repaired by changing accents alone. [R6]

Warn about poor contrast without removing full custom-colour support. A deliberate **Use anyway** acknowledgement may be required at Apply for a failing palette. Do not store an accessibility certification flag. Store only the user's palette, and recompute checks against the actual resolved scheme.

## 8. Display tab

Keep this tab intentionally small: two meaningful groups are better than a padded page of unrelated toggles.

### 8.1 Appearance mode

Show System, Light, and Dark as a single-selection control with short labels. Stack the options as selectable rows when localization or large text requires more width. Display the resolved mode below System only when it adds information, for example `Currently using Dark`.

Mode selection changes the draft. It must not dismiss a dialog, reset the chosen tab, or trigger Activity recreation as a theme-editing mechanism.

**Important existing limitation:** `CustomThemePalette` contains one explicit background and one surface, not separate light/dark variants. Changing `ThemeMode` does not automatically transform these fixed colours; the current resolver copies the palette into the selected base scheme. [R4, R5]

Preserve those exact custom colours. When a fixed palette is active, show the contextual line `Custom colours stay fixed across display modes` with a help action explaining that mode affects the remaining base roles. A light-looking custom theme should be selected or edited explicitly. Do not silently invert a palette or promise automatic light/dark pairs. Paired custom palettes can be a later versioned enhancement, outside this required redesign.

### 8.2 Wallpaper colours

Retain the existing wallpaper-colour capability, represented as a clear row with a switch. Explain its precedence only when enabled: `Wallpaper colours replace the custom palette while this is on.` The manual palette remains retained for later use. Android dynamic colour is available on Android 12 and later; use platform capability checks appropriate to the app's actual minimum SDK and supported configurations. [E2]

Turning wallpaper colours on updates the draft source and preview. It must not delete a custom palette or saved theme. Turning it off restores the retained manual/default source. Restoring the same configuration onto another device may produce different wallpaper colours; retain the source choice, not an accidental screenshot of the original wallpaper palette.

While wallpaper colours are selected, the Colours tab shows the effective preview and a concise **Use custom colours** action. Do not allow apparently successful edits that remain invisibly overridden. Choosing Use custom colours switches only the draft to manual colours and then enables the editor.

```text
┌─────────────────────────────────────┐
│ ‹  Appearance                    ⋮  │
│ Active: Cyan · Dark                 │
│ Style   Colours   Display   Saved   │
│                   ━━━━━━━           │
│ Appearance mode                     │
│ [ System ] [ Light ] [ Dark ✓ ]     │
│                                     │
│ Colour source                       │
│ Wallpaper colours             [off] │
│                                     │
│ [ Preview                         ] │
│                                     │
│ Contextual information only when    │
│ wallpaper or a fixed palette needs  │
│ an explanation.                     │
└─────────────────────────────────────┘
```

## 9. Saved tab

### 9.1 Personal library

Show only user-created themes. Use a simple list by default: miniature swatch/sample, name, mode, optional Active label, and one overflow button. A list handles longer user names and management actions more cleanly than placing Delete inside a clickable tile.

Tapping a row previews it through the same draft system as Style. It does not apply it immediately. Overflow actions are **Rename**, **Duplicate**, and **Delete**. Editing a saved palette loads a copy into Colours; replacing the stored profile later requires an explicit update/save choice.

Provide search within Saved when the collection is sufficiently large to benefit from it; the search affects this library only. Keep the existing stored order unless a deliberately selected sort is implemented. Do not reorder the list on every preview selection.

### 9.2 Save, rename, duplicate, and delete

The save dialog contains one name field and clear Cancel/Save actions. Preserve the current 40-character naming constraint, with Unicode-aware validation that does not split a surrogate pair. Trim outer whitespace and reject blank names. For an existing name, compare consistently and present **Replace existing theme** or **Choose another name**; never silently overwrite. Existing imported names must not be destructively shortened during migration.

Saving is library-only. It must not call the current save-and-apply method unchanged. Offer explicit **Save and apply** only as a deliberate secondary flow if implemented; it must not be the hidden effect of Save.

Duplicate creates a new profile identity and proposes an editable name, such as `Cyan study copy`. Rename changes the label, not the profile identity. Delete asks for confirmation and offers Undo after success. Deleting the active library entry retains the applied palette and changes its label to **Custom**; it does not unexpectedly reset the app's appearance. Deleting a profile currently being previewed leaves the draft as an unsaved custom palette rather than a dangling reference.

A **Backup & restore** navigation row opens the existing backup flow. This is not a second backup implementation, and the page must not claim that a backup exists unless that fact is actually known. Retain the user's choices in the existing backup flow.

### 9.3 Empty state and wireframe

Use the title `No saved themes yet`, one brief instruction, and **Create a theme**. That action opens Colours with the current effective manual/default palette as the starting point. Do not auto-save an entry or manufacture example user profiles.

```text
┌─────────────────────────────────────┐
│ ‹  Appearance                    ⋮  │
│ Active: Evening · Dark              │
│ Style   Colours   Display   Saved   │
│                             ━━━━━   │
│ Saved themes                        │
│ [sample] Evening              ⋮     │
│          Dark · Active              │
│ [sample] Clean daylight       ⋮     │
│          Light                      │
│                                     │
│ Create a theme                      │
│ Backup & restore                 ›  │
└─────────────────────────────────────┘
```

## 10. Adaptive layout

Base decisions on available window size, content measurement, font scale, and height constraints. Do not identify a phone/tablet by model name. The following dimensions are proposed content rules layered on Android's adaptive approach. [E4]

| Available layout | Behaviour |
|---|---|
| Compact width | Single content column; usually two Style tiles per row if minimum tile width and labels fit. |
| Very narrow width or large text | One Style tile per row or a horizontal sample row; scrollable labelled tabs. |
| Medium width | Centre the content with a sensible maximum reading width; do not stretch settings rows across the entire window. |
| At least 840 dp usable width, with sufficient height | Allow controls and preview side by side, separated by a 24 dp gutter. |
| Expanded width but short height / landscape keyboard | Prefer a single practical editing pane or collapsible preview rather than forcing a cramped two-column layout. |
| Foldable with an obstructing hinge | Keep controls and action buttons out of the hinge area; use panes only when both remain usable. |

For a two-pane implementation, target a controls pane of at least 360 dp and a preview pane of roughly 280–360 dp, adapting rather than overflowing. Treat these as starting values to validate, not absolute requirements on all translations.

```text
┌────────────────────────────────────────────────────────────────┐
│ ‹ Appearance                                               ⋮   │
│ Active: Cyan · Dark                                            │
│ Style       Colours       Display       Saved                  │
├──────────────────────────────────┬─────────────────────────────┤
│ Current tab controls             │ Preview                     │
│                                  │                             │
│ Role / setting rows              │ Conversation sample         │
│ Detailed editor when opened      │ Source / code / input       │
│                                  │                             │
│                                  │ Applied vs draft status     │
├──────────────────────────────────┴─────────────────────────────┤
│ Discard                                          Apply theme   │
└────────────────────────────────────────────────────────────────┘
```

Each tab owns its scroll state. Avoid placing an unbounded lazy grid inside a vertically scrolling column. Use one lazy scrolling owner per pane, or a deliberately bounded editor. Account for Scaffold padding, system bars, gestures, and IME insets once, without double padding. The final row and Apply action must remain reachable above the keyboard and navigation bar.

## 11. Interaction and action contracts

| Action | Immediate result | Persisted effect |
|---|---|---|
| Select Style/Saved entry | Replace appearance draft and update local preview. | None. |
| Change Display mode/source | Update draft and effective preview. | None. |
| Use colour | Update one draft palette role. | None. |
| Apply theme | Validate, commit active appearance, then update the app. | One atomic active-appearance transaction. |
| Save theme | Add a named library entry after validation/confirmation. | Library only; app appearance unchanged. |
| Rename / Duplicate | Update the library after successful persistence. | Library only. |
| Delete | Confirm, remove library entry, offer Undo. | Library only; preserve active palette. |
| Discard | Reset draft to the latest applied state. | None. |
| Restore defaults | Confirm scope, then commit the existing default appearance. | Active appearance only; never delete saved profiles. |
| Back with a dirty page | Offer Keep editing / Discard / Apply, with Apply disabled for invalid data. | Only the explicitly selected action. |
| Backup & restore | Open existing destination after resolving dirty edits. | Existing backup flow owns the operation. |

The bottom action bar appears only when active appearance differs from the draft. It contains one filled primary action and a quieter Discard action. **Saved** does not imply **Applied**. Avoid several full-width filled buttons competing in the same viewport.

Applying uses a visible in-progress state to prevent duplicate submission. Update the active label and show `Theme applied` only after persistence succeeds. On failure, retain the draft and show a concise retryable error. A failed save does not close its dialog or erase the entered name.

Offer Undo for successful apply, deletion, and suggested colour corrections where feasible. Undo must be revision-aware: it cannot overwrite a newer theme change or backup restore. An expired/conflicting Undo explains that the appearance has since changed rather than replacing newer data.

Do not show discard prompts merely because a user opens a different tab. However, loading a different preset over an edited custom draft must offer Replace draft / Keep editing, unless the current draft consists only of a previous, unedited preset selection. Preserve the editor's last values while switching colour roles.

## 12. State and preview architecture

### 12.1 Ownership

Keep `ThemeViewModel` and `SettingRepository` as the shared applied-theme pathway. Add a route-scoped appearance editor ViewModel/state holder rather than making the existing app-wide theme ViewModel own transient modal state. The current `ThemeViewModel` already observes repository changes and serializes its own writes with a mutex; retain that useful behaviour while moving transaction authority to the persistence layer where needed. [R3, R9, R10]

The editor state needs:

| State | Contents |
|---|---|
| Applied snapshot | Current appearance fields, active identity if known, and a revision/fingerprint. |
| Draft | Mode, source, manual accent/palette, and selected profile identity if still applicable. |
| Library | Observed saved profiles; not copied wholesale into every appearance transaction. |
| UI state | Stable selected tab, per-tab scroll/filter state, selected colour role, open editor/dialog. |
| Validation | Invalid field values, readable-colour results, save-name conflicts. |
| Operation state | Idle / Applying / Saving / Error; one-shot messages and undo payloads. |

Illustrative shape, not drop-in compiled code:

```kotlin
enum class AppearanceTab(val key: String) {
    STYLE("style"), COLOURS("colours"), DISPLAY("display"), SAVED("saved")
}

// Proposed draft type. Preserve nullable legacy/default representations.
data class AppearanceDraft(
    val themeMode: ThemeMode,
    val dynamicTheme: DynamicTheme,
    val customPrimaryArgb: Long?,
    val customPalette: CustomThemePalette?,
    val sourceProfileId: String?
)
```

Use a `SavedStateHandle` or equivalent lightweight saveable state for the draft and editing fields. Do not put Activity, Context, `ColorScheme`, ViewModel objects, or the full saved-theme library into a saved-state bundle. Restore transient drafts for the navigation entry; do not include them in user configuration backups.

### 12.2 Pure colour resolution

Extract colour resolution from `GPTMobileTheme` into a side-effect-free function. It should take explicit mode/system-dark information, source settings, custom values, and an already obtained dynamic scheme when applicable. It returns the same effective scheme and resolved metadata for both app and preview.

Preserve the current precedence: enabled wallpaper colours first; otherwise full custom palette; otherwise custom primary override; otherwise the selected base scheme. Preserve `customPaletteColorScheme` as the shared role-mapping implementation unless independently reviewed fixes are needed. [R5]

The app host obtains environment values and owns system-bar/window side effects. The preview wraps only its sample content in `MaterialTheme` and matching local palette/icon providers. It must not call the full `GPTMobileTheme` wrapper repeatedly inside cards or preview panels. This avoids previews writing window state, using a mismatched system mode, or affecting the rest of the app.

Compute preview and active identity separately. Two profiles may have identical palettes without being the same profile. After editing a saved profile's draft colours, show `Custom changes` until explicitly saved or applied with appropriate identity rules.

### 12.3 External updates and races

A backup restore or another settings entry point can update the applied theme while Appearance is open. When the editor is clean, adopt the new applied state immediately. When dirty, preserve the draft and display `Appearance changed elsewhere` with Reload / Keep editing. Applying must rebase or explicitly resolve against the latest applied revision.

Do not write an old `ThemeSetting` snapshot back wholesale and erase newly restored saved profiles. Perform field-scoped updates against the latest repository state in a single DataStore transaction or equivalent atomic operation. A mutex in one ViewModel alone cannot protect a separate backup writer.

## 13. Persistence, migration, and backup

### 13.1 Preserve the existing data contract

The current active settings fields are `dynamicTheme`, `themeMode`, `customPrimaryArgb`, `customPalette`, and `savedProfiles`. Saved profiles currently contain `name`, `palette`, and `mode`. The configuration backup already carries those theme fields. [R4, R11]

Do not rename existing serialization fields, reinterpret persisted enum integers, remove null/default semantics, change package identifiers, or clear saved profiles for a UI-only reorganization. A legacy accent-only theme must render as an accent-only theme until the user explicitly chooses a complete palette. Opening Colours may construct an editable palette from the effective scheme, but merely opening the page must not persist that expanded representation.

### 13.2 Profile identity

Stable IDs are recommended for reliable rename/delete/active state. Implement them as a compatible schema extension, not a destructive replacement of the library.

Add an optional profile ID that old backups may omit, plus optional active-profile metadata only if the complete import/export pipeline supports it. Built-in themes have stable namespaced IDs such as `builtin:cyan`; saved themes have independent local IDs. Do not identify a profile by its mutable display name or current array position.

Migrate missing saved IDs once and persist the result atomically. Use a documented deterministic legacy identity strategy that includes enough data to disambiguate duplicate entries, or generate IDs once as part of a durable migration. An implementation that regenerates random IDs on every read is not acceptable. Preserve duplicate legacy entries during migration; do not silently deduplicate user data to simplify the UI.

When importing an older backup, assign valid missing IDs during the import transaction. Handle collisions explicitly; a different payload must not silently overwrite a local profile just because an ID or name matches. Reuse the existing backup flow's documented replace/merge semantics rather than inventing a second hidden policy in Appearance.

### 13.3 Backup and restore acceptance

Verify both configuration JSON and the app's complete-backup route; inspecting `ThemeBackupDto` alone is not sufficient proof that every backup path is covered. New profile metadata must round-trip wherever themes are already exported. [R11, R13]

| Scenario | Required result |
|---|---|
| Old backup without custom fields | Restore the established legacy/default appearance. |
| Accent-only backup | Preserve the accent and nullable full-palette representation. |
| Full palette and saved profiles | Preserve all colours, names, modes, and library entries. |
| New backup with IDs | Preserve identities and active metadata, or use a documented compatible fallback. |
| Wallpaper source on another device | Preserve the source choice; resolve that device's wallpaper palette. |
| Restore while Appearance is open and clean | Refresh the library and preview without reopening the app. |
| Restore while Appearance has a draft | Preserve the draft and present an explicit conflict choice. |
| Invalid palette/profile payload | Report the affected data; do not erase the whole valid library or crash the screen. |
| Restore defaults | Reset active appearance only; keep the saved library. |

Do not change the global backup version casually for a theme-only addition. Coordinate schema handling in the repository, data source, DTOs, complete-backup exporter/importer, and tests. A rollback must not discard valid new fields simply to make the old UI render.

## 14. Navigation and search compatibility

Keep the existing `ThemeSettingsScreen(onDismiss, asDialog, initialTab)` entry points working during migration. `SettingScreen` currently exposes both an Appearance navigation callback and a dialog wrapper. Both must render the same new screen content, not divergent settings implementations. [R1, R12]

Prefer typed/stable internal tab IDs. Keep a compatibility adapter for the existing integer/search focus values:

| Existing target | New destination |
|---|---|
| `0` / `appearance.presets` | Style |
| `1` / `appearance.custom` | Colours |
| `2` / `appearance.display` | Display |
| New `saved` target | Saved |
| Unknown target | Style, without a crash. |

Retain existing search keys as aliases and update display labels/breadcrumbs. Add entries for Saved themes, Wallpaper colours, Dark mode, and HEX colour with concrete tab/section targets. Include both `colour` and `color` in keywords. Do not reindex the old numeric target `2` to Saved when inserting a new tab. [R8]

A focused search result should select the destination tab and scroll/focus the requested section once. Avoid recomposition repeatedly resetting the tab or scroll position. Back closes the topmost colour editor/save dialog first, then resolves a dirty Appearance draft, then returns to the previous app destination.

## 15. Repository implementation map

Paths below are relative to `app/src/main/kotlin/dev/chungjungsoo/gptmobile/` unless stated otherwise. Existing files marked reviewed were inspected at the baseline. New filenames are proposals and must be checked for collisions before implementation.

| Existing file | Required work |
|---|---|
| `presentation/ui/setting/ThemeSettingsScreen.kt` — reviewed | Replace monolithic scrolling content with shared shell, real tabs, independent content owners, preview and dirty-state action bar. Preserve entry-point compatibility. |
| `presentation/ui/setting/CustomPaletteEditor.kt` — reviewed | Extract reusable picker functionality; move draft ownership out of the conditional tab; remove direct page-level save/apply/reset duplication. |
| `presentation/common/ThemeViewModel.kt` — reviewed | Separate appearance application from library CRUD; expose successful/failing operations; preserve observation and coordinate with repository transactions. |
| `presentation/theme/Theme.kt` — reviewed | Extract pure resolution; keep system/window updates in the app host; make app and preview use identical resolution inputs. |
| `presentation/theme/PaletteContrast.kt` — reviewed | Retain calculations and explicit draft-only suggestions; extend coverage for actual preview roles where justified. |
| `data/dto/ThemeSetting.kt` — reviewed | Preserve all presets/fields; add backward-compatible identity metadata only with completed migration support. |
| `data/repository/SettingRepository.kt` — reviewed | Define atomic, field-scoped active-theme and library operations without stale whole-object writes. |
| `data/repository/SettingRepositoryImpl.kt` — theme-read portion reviewed | Implement atomic update/conflict behaviour; audit all write/export/import paths before modifying them. |
| `data/dto/ConfigBackupDto.kt` — reviewed | Preserve `ThemeBackupDto`; support new metadata with compatible defaults and round-trip tests. |
| `presentation/ui/setting/SettingsDesign.kt` — reviewed | Reuse panels/tokens where suitable. Do not globally change `SettingsTabs` semantics for unrelated screens without auditing its callers. |
| `presentation/ui/setting/SettingsSearchRegistry.kt` — reviewed | Add stable destinations and aliases; update breadcrumbs and search keywords. |
| `presentation/ui/setting/SettingScreen.kt` — reviewed | Preserve navigation and dialog entry points and avoid double app bars. |
| `presentation/ui/setting/BackupRestoreHost.kt` / `CompleteBackupDialog.kt` — located, not fully reviewed | Verify theme scope and restore refresh through the existing flow; no parallel backup subsystem. |
| `data/datastore/SettingDataSource.kt` / `SettingDataSourceImpl.kt` — referenced by reviewed repository code | Inspect implementation and preference keys; centralize atomic theme updates and compatible migration. |
| `app/src/main/res/values/strings.xml` and relevant translations | Add localized tab labels, actions, validation, state descriptions, confirmations, and search copy. |

Suggested new components under `presentation/ui/setting/appearance/`:

```text
AppearanceScreenContent.kt       shared content for route and dialog hosts
AppearanceEditorViewModel.kt     route-scoped draft, conflicts, and operations
AppearanceUiState.kt             typed tabs, draft, validation, and UI state
AppearanceTabBar.kt              actual primary tabs
ThemePreviewCard.kt              pure local sample preview
ThemeStyleTab.kt                 built-in gallery
ThemeColoursTab.kt              four colour roles and readability row
ThemeDisplayTab.kt              mode and wallpaper source
SavedThemesTab.kt               personal library
ThemeColourPicker.kt            wheel, HSB, HEX, temporary picker state
ThemeProfileDialogs.kt          save, rename, duplicate, delete confirmation
AppearanceActionBar.kt          conditional Apply/Discard actions
```

A proposed `presentation/theme/ThemeResolver.kt` can host the pure resolver. Keep this decomposition pragmatic: small related components may share a file. Do not create a second dependency-injection scope, persistence store, or theme engine merely to match this file list.

Before implementation, search all call sites of `ThemeSettingsScreen`, `CustomPaletteEditor`, `applyProfile`, `saveProfile`, `deleteProfile`, `updateThemes`, and `ThemeBackupDto`. The map is not a claim that the entire repository call graph was executed or verified in this documentation change.

## 16. Accessibility and readable colours

Use at least 48 dp interaction targets. A 20 dp swatch may be visually small, but its control must remain large enough to activate. Keep sufficient separation between touch targets rather than relying on overlapping expanded hit areas. [E3]

Give the tab row true tab selection semantics. Colour rows announce role, HEX value, and action. The selected state must be expressed with shape/icon/text as well as colour. The wheel must have equivalent HEX, slider, and numeric input paths; dragging must never be the only way to choose a colour.

Use headings for major groups. Announce apply/save success and failures politely, not on every slider movement. Modal editors need an appropriate pane title and predictable initial focus; return focus to the launching swatch or overflow button on dismissal. Decorative preview fragments should not create dozens of unnecessary TalkBack stops. Provide a readable preview summary and a deliberate way to inspect the larger sample. [E6]

Design targets: ordinary text at least 4.5:1; large text at least 3:1 when it meets the relevant large-text definition; important control boundaries/indicators at least 3:1 where applicable. Validate actual alpha-composited colours, including secondary text and raised surfaces. W3C guidance is used as a measurable target, not a claim of legal certification for this native app. [E5, R6]

Test 100%, 130%, and 200% font scales and large display size. Titles and names may wrap; action labels remain readable; no fixed-height text containers clip the second line. Support RTL layout and localized strings. Long theme names must not push overflow actions off-screen.

Respect the platform's reduced/disabled motion behaviour. Propose a short 120–180 ms selection transition and no more than a subtle crossfade for preview changes; these are tunable design values. Avoid flashing full-screen colours, continuous decorative animation, parallax, or bouncy movement in the editor.

## 17. Performance and reliability

The screen is entirely local. Theme previews must not initialize model runtimes, contact providers, fetch web content, or wait on unrelated settings. Opening Appearance must not start background AI work.

Use lazy content for galleries and saved lists. Memoize palette samples and colour resolution by relevant inputs. Slider/wheel changes update only the temporary picker and local preview; they do not write preferences for every pointer event or re-theme the entire app before Apply.

Do not animate through every intermediate colour when the pointer produces rapid changes. Render the most recent draft. Run heavier validation outside repeated layout work, while keeping required feedback responsive. Keep diagnostics redacted and local; adding analytics is out of scope.

Use the repository's established unit, lint, build, and UI-validation workflows after implementation. Validate frame behaviour on a representative phone and an emulator/device with a smaller window; any performance figures added to the implementation report must be measured, not inferred from this document.

Avoid broad shared-component changes before auditing their users. In particular, changing the existing generic `SettingsTabs` helper may affect several other settings screens. The theme workspace can adopt a dedicated tab bar without forcing an unrequested app-wide redesign.

## 18. Acceptance tests

The following tests are required work for implementation. None are reported as executed by this design-document commit.

| ID | Scenario | Expected result |
|---|---|---|
| A01 | Open Appearance from Settings navigation. | One title bar, Style selected by default, active appearance accurately identified. |
| A02 | Open the legacy theme dialog. | Same controls and behaviour as the routed screen. |
| A03 | Open old search targets 0, 1, 2. | Style, Colours, Display respectively. |
| A04 | Open new Saved search target or an unknown target. | Saved for the valid target; safe Style fallback for unknown input. |
| A05 | Scroll a long gallery/list. | Tabs remain available; the final item remains reachable. |
| A06 | Browse every built-in preset. | All 16 remain available with their original colours and mode metadata. |
| A07 | Select a preset. | Draft/preview changes; persisted appearance does not change until Apply. |
| A08 | Apply successfully. | Appearance fields commit atomically; active state updates after success. |
| A09 | Apply fails. | Draft remains, active state is unchanged, retry is available. |
| A10 | Switch tabs after editing several colours. | Draft and per-tab state are preserved; no implicit save/discard. |
| A11 | Rotate or recreate the editor entry. | Lightweight draft/UI state restores without replacing the saved library. |
| A12 | Open/cancel a colour picker. | No page-draft or persisted changes on Cancel. |
| A13 | Use a picked colour. | Only the selected draft role changes. |
| A14 | Enter valid HEX, optional #, whitespace, lowercase. | Correct normalized colour; exact expected opaque ARGB. |
| A15 | Enter invalid, incomplete, oversized, or pasted malformed HEX. | Clear validation; no silent conversion or enabled invalid action. |
| A16 | Adjust HSB, including saturation zero and hue boundary. | Stable predictable colour and equivalent accessible input paths. |
| A17 | Request a contrast suggestion. | Draft-only explicit correction; checks rerun; Undo works. |
| A18 | Inspect a failing palette. | Specific warnings; no false whole-app accessibility claim. |
| A19 | Switch manual/wallpaper source. | Effective preview follows precedence; manual palette and saved library remain intact. |
| A20 | Switch System/Light/Dark with a fixed palette. | Exact custom colours preserved; contextual explanation matches actual behaviour. |
| A21 | Save a draft. | Library entry created without applying the theme. |
| A22 | Save a duplicate name with different case/outer spaces. | Explicit replace-or-rename choice; no silent overwrite. |
| A23 | Rename or duplicate a profile. | Rename preserves identity; duplicate creates a new identity. |
| A24 | Delete active or previewed saved profile. | Applied/draft colours retained appropriately; no reset or dangling reference. |
| A25 | Restore defaults. | Existing default applied after confirmation; saved themes remain. |
| A26 | Back from a dirty picker and dirty page. | Topmost editor is handled first; no unintended double dismissal. |
| A27 | Restore an old, accent-only, full-palette, and ID-bearing backup. | Correct backwards-compatible round trip, with no lost profiles. |
| A28 | Restore backup while editor is open. | Clean editor refreshes; dirty editor presents a non-destructive conflict choice. |
| A29 | Apply/Undo while another writer changes theme/library. | Newer data is not overwritten by a stale snapshot or Undo. |
| A30 | Render rich preview and many tiles. | No app-window/system-bar side effects and no model/network work. |
| A31 | App-selected Light while system is Dark, and vice versa. | Preview matches the app's resolved scheme, not an unrelated system theme. |
| A32 | Run TalkBack and keyboard navigation. | Correct roles, selection, focus return, headings, names, and usable non-drag controls. |
| A33 | Test 320/360/412 dp widths, landscape, and split-screen. | No clipped tabs/actions; practical preview and scroll behaviour. |
| A34 | Test large text, long names, and RTL. | No truncated essential actions, off-screen overflow, or broken row ordering. |
| A35 | Rapidly drag colour wheel and repeatedly apply/save. | No preference writes per pointer event, duplicate operations, or uncontrolled recompositions. |
| A36 | Return from backup flow or resume after external theme change. | Active label, saved library, and preview are synchronized. |

Suggested unit-test groups: pure resolver precedence; draft dirty-state/reducer transitions; HEX/HSB validation; profile identity and conflict handling; atomic field-scoped updates; legacy migration; backup round trips; search aliases. Suggested UI tests: tab semantics/state retention, dialog dismissal, action feedback, large-text layout, and preview isolation.

Capture implementation screenshots for all four tabs in both app-selected light and dark contexts, including dirty, wallpaper-source, empty-library, error, and large-text states. Screenshots should come from the running implementation or established preview tooling, not be presented as proof of behaviour they do not test.

## 19. Implementation sequence

| Phase | Work | Exit condition |
|---|---|---|
| 1 — Characterize | Revalidate baseline/call sites; add tests for current precedence, all presets, backup fields, and legacy navigation targets. | Existing contracts recorded without altering user data. |
| 2 — State correctness | Introduce route-scoped draft and operation state; pure resolver; atomic repository operations; explicit save/apply split. | Draft isolation, successful/failed writes, and concurrent restore tests pass. |
| 3 — Screen shell | Build persistent tabs, status row, shared preview, adaptive content, and action bar. | All entry points work; no nested-scroll or small-window failures. |
| 4 — Tab content | Implement Style, Colours, focused picker, and Display using existing capabilities. | All preset, colour, mode, source, and contrast contracts pass. |
| 5 — Saved library | Add identity migration, library actions, confirmations, and complete backup compatibility. | Old/new backup round trips and no-loss CRUD tests pass. |
| 6 — Polish and verification | Accessibility, localization, focus, motion, runtime screenshots, performance checks, and regression build. | Acceptance matrix completed with actual evidence and remaining limitations documented. |

Do not enable a partially implemented Saved tab that loses existing profiles or ships an untested migration. Do not claim a visual-only completion while leaving Save coupled to Apply. Keep changes reviewable by separating state/data work from rendering changes where practical.

## 20. Definition of done and exclusions

The redesign is complete only when the four tabs are implemented, existing features are preserved, browsing and editing remain non-destructive, both Appearance entry points work, and theme data survives existing backup/restore flows. All destructive or overwriting actions must be explicit. Preview and app colours must resolve through the same logic without preview window side effects.

The implementation report must distinguish automated checks, manual device checks, screenshots, and untested items. Passing a build alone is not evidence of accessibility, visual polish, restore compatibility, or smooth dragging.

**Out of scope for this document's required implementation:** remote theme downloads, theme accounts, AI-generated palettes, custom font downloads, new reading/motion settings, automatic schedules, paired custom light/dark palettes, a separate backup system, changing the default palette, and an app-wide dependency upgrade. These may be evaluated separately without blocking the focused redesign.

**This repository change contains only this design document.** It does not modify Kotlin sources, app resources, persisted data, version numbers, releases, or CI configuration.

## 21. Evidence and references

### Repository evidence

All code observations below refer to the audited commit `7553e4ff14316f0d28639d5cc0ed24cb59511066`, not an assumed later version of main.

- **R1 — Current screen:** [ThemeSettingsScreen.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/ThemeSettingsScreen.kt).
- **R2 — Current colour editor:** [CustomPaletteEditor.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/CustomPaletteEditor.kt).
- **R3 — Shared applied-theme state and operations:** [ThemeViewModel.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/common/ThemeViewModel.kt).
- **R4 — Models and 16 built-in presets:** [ThemeSetting.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/dto/ThemeSetting.kt).
- **R5 — App theme, precedence, role mapping, and window effects:** [Theme.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/theme/Theme.kt).
- **R6 — Existing contrast checks and draft suggestions:** [PaletteContrast.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/theme/PaletteContrast.kt).
- **R7 — Shared settings components:** [SettingsDesign.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingsDesign.kt).
- **R8 — Existing search targets and aliases:** [SettingsSearchRegistry.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingsSearchRegistry.kt).
- **R9 — Repository contract:** [SettingRepository.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepository.kt).
- **R10 — Repository theme observation/read implementation:** [SettingRepositoryImpl.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepositoryImpl.kt). Review for this document covered the beginning of the file through line 220, including theme reads; complete write/import/export paths require implementation-stage inspection.
- **R11 — Configuration backup theme fields:** [ConfigBackupDto.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/dto/ConfigBackupDto.kt).
- **R12 — Settings entry and dialog wrapper:** [SettingScreen.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingScreen.kt).
- **R13 — Existing backup integration targets, located but not fully audited here:** [BackupRestoreHost.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/BackupRestoreHost.kt) and [CompleteBackupDialog.kt](https://github.com/tailscale-signin/GPT_Mobile_AI-improved/blob/7553e4ff14316f0d28639d5cc0ed24cb59511066/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/CompleteBackupDialog.kt).

### External primary guidance

Consulted October 12, 2026. These sources support the stated design principles; proposed product decisions and dimensions remain recommendations in this document.

- **E1:** Android Developers, [Tabs in Jetpack Compose](https://developer.android.com/develop/ui/compose/components/tabs).
- **E2:** Android Developers, [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3).
- **E3:** Android Developers, [Accessibility API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).
- **E4:** Android Developers, [Use window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes).
- **E5:** W3C WAI, [Understanding Contrast (Minimum), WCAG 2.2](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html).
- **E6:** Android Developers, [Semantics in Jetpack Compose](https://developer.android.com/develop/ui/compose/accessibility/semantics).
