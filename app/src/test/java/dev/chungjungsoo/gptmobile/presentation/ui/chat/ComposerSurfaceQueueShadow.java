package dev.chungjungsoo.gptmobile.presentation.ui.chat;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceControl;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/**
 * Robolectric 4.17's SDK 34 BLAST shadow returns null with legacy graphics. Supply only its
 * missing Surface lifecycle so the real Magnifier can dismiss without enabling native HWUI
 * rendering. Magnifier, input dispatch, Compose focus, and editor semantics remain unchanged.
 */
@Implements(
    className = "android.graphics.BLASTBufferQueue",
    isInAndroidSdk = false,
    minSdk = 34,
    maxSdk = 34)
public final class ComposerSurfaceQueueShadow {
  private static final Object LOCK = new Object();
  private static final List<Lease> LIVE = new ArrayList<>();
  private final List<Lease> owned = new ArrayList<>();
  private int width;
  private int height;

  @Implementation
  protected void update(SurfaceControl control, int width, int height, int format) {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("Surface dimensions must be positive");
    }
    this.width = width;
    this.height = height;
  }

  @Implementation
  protected Surface createSurfaceWithHandle() {
    synchronized (LOCK) {
      if (width <= 0 || height <= 0) {
        throw new IllegalStateException("Queue must be sized before creating a Surface");
      }
      SurfaceTexture texture = new SurfaceTexture(false);
      texture.setDefaultBufferSize(width, height);
      Lease lease = new Lease(new Surface(texture), texture);
      owned.add(lease);
      LIVE.add(lease);
      return lease.surface;
    }
  }

  @Implementation
  protected Surface createSurface() {
    return createSurfaceWithHandle();
  }

  @Implementation
  protected void destroy() {
    synchronized (LOCK) {
      for (Lease lease : owned) {
        lease.close();
        LIVE.remove(lease);
      }
      owned.clear();
    }
  }

  public static int liveSurfaceCount() {
    synchronized (LOCK) {
      return LIVE.size();
    }
  }

  /** Runs after Compose teardown and also when Robolectric resets this test's sandbox. */
  @Resetter
  public static void releaseAllSurfaces() {
    synchronized (LOCK) {
      for (Lease lease : LIVE) {
        lease.close();
      }
      LIVE.clear();
    }
  }

  private static final class Lease {
    final Surface surface;
    final SurfaceTexture texture;

    Lease(Surface surface, SurfaceTexture texture) {
      this.surface = surface;
      this.texture = texture;
    }

    void close() {
      surface.release();
      texture.release();
    }
  }
}
