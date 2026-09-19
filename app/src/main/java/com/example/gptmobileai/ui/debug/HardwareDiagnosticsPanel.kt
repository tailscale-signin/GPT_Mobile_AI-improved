package com.example.gptmobileai.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.gptmobileai.data.model.HardwareDiagnostics
import com.example.gptmobileai.data.model.NetworkType
import com.example.gptmobileai.data.model.PowerMode
import com.example.gptmobileai.data.model.ThermalStatus

@Composable
fun HardwareDiagnosticsPanel(
    hardware: HardwareDiagnostics?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "🖥️ HARDWARE DIAGNOSTICS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            hardware?.let { hw ->
                Text(
                    text = "${hw.socModel} | ${hw.cpuCores} cores | ${hw.gpuModel}",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (hw.npuModel != null) {
                    Text(
                        text = "NPU: ${hw.npuModel} (QNN Ready: ${if (hw.qnnReady) "✅" else "❌"})",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricItem("RAM", "${hw.availableRamMb} / ${hw.totalRamGb} GB")
                    MetricItem("Thermal", thermalStatusText(hw.thermalStatus))
                }

                if (hw.gpuThermalCelsius != null) {
                    Text(
                        text = "GPU: ${hw.gpuThermalCelsius.toInt()}°C | NPU: ${hw.npuThermalCelsius?.toInt() ?: "N/A"}°C",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricItem("Battery", "${hw.batteryLevel}%")
                    MetricItem("Network", networkTypeText(hw.networkType))
                }

                Text(
                    text = "Power Mode: ${powerModeText(hw.powerMode)}",
                    style = MaterialTheme.typography.bodySmall
                )
            } ?: run {
                Text(
                    text = "No hardware data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(modifier = Modifier.width(100.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun thermalStatusText(status: ThermalStatus): String {
    return when (status) {
        ThermalStatus.NORMAL -> "Normal"
        ThermalStatus.WARNING -> "⚠️ Warning"
        ThermalStatus.CRITICAL -> "🔥 Critical"
    }
}

private fun networkTypeText(type: NetworkType): String {
    return when (type) {
        NetworkType.WIFI -> "WiFi"
        NetworkType.CELLULAR_5G -> "5G"
        NetworkType.CELLULAR_LTE -> "LTE"
        NetworkType.ETHERNET -> "Ethernet"
        NetworkType.UNKNOWN -> "Unknown"
    }
}

private fun powerModeText(mode: PowerMode): String {
    return when (mode) {
        PowerMode.NORMAL -> "Normal"
        PowerMode.PERFORMANCE -> "Performance"
        PowerMode.BATTERY_SAVER -> "Battery Saver"
    }
}
