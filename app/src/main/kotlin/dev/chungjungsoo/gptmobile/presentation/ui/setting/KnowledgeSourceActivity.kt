package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeChunk
import dev.chungjungsoo.gptmobile.data.knowledge.MemoryDocumentRepository
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class KnowledgeSourceActivity : ComponentActivity() {
    @Inject lateinit var repository: MemoryDocumentRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var title by mutableStateOf("Document source")
        var chunks by mutableStateOf<List<KnowledgeChunk>>(emptyList())
        var unavailable by mutableStateOf(false)
        val documentId = intent.data?.lastPathSegment.orEmpty()
        val selected = intent.data?.getQueryParameter("chunk")?.toIntOrNull()
        lifecycleScope.launch {
            val document = repository.dao.document(documentId)
            unavailable = document == null || document.deleted
            if (!unavailable) {
                title = document!!.title
                chunks = repository.dao.chunks(documentId).let { all ->
                    all.filter { it.chunkIndex == selected } + all.filter { it.chunkIndex != selected }
                }
            }
        }
        setContent {
            GPTMobileTheme(themeMode = dev.chungjungsoo.gptmobile.data.model.ThemeMode.SYSTEM) {
                LazyColumn(Modifier.padding(20.dp)) {
                    item {
                        TextButton(onClick = ::finish) { Text("Back") }
                        Text(title, style = MaterialTheme.typography.titleLarge)
                    }
                    if (unavailable) item { Text("This source was removed or is no longer available.") }
                    items(chunks, key = { it.id }) { chunk ->
                        Card(Modifier.padding(vertical = 8.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Excerpt ${chunk.chunkIndex + 1} · characters ${chunk.startOffset}–${chunk.endOffset}", style = MaterialTheme.typography.labelMedium)
                                androidx.compose.foundation.text.selection.SelectionContainer { Text(chunk.text) }
                            }
                        }
                    }
                }
            }
        }
    }
}
