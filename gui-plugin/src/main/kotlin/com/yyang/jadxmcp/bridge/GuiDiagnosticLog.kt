package com.yyang.jadxmcp.bridge

import com.yyang.jadxmcp.protocol.ProtocolException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.logging.ErrorManager
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord

/** Private handler, never a host logger. No files (including host cache lookup) until enabled. */
class GuiDiagnosticLog(
    private val directory: () -> Path,
    private val limit: Int = 2 * 1024 * 1024,
    private val count: Int = 5,
) : AutoCloseable {
    private val instance = UUID.randomUUID().toString()
    private var handler: FileHandler? = null
    private var closed = false
    @Volatile var notice: String = "Diagnostic log: OFF"
        private set
    @get:Synchronized val enabled: Boolean get() = handler != null

    /** Persistence shares the existing plugin Preferences node; a failed enable is never advertised. */
    @Synchronized fun setEnabled(value: Boolean, persist: (Boolean) -> Unit = {}): Boolean {
        if (closed) return false
        if (!value) {
            release()
            return try { persist(false); notice = "Diagnostic log: OFF"; true }
            catch (e: Exception) { notice = "Diagnostic log: OFF; setting save failed (${e.javaClass.simpleName})"; false }
        }
        if (handler != null) return true
        return try {
            val path = directory()
            Files.createDirectories(path)
            val opened = FileHandler(path.resolve("bridge-$instance-%g.log").toString(), limit, count, true)
            handler = opened
            opened.encoding = UTF_8.name()
            opened.formatter = object : Formatter() {
                override fun format(record: LogRecord): String = record.message + "\n"
            }
            opened.errorManager = object : ErrorManager() {
                override fun error(message: String?, failure: Exception?, code: Int) {
                    synchronized(this@GuiDiagnosticLog) {
                        // FileHandler reports I/O errors here instead of throwing them.
                        release()
                        notice = "Diagnostic log: OFF; file write failed (code=$code)"
                    }
                }
            }
            persist(true)
            notice = "Diagnostic log: ON"
            event("logging_enabled")
            enabled
        } catch (e: Exception) {
            release()
            notice = "Diagnostic log: OFF; enable failed (${e.javaClass.simpleName})"
            false
        }
    }

    /** Callers supply only local vocabulary/numbers/enums; external identities use [id]. */
    @Synchronized fun event(name: String, failure: Throwable? = null, vararg fields: Pair<String, Any?>) {
        val output = handler ?: return
        try {
            val text = buildString {
                append(Instant.now()).append(" event=").append(atom(name))
                fields.forEach { (key, value) -> append(' ').append(atom(key)).append('=').append(atom(value.toString())) }
                if (failure != null) {
                    var cause: Throwable? = failure
                    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
                    repeat(6) {
                        val current = cause ?: return@repeat
                        if (!seen.add(current)) return@repeat
                        append(" exception=").append(atom(current.javaClass.name))
                        (current as? ProtocolException)?.let {
                            append(" code=").append(it.error.code)
                            append(" reason=").append(safeReason(it))
                        }
                        // No Throwable.toString/message or filenames: they can contain payloads/paths.
                        current.stackTrace.take(8).forEach { frame ->
                            append(" at=").append(atom(frame.className.take(100))).append('.').append(atom(frame.methodName.take(80)))
                                .append(':').append(frame.lineNumber)
                        }
                        if (current.stackTrace.size > 8) append(" stack_truncated=true")
                        cause = current.cause
                    }
                    if (cause != null) append(" cause_truncated=true")
                }
            }
            output.publish(LogRecord(Level.INFO, if (text.length > 12_000) text.take(11_980) + " truncated=true" else text))
            output.flush()
        } catch (_: Exception) {
            release()
            notice = "Diagnostic log: OFF; file write failed"
        }
    }

    private fun release() {
        val old = handler
        handler = null
        try { old?.close() } catch (_: Exception) { }
    }

    @Synchronized override fun close() { closed = true; release(); notice = "Diagnostic log: OFF" }

    companion object {
        fun id(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(UTF_8)).take(8).joinToString("") { "%02x".format(it) }
        private fun atom(value: String) = value.take(180).replace(Regex("[^A-Za-z0-9_.:=/-]"), "_")
        private fun safeReason(failure: ProtocolException): String = when (failure.error.message) {
            "GUI request exceeds size limit" -> "request_size_limit"
            "GUI response exceeds size limit" -> "response_size_limit"
            "Master rejected GUI acknowledgement" -> "ack_rejected"
            "GUI HTTP call interrupted" -> "interrupted"
            "GUI HTTP call timed out" -> "timeout"
            "GUI HTTP transport failed" -> "transport_failure"
            "Invalid GUI transport object" -> "invalid_transport_object"
            "Unknown GUI transport fields" -> "unknown_transport_fields"
            "Invalid poll status" -> "invalid_poll_status"
            "Invalid poll command/status" -> "invalid_poll_command_status"
            "Invalid GUI registration" -> "invalid_registration"
            "Unsupported protocol version" -> "unsupported_protocol_version"
            "Exactly one result/error is required" -> "invalid_result_error_pair"
            "GUI session mismatch" -> "session_mismatch"
            "GUI command must be a backend request" -> "invalid_command_type"
            "Endpoint returned a mismatched result type" -> "endpoint_result_type_mismatch"
            "Operation is not supported by this GUI endpoint" -> "unsupported_operation"
            else -> "protocol_validation"
        }
    }
}
