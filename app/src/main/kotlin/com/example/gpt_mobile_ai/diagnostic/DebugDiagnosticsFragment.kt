package com.example.gpt_mobile_ai.diagnostic

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.gpt_mobile_ai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fragment for displaying debug diagnostics information
 */
class DebugDiagnosticsFragment : Fragment() {
    companion object {
        private const val TAG = "DebugDiagnosticsFragment"
    }

    private lateinit var diagnosticManager: DiagnosticManager
    private lateinit var coroutineScope: CoroutineScope

    // UI elements
    private var hardwareInfoTextView: TextView? = null
    private var runtimeInfoTextView: TextView? = null
    private var systemInfoTextView: TextView? = null
    private var performanceTextView: TextView? = null
    private var qnnInfoTextView: TextView? = null
    private var refreshButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugUtils.setDebugMode(true)
        coroutineScope = CoroutineScope(Dispatchers.Main)
        diagnosticManager = DiagnosticManager(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_debug_diagnostics, container, false)
        
        // Initialize UI elements
        hardwareInfoTextView = view.findViewById(R.id.hardware_info_text)
        runtimeInfoTextView = view.findViewById(R.id.runtime_info_text)
        systemInfoTextView = view.findViewById(R.id.system_info_text)
        performanceTextView = view.findViewById(R.id.performance_text)
        qnnInfoTextView = view.findViewById(R.id.qnn_info_text)
        refreshButton = view.findViewById(R.id.refresh_button)
        
        // Set up refresh button
        refreshButton?.setOnClickListener {
            refreshDiagnostics()
        }
        
        // Initial load
        refreshDiagnostics()
        
        return view
    }

    /**
     * Refresh diagnostic information
     */
    private fun refreshDiagnostics() {
        coroutineScope.launch {
            try {
                // Collect diagnostics
                val report = diagnosticManager.collectDiagnostics()
                
                // Update UI with collected information
                updateHardwareInfo(report.hardwareInfo)
                updateRuntimeInfo(report.runtimeInfo)
                updateSystemInfo(report.systemInfo)
                updatePerformanceInfo(report.performanceMetrics)
                updateQnnInfo(report.qnnInfo)
                
                DebugUtils.logDiagnosticInfo("Diagnostics refreshed successfully")
            } catch (e: Exception) {
                DebugUtils.logError("Error refreshing diagnostics", e)
            }
        }
    }

    /**
     * Update hardware information display
     */
    private fun updateHardwareInfo(hardwareInfo: DeviceHardwareInfo) {
        val memoryInfo = hardwareInfo.memoryInfo
        val storageInfo = hardwareInfo.storageInfo
        val cpuInfo = hardwareInfo.cpuInfo
        val gpuInfo = hardwareInfo.gpuInfo
        val qnnInfo = hardwareInfo.qnnInfo
        
        val hardwareText = buildString {
            appendLine("Memory Info:")
            appendLine("  Max Memory: ${(memoryInfo.maxMemory / (1024 * 1024)).toInt()} MB")
            appendLine("  Memory Class: ${memoryInfo.memoryClass} MB")
            
            appendLine("\nStorage Info:")
            appendLine("  Total Space: ${(storageInfo.totalSpace / (1024 * 1024)).toInt()} MB")
            appendLine("  Available Space: ${(storageInfo.availableSpace / (1024 * 1024)).toInt()} MB")
            
            appendLine("\nCPU Info:")
            appendLine("  CPU Name: ${cpuInfo.cpuName}")
            appendLine("  CPU Count: ${cpuInfo.cpuCount}")
            
            appendLine("\nGPU Info:")
            appendLine("  GPU Name: ${gpuInfo.gpuName}")
            appendLine("  GPU Count: ${gpuInfo.gpuCount}")
            
            appendLine("\nQNN Info:")
            appendLine("  QNN Support: ${qnnInfo.hasQnnSupport}")
            appendLine("  QNN Version: ${qnnInfo.qnnVersion}")
        }
        
        hardwareInfoTextView?.text = hardwareText
    }

    /**
     * Update runtime information display
     */
    private fun updateRuntimeInfo(runtimeInfo: Map<String, Any>) {
        val runtimeText = buildString {
            runtimeInfo.forEach { (key, value) ->
                appendLine("$key: $value")
            }
        }
        runtimeInfoTextView?.text = runtimeText
    }

    /**
     * Update system information display
     */
    private fun updateSystemInfo(systemInfo: Map<String, Any>) {
        val systemText = buildString {
            systemInfo.forEach { (key, value) ->
                appendLine("$key: $value")
            }
        }
        systemInfoTextView?.text = systemText
    }

    /**
     * Update performance information display
     */
    private fun updatePerformanceInfo(performanceMetrics: Map<String, Any>) {
        val performanceText = buildString {
            performanceMetrics.forEach { (key, value) ->
                appendLine("$key: $value")
            }
        }
        performanceTextView?.text = performanceText
    }

    /**
     * Update QNN information display
     */
    private fun updateQnnInfo(qnnInfo: Map<String, Any>) {
        val qnnText = buildString {
            qnnInfo.forEach { (key, value) ->
                appendLine("$key: $value")
            }
        }
        qnnInfoTextView?.text = qnnText
    }

    override fun onResume() {
        super.onResume()
        DebugUtils.logDiagnosticInfo("Debug diagnostics fragment resumed")
    }

    override fun onPause() {
        super.onPause()
        DebugUtils.logDiagnosticInfo("Debug diagnostics fragment paused")
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugUtils.logDiagnosticInfo("Debug diagnostics fragment destroyed")
        coroutineScope.cancel()
    }
}