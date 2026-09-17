package dev.chungjungsoo.gptmobile.presentation.ui.voice

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity

class VoiceAssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = intent?.action
            data = intent?.data
        }
        startActivity(launchIntent)
        finish()
    }
}
