package dev.chungjungsoo.gptmobile.presentation.ui.chat;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.util.DisplayMetrics;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLegacyBitmap;

/**
 * Robolectric 4.17's legacy Bitmap shadow omits the six-argument factory used by Compose.
 * Retain its existing colored-bitmap allocation and lifecycle, adding only the optional density.
 * This fixture is scoped to the SDK 34 composer tests; it does not enable native HWUI rendering.
 */
@Implements(value = Bitmap.class, minSdk = 34, maxSdk = 34)
public final class ComposerBitmapShadow extends ShadowLegacyBitmap {
  @Implementation
  protected static Bitmap createBitmap(
      DisplayMetrics displayMetrics,
      int width,
      int height,
      Bitmap.Config config,
      boolean hasAlpha,
      ColorSpace colorSpace) {
    if (config == Bitmap.Config.HARDWARE) {
      throw new IllegalArgumentException("Cannot create a mutable bitmap with config HARDWARE");
    }
    // Call Android's supported overload so the inherited shadow handles allocation and validation.
    Bitmap bitmap = Bitmap.createBitmap(width, height, config, hasAlpha, colorSpace);
    if (displayMetrics != null) {
      bitmap.setDensity(displayMetrics.densityDpi);
    }
    return bitmap;
  }
}
