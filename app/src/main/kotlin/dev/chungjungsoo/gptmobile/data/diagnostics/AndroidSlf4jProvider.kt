package dev.chungjungsoo.gptmobile.data.diagnostics

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/** Opt-in redacted library warnings; never enable protocol payload/debug logging. */
class AndroidSlf4jProvider : SLF4JServiceProvider {
    private val loggers = java.util.concurrent.ConcurrentHashMap<String, RedactedLogger>()
    private val factory = ILoggerFactory { name -> loggers.computeIfAbsent(name, ::RedactedLogger) }
    private val markers = BasicMarkerFactory()
    private val context = NOPMDCAdapter()
    override fun initialize() = Unit
    override fun getLoggerFactory(): ILoggerFactory = factory
    override fun getMarkerFactory(): IMarkerFactory = markers
    override fun getMDCAdapter(): MDCAdapter = context
    override fun getRequestedApiVersion() = "2.0.99"
}

private class RedactedLogger(loggerName: String) : AbstractLogger() {
    init {
        name = loggerName
    }
    override fun getFullyQualifiedCallerName() = javaClass.name
    override fun isTraceEnabled() = false
    override fun isTraceEnabled(marker: Marker?) = false
    override fun isDebugEnabled() = false
    override fun isDebugEnabled(marker: Marker?) = false
    override fun isInfoEnabled() = false
    override fun isInfoEnabled(marker: Marker?) = false
    override fun isWarnEnabled() = AppLogRecorder.enabled.value
    override fun isWarnEnabled(marker: Marker?) = isWarnEnabled
    override fun isErrorEnabled() = AppLogRecorder.enabled.value
    override fun isErrorEnabled(marker: Marker?) = isErrorEnabled
    override fun handleNormalizedLoggingCall(level: Level, marker: Marker?, messagePattern: String?, arguments: Array<out Any?>?, throwable: Throwable?) {
        val message = MessageFormatter.arrayFormat(messagePattern, arguments).message.orEmpty() + throwable?.message?.let { ": $it" }.orEmpty()
        val optionalGet = message.contains("405") && message.contains("SSE", true)
        AppLogRecorder.record(
            "Library",
            if (optionalGet) "MCP_OPTIONAL_STREAM_UNSUPPORTED · $message" else "${name.takeLast(80)} · $message",
            if (optionalGet) {
                "I"
            } else if (level == Level.ERROR) {
                "E"
            } else {
                "W"
            }
        )
    }
}
