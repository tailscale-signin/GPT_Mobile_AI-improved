# Settings Home native render review

These images are actual captures of the production `SettingsHomePanel` rendered by Compose in Robolectric 4.17 using Android native graphics. The wallet amount, profile names and status values are synthetic fixtures. They are not physical-device screenshots, live account balances, or provider availability checks.

The final `SettingsHomeRenderTest` run passed all four cases. The three layout cases each exercised zero through five selected information items and captured the final five-item state. Visual review of every image below found legible text, separated status labels, and no clipped text or overlapping tiles.

| Capture | Component image | Fixture conditions |
| --- | --- | --- |
| [Compact light](compact-light-native.png) | 288 × 498 px | 320 dp viewport, 16 dp Settings padding on each side, font scale 1.0, left-to-right, Material light palette. |
| [Wide light](wide-light-native.png) | 528 × 326 px | 560 dp viewport, 16 dp Settings padding on each side, font scale 1.0, left-to-right, Material light palette. |
| [Large text, RTL, dark](large-text-rtl-dark-native.png) | 288 × 1058 px | 320 dp viewport, 16 dp Settings padding on each side, font scale 2.0, right-to-left, Material dark palette. |

The native Android API 34 host uses mdpi density and a 2200 dp height so the complete component can be inspected. A separate production `SettingsHomeScreen` interaction case uses a 360 × 800 dp scrollable viewport. It verifies the accessible Back action, five-selection limit, disabled sixth option, replacing a selection, and returning to an explanatory empty state.

Layout assertions check the bubble width, positive tile dimensions, containment and non-overlap for every one-to-five-item layout. Text checks compare the actual visible text bounds and remeasure the same text, style and fonts within those bounds, asserting no overflow, no ellipsis, complete character coverage and line widths that fit. This avoids Compose 1.12.1's String-text inspection helper pairing a wrap-content text size with the wider parent paragraph constraints. Pixel assertions reject empty or flat placeholder captures. The three images were also opened at their original resolution for visual review.

These captures establish the tested component layouts and interactions in the native test host. They do not establish physical-device behavior, every application theme, live network behavior, or the appearance of navigation animations.
