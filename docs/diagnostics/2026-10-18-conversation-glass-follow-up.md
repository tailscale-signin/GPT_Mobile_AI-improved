# Conversation glass follow-up

Base: main at ab2123d (PR #717).

The requested 50% arrow and 85% composer tints were merged, but their existing test only checked visibility/actions and saved a screenshot. It did not assert the material pixels. In the real conversation, the list source recorded the bottom edge fade, removing the message pixels behind the composer before Haze could sample them.

## Changes

- Capture unfaded messages inside the viewport mask. The visible messages still fade at the top/bottom, but the glass controls sample the original content. Entry visibility stays inside the capture so restored chats cannot reveal an unpositioned message list through the glass.
- Give each conversation an independent same-window backdrop state. Its arrow and composer cannot sample outgoing destinations or other conversations. Dialogs/popups continue using the enclosing window state and opening order.
- Remove the arrow's shadow paint above its glass. Keep its 26dp plate, sharp icon, 48dp hit area and 50% material tint; keep the composer at 85%.
- Skip the viewport mask when edge fades are disabled, including the previous residual bottom 4% fade.
- Add pixel assertions for both controls in light/dark themes: red/blue background transmission verifies opacity, and stripe variance verifies actual blur. The opacity fixture uses the production viewport modifier with edge fades enabled. Controls stay outside the source. An unrelated global source checks conversation isolation. A third case verifies that hidden messages remain absent from the blur until entry restoration finishes.
- Require the new instrumentation protocol results and six screenshots in Android acceptance. Existing send/stop/latest and Settings-transition tests remain required.

## Feature audit

- Settings/popups retain the shared 70% glass tint and rasterized Home backdrop crash repair.
- Local model imports still launch one OpenDocument picker and install from an input stream; bundles need no folder selection. URI-grant ownership and cancellation cleanup remain in the worker.
- Local models without explicit native tool support still use the app-managed prompt bridge with schema/grant/budget enforcement and bounded correction.
- Solid material and Blur Off remain explicit appearance choices. Glass is active with Glass + Blur Auto; a flat background naturally has little visible texture.

## Validation

Kotlin formatting, shell/Python syntax, resource/regex checks and all 138 repository-script tests pass locally. Android acceptance run 39138580562 passed on initial head 6b560eb: measured transmission was 14.64–14.66% for the composer and 48.92–49.12% for the arrow, with background stripe variance reduced by over 99.9% in both themes. Screenshots were inspected. The final entry-visibility adjustment adds a third required pixel case; its exact-head result is tracked in PR #718 checks. Earlier local builds cannot resolve the uncached Android Gradle plugin in this environment. No fresh phone screenshot or diagnostic export was supplied for this report.
