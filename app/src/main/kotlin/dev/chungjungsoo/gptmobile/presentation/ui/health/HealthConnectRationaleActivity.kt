package dev.chungjungsoo.gptmobile.presentation.ui.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme

/** Public Android rationale entry point. Displays static policy only; never reads health data or accepts consent. */
class HealthConnectRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GPTMobileTheme(themeMode = ThemeMode.SYSTEM) {
                LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { TextButton(onClick = ::finish) { Text("Back") } }
                    item { Text("Health data privacy", style = MaterialTheme.typography.headlineMedium) }
                    item { Text("Smartwatch Health Connect uses read-only access to show your synced wellness statistics and, only with separate consent, answer AI questions about them. Choose each category in Android's permission screen. No health records are written or deleted.") }
                    item { Text("What can be read", style = MaterialTheme.typography.titleLarge) }
                    item { Text("Steps, distance, active and total calories, heart rate, resting heart rate, heart rate variability, sleep, exercise sessions, oxygen saturation, respiratory rate, weight and hydration. Availability depends on your device and the apps writing into Health Connect. This plugin does not read routes, workout notes, reproductive records or medical records.") }
                    item { Text("AI sharing", style = MaterialTheme.typography.titleLarge) }
                    item { Text(HealthConnectPrivacy.AI_DISCLOSURE) }
                    item { Text("Storage and revocation", style = MaterialTheme.typography.titleLarge) }
                    item { Text("The plugin has no health-record database, automatic synchronization, background collection or raw-health export. Local previews stay in memory and are cleared when the screen is left. Raw health tool responses and arguments are redacted from saved tool traces and recovery checkpoints. AI-sharing consent is device-local and excluded from configuration backups. Disconnect in the plugin or revoke permissions in Health Connect to stop future reads. Disconnect does not delete existing conversations, provider-held data or your Health Connect records.") }
                    item { Text("Limitations", style = MaterialTheme.typography.titleLarge) }
                    item { Text("The app reads only a bounded window within the last 30 days, while visible and the phone is unlocked. It cannot force a watch to sync. Query time is not source sync time. Missing measurements are not zero; source records may be delayed or incomplete. This is wellness information, not medical diagnosis, emergency monitoring or treatment advice.") }
                }
            }
        }
    }
}

object HealthConnectPrivacy {
    const val AI_DISCLOSURE = "When you enable AI sharing and select a profile, requested health statistics and source/time metadata may be sent to that model's configured provider, gateway and delegates to answer your question. This may include remote cloud providers. Only enable profiles and destinations you trust. The provider's own privacy and retention terms apply. AI replies and any health details you type can be saved in conversations, backups, memory or exports under the app's existing settings. Raw tool-trace redaction does not remove those replies. Consent is optional, off by default and revocable here; a local statistics preview does not need AI consent."
}
