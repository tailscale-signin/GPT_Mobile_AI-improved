package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.data.dto.CustomThemePalette
import dev.chungjungsoo.gptmobile.presentation.common.LocalCustomPalette
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeViewModel

@Composable
fun CustomPaletteEditor() {
    val current = LocalCustomPalette.current
    val theme = LocalThemeViewModel.current
    val scheme = MaterialTheme.colorScheme
    val labels = listOf("Primary", "Secondary", "Background", "Surface")
    val initial = listOf(current?.primary ?: scheme.primary.toArgb().toLong(), current?.secondary ?: scheme.secondary.toArgb().toLong(), current?.background ?: scheme.background.toArgb().toLong(), current?.surface ?: scheme.surface.toArgb().toLong())
    var values by remember(current) { mutableStateOf(initial.map { "#%06X".format(it and 0xFFFFFF) }) }
    val colors = values.map { text -> text.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { it or 0xFF000000 } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Custom palette", style = MaterialTheme.typography.titleMedium)
        Text("Start from your current theme. Text contrast adjusts automatically.", style = MaterialTheme.typography.bodySmall)
        labels.forEachIndexed { index, name ->
            OutlinedTextField(
                value = values[index],
                onValueChange = { value -> values = values.toMutableList().apply { set(index, value.take(7)) } },
                label = { Text(name) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = colors[index] == null
            )
        }
        if (colors.all { it != null }) {
            val primary = Color(colors[0]!!)
            val secondary = Color(colors[1]!!)
            Surface(color = Color(colors[2]!!), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = Color(colors[3]!!), shape = MaterialTheme.shapes.medium) {
                        Text("Conversation preview", Modifier.padding(12.dp), color = if (androidx.core.graphics.ColorUtils.calculateLuminance(colors[3]!!.toInt()) > 0.179) Color.Black else Color.White)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(color = primary, shape = MaterialTheme.shapes.small) { Text("Primary", Modifier.padding(8.dp), color = if (androidx.core.graphics.ColorUtils.calculateLuminance(primary.toArgb()) > 0.179) Color.Black else Color.White) }
                        Surface(color = secondary, shape = MaterialTheme.shapes.small) { Text("Secondary", Modifier.padding(8.dp), color = if (androidx.core.graphics.ColorUtils.calculateLuminance(secondary.toArgb()) > 0.179) Color.Black else Color.White) }
                    }
                }
            }
        }
        Button(onClick = { theme.updateCustomPalette(CustomThemePalette(colors[0]!!, colors[1]!!, colors[2]!!, colors[3]!!)) }, enabled = colors.all { it != null }, modifier = Modifier.fillMaxWidth()) { Text("Apply palette") }
        TextButton(onClick = { theme.updateCustomPalette(null) }, modifier = Modifier.fillMaxWidth()) { Text("Restore default app palette") }
    }
}
