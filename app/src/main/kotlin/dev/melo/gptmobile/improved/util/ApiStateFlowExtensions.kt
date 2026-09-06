package dev.melo.gptmobile.improved.util

import kotlinx.coroutines.flow.MutableStateFlow

fun <T> MutableStateFlow<T>.updateIfChanged(function: (T) -> T) {
    while (true) {
        val prevValue = value
        val nextValue = function(prevValue)
        if (prevValue == nextValue || compareAndSet(prevValue, nextValue)) {
            return
        }
    }
}
