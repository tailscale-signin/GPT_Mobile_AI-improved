package dev.chungjungsoo.gptmobile.util

/**
 * Small implementation of HashMap, but with default values.
 * This way the get operator will not throw an error or null when accessing an unset key.
 * Inspired by Python collections defaultdict.
 */
open class DefaultHashMap<K, V>(protected val defaultValueProvider: () -> V) : HashMap<K, V>() {
    override operator fun get(key: K): V {
        if (containsKey(key)) {
            val value = super.get(key)
            if (value != null) return value
        }

        val defaultValue = defaultValueProvider()
        put(key, defaultValue)
        return defaultValue
    }
}
