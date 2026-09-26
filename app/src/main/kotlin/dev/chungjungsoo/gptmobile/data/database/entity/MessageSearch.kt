package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = MessageV2::class)
@Entity(tableName = "messages_search")
data class MessageSearch(val content: String, val revisions: String)

/** Quote tokens so a search string is data, not FTS query syntax. */
fun messageSearchQuery(query: String): String = Regex("[\\p{L}\\p{N}_]+").findAll(query).take(12)
    .joinToString(" AND ") { "\"${it.value}\"*" }
