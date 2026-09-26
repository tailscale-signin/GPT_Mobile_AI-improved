package dev.chungjungsoo.gptmobile.presentation.ui.mcp

import androidx.annotation.DrawableRes
import dev.chungjungsoo.gptmobile.R

/** Bundled provider logos; source and attribution are recorded in docs/mcp-brand-assets.json. */
object McpBrandAssets {
    @DrawableRes
    fun drawableFor(iconName: String): Int? = when (iconName) {
        "linear" -> R.drawable.mcp_brand_linear
        "sentry" -> R.drawable.mcp_brand_sentry
        "vercel" -> R.drawable.mcp_brand_vercel
        "atlassian" -> R.drawable.mcp_brand_atlassian
        "ollama-mcp-bridge" -> R.drawable.mcp_brand_protocol
        "mcp-client-for-ollama" -> R.drawable.mcp_brand_mcp_client_for_ollama
        "houtini-lm" -> R.drawable.mcp_brand_houtini_lm
        "claude-lmstudio-bridge" -> R.drawable.mcp_brand_protocol
        "comfyui-mcp" -> R.drawable.mcp_brand_protocol
        "marm-memory" -> R.drawable.mcp_brand_marm_memory
        "truememory" -> R.drawable.mcp_brand_protocol
        "clawmem" -> R.drawable.mcp_brand_protocol
        "uteke" -> R.drawable.mcp_brand_protocol
        "sibyl-memory" -> R.drawable.mcp_brand_protocol
        "agentset" -> R.drawable.mcp_brand_agentset
        "dbhub" -> R.drawable.mcp_brand_dbhub
        "chat2db" -> R.drawable.mcp_brand_chat2db
        "dbx" -> R.drawable.mcp_brand_protocol
        "mcp-sqlite" -> R.drawable.mcp_brand_mcp_sqlite
        "mcp-alchemy" -> R.drawable.mcp_brand_protocol
        "tabularis" -> R.drawable.mcp_brand_tabularis
        "filesystem-mcp" -> R.drawable.mcp_brand_protocol
        "mcp-workspace-server" -> R.drawable.mcp_brand_protocol
        "terminal-guardian-mcp" -> R.drawable.mcp_brand_protocol
        "flyenv" -> R.drawable.mcp_brand_flyenv
        "smart-connections-mcp" -> R.drawable.mcp_brand_protocol
        "code-memory" -> R.drawable.mcp_brand_code_memory
        "unreal-mcp" -> R.drawable.mcp_brand_unrealengine
        "arcade-mcp" -> R.drawable.mcp_brand_protocol
        "llm-server-docs" -> R.drawable.mcp_brand_protocol
        "decisionnode" -> R.drawable.mcp_brand_protocol
        "qodex" -> R.drawable.mcp_brand_protocol
        "deskdrop" -> R.drawable.mcp_brand_protocol
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
