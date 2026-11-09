package com.yyang.jadxmcp.bridge

import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GuiDiagnosticLogTest {
    @TempDir lateinit var root: Path

    private fun logs(): List<Path> = Files.walk(root).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".log") }.toList()
    }
    private fun text() = logs().joinToString("\n") { Files.readString(it) }

    @Test fun defaultOffDoesNotEvenResolveDirectoryAndCloseIsTerminal() {
        var lookups = 0
        val log = GuiDiagnosticLog(directory = { lookups++; root.resolve("logs") })
        log.event("ignored", IllegalStateException("secret"))
        assertFalse(log.enabled)
        assertEquals(0, lookups)
        assertFalse(Files.exists(root.resolve("logs")))
        log.close()
        assertFalse(log.setEnabled(true))
        assertEquals(0, lookups)
    }

    @Test fun togglingStopsWritesReleasesHandlerAndReusesSameFiles() {
        val saved = mutableListOf<Boolean>()
        val log = GuiDiagnosticLog(directory = { root.resolve("logs") })
        assertTrue(log.setEnabled(true) { saved.add(it) })
        log.event("before_disable")
        assertTrue(log.setEnabled(false) { saved.add(it) })
        val before = text()
        val names = logs().map { it.fileName.toString() }.toSet()
        repeat(10) { log.event("must_not_be_written") }
        assertEquals(before, text())
        assertFalse(Files.list(root.resolve("logs")).use { it.anyMatch { path -> path.toString().endsWith(".lck") } })
        assertTrue(log.setEnabled(true) { saved.add(it) })
        log.event("after_enable")
        log.close()
        assertEquals(names, logs().map { it.fileName.toString() }.toSet())
        assertEquals(listOf(true, false, true), saved)
        assertTrue(text().contains("before_disable"))
        assertTrue(text().contains("after_enable"))
    }

    @Test fun failedEnableAndPersistenceFailureNeverReportOn() {
        val occupied = root.resolve("file")
        Files.writeString(occupied, "not a directory")
        val log = GuiDiagnosticLog(directory = { occupied.resolve("logs") })
        assertFalse(log.setEnabled(true))
        assertFalse(log.enabled)
        assertTrue(log.notice.contains("enable failed"))
        val other = GuiDiagnosticLog(directory = { root.resolve("logs") })
        assertFalse(other.setEnabled(true) { throw IllegalStateException("private path") })
        assertFalse(other.enabled)
        assertFalse(other.notice.contains("private"))
        other.close()
    }

    @Test fun rollingAndConcurrentInstancesHaveSeparateBoundedFiles() {
        val first = GuiDiagnosticLog({ root }, 1024, 5)
        val second = GuiDiagnosticLog({ root }, 1024, 5)
        assertTrue(first.setEnabled(true)); assertTrue(second.setEnabled(true))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(first, second).mapIndexed { index, log ->
                pool.submit { repeat(100) { log.event("instance_$index", null, "padding" to "a".repeat(150)) } }
            }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); first.close(); second.close() }
        assertEquals(10, logs().size)
        assertTrue(logs().all { Files.size(it) <= 1024 + 512 })
        assertEquals(5, logs().count { Files.readString(it).contains("instance_0") })
        assertEquals(5, logs().count { Files.readString(it).contains("instance_1") })
    }

    @Test fun exceptionsKeepCauseAndFramesButNotMessagesPayloadOrPaths() {
        val log = GuiDiagnosticLog(directory = { root })
        log.setEnabled(true)
        val failure = ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT, "token=TOKEN_SENTINEL source=SOURCE_SENTINEL"))
        failure.initCause(IllegalStateException("/private/PATH_SENTINEL selection=SELECTION_SENTINEL comment=COMMENT_SENTINEL"))
        log.event("failure", failure, "request" to GuiDiagnosticLog.id("REQUEST_SENTINEL\nforged"))
        log.event("size", ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT, "GUI request exceeds size limit")),
            "bytes" to 1234, "limit" to 1000)
        log.close()
        val output = text()
        listOf("TOKEN_SENTINEL", "SOURCE_SENTINEL", "PATH_SENTINEL", "SELECTION_SENTINEL", "COMMENT_SENTINEL", "REQUEST_SENTINEL").forEach {
            assertFalse(output.contains(it), it)
        }
        assertTrue(output.contains("java.lang.IllegalStateException"))
        assertTrue(output.contains("GuiDiagnosticLogTest"))
        assertTrue(output.contains("reason=request_size_limit"))
        assertTrue(output.contains("bytes=1234 limit=1000"))
    }

    @Test fun handlerReportedWriteFailureStopsLoggingWithoutThrowing() {
        val log = GuiDiagnosticLog(directory = { root })
        log.setEnabled(true)
        val field = GuiDiagnosticLog::class.java.getDeclaredField("handler").apply { isAccessible = true }
        val handler = field.get(log) as java.util.logging.FileHandler
        assertDoesNotThrow {
            handler.errorManager.error("PRIVATE_SENTINEL", java.io.IOException("TOKEN_SENTINEL"), 1)
        }
        assertFalse(log.enabled)
        assertTrue(log.notice.contains("write failed"))
        val previous = text()
        log.event("after_failure")
        assertEquals(previous, text())
        assertFalse(log.notice.contains("SENTINEL"))
    }
}
