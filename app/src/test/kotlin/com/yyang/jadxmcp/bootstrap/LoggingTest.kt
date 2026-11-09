package com.yyang.jadxmcp.bootstrap

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.yyang.jadxmcp.api.McpClientLifecycle
import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.session.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** No server/JVM launch: captures real Logback filtering and formatted lifecycle messages. */
class LoggingTest {
    @Test fun shutdownDuringLoadingDoesNotLogSuccessfulPublication() = capture { events ->
        val entered = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val disposed = java.util.concurrent.CountDownLatch(1)
        val service = SessionService(HeadlessBackendFactory { _, _ ->
            entered.countDown()
            check(resume.await(3, java.util.concurrent.TimeUnit.SECONDS))
            object : BackendEndpoint {
                override val capabilities = BackendCapabilities(BackendKind.HEADLESS)
                override fun execute(request: BackendRequest, context: CallContext): OperationResult = SummaryResult()
                override fun close() { disposed.countDown() }
            }
        })
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val creating = executor.submit<SessionInfo> {
                service.create(CreateSessionRequest("private-file.apk"), CallContext("race", 0, System.currentTimeMillis() + 5_000))
            }
            try {
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                service.close()
                resume.countDown()
                assertThrows(java.util.concurrent.ExecutionException::class.java) { creating.get(3, java.util.concurrent.TimeUnit.SECONDS) }
                assertTrue(disposed.await(3, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(events.any { it.formattedMessage.startsWith("Session opened") })
                assertEquals(1, events.count { it.formattedMessage.startsWith("Session open aborted") })
                assertTrue(service.list().sessions.isEmpty())
            } finally { resume.countDown(); service.close() }
        }
    }
    @Test fun cliAndWorkerConfigurationUseExactValidatedLevels() {
        assertEquals(LogLevel.INFO, MasterOptions.parse(emptyArray()).logLevel)
        LogLevel.entries.forEach { level ->
            val options = MasterOptions.parse(arrayOf("--log-level=${level.name}"))
            assertEquals(level, options.logLevel)
            assertEquals(level, options.workerConfig().logLevel)
            assertEquals(level, MasterOptions.parse(arrayOf("--log-level", level.name)).logLevel)
        }
        listOf("", "VERBOSE", "info", "INFO=DEBUG").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { MasterOptions.parse(arrayOf("--log-level=$value")) }
        }
    }

    @Test fun clientLifecycleIsExactlyOnceAndRequestsAreHiddenAtInfo() = capture(LogLevel.INFO) { events ->
        assertEquals("false", System.getProperty("kotlin-logging.logStartupMessage"))
        val lifecycle = McpClientLifecycle("safe-session")
        lifecycle.connected(); lifecycle.connected(); lifecycle.closed(); lifecycle.closed()
        LoggerFactory.getLogger("com.yyang.jadxmcp.test").debug("Request started operation=session_list")
        assertEquals(1, events.count { it.formattedMessage.startsWith("MCP client connected") })
        assertEquals(1, events.count { it.formattedMessage.startsWith("MCP client exited") })
        assertFalse(events.any { it.formattedMessage.startsWith("Request started") })
        val uninitialized = McpClientLifecycle("no-init")
        uninitialized.closed(); uninitialized.connected()
        assertFalse(events.any { "no-init" in it.formattedMessage })
    }

    @Test fun warnErrorAndOffThresholdsApplyToApplicationAndDependencies() {
        listOf(LogLevel.WARN, LogLevel.ERROR, LogLevel.OFF).forEach { level -> capture(level) { events ->
            listOf("com.yyang.jadxmcp.test", "jadx.test", "io.ktor.test", "io.netty.test").forEach { name ->
                val log = LoggerFactory.getLogger(name)
                log.info("threshold-info"); log.warn("threshold-warn"); log.error("threshold-error")
            }
            assertFalse(events.any { it.formattedMessage == "threshold-info" })
            assertEquals(if (level == LogLevel.WARN) 4 else 0, events.count { it.formattedMessage == "threshold-warn" })
            assertEquals(if (level != LogLevel.OFF) 4 else 0, events.count { it.formattedMessage == "threshold-error" })
        } }
    }

    @Test fun debugRequestsAreVisibleButDependencyPayloadTraceIsNeverRecorded() = capture(LogLevel.TRACE) { events ->
        LoggerFactory.getLogger("com.yyang.jadxmcp.test").debug("Request started operation=session_list")
        LoggerFactory.getLogger("io.modelcontextprotocol.kotlin.sdk.server.Server").trace("private-payload")
        assertTrue(events.any { it.formattedMessage.startsWith("Request started") })
        assertFalse(events.any { it.formattedMessage.contains("private-payload") })
    }

    companion object {
        fun capture(level: LogLevel = LogLevel.INFO, block: (List<ILoggingEvent>) -> Unit) {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val old = root.level ?: Level.INFO
            val appender = ListAppender<ILoggingEvent>().apply {
                list = java.util.concurrent.CopyOnWriteArrayList()
                start()
            }
            Logging.configure(level)
            root.addAppender(appender)
            try { block(appender.list) } finally {
                root.detachAppender(appender); appender.stop()
                Logging.configure(LogLevel.valueOf(old.levelStr))
            }
        }
    }
}
