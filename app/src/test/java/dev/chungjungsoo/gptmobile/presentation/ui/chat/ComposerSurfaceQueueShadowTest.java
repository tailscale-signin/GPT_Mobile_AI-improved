package dev.chungjungsoo.gptmobile.presentation.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceControl;
import android.widget.EditText;
import android.widget.Magnifier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, manifest = Config.NONE, shadows = ComposerSurfaceQueueShadow.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class ComposerSurfaceQueueShadowTest {
  @Before
  public void previousTestOwnsNoSurfaces() {
    assertEquals(0, ComposerSurfaceQueueShadow.liveSurfaceCount());
  }

  @After
  public void releaseRemainingTestSurfaces() {
    ComposerSurfaceQueueShadow.releaseAllSurfaces();
    assertEquals(0, ComposerSurfaceQueueShadow.liveSurfaceCount());
  }

  @Test
  public void realMagnifierDismissesWithoutChangingEditorFocusOrText() {
    try (ActivityController<Activity> controller =
        Robolectric.buildActivity(Activity.class).setup()) {
      EditText editor = new EditText(controller.get());
      editor.setText("A real focused editor");
      controller.get().setContentView(editor);
      editor.layout(0, 0, 300, 100);
      assertTrue(editor.requestFocus());
      Magnifier magnifier = new Magnifier.Builder(editor).build();
      magnifier.show(40, 30);
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      assertTrue(ComposerSurfaceQueueShadow.liveSurfaceCount() > 0);
      magnifier.dismiss();
      assertEquals(0, ComposerSurfaceQueueShadow.liveSurfaceCount());
      assertTrue(editor.hasFocus());
      assertEquals("A real focused editor", editor.getText().toString());
    }
  }

  @Test
  public void actualQueueCreatesDrawableSurfacesAndReleasesEveryOwnedHandle() throws Exception {
    Object queue = queue();
    Surface first = ReflectionHelpers.callInstanceMethod(queue, "createSurface");
    Surface second = ReflectionHelpers.callInstanceMethod(queue, "createSurfaceWithHandle");
    assertTrue(first.isValid());
    assertTrue(second.isValid());
    assertNotSame(first, second);
    Canvas canvas = first.lockCanvas(null);
    canvas.drawColor(Color.GREEN);
    first.unlockCanvasAndPost(canvas);
    ReflectionHelpers.callInstanceMethod(queue, "destroy");
    assertFalse(first.isValid());
    assertFalse(second.isValid());
    assertEquals(0, ComposerSurfaceQueueShadow.liveSurfaceCount());
  }

  @Test
  public void testTeardownReleasesAnUndismissedSurfaceAndNextTestStartsClean() throws Exception {
    Surface retained = ReflectionHelpers.callInstanceMethod(queue(), "createSurface");
    assertTrue(retained.isValid());
    ComposerSurfaceQueueShadow.releaseAllSurfaces();
    assertFalse(retained.isValid());
    assertEquals(0, ComposerSurfaceQueueShadow.liveSurfaceCount());
    Surface next = ReflectionHelpers.callInstanceMethod(queue(), "createSurface");
    assertTrue(next.isValid());
    assertEquals(1, ComposerSurfaceQueueShadow.liveSurfaceCount());
  }

  private Object queue() throws Exception {
    Class<?> type = Class.forName("android.graphics.BLASTBufferQueue");
    Object queue = ReflectionHelpers.callConstructor(
        type,
        ClassParameter.from(String.class, "composer-fixture"),
        ClassParameter.from(boolean.class, false));
    ReflectionHelpers.callInstanceMethod(
        queue,
        "update",
        ClassParameter.from(SurfaceControl.class, null),
        ClassParameter.from(int.class, 64),
        ClassParameter.from(int.class, 32),
        ClassParameter.from(int.class, 1));
    return queue;
  }
}
