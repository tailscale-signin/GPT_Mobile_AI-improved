package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.combinedWordingSpans
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.model.ProfileColorIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PassageIndicatorSnapshot(
    val passage: String,
    val sources: List<CombinedModelResponse>,
    val matches: List<Pair<String, Set<String>>>
)

/** Matching wording gets the same profile badges as prose colours; shared matches retain all submitters. */
@Composable
internal fun CombinedPassageIndicators(
    passage: String,
    sources: List<CombinedModelResponse>,
    identities: Map<String, ProfileColorIdentity>,
    onOpenContribution: (String) -> Unit
) {
    val snapshot by produceState<PassageIndicatorSnapshot?>(null, passage, sources) {
        value = withContext(Dispatchers.Default) {
            PassageIndicatorSnapshot(
                passage,
                sources,
                combinedWordingSpans(passage, sources).map { span ->
                    passage.substring(span.start, span.end) to span.profileUids
                }
            )
        }
    }
    val matches = snapshot?.takeIf { it.passage == passage && it.sources == sources }?.matches.orEmpty()
    Column {
        matches.forEach { (sentence, profiles) ->
            Text(stringResource(R.string.combined_text_match_passage, sentence.take(72)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                profiles.forEach { uid ->
                    val source = sources.singleOrNull { it.platformUid == uid }
                    val identity = identities[uid]
                    TextButton(onClick = { onOpenContribution(uid) }) {
                        Text(
                            listOfNotNull(identity?.let { "[${it.marker}]" }, source?.platformName).joinToString(" "),
                            color = identity?.let { ProfileAttributionPalette.foreground(it, MaterialTheme.colorScheme.surface) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
