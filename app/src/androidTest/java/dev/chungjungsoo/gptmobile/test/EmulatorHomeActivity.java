package dev.chungjungsoo.gptmobile.test;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

/** Minimal HOME for disposable CI emulators; never included in the application APK. */
public final class EmulatorHomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View background = new View(this);
        background.setBackgroundColor(Color.BLACK);
        setContentView(background);
    }
}
