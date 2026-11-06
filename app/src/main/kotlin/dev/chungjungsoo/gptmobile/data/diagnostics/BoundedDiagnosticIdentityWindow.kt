package dev.chungjungsoo.gptmobile.data.diagnostics

/** Duplicate flags are limited to a declared window; a legacy file with tiny lines cannot grow a history-sized set. */
internal class BoundedDiagnosticIdentityWindow(private val maximumRecords: Int = 8192, private val maximumCharacters: Int = 256 * 1024) {
    private val values = linkedSetOf<String>()
    var retainedCharacters: Int = 0
        private set
    var evictedRecords: Long = 0
        private set
    val size: Int get() = values.size

    init {
        require(maximumRecords > 0 && maximumCharacters > 0)
    }

    fun add(value: String): Boolean {
        if (value in values) return false
        if (value.length > maximumCharacters) {
            evictedRecords++
            return true
        }
        while (values.size >= maximumRecords || retainedCharacters > maximumCharacters - value.length) {
            val removed = values.first()
            values.remove(removed)
            retainedCharacters -= removed.length
            evictedRecords++
        }
        values += value
        retainedCharacters += value.length
        return true
    }
}
