package com.yyang.jadxmcp.bootstrap

import com.yyang.jadxmcp.backend.AnalysisWorkerEndpointFactory
import com.yyang.jadxmcp.process.WorkerBackendFactory
import com.yyang.jadxmcp.process.WorkerProcessConfig
import com.yyang.jadxmcp.session.SessionService
import java.util.concurrent.atomic.AtomicBoolean

data class MasterOptions(
    val port: Int = 8650,
    val workerHeap: String = "2g",
    /** Loopback by default: the service has no general authentication. Use --host explicitly for LAN. */
    val host: String = "127.0.0.1",
    val logLevel: LogLevel = LogLevel.INFO
) {
    init {
        require(port in 1..65535) { "port must be 1..65535" }
        require(Regex("[1-9][0-9]*[kKmMgG]").matches(workerHeap)) { "Invalid worker heap" }
    }

    fun workerConfig() = WorkerProcessConfig(
        factoryClassName = AnalysisWorkerEndpointFactory::class.java.name,
        defaultMaxHeap = workerHeap,
        logLevel = logLevel
    )

    companion object {
        /** Generated data is allocated beside each session input, not in a central store. */
        fun parse(args: Array<String>): MasterOptions {
            var port = 8650
            var heap = "2g"
            var host = "127.0.0.1"
            var level = LogLevel.INFO
            var index = 0
            while (index < args.size) {
                val argument = args[index++]
                val option = argument.substringBefore('=')
                require(option in setOf("--port", "--worker-heap", "--host", "--log-level")) { "Unknown option: $option" }
                val value = if ('=' in argument) argument.substringAfter('=') else {
                    require(index < args.size && args[index].isNotBlank()) { "Missing value for $option" }
                    args[index++]
                }
                require(value.isNotBlank()) { "Missing value for $option" }
                when (option) {
                    "--port" -> port = value.toIntOrNull() ?: throw IllegalArgumentException("Invalid port")
                    "--worker-heap" -> heap = value
                    "--host" -> host = value
                    "--log-level" -> level = LogLevel.parse(value)
                }
            }
            return MasterOptions(port, heap, host, level)
        }
    }
}

/** Real child-JVM composition. The configurable SPI is injectable for tests, never a fake default. */
class MasterRuntime(
    options: MasterOptions,
    workerConfig: WorkerProcessConfig = options.workerConfig()
) : AutoCloseable {
    private val workers = WorkerBackendFactory(workerConfig)
    val sessions = SessionService(workers)
    private val api = MasterApiServer(sessions, options.port, options.host)
    private val closed = AtomicBoolean()

    fun start(wait: Boolean = false) {
        check(!closed.get()) { "Master runtime is closed" }
        try { api.start(wait) } catch (failure: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        // Even if HTTP shutdown fails, all owned sessions and child processes must be disposed.
        listOf<() -> Unit>({ api.close() }, { sessions.close() }, { workers.close() }).forEach { action ->
            runCatching(action).exceptionOrNull()?.let { error ->
                val prior = failure
                if (prior == null) failure = error else prior.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
