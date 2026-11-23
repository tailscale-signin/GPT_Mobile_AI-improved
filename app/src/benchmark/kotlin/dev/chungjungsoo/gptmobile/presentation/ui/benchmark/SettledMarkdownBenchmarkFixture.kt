package dev.chungjungsoo.gptmobile.presentation.ui.benchmark

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Density
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatMarkdown

/** Synthetic settled transcript revisit plus live theme/font invalidation; no user data or network. */
@Composable
internal fun SettledMarkdownBenchmarkFixture() {
    var visible by remember { mutableStateOf(true) }
    var ready by remember { mutableStateOf(false) }
    var largeFont by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(false) }
    val content = remember {
        buildString {
            repeat(24) { index ->
                append("## Pinned source result $index\n\n")
                append("A settled source paragraph with **strong text**, a [retained receipt][receipt], and reproducible Unicode text: λ, ✓, résumé. ".repeat(5))
                append("\n\n| Check | Observation |\n|---|---|\n| Source | Exact revision |\n| Coverage | Partial and explicit |\n\n```kotlin\nfun observed$index() = \"retained\"\n```\n\n")
            }
            append("[receipt]: https://example.com/pinned-receipt\n")
        }
    }
    val density = LocalDensity.current
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeFont) 1.4f else 1f)) {
            Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        visible = !visible
                        ready = false
                    }, Modifier.testTag("settled_toggle")) { Text(if (visible) "Leave" else "Reopen") }
                    TextButton(onClick = { largeFont = !largeFont }, Modifier.testTag("settled_font")) { Text(if (largeFont) "Default text" else "Large text") }
                    TextButton(onClick = { dark = !dark }, Modifier.testTag("settled_theme")) { Text(if (dark) "Light" else "Dark") }
                }
                if (visible) {
                    LazyColumn(Modifier.weight(1f).testTag("settled_document")) {
                        item {
                            ChatMarkdown(content, contentIdentity = "settled-benchmark", onContentDrawn = { ready = true })
                        }
                    }
                    if (ready) Text("Settled content drawn", Modifier.testTag("settled_ready"))
                } else {
                    Text("Document closed", Modifier.testTag("settled_closed"))
                }
            }
        }
    }
}
