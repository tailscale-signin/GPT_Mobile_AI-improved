package dev.chungjungsoo.gptmobile.data.voice

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.localruntime.QnnEnvironment
import dev.chungjungsoo.gptmobile.data.localruntime.QualcommSocSupport
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

internal class PocketCompiledCleanupFailure(val retainedResources: Any?, cause: Throwable) : IllegalStateException("Pocket native retirement is unconfirmed.", cause)

/** Real CompiledModel graph execution; requested placement never becomes a hardware proof. */
internal class PocketCompiledRuntime private constructor(
    private val environment: Environment,
    val languageModel: Graph,
    val transformer: Graph,
    val vocoder: Graph,
    val requested: Accelerator,
    val cpuThreads: Int
) : AutoCloseable {
    fun resetMeasurements() {
        listOf(languageModel, transformer, vocoder).forEach { it.invocations = 0 }
    }

    fun receipt(): String = "lm_requested=${requested.name};mimi_transformer_requested=CPU;vocoder_requested=${requested.name};" +
        "lm_invocations=${languageModel.invocations};transformer_invocations=${transformer.invocations};vocoder_invocations=${vocoder.invocations};" +
        "cpu_transformer_threads=$cpuThreads;kernel_attribution=NOT_MEASURED;auto_cpu_fallback=DISABLED;hardware_qualified=false"

    override fun close() {
        val graphs = listOf(vocoder, transformer, languageModel)
        val failures = graphs.mapNotNull { runCatching { it.close() }.exceptionOrNull() }
        // The environment must outlive every graph and tensor. An uncertain graph retirement
        // quarantines that environment too; closing it would invalidate a possibly live model.
        if (failures.isNotEmpty()) throw PocketCompiledCleanupFailure(listOf(graphs, environment), failures.first())
        environment.close()
    }

    internal class Graph private constructor(
        private val model: CompiledModel,
        private val inputs: List<TensorBuffer>,
        private val outputs: List<TensorBuffer>
    ) : PocketCompiledGraph {
        @Volatile var invocations = 0L

        override fun run(inputs: List<FloatArray>): FloatArray {
            require(inputs.size == this.inputs.size) { "POCKET_GRAPH_INPUT_COUNT" }
            inputs.forEachIndexed { index, values -> this.inputs[index].writeFloat(values) }
            model.run(this.inputs, outputs)
            invocations++
            return outputs.single().readFloat()
        }

        override fun close() {
            val resources = inputs + outputs + model
            val failures = resources.mapNotNull { runCatching { it.close() }.exceptionOrNull() }
            if (failures.isNotEmpty()) throw PocketCompiledCleanupFailure(resources, failures.first())
        }

        companion object {
            fun open(file: File, options: CompiledModel.Options, environment: Environment): Graph {
                val model = CompiledModel.create(file.absolutePath, options, environment)
                val allocated = ArrayList<TensorBuffer>()
                try {
                    val inputs = model.createInputBuffers().also(allocated::addAll)
                    val outputs = model.createOutputBuffers().also(allocated::addAll)
                    require(outputs.size == 1) { "POCKET_GRAPH_OUTPUT_COUNT" }
                    return Graph(model, inputs, outputs)
                } catch (error: Throwable) {
                    val resources = allocated + model
                    val failures = resources.mapNotNull { runCatching { it.close() }.exceptionOrNull() }
                    if (failures.isNotEmpty()) throw PocketCompiledCleanupFailure(resources, error)
                    throw error
                }
            }
        }
    }

    companion object {
        fun open(context: Context, directory: File, backend: VoiceSpeechBackend, threads: Int, checkActive: () -> Unit): PocketCompiledRuntime {
            require(backend.isCompiledPocket && threads in 1..64)
            checkActive()
            val requested = if (backend == VoiceSpeechBackend.POCKET_GPU) Accelerator.GPU else Accelerator.NPU
            val environment = preparePocketCompiledGraph(backend, PocketCompiledPreparationGraph.ENVIRONMENT) {
                val environmentOptions = if (requested == Accelerator.NPU) {
                    check(!BuildConfig.GENIEX_ENABLED) { "POCKET_NPU_REQUIRES_STANDARD_ANDROID_BUILD" }
                    val probe = QnnEnvironment.prepareForExecution(context)
                    check(probe.prerequisitesReady) { "POCKET_NPU_PREREQUISITES_UNAVAILABLE" }
                    val vendor = PocketCompiledNpuLibraries.prepare(context, probe, checkActive)
                    mapOf(
                        Environment.Option.CompilerPluginLibraryDir to vendor.absolutePath,
                        Environment.Option.DispatchLibraryDir to vendor.absolutePath
                    )
                } else {
                    emptyMap()
                }
                // The context overload supplies LiteRT's compiler cache directory. The map-only
                // overload silently disables that cache, recompiling the NPU graphs on every open.
                Environment.create(context, environmentOptions)
            }
            val opened = ArrayList<Graph>()
            try {
                checkActive()
                val accelerated = pocketCompiledAccelerationOptions(requested)
                val cpu = CompiledModel.Options(Accelerator.CPU).apply { cpuOptions = CompiledModel.CpuOptions(numThreads = threads) }
                fun graph(name: String, options: CompiledModel.Options, stage: PocketCompiledPreparationGraph): Graph {
                    checkActive()
                    return preparePocketCompiledGraph(backend, stage) { Graph.open(File(directory, name), options, environment) }.also {
                        opened.add(it)
                        checkActive()
                    }
                }
                val languageModel = graph(PocketCompiledSpeechCatalog.LM, accelerated, PocketCompiledPreparationGraph.LANGUAGE_MODEL)
                // The publisher documents degraded Mimi-transformer GPU audio, including FP32.
                val transformer = graph(PocketCompiledSpeechCatalog.DECODER_TRANSFORMER, cpu, PocketCompiledPreparationGraph.DECODER_TRANSFORMER)
                val vocoder = graph(PocketCompiledSpeechCatalog.VOCODER, accelerated, PocketCompiledPreparationGraph.VOCODER)
                return PocketCompiledRuntime(environment, languageModel, transformer, vocoder, requested, threads)
            } catch (error: Throwable) {
                val graphs = opened.asReversed()
                val failures = graphs.mapNotNull { runCatching { it.close() }.exceptionOrNull() }
                if (failures.isNotEmpty() || error is PocketCompiledCleanupFailure) throw PocketCompiledCleanupFailure(listOf(graphs, environment, error), error)
                try {
                    environment.close()
                } catch (retirement: Throwable) {
                    throw PocketCompiledCleanupFailure(listOf(environment, error), retirement)
                }
                throw error
            }
        }
    }
}

/**
 * Keep LiteRT 2.4.1's matching compiler/dispatch separate from the existing LiteRT-LM dispatch.
 * Both use the app's one audited QAIRT 2.52 runtime. The build verifies the official AAR and each
 * extracted library; this boundary verifies the private files again before loading native code.
 */
internal object PocketCompiledNpuLibraries {
    const val ASSET_DIRECTORY = "pocket-compiled-npu-2.4.1"
    private val artifacts = mapOf(
        "libLiteRtCompilerPlugin_Qualcomm.so" to (953_512L to "745fe1a13e173950ad2520980728d138085b3c1cc945c7d729f527fc1e7abfab"),
        "libLiteRtDispatch_Qualcomm.so" to (461_880L to "a5cdbc8da207eae2f9822fe6b19c61a8d26a584dbc71bb7a986b82195887a74e")
    )

    @Synchronized
    fun prepare(context: Context, probe: QnnEnvironment.QnnProbeStatus, checkActive: () -> Unit): File {
        check(probe.prerequisitesReady && probe.libraryDigestStatus == "TRUSTED_HASH_PASS") { "POCKET_NPU_PREREQUISITES_UNAVAILABLE" }
        val directory = File(context.noBackupFilesDir, ASSET_DIRECTORY)
        val libraries = artifacts.mapValues { (name, artifact) ->
            PocketCompiledLibraryLayout.Library(artifact.first, artifact.second) { context.assets.open("$ASSET_DIRECTORY/$name") }
        }.toMutableMap()
        val audit = JSONObject(context.assets.open("local-runtime-native-libraries.json").bufferedReader().use { it.readText() }).getJSONObject("libraries")
        // LiteRT's pinned Qualcomm API has no separate QNN-library directory option. Its
        // loader resolves QNN beside the private dispatch first; changing LD_LIBRARY_PATH
        // after Android creates the linker namespace cannot establish that dependency path.
        // Copy only already-audited dependencies, preserving the LiteRT-LM dispatch in place.
        for (name in QualcommSocSupport.requiredLibraries(probe.socModel).filter { it.startsWith("libQnn") }) {
            val expected = audit.getJSONObject("lib/arm64-v8a/$name")
            val source = File(probe.dispatchDir, name)
            libraries[name] = PocketCompiledLibraryLayout.Library(expected.getLong("bytes"), expected.getString("sha256")) {
                check(!Files.isSymbolicLink(source.toPath())) { "POCKET_NPU_LIBRARY_SOURCE" }
                source.inputStream()
            }
        }
        return PocketCompiledLibraryLayout.prepare(directory, libraries, File(context.noBackupFilesDir, "model-identity-cache"), checkActive)
    }
}
