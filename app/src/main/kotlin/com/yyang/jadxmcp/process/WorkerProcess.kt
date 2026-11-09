package com.yyang.jadxmcp.process

import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class WorkerLaunchSpec(val command: List<String>, val environment: Map<String, String>, val workingDirectory: Path)

/** Injectable process boundary: tests never need to imitate java.lang.Process. */
interface WorkerProcess {
    val isAlive: Boolean
    val exitCode: Int?
    val diagnostics: String
    fun terminate(graceMillis: Long)
}

fun interface WorkerLauncher {
    fun launch(spec: WorkerLaunchSpec): WorkerProcess
}

class JvmWorkerLauncher : WorkerLauncher {
    override fun launch(spec: WorkerLaunchSpec): WorkerProcess {
        val process = ProcessBuilder(spec.command).redirectErrorStream(true).apply {
            // Relative native/plugin log and output paths belong to this worker, not the Master.
            directory(spec.workingDirectory.toFile())
            environment().putAll(spec.environment)
        }.start()
        return object : WorkerProcess {
            private val tail = StringBuilder()
            init {
                // Continuously drain output, retaining only bounded diagnostics (no files/tokens).
                Thread.ofVirtual().name("worker-output-${process.pid()}").start {
                    runCatching {
                        process.inputStream.reader().use { reader ->
                            val buffer = CharArray(2_048)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                synchronized(tail) {
                                    tail.append(buffer, 0, count)
                                    if (tail.length > 16_384) tail.delete(0, tail.length - 16_384)
                                }
                            }
                        }
                    }
                }
            }
            override val isAlive get() = process.isAlive
            override val exitCode get() = if (process.isAlive) null else process.exitValue()
            override val diagnostics get() = synchronized(tail) { tail.toString() }
            override fun terminate(graceMillis: Long) {
                process.destroy()
                try {
                    if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        if (!process.waitFor(5_000, TimeUnit.MILLISECONDS))
                            throw IllegalStateException("Worker could not be reaped after forced termination")
                    }
                } catch (e: InterruptedException) {
                    process.destroyForcibly()
                    Thread.currentThread().interrupt()
                    throw e
                } finally {
                    runCatching { process.outputStream.close() }
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                }
            }
        }
    }
}

data class WorkerProcessConfig(
    val factoryClassName: String,
    val classpath: String = System.getProperty("java.class.path"),
    val javaExecutable: String = Path.of(System.getProperty("java.home"), "bin", "java").toString(),
    val defaultMaxHeap: String = "2g",
    val startupTimeoutMillis: Long = 120_000,
    val shutdownGraceMillis: Long = 1_000,
    val logLevel: com.yyang.jadxmcp.bootstrap.LogLevel = com.yyang.jadxmcp.bootstrap.LogLevel.parse(
        System.getProperty(com.yyang.jadxmcp.bootstrap.Logging.PROPERTY, "INFO"))
) {
    init {
        require(factoryClassName.isNotBlank() && classpath.isNotBlank())
        require(Regex("[1-9][0-9]*[kKmMgG]").matches(defaultMaxHeap))
        require(startupTimeoutMillis > 0 && shutdownGraceMillis > 0)
    }
}
