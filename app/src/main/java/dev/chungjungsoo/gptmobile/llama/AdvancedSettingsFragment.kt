package dev.chungjungsoo.gptmobile.llama

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import dev.chungjungsoo.gptmobile.databinding.FragmentAdvancedLlamaSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AdvancedSettingsFragment : Fragment() {

    private var _binding: FragmentAdvancedLlamaSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: AdvancedSettingsViewModel
    private lateinit var adapter: RouterModeAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdvancedLlamaSettingsBinding.inflate(inflater, container, false)
        viewModel = ViewModelProvider(this)[AdvancedSettingsViewModel::class.java]

        setupRecyclerView()
        setupObservers()
        setupListeners()

        return binding.root
    }

    private fun setupRecyclerView() {
        adapter = RouterModeAdapter(viewModel.models.value ?: emptyList())
        adapter.setOnModelClickListener { selectedModel ->
            binding.etSelectedModel.setText(selectedModel.id)
            Toast.makeText(requireContext(), "Selected ${selectedModel.name}", Toast.LENGTH_SHORT).show()
        }
        binding.recyclerViewModels.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AdvancedSettingsFragment.adapter
        }
    }

    private fun setupObservers() {
        viewModel.settings.observe(viewLifecycleOwner) { settings ->
            binding.etServerUrl.setText(settings.serverUrl)
            binding.etApiTimeout.setText(settings.apiTimeout.toString())
            binding.etMaxRetries.setText(settings.maxRetries.toString())
            binding.etRetryDelay.setText(settings.retryDelayMs.toString())
            binding.cbRouterModeEnabled.isChecked = settings.routerModeEnabled
            binding.etModelsMax.setText(settings.modelsMax.toString())
            binding.cbModelsAutoload.isChecked = settings.modelsAutoload
            binding.etModelsDir.setText(settings.modelsDir)
            binding.etModelsPreset.setText(settings.modelsPreset)
            binding.etSelectedModel.setText(settings.selectedModel)
            binding.etModelAliases.setText(settings.modelAliases)
            binding.etModelTags.setText(settings.modelTags)
            binding.etNGpuLayers.setText(settings.nGpuLayers.toString())
            binding.etNThreads.setText(settings.nThreads.toString())
            binding.etNBatch.setText(settings.nBatch.toString())
            binding.etNCtx.setText(settings.nCtx.toString())
            binding.etNParallel.setText(settings.nParallel.toString())
            binding.cbKVUnified.isChecked = settings.kvUnified
            binding.etTemperature.setText(settings.temperature.toString())
            binding.etTopP.setText(settings.topP.toString())
            binding.etTopK.setText(settings.topK.toString())
            binding.etMinP.setText(settings.minP.toString())
            binding.etTypicalP.setText(settings.typicalP.toString())
            binding.etRepeatLastN.setText(settings.repeatLastN.toString())
            binding.etRepeatPenalty.setText(settings.repeatPenalty.toString())
            binding.etFrequencyPenalty.setText(settings.frequencyPenalty.toString())
            binding.etPresencePenalty.setText(settings.presencePenalty.toString())
            binding.etMirostat.setText(settings.mirostat.toString())
            binding.etMirostatTau.setText(settings.mirostatTau.toString())
            binding.etMirostatEta.setText(settings.mirostatEta.toString())
            binding.cbPenalizeNewline.isChecked = settings.penalizeNewline
            binding.etSeed.setText(settings.seed.toString())
            binding.etStop.setText(settings.stop)
            binding.etStopSpecialTokens.setText(settings.stopSpecialTokens)
            binding.etGrammar.setText(settings.grammar)
            binding.etNPredict.setText(settings.nPredict.toString())
            binding.etNKeep.setText(settings.nKeep.toString())
            binding.etNHistory.setText(settings.nHistory.toString())
            binding.etNHistoryMax.setText(settings.nHistoryMax.toString())
            binding.etLoraBase.setText(settings.loraBase)
            binding.etLoraScale.setText(settings.loraScale.toString())
            binding.etLoraPrompt.setText(settings.loraPrompt)
            binding.cbEmbeddingEnabled.isChecked = settings.embeddingEnabled
            binding.etEmbeddingBatchSize.setText(settings.embeddingBatchSize.toString())
            binding.cbUseMmap.isChecked = settings.useMmap
            binding.cbUseMlock.isChecked = settings.useMlock
            binding.cbUseVllm.isChecked = settings.useVllm
            binding.cbUseFlashAttn.isChecked = settings.useFlashAttn
            binding.etGatewayPerformanceProfile.setText(settings.gatewayPerformanceProfile)
            binding.cbGatewaySlotPinning.isChecked = settings.gatewaySlotPinning
            binding.etGatewaySlotCount.setText(settings.gatewaySlotCount.toString())
            binding.cbGatewayCachePrompt.isChecked = settings.gatewayCachePrompt
            binding.etGatewayCacheReuse.setText(settings.gatewayCacheReuse.toString())
            binding.etGatewayReasoningEffort.setText(settings.gatewayReasoningEffort)
            binding.cbGatewayToolOptimization.isChecked = settings.gatewayToolOptimization
            binding.etGatewayToolSurfaceLimit.setText(settings.gatewayToolSurfaceLimit.toString())
            binding.etLogLevel.setText(settings.logLevel)
            binding.cbVerbose.isChecked = settings.verbose
            binding.cbShowMetrics.isChecked = settings.showMetrics
            binding.cbEnableCORS.isChecked = settings.enableCORS
            binding.cbUseCache.isChecked = settings.useCache
            binding.etCacheDir.setText(settings.cacheDir)
            binding.etStopTimeout.setText(settings.stopTimeout.toString())
            binding.etMaxInstances.setText(settings.maxInstances.toString())
            binding.etDefaultGenerationSettings.setText(settings.defaultGenerationSettings)
            binding.etWebuiSettings.setText(settings.webuiSettings)
        }

        viewModel.models.observe(viewLifecycleOwner) { models ->
            adapter.updateData(models)
        }

        viewModel.loading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }

        viewModel.saved.observe(viewLifecycleOwner) { saved ->
            if (saved) {
                Toast.makeText(requireContext(), "Settings saved successfully", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener {
            validateAndSave()
        }

        binding.btnReset.setOnClickListener {
            viewModel.resetToDefaults()
            Toast.makeText(requireContext(), "Settings reset to defaults", Toast.LENGTH_SHORT).show()
        }

        binding.btnLoadModels.setOnClickListener {
            viewModel.loadModels()
        }
    }

    private fun validateAndSave() {
        val errors = viewModel.validateSettings()
        if (errors.isNotEmpty()) {
            showErrorDialog(errors)
            return
        }

        viewModel.updateSettings(
            AdvancedSettings().apply {
                serverUrl = binding.etServerUrl.text.toString()
                apiTimeout = binding.etApiTimeout.text.toString().toIntOrNull() ?: 30000
                maxRetries = binding.etMaxRetries.text.toString().toIntOrNull() ?: 3
                retryDelayMs = binding.etRetryDelay.text.toString().toIntOrNull() ?: 1000
                routerModeEnabled = binding.cbRouterModeEnabled.isChecked
                modelsMax = binding.etModelsMax.text.toString().toIntOrNull() ?: 4
                modelsAutoload = binding.cbModelsAutoload.isChecked
                modelsDir = binding.etModelsDir.text.toString()
                modelsPreset = binding.etModelsPreset.text.toString()
                selectedModel = binding.etSelectedModel.text.toString()
                modelAliases = binding.etModelAliases.text.toString()
                modelTags = binding.etModelTags.text.toString()
                nGpuLayers = binding.etNGpuLayers.text.toString().toIntOrNull() ?: 35
                nThreads = binding.etNThreads.text.toString().toIntOrNull() ?: 8
                nBatch = binding.etNBatch.text.toString().toIntOrNull() ?: 512
                nCtx = binding.etNCtx.text.toString().toIntOrNull() ?: 8192
                nParallel = binding.etNParallel.text.toString().toIntOrNull() ?: 4
                kvUnified = binding.cbKVUnified.isChecked
                temperature = binding.etTemperature.text.toString().toFloatOrNull() ?: 0.7f
                topP = binding.etTopP.text.toString().toFloatOrNull() ?: 0.95f
                topK = binding.etTopK.text.toString().toIntOrNull() ?: 40
                minP = binding.etMinP.text.toString().toFloatOrNull() ?: 0.05f
                typicalP = binding.etTypicalP.text.toString().toFloatOrNull() ?: 1.0f
                repeatLastN = binding.etRepeatLastN.text.toString().toIntOrNull() ?: 64
                repeatPenalty = binding.etRepeatPenalty.text.toString().toFloatOrNull() ?: 1.1f
                frequencyPenalty = binding.etFrequencyPenalty.text.toString().toFloatOrNull() ?: 0.0f
                presencePenalty = binding.etPresencePenalty.text.toString().toFloatOrNull() ?: 0.0f
                mirostat = binding.etMirostat.text.toString().toIntOrNull() ?: 0
                mirostatTau = binding.etMirostatTau.text.toString().toFloatOrNull() ?: 5.0f
                mirostatEta = binding.etMirostatEta.text.toString().toFloatOrNull() ?: 0.1f
                penalizeNewline = binding.cbPenalizeNewline.isChecked
                seed = binding.etSeed.text.toString().toLongOrNull() ?: -1
                stop = binding.etStop.text.toString()
                stopSpecialTokens = binding.etStopSpecialTokens.text.toString()
                grammar = binding.etGrammar.text.toString()
                nPredict = binding.etNPredict.text.toString().toIntOrNull() ?: -1
                nKeep = binding.etNKeep.text.toString().toIntOrNull() ?: 0
                nHistory = binding.etNHistory.text.toString().toIntOrNull() ?: 0
                nHistoryMax = binding.etNHistoryMax.text.toString().toIntOrNull() ?: 0
                loraBase = binding.etLoraBase.text.toString()
                loraScale = binding.etLoraScale.text.toString().toFloatOrNull() ?: 1.0f
                loraPrompt = binding.etLoraPrompt.text.toString()
                embeddingEnabled = binding.cbEmbeddingEnabled.isChecked
                embeddingBatchSize = binding.etEmbeddingBatchSize.text.toString().toIntOrNull() ?: 8
                useMmap = binding.cbUseMmap.isChecked
                useMlock = binding.cbUseMlock.isChecked
                useVllm = binding.cbUseVllm.isChecked
                useFlashAttn = binding.cbUseFlashAttn.isChecked
                gatewayPerformanceProfile = binding.etGatewayPerformanceProfile.text.toString().trim().lowercase().ifBlank { "turbo" }
                gatewaySlotPinning = binding.cbGatewaySlotPinning.isChecked
                gatewaySlotCount = binding.etGatewaySlotCount.text.toString().toIntOrNull() ?: 1
                gatewayCachePrompt = binding.cbGatewayCachePrompt.isChecked
                gatewayCacheReuse = binding.etGatewayCacheReuse.text.toString().toIntOrNull() ?: 512
                gatewayReasoningEffort = binding.etGatewayReasoningEffort.text.toString().trim().lowercase().ifBlank { "low" }
                gatewayToolOptimization = binding.cbGatewayToolOptimization.isChecked
                gatewayToolSurfaceLimit = binding.etGatewayToolSurfaceLimit.text.toString().toIntOrNull() ?: 12
                logLevel = binding.etLogLevel.text.toString()
                verbose = binding.cbVerbose.isChecked
                showMetrics = binding.cbShowMetrics.isChecked
                enableCORS = binding.cbEnableCORS.isChecked
                useCache = binding.cbUseCache.isChecked
                cacheDir = binding.etCacheDir.text.toString()
                stopTimeout = binding.etStopTimeout.text.toString().toIntOrNull() ?: 60
                maxInstances = binding.etMaxInstances.text.toString().toIntOrNull() ?: 4
                defaultGenerationSettings = binding.etDefaultGenerationSettings.text.toString()
                webuiSettings = binding.etWebuiSettings.text.toString()
            }
        )
    }

    private fun showErrorDialog(errors: List<String>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Validation Errors")
            .setMessage(errors.joinToString("\n"))
            .setPositiveButton("OK") { _, _ -> }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
