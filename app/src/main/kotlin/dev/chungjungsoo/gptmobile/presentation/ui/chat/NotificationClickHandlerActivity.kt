package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.chungjungsoo.gptmobile.presentation.GPTMobileApp

/**
 * Activity that handles notification clicks and deep-links to the relevant conversation.
 * This activity is launched from notifications with a singleTask launch mode to prevent
 * multiple instances and ensure smooth navigation back to the app.
 */
class NotificationClickHandlerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract chat room ID from intent extras
        val chatRoomId = intent.getIntExtra("chatRoomId", 0)
        val chatRoomTitle = intent.getStringExtra("chatRoomTitle") ?: "AI Chat"

        // If we have a valid chat room ID, open the chat directly
        if (chatRoomId > 0) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("chatRoomId", chatRoomId)
                putExtra("chatRoomTitle", chatRoomTitle)
                action = "OPEN_CHAT_FROM_NOTIFICATION"
            }
            startActivity(intent)
            finish()
            return
        }

        // Fallback: just open the app's main activity
        val fallbackIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(fallbackIntent)
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Ensure the main activity is brought to front
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }
}