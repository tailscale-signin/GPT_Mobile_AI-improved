package com.example.gpt_mobile_ai.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.gpt_mobile_ai.R
import com.example.gpt_mobile_ai.LocalRuntimeRouter
import com.example.gpt_mobile_ai.QnnEnvironment
import com.example.gpt_mobile_ai.DeviceHardwareGovernor

/**
 * Fragment for displaying debug diagnostics information
 */
class DebugDiagnosticsFragment : Fragment() {
    private lateinit var diagnosticManager: DiagnosticManager
    private lateinit var diagnosticsTextView: TextView
    private lateinit var reportButton: Button
    private lateinit var runtimeStatusButton: Button
    private lateinit var hardwareStatusButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize diagnostic manager
        val context = requireContext()
        diagnosticManager = DiagnosticManager(context)
        diagnosticManager.initialize()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_debug_diagnostics, container, false)
        
        diagnosticsTextView = view.findViewById(R.id.tv_diagnostics_info)
        reportButton = view.findViewById(R.id.btn_report_diagnostics)
        runtimeStatusButton = view.findViewById(R.id.btn_runtime_status)
        hardwareStatusButton = view.findViewById(R.id.btn_hardware_status)
        
        // Set up button click listeners
        reportButton.setOnClickListener {
            reportDiagnostics()
        }
        
        runtimeStatusButton.setOnClickListener {
            showRuntimeStatus()
        }
        
        hardwareStatusButton.setOnClickListener {
            showHardwareStatus()
        }
        
        // Display initial diagnostics
        displayDiagnostics()
        
        return view
    }

    private fun displayDiagnostics() {
        try {
            val diagnostics = diagnosticManager.collectDiagnostics()
            val formattedDiagnostics = formatDiagnostics(diagnostics)
            diagnosticsTextView.text = formattedDiagnostics
        } catch (e: Exception) {
            diagnosticsTextView.text = "Failed to collect diagnostics: ${e.message}"
        }
    }

    private fun reportDiagnostics() {
        try {
            diagnosticManager.reportAllDiagnostics()
            // In a real implementation, we might want to show a success message
        } catch (e: Exception) {
            // Handle error appropriately
            diagnosticsTextView.text = "Failed to report diagnostics: ${e.message}"
        }
    }

    private fun showRuntimeStatus() {
        try {
            val context = requireContext()
            val router = LocalRuntimeRouter(context)
            router.initialize()
            
            val status = router.getStatus()
            val formattedStatus = formatStatus(status)
            diagnosticsTextView.text = "Runtime Status:\n$formattedStatus"
            
            router.cleanup()
        } catch (e: Exception) {
            diagnosticsTextView.text = "Failed to get runtime status: ${e.message}"
        }
    }

    private fun showHardwareStatus() {
        try {
            val context = requireContext()
            val governor = DeviceHardwareGovernor(context)
            governor.initialize()
            
            val health = governor.getDeviceHealth()
            val formattedHealth = formatHealth(health)
            diagnosticsTextView.text = "Hardware Health:\n$formattedHealth"
            
            governor.cleanup()
        } catch (e: Exception) {
            diagnosticsTextView.text = "Failed to get hardware status: ${e.message}"
        }
    }

    private fun formatDiagnostics(diagnostics: Map<String, Any>): String {
        val sb = StringBuilder()
        
        diagnostics.forEach { (key, value) ->
            sb.append("$key: $value\n")
        }
        
        return sb.toString()
    }

    private fun formatStatus(status: Map<String, Any>): String {
        val sb = StringBuilder()
        
        status.forEach { (key, value) ->
            sb.append("  $key: $value\n")
        }
        
        return sb.toString()
    }

    private fun formatHealth(health: Map<String, Any>): String {
        val sb = StringBuilder()
        
        health.forEach { (key, value) ->
            sb.append("  $key: $value\n")
        }
        
        return sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        diagnosticManager.cleanup()
    }
}