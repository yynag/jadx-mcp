package com.yyang.jadxmcp.bootstrap

/** Alternate executable entry; the legacy application/shadow-JAR Main remains unchanged. */
object MasterMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.contentEquals(arrayOf("--help")) || args.contentEquals(arrayOf("-h"))) {
            println("Usage: MasterMain [--host 127.0.0.1] [--port 8650] [--worker-heap 2g] [--log-level=INFO]")
            println("Log levels: TRACE, DEBUG, INFO (default), WARN, ERROR, OFF. Space-separated values also accepted.")
            println("Generated data: <input full filename>.jadx_mcp_cache/session-<unique>/ beside each input (APK, JAR, etc.).")
            return
        }
        val options = MasterOptions.parse(args)
        Logging.configure(options.logLevel)
        val log = org.slf4j.LoggerFactory.getLogger(MasterMain::class.java)
        val runtime = MasterRuntime(options)
        val hook = Thread({
            runCatching { runtime.close() }.onFailure { log.error("Master shutdown failed type={}", it.javaClass.simpleName) }
        }, "master-runtime-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        log.info("Master starting port={} logLevel={} trustedNetworkOnly=true", options.port, options.logLevel)
        try { runtime.start(wait = true) } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            runtime.close()
        }
    }
}
