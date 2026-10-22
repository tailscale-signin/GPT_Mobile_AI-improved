package dev.chungjungsoo.gptmobile.data.crawl

import java.net.URI

internal object CrawlPolicy {
    fun url(raw: String): String {
        require(raw.length in 1..8192 && raw.none { it <= ' ' }) { "Invalid crawler URL" }
        val uri = URI(raw)
        val host = uri.host.orEmpty().lowercase()
        require(uri.scheme in setOf("https", "http") && host.isNotBlank() && uri.userInfo == null && uri.port in setOf(-1, 80, 443)) { "Crawler needs a public HTTP(S) URL on a standard web port" }
        require(host != "localhost" && !host.endsWith(".local") && !host.endsWith(".internal") && !host.endsWith(".localhost")) { "Private crawler destinations are unavailable" }
        if (host.matches(Regex("[0-9.]+")) || host.contains(':')) require(!dev.chungjungsoo.gptmobile.data.agent.tool.SpecialUseAddress.isSpecialUse(java.net.InetAddress.getByName(host))) { "Private crawler destinations are unavailable" }
        return URI("${uri.scheme}://$host${if (uri.port == -1) "" else ":${uri.port}"}${uri.rawPath.ifBlank { "/" }}${uri.rawQuery?.let { "?$it" } ?: ""}").toASCIIString()
    }
    fun inScope(raw: String, hosts: Set<String>): String? = runCatching { url(raw).takeIf { URI(it).host in hosts } }.getOrNull()

    /** Conservative RFC 9309 matching: specific agent groups, longest path rule, allow wins ties. */
    fun robotsAllows(text: String, target: String): Boolean {
        data class Group(val agents: MutableList<String> = mutableListOf(), val rules: MutableList<Pair<Boolean, String>> = mutableListOf())
        val groups = mutableListOf<Group>()
        var group = Group()
        for (line in text.lineSequence()) {
            val clean = line.substringBefore('#').trim()
            val field = clean.substringBefore(':').trim().lowercase()
            val value = clean.substringAfter(':', "").trim()
            if (field == "user-agent") {
                if (group.rules.isNotEmpty()) {
                    groups += group
                    group = Group()
                }
                group.agents += value.lowercase()
            } else if (field in setOf("allow", "disallow") && group.agents.isNotEmpty() && value.isNotEmpty()) {
                group.rules += (field == "allow") to value
            }
        }
        groups += group
        val specific = groups.filter { it.agents.any { agent -> agent != "*" && "gptmobilecrawler".contains(agent) } }
        val chosen = specific.ifEmpty { groups.filter { "*" in it.agents } }
        val uri = URI(target)
        val path = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        val match = chosen.flatMap { it.rules }.filter { (_, rule) ->
            val end = rule.endsWith('$')
            val pattern = rule.removeSuffix("$").split('*').joinToString(".*") { Regex.escape(it) }
            Regex("^$pattern${if (end) "$" else ""}").containsMatchIn(path)
        }.maxWithOrNull(compareBy<Pair<Boolean, String>> { it.second.replace("*", "").removeSuffix("$").length }.thenBy { it.first })
        return match?.first != false
    }
}
