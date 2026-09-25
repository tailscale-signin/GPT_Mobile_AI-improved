package dev.chungjungsoo.gptmobile.presentation.ui.mcp

import androidx.annotation.DrawableRes
import dev.chungjungsoo.gptmobile.R

/** Bundled provider logos; source and attribution are recorded in docs/mcp-brand-assets.json. */
object McpBrandAssets {
    @DrawableRes
    fun drawableFor(iconName: String): Int? = when (iconName) {
        "airtable" -> R.drawable.mcp_brand_airtable
        "asana" -> R.drawable.mcp_brand_asana
        "brave" -> R.drawable.mcp_brand_brave
        "brightdata" -> R.drawable.mcp_brand_brightdata
        "cloudflare" -> R.drawable.mcp_brand_cloudflare
        "context7" -> R.drawable.mcp_brand_context7
        "deepwiki" -> R.drawable.mcp_brand_deepwiki
        "exa" -> R.drawable.mcp_brand_exa
        "excalidraw" -> R.drawable.mcp_brand_excalidraw
        "firecrawl" -> R.drawable.mcp_brand_firecrawl
        "googledrive" -> R.drawable.mcp_brand_googledrive
        "googlesheets" -> R.drawable.mcp_brand_googlesheets
        "huggingface" -> R.drawable.mcp_brand_huggingface
        "jina" -> R.drawable.mcp_brand_jina
        "mem0" -> R.drawable.mcp_brand_mem0
        "microsoft" -> R.drawable.mcp_brand_microsoft
        "mnemoverse" -> R.drawable.mcp_brand_mnemoverse
        "neon" -> R.drawable.mcp_brand_neon
        "netlify" -> R.drawable.mcp_brand_netlify
        "prisma" -> R.drawable.mcp_brand_prisma
        "semgrep" -> R.drawable.mcp_brand_semgrep
        "slack" -> R.drawable.mcp_brand_slack
        "stackoverflow" -> R.drawable.mcp_brand_stackoverflow
        "stripe" -> R.drawable.mcp_brand_stripe
        "supabase" -> R.drawable.mcp_brand_supabase
        "supermemory" -> R.drawable.mcp_brand_supermemory
        "tavily" -> R.drawable.mcp_brand_tavily
        "todoist" -> R.drawable.mcp_brand_todoist
        "github" -> R.drawable.ic_github
        else -> null
    }
}
