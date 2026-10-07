package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.R
import java.util.Locale

internal data class ChatSourceBrand(
    val id: String,
    val name: String,
    val icon: Int,
    val color: Long,
    val domains: List<String>,
    val engineAliases: List<String> = emptyList(),
    val monochrome: Boolean = true
)

/** Bundled logos cover common references without leaking viewed sites to favicon services. */
internal val chatSourceBrands = listOf(
    ChatSourceBrand("wikipedia", "Wikipedia", R.drawable.chat_source_wikipedia, 0xFF000000, listOf("wikipedia.org")),
    ChatSourceBrand("github", "GitHub", R.drawable.chat_source_github, 0xFF181717, listOf("github.com", "github.io", "githubusercontent.com")),
    ChatSourceBrand("gitlab", "GitLab", R.drawable.chat_source_gitlab, 0xFFFC6D26, listOf("gitlab.com")),
    ChatSourceBrand("stackoverflow", "Stack Overflow", R.drawable.chat_source_stackoverflow, 0xFFF58025, listOf("stackoverflow.com")),
    ChatSourceBrand("stackexchange", "Stack Exchange", R.drawable.chat_source_stackexchange, 0xFF1E5397, listOf("stackexchange.com", "superuser.com", "serverfault.com", "askubuntu.com")),
    ChatSourceBrand("reddit", "Reddit", R.drawable.chat_source_reddit, 0xFFFF4500, listOf("reddit.com", "redd.it")),
    ChatSourceBrand("youtube", "YouTube", R.drawable.chat_source_youtube, 0xFFFF0000, listOf("youtube.com", "youtu.be")),
    ChatSourceBrand("medium", "Medium", R.drawable.chat_source_medium, 0xFF000000, listOf("medium.com")),
    ChatSourceBrand("devdotto", "DEV Community", R.drawable.chat_source_devdotto, 0xFF0A0A0A, listOf("dev.to")),
    ChatSourceBrand("arxiv", "arXiv", R.drawable.chat_source_arxiv, 0xFFB31B1B, listOf("arxiv.org")),
    ChatSourceBrand("pubmed", "PubMed", R.drawable.chat_source_pubmed, 0xFF326599, listOf("pubmed.ncbi.nlm.nih.gov", "ncbi.nlm.nih.gov")),
    ChatSourceBrand("scipy", "SciPy", R.drawable.chat_source_scipy, 0xFF8CAAE6, listOf("scipy.org")),
    ChatSourceBrand("googlescholar", "Google Scholar", R.drawable.chat_source_googlescholar, 0xFF4285F4, listOf("scholar.google.com")),
    ChatSourceBrand("doi", "DOI", R.drawable.chat_source_doi, 0xFFFAB70C, listOf("doi.org")),
    ChatSourceBrand("cnn", "CNN", R.drawable.chat_source_cnn, 0xFFCC0000, listOf("cnn.com")),
    ChatSourceBrand("theguardian", "The Guardian", R.drawable.chat_source_theguardian, 0xFF052962, listOf("theguardian.com")),
    ChatSourceBrand("newyorktimes", "The New York Times", R.drawable.chat_source_newyorktimes, 0xFF000000, listOf("nytimes.com")),
    ChatSourceBrand("arstechnica", "Ars Technica", R.drawable.chat_source_arstechnica, 0xFFFF4E00, listOf("arstechnica.com")),
    ChatSourceBrand("techcrunch", "TechCrunch", R.drawable.chat_source_techcrunch, 0xFF029F00, listOf("techcrunch.com")),
    ChatSourceBrand("imdb", "IMDb", R.drawable.chat_source_imdb, 0xFFF5C518, listOf("imdb.com")),
    ChatSourceBrand("goodreads", "Goodreads", R.drawable.chat_source_goodreads, 0xFF1E1914, listOf("goodreads.com")),
    ChatSourceBrand("quora", "Quora", R.drawable.chat_source_quora, 0xFFB92B27, listOf("quora.com")),
    ChatSourceBrand("pinterest", "Pinterest", R.drawable.chat_source_pinterest, 0xFFBD081C, listOf("pinterest.com")),
    ChatSourceBrand("instagram", "Instagram", R.drawable.chat_source_instagram, 0xFFFF0069, listOf("instagram.com")),
    ChatSourceBrand("facebook", "Facebook", R.drawable.chat_source_facebook, 0xFF0866FF, listOf("facebook.com", "fb.com")),
    ChatSourceBrand("x", "X", R.drawable.chat_source_x, 0xFF000000, listOf("x.com", "twitter.com", "t.co")),
    ChatSourceBrand("substack", "Substack", R.drawable.chat_source_substack, 0xFFFF6719, listOf("substack.com")),
    ChatSourceBrand("wordpress", "WordPress", R.drawable.chat_source_wordpress, 0xFF21759B, listOf("wordpress.com", "wordpress.org")),
    ChatSourceBrand("nasa", "NASA", R.drawable.chat_source_nasa, 0xFFE03C31, listOf("nasa.gov")),
    ChatSourceBrand("khanacademy", "Khan Academy", R.drawable.chat_source_khanacademy, 0xFF14BF96, listOf("khanacademy.org")),
    ChatSourceBrand("coursera", "Coursera", R.drawable.chat_source_coursera, 0xFF0056D2, listOf("coursera.org")),
    ChatSourceBrand("edx", "edX", R.drawable.chat_source_edx, 0xFF02262B, listOf("edx.org")),
    ChatSourceBrand("wolfram", "Wolfram", R.drawable.chat_source_wolfram, 0xFFDD1100, listOf("wolfram.com", "wolframalpha.com")),
    ChatSourceBrand("anthropic", "Anthropic", R.drawable.chat_source_anthropic, 0xFF191919, listOf("anthropic.com", "claude.ai")),
    ChatSourceBrand("huggingface", "Hugging Face", R.drawable.chat_source_huggingface, 0xFFFFD21E, listOf("huggingface.co")),
    ChatSourceBrand("pytorch", "PyTorch", R.drawable.chat_source_pytorch, 0xFFEE4C2C, listOf("pytorch.org")),
    ChatSourceBrand("tensorflow", "TensorFlow", R.drawable.chat_source_tensorflow, 0xFFFF6F00, listOf("tensorflow.org")),
    ChatSourceBrand("android", "Android Developers", R.drawable.chat_source_android, 0xFF3DDC84, listOf("developer.android.com", "android.com")),
    ChatSourceBrand("googlechrome", "Chrome Developers", R.drawable.chat_source_googlechrome, 0xFF4285F4, listOf("developer.chrome.com")),
    ChatSourceBrand("python", "Python", R.drawable.chat_source_python, 0xFF3776AB, listOf("python.org")),
    ChatSourceBrand("kotlin", "Kotlin", R.drawable.chat_source_kotlin, 0xFF7F52FF, listOf("kotlinlang.org")),
    ChatSourceBrand("unity", "Unity", R.drawable.chat_source_unity, 0xFFFFFFFF, listOf("unity.com", "unity3d.com")),
    ChatSourceBrand("unrealengine", "Unreal Engine", R.drawable.chat_source_unrealengine, 0xFF0E1128, listOf("unrealengine.com", "dev.epicgames.com")),
    ChatSourceBrand("rust", "Rust", R.drawable.chat_source_rust, 0xFF000000, listOf("rust-lang.org")),
    ChatSourceBrand("spring", "Spring", R.drawable.chat_source_spring, 0xFF000000, listOf("spring.io")),
    ChatSourceBrand("nodedotjs", "Node.js", R.drawable.chat_source_nodedotjs, 0xFF5FA04E, listOf("nodejs.org")),
    ChatSourceBrand("docker", "Docker", R.drawable.chat_source_docker, 0xFF2496ED, listOf("docker.com")),
    ChatSourceBrand("kubernetes", "Kubernetes", R.drawable.chat_source_kubernetes, 0xFF326CE5, listOf("kubernetes.io")),
    ChatSourceBrand("npm", "npm", R.drawable.chat_source_npm, 0xFFCB3837, listOf("npmjs.com")),
    ChatSourceBrand("mozilla", "Mozilla", R.drawable.chat_source_mozilla, 0xFF161616, listOf("mozilla.org", "developer.mozilla.org")),
    ChatSourceBrand("firefox", "Firefox", R.drawable.chat_source_firefox, 0xFFFF7139, listOf("firefox.com")),
    ChatSourceBrand("apple", "Apple", R.drawable.chat_source_apple, 0xFF000000, listOf("apple.com"))
)

internal val chatSearchEngineBrands = listOf(
    ChatSourceBrand("brave", "Brave Search", R.drawable.chat_source_brave, 0xFFFB542B, listOf("search.brave.com"), listOf("brave")),
    ChatSourceBrand("duckduckgo", "DuckDuckGo", R.drawable.chat_source_duckduckgo, 0xFFDE5833, listOf("duckduckgo.com"), listOf("duckduckgo", "ddg")),
    ChatSourceBrand("google", "Google", R.drawable.chat_source_google, 0xFF4285F4, listOf("google.com"), listOf("google", "googlesearch")),
    ChatSourceBrand("baidu", "Baidu", R.drawable.chat_source_baidu, 0xFF2932E1, listOf("baidu.com"), listOf("baidu")),
    ChatSourceBrand("ecosia", "Ecosia", R.drawable.chat_source_ecosia, 0xFF008009, listOf("ecosia.org"), listOf("ecosia")),
    ChatSourceBrand("startpage", "Startpage", R.drawable.chat_source_startpage, 0xFF6563FF, listOf("startpage.com"), listOf("startpage")),
    ChatSourceBrand("qwant", "Qwant", R.drawable.chat_source_qwant, 0xFF282B2F, listOf("qwant.com"), listOf("qwant")),
    ChatSourceBrand("kagi", "Kagi", R.drawable.chat_source_kagi, 0xFFFFB319, listOf("kagi.com"), listOf("kagi")),
    ChatSourceBrand("searxng", "SearXNG", R.drawable.chat_source_searxng, 0xFF3050FF, listOf("searx.space"), listOf("searxng", "searx")),
    ChatSourceBrand("perplexity", "Perplexity", R.drawable.chat_source_perplexity, 0xFF1FB8CD, listOf("perplexity.ai"), listOf("perplexity")),
    ChatSourceBrand("bing", "Bing", R.drawable.mcp_brand_microsoft, 0xFF0078D4, listOf("bing.com"), listOf("bing"), monochrome = false),
    ChatSourceBrand("tavily", "Tavily", R.drawable.mcp_brand_tavily, 0xFF635BFF, listOf("tavily.com"), listOf("tavily"), monochrome = false),
    ChatSourceBrand("exa", "Exa", R.drawable.mcp_brand_exa, 0xFF3755E8, listOf("exa.ai"), listOf("exasearch", "exa"), monochrome = false),
    ChatSourceBrand("jina", "Jina", R.drawable.mcp_brand_jina, 0xFF009191, listOf("jina.ai"), listOf("jinasearch", "jina"), monochrome = false),
    ChatSourceBrand("firecrawl", "Firecrawl", R.drawable.mcp_brand_firecrawl, 0xFFFF6B35, listOf("firecrawl.dev"), listOf("firecrawl"), monochrome = false)
)

internal fun chatSourceBrand(host: String): ChatSourceBrand? {
    val normalized = host.lowercase(Locale.ROOT).removePrefix("www.")
    return (chatSourceBrands + chatSearchEngineBrands).flatMap { brand ->
        brand.domains.filter { normalized == it || normalized.endsWith(".$it") }.map { it.length to brand }
    }.maxByOrNull { it.first }?.second
}

internal fun chatSearchEngineBrand(name: String): ChatSourceBrand? {
    val normalized = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    val tokens = name.lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+"))
    return chatSearchEngineBrands.firstOrNull { brand -> brand.engineAliases.any { if (it.length <= 3) it in tokens else normalized.contains(it) } }
}
