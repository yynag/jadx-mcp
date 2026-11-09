package com.yyang.jadxmcp.worker

import java.nio.file.Path
import kotlin.system.exitProcess

/** Independent child entry point. The existing application Main is intentionally unchanged. */
object WorkerMain {
    @JvmStatic fun main(args: Array<String>) {
        com.yyang.jadxmcp.bootstrap.Logging.configure(com.yyang.jadxmcp.bootstrap.LogLevel.parse(
            System.getProperty(com.yyang.jadxmcp.bootstrap.Logging.PROPERTY, "INFO")))
        val log = org.slf4j.LoggerFactory.getLogger(WorkerMain::class.java)
        try {
            val token = required("JADX_MCP_WORKER_TOKEN")
            val port = required("JADX_MCP_WORKER_PORT").toInt().also { require(it in 1..65535) }
            val cache = Path.of(required("JADX_MCP_WORKER_CACHE"))
            // Factory selection is trusted launch configuration, never received over HTTP.
            val factory = Class.forName(required("JADX_MCP_WORKER_FACTORY")).getDeclaredConstructor()
                .newInstance() as WorkerEndpointFactory
            val parent = ProcessHandle.of(required("JADX_MCP_PARENT_PID").toLong())
            val server = WorkerServer(port, token, factory, cache)
            Thread.ofVirtual().name("worker-parent-watchdog").start {
                while (parent.map { it.isAlive }.orElse(false)) Thread.sleep(500)
                Runtime.getRuntime().halt(74) // Parent vanished: do not leave an orphan JVM.
            }
            server.start()
            log.info("Worker started")
            server.awaitStopped()
            log.info("Worker stopped")
            exitProcess(0) // Some analysis libraries retain non-daemon executors after close.
        } catch (_: OutOfMemoryError) {
            log.error("WORKER_OOM")
            Runtime.getRuntime().halt(73)
        } catch (e: Throwable) {
            log.error("WORKER_START_FAILED type={}", e.javaClass.simpleName)
            exitProcess(72)
        }
    }
    private fun required(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("Missing worker launch configuration: $name")
}
