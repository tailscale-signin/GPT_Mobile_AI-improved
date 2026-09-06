package dev.melo.gptmobile.improved.util

class DefaultHashMap<K, V>(
    private val defaultValue: () -> V,
    private val map: HashMap<K, V> = HashMap()
) : Map<K, V> by map {
    override operator fun get(key: K): V {
        var value = map[key]
        if (value == null) {
            value = defaultValue()
            map[key] = value
        }
        return value
    }

    operator fun set(key: K, value: V) {
        map[key] = value
    }

    fun contains(key: K): Boolean = map.containsKey(key)
    fun remove(key: K): V? = map.remove(key)
    fun clear() = map.clear()
}
