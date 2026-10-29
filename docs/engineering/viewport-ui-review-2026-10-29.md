# Viewport and GitHub UI review

Repository: tailscale-signin/GPT_Mobile_AI-improved. Base: main at
`af348e0a4c556bf7cb212b8455a83deacbefbe67`. PR: #760, branch
`fix/viewport-pane-insets-2026-10-29`. Follow-up review started from
`636a5df1ff4dfe0085c861daf8b7b3358d6fcc37`.

## Review findings and changes

- Fixed both CI formatting errors: import order and a multiline conditional.
- Updated the existing approval test, which still expected the old inline diff.
- GitHub explicitly disables research presentation before asynchronous context binding;
  persisted GitHub context also vetoes it. Research chips follow the same policy.
- The compact approval keeps one row of 48 dp actions. Narrow panes or large text use
  labeled accessibility actions with icons. Stale and empty proposals cannot commit;
  busy states block writes but permit read-only inspection. Restaging clears old edit state.
- Details uses a bounded lazy list, pinned Close action, safe drawing/keyboard insets,
  and scrolls to the editor when a file is selected. Approval still goes through the
  existing frozen-digest verification in the view model and engineering controller.
- Summary sentence splitting preserves filenames and versions. Full destination,
  commit message and diff remain available in Details.
- A shared generation-progress component provides chat and GitHub with the same font,
  color, animated dots, activity resolver and progress indicator. GitHub renders it
  under the repository title on every tab and suppresses the duplicate bubble indicator.
  Only events belonging to active runs contribute to the header; approval/waiting states
  take priority. GitHub tab bubbles have no surrounding border/background shape.
- Chat measures actual allocated space and consumes horizontal safe-drawing insets.
  The navigation shell owns IME consumption; separate dialog windows own theirs.

## Validation

- Local ktlint 1.3.1, verified against the workflow SHA-256: passed after formatting.
- Canonical fast validation: 212 host tests and CI/resource/regex/provenance checks passed.
- Added or updated Android regression coverage for pane resizing, single IME consumption,
  restoration after keyboard dismissal, large-font horizontal actions, busy-state behavior,
  Details without approval, research gating, progress event scope and unframed tab selection.
- Android/Compose execution requires CI. This workspace lacks JDK 21 and an Android SDK;
  the Gradle wrapper download previously failed. Host fixture output is not evidence
  of device execution. Do not mark these device cases verified from host checks.

## Device acceptance still required

Check portrait and landscape, gesture and three-button navigation, split screen,
fold/unfold, display cutouts, keyboard open/close, 100% and 200% font scale, theme
and blur preferences, switching all GitHub tabs during generation, proposal restaging,
and repeated Details open/close with a long patch. Confirm the header stays pinned,
all actions are reachable, text is readable, and the composer clears the keyboard.
