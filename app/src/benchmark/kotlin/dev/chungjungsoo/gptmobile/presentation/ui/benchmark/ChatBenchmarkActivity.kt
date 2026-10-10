package dev.chungjungsoo.gptmobile.presentation.ui.benchmark

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dev.chungjungsoo.gptmobile.presentation.service.AgentRunForegroundService
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatBottomAutoScroller
import dev.chungjungsoo.gptmobile.presentation.ui.chat.OpponentChatBubble
import kotlinx.coroutines.delay

/** Benchmark-only entry point. No accounts, user data or model/network requests. */
class ChatBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streaming = intent.getBooleanExtra("stream", false)
        setContent {
            MaterialTheme {
                var chunks by remember { mutableIntStateOf(1) }
                var input by remember { mutableStateOf("") }
                var serviceRaceDone by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (intent.getBooleanExtra("serviceRace", false)) {
                        repeat(20) {
                            AgentRunForegroundService.start(this@ChatBenchmarkActivity)
                            startService(Intent(this@ChatBenchmarkActivity, AgentRunForegroundService::class.java).setAction(AgentRunForegroundService.ACTION_CANCEL_ALL))
                            delay(30)
                            AgentRunForegroundService.start(this@ChatBenchmarkActivity)
                            delay(30)
                        }
                        delay(1000)
                        serviceRaceDone = true
                    }
                }
                val state = rememberLazyListState(initialFirstVisibleItemIndex = if (streaming) 999 else 0)
                LaunchedEffect(streaming) {
                    if (streaming) {
                        repeat(160) {
                            delay(25)
                            chunks++
                        }
                    }
                }
                Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth().testTag("benchmark_chat")) {
                        items(1000, key = { it }) { index ->
                            if (index % 2 == 0) {
                                Text("Question $index: explain this Kotlin example.")
                            } else {
                                OpponentChatBubble(canRetry = true, isLoading = streaming && index == 999, text = if (index == 999 && streaming) "Observed streaming text. ".repeat(chunks) else "## Answer $index\nA measured fixture with **formatting**, [a reference](https://example.com), and code.\n```kotlin\nval answer = 42\n```", contentIdentity = index)
                            }
                        }
                    }
                    OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth().testTag("benchmark_input"), singleLine = true)
                    if (serviceRaceDone) Text("Startup race completed", Modifier.testTag("benchmark_service_done"))
                    ChatBottomAutoScroller(state, isEnabled = streaming)
                }
            }
        }
    }
}
