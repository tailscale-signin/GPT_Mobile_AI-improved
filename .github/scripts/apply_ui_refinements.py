from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Expected source fragment not found: {label}")
    return text.replace(old, new, 1)


tool = Path("app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ToolTraceBlock.kt")
text = tool.read_text()
text = replace_once(
    text,
    "painter = painterResource(id = R.drawable.ic_gpt_mobile_foreground),",
    """painter = painterResource(
                id = if (info.monogram == GitHubTool.monogram && info.badgeColor == GitHubTool.badgeColor) {
                    R.drawable.ic_github
                } else {
                    R.drawable.ic_gpt_mobile_foreground
                },
            ),""",
    "GitHub icon",
)
text = replace_once(
    text,
    ".padding(start = 16.dp)\n            .fillMaxWidth()",
    ".padding(horizontal = 16.dp)\n            .fillMaxWidth()",
    "tool trace horizontal padding",
)
text = replace_once(
    text,
    ".background(MaterialTheme.colorScheme.surfaceVariant)\n            .semantics { contentDescription = traceBlockDescription },",
    ".background(Color.Black.copy(alpha = 0.18f))\n            .semantics { contentDescription = traceBlockDescription },",
    "tool trace background",
)
text = replace_once(
    text,
    ".padding(12.dp),\n            verticalAlignment = Alignment.CenterVertically,\n        ) {\n            ToolServiceCircleIcon(primaryServiceInfo)",
    ".padding(horizontal = 12.dp, vertical = 8.dp),\n            verticalAlignment = Alignment.CenterVertically,\n        ) {\n            ToolServiceCircleIcon(primaryServiceInfo)",
    "tool trace header padding",
)
tool.write_text(text)

home = Path("app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/HomeScreen.kt")
text = home.read_text()
text = replace_once(
    text,
    "import androidx.compose.foundation.ExperimentalFoundationApi\n",
    "import androidx.compose.foundation.ExperimentalFoundationApi\nimport androidx.compose.foundation.background\n",
    "background import",
)
text = replace_once(
    text,
    "import androidx.compose.ui.Alignment\n",
    "import androidx.compose.ui.Alignment\nimport androidx.compose.ui.draw.clip\nimport androidx.compose.ui.graphics.Brush\n",
    "gradient imports",
)
old = """    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .combinedClickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {"""
new = """    val shape = RoundedCornerShape(12.dp)
    val topColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val bottomColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .heightIn(min = 120.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
            .combinedClickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {"""
text = replace_once(text, old, new, "favorite message card")
home.write_text(text)
