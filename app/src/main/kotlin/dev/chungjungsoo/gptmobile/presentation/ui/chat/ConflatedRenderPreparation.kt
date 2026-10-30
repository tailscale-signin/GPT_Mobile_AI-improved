package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map

/** Finish the current frame while retaining only the newest pending render request. */
internal fun <T, R> Flow<T>.prepareConflatedRender(prepare: suspend (T) -> R): Flow<R> =
    conflate().map { prepare(it) }
