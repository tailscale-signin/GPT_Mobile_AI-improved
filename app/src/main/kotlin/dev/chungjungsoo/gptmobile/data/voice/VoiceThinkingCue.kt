package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import java.util.Locale

/** An optional spoken status belongs to the active reply and never becomes assistant message text. */
internal data class VoiceThinkingCue(val text: String, val lookup: Boolean = false)

internal data class VoiceOpeningPlan(val identity: VoiceMessageIdentity, val cue: VoiceThinkingCue) {
    val delayMs: Long get() = if (cue.lookup) 180L else 850L
}

internal object VoiceTurnOpenings {
    private data class Lines(
        val lookup: List<String>,
        val checking: String,
        val compare: String,
        val explain: String,
        val drafting: String,
        val thinking: List<String>
    )

    private val languages = mapOf(
        "en" to Lines(listOf("Let me look that up for you.", "Let me check.", "Let me check the sources."), "I can find that for you.", "Let me compare those.", "Let me break that down.", "Sure, no problem.", listOf("Let me think.", "One moment.")),
        "fr" to Lines(listOf("Je vais vérifier ça.", "Je consulte les sources."), "Je peux vérifier ça.", "Je vais les comparer.", "Je vais vous expliquer.", "Oui, bien sûr.", listOf("Je réfléchis.", "Un instant.")),
        "es" to Lines(listOf("Voy a consultarlo.", "Estoy revisando las fuentes."), "Puedo comprobarlo.", "Voy a compararlos.", "Te lo explico.", "Claro, sin problema.", listOf("Déjame pensar.", "Un momento.")),
        "it" to Lines(listOf("Ora controllo.", "Sto consultando le fonti."), "Posso verificarlo.", "Faccio un confronto.", "Ora ti spiego.", "Certo, nessun problema.", listOf("Ci penso.", "Un momento.")),
        "pt" to Lines(listOf("Vou pesquisar isso.", "Estou consultando as fontes."), "Posso verificar isso.", "Vou fazer uma comparação.", "Vou explicar.", "Claro, sem problema.", listOf("Deixe-me pensar.", "Um momento.")),
        "hi" to Lines(listOf("मैं इसकी जानकारी देख रहा हूँ।", "मैं स्रोत देख रहा हूँ।"), "मैं इसकी जाँच कर सकता हूँ।", "चलिए तुलना करें।", "चलिए इसे समझें।", "हाँ, ज़रूर।", listOf("सोचने दीजिए।", "एक पल।")),
        "zh" to Lines(listOf("我来查一下。", "我正在查资料。"), "我可以帮你查。", "我来比较一下。", "我来解释一下。", "好的，没问题。", listOf("让我想想。", "请稍等。")),
        "ja" to Lines(listOf("調べてみます。", "資料を確認しています。"), "確認しますね。", "比べてみます。", "順に説明します。", "はい、わかりました。", listOf("少し考えますね。", "少々お待ちください。"))
    )

    private val exact = Regex("""(?iu)\b(?:exactly|verbatim|only|just (?:say|give|answer)|one word|no (?:preamble|commentary)|repeat|recite|translate|pronounce|spell|say|uniquement|seulement|mot pour mot|sans préambule|tradui\p{L}*|rép[èé]t\p{L}*|prononc\p{L}*|solo|solamente|literalmente|tradu\p{L}*|ripeti|apenas|somente|केवल|सिर्फ)\b|只(?:说|回答|输出)|不要前言|翻译|逐字|だけ|そのまま|翻訳|繰り返""")
    private val greeting = Regex("""(?iu)(?:hi|hey|hello|ok(?:ay)?|yes|no|thanks(?: you)?|thank you|bonjour|salut|merci|hola|gracias|ciao|grazie|olá|obrigad[oa]|नमस्ते|धन्यवाद|你好|谢谢|こんにちは|ありがとう)[.!?。！？\s]*""")
    private val comparing = Regex("""(?iu)\b(?:compar\p{L}*|contrast\p{L}*|versus|diff[ée]rence\p{L}*|difer[êe]n\p{L}*|तुलना)\b|比较|对比|比較|比べ""")
    private val explaining = Regex("""(?iu)\b(?:explain\p{L}*|expliqu\p{L}*|explic\p{L}*|spieg\p{L}*|समझा\p{L}*)\b|解释|说明|説明""")
    private val drafting = Regex("""(?iu)\b(?:draft|design|brainstorm|outline|compose|write|create|écri\p{L}*|escrib\p{L}*|rédig\p{L}*|esbo[çz]\p{L}*|redact\p{L}*|progett\p{L}*|मसौदा)\b|起草|设计|下書き""")
    private val finding = Regex("""(?iu)\b(?:look up|look for|search|find(?: out)?|check|recherch\p{L}*|vérifi\p{L}*|cherch\p{L}*|busc\p{L}*|pesquis\p{L}*|cerc\p{L}*|खोज\p{L}*)\b|查一下|查询|検索|調べ""")

    // These first-party operations only retrieve information. A mutating tool must never announce a search.
    private val lookupTools = setOf("web_search", "read_url", "read_file_slice", "github_search", "github_read", "deep_research_search_papers", "deep_research_read_paper", "deep_research_get_paper", "jina_crawler_fetch")

    private fun runningLookup(runId: String, events: List<ToolEvent>): Boolean = events.any {
        it.runId == runId &&
            it.status == ToolEventStatus.RUNNING &&
            it.startedAt != null &&
            it.completedAt == null &&
            !it.isError &&
            it.connectionUidSnapshot == null &&
            it.toolName == it.modelToolName &&
            it.toolName in lookupTools
    }

    fun plan(prompt: String, run: AgentRun, language: String, events: List<ToolEvent>): VoiceOpeningPlan? {
        if (run.status != AgentRunStatus.RUNNING || prompt.isBlank() || prompt.length > 8000 || exact.containsMatchIn(prompt) || greeting.matches(prompt.trim())) return null
        val locale = Locale.forLanguageTag(language).language
        val lines = languages[locale] ?: return null
        val lookup = runningLookup(run.runId, events)
        val text = when {
            lookup -> (if (locale == "en") VoiceSearchOpening.line(prompt, run.runId, events) else null)
                ?: lines.lookup[Math.floorMod(run.runId.hashCode(), lines.lookup.size)]
            comparing.containsMatchIn(prompt) -> lines.compare
            explaining.containsMatchIn(prompt) -> lines.explain
            drafting.containsMatchIn(prompt) -> lines.drafting
            finding.containsMatchIn(prompt) -> lines.checking
            else -> lines.thinking[Math.floorMod(run.runId.hashCode(), lines.thinking.size)]
        }
        return VoiceOpeningPlan(VoiceMessageIdentity(run.assistantMessageId, run.runId), VoiceThinkingCue(text, lookup))
    }
}
