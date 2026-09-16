package com.example.gpt_mobile_ai.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.gpt_mobile_ai.R

/**
 * Fragment for displaying debug diagnostics information
 */
class DebugDiagnosticsFragment : Fragment() {
    private lateinit var diagnosticManager: DiagnosticManager
    private lateinit var diagnosticsTextView: TextView
    private lateinit var reportButton: Button

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
        
        // Set up button click listener
        reportButton.setOnClickListener {
            reportDiagnostics()
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
        }
    }

    private fun formatDiagnostics(diagnostics: Map<String, Any>): String {
        val sb = StringBuilder()
        
        diagnostics.forEach { (key, value) ->
            sb.append("$key: $value\n")
        }
        
        return sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        diagnosticManager.cleanup()
    }
}