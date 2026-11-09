package com.yyang.jadxmcp.bootstrap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.LoggerFactory
import org.slf4j.Marker

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR, OFF;
    companion object {
        fun parse(value: String): LogLevel = entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Invalid log level; expected TRACE, DEBUG, INFO, WARN, ERROR or OFF")
    }
}

object Logging {
    const val PROPERTY = "jadx.mcp.logLevel"
    fun configure(level: LogLevel) {
        // Set the property before SLF4J initializes the XML, including its console threshold.
        System.setProperty(PROPERTY, level.name)
        // kotlin-logging's factory banner uses stdout rather than SLF4J and defaults to on.
        // Disable it before SDK classes load, otherwise even OFF prints outside Logback.
        System.setProperty("kotlin-logging.logStartupMessage", "false")
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        context.loggerList.forEach { it.level = if (it.name == org.slf4j.Logger.ROOT_LOGGER_NAME) Level.valueOf(level.name) else null }
    }
}

/** SDK TRACE includes tool arguments; dependency diagnostics are deliberately WARN+ only.
 * This also keeps analysis progress/request chatter out of the INFO lifecycle stream. The
 * root threshold still applies first, so ERROR/OFF cannot be bypassed by this safety policy.
 */
class DependencyLogFilter : TurboFilter() {
    override fun decide(marker: Marker?, logger: ch.qos.logback.classic.Logger, level: Level,
        format: String?, params: Array<out Any?>?, throwable: Throwable?): FilterReply =
        if (!logger.name.startsWith("com.yyang.jadxmcp") && level.toInt() < Level.WARN_INT)
            FilterReply.DENY else FilterReply.NEUTRAL
}
