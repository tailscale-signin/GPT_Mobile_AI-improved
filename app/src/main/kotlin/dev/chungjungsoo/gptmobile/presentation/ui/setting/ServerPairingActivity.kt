package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.pairing.PairedServer
import dev.chungjungsoo.gptmobile.data.pairing.PairingLink
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString
import kotlinx.serialization.json.Json

/** Opening a QR/link only previews its destination. Fetch and profile creation each require a tap. */
@AndroidEntryPoint
class ServerPairingActivity : ComponentActivity() {
    @Inject lateinit var network: NetworkClient

    @Inject lateinit var settings: SettingRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = runCatching { PairingLink.parse(intent.dataString.orEmpty()) }.getOrNull()
        setContent {
            GPTMobileTheme {
                var server by remember { mutableStateOf<PairedServer?>(null) }
                var busy by remember { mutableStateOf(false) }
                var status by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()
                Surface {
                    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.pair_server_title), style = MaterialTheme.typography.headlineSmall)
                        if (link == null) {
                            Text(stringResource(R.string.pair_server_invalid))
                        } else {
                            Text(link.endpoint)
                            Text(stringResource(R.string.pair_server_preview))
                            val config = server
                            if (config == null) {
                                Button(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            check(link.expiresAt > System.currentTimeMillis() / 1000)
                                            withTimeout(15000) {
                                                // Never follow redirects with the one-time code.
                                                network().config { followRedirects = false }.use { client ->
                                                    val response = client.get(link.endpoint) { header("Authorization", "Bearer ${link.code}") }
                                                    check(response.status.value == 200)
                                                    val text = response.bodyAsChannel().readRemaining(16385).readString()
                                                    require(text.toByteArray().size <= 16384)
                                                    val value = Json.decodeFromString<PairedServer>(text)
                                                    value.profile()
                                                    server = value
                                                }
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            status = getString(R.string.pair_server_failed)
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }) { Text(stringResource(R.string.pair_server_fetch)) }
                            } else {
                                Text("${config.name}\n${config.apiUrl}\n${config.model}")
                                Text(stringResource(R.string.pair_server_save_hint))
                                Button(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            settings.addPlatformV2(config.profile())
                                            status = getString(R.string.pair_server_saved)
                                            server = null
                                            finish()
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            status = getString(R.string.pair_server_failed)
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }) { Text(stringResource(R.string.save)) }
                            }
                            Text(status)
                        }
                        Button(onClick = { finish() }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }
}
