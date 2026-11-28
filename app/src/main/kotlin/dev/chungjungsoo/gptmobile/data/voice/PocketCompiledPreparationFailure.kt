package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException

internal enum class PocketCompiledPreparationGraph { ENVIRONMENT, LANGUAGE_MODEL, DECODER_TRANSFORMER, VOCODER, HOST_ASSETS }

/** Playback preparation failed; the graph identifies its observed boundary, not hardware placement. */
internal class PocketCompiledPreparationFailure(
    val backend: VoiceSpeechBackend,
    val graph: PocketCompiledPreparationGraph,
    cause: Throwable
) : IllegalStateException("Pocket ${backend.name} preparation failed at ${graph.name}.", cause)

internal fun <T> preparePocketCompiledGraph(backend: VoiceSpeechBackend, graph: PocketCompiledPreparationGraph, prepare: () -> T): T =
    try {
        prepare()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: PocketCompiledCleanupFailure) {
        throw failure
    } catch (failure: PocketCompiledPreparationFailure) {
        throw failure
    } catch (failure: Exception) {
        throw PocketCompiledPreparationFailure(backend, graph, failure)
    } catch (failure: LinkageError) {
        throw PocketCompiledPreparationFailure(backend, graph, failure)
    }
