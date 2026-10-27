package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthConfiguration
import dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity

/** Verified HTTPS callback; encrypted state and PKCE are validated before exchange. */
class GitHubAuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && callback != null && GitHubPublicAuthConfiguration.project().acceptsCallback(callback.toString())) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = callback
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
        finish()
    }
}
