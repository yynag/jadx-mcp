package com.yyang.jadxmcp.worker

import com.yyang.jadxmcp.protocol.*
import java.nio.file.Path

/**
 * Trusted child-side assembly SPI. Supply a public no-argument implementation class on the child
 * classpath. Compose new analysis services here; never load a factory name from an HTTP payload.
 * generatedCache is exclusively worker-owned; user codeData must stay outside that directory.
 */
fun interface WorkerEndpointFactory {
    fun open(request: CreateSessionRequest, context: CallContext, generatedCache: Path): BackendEndpoint
}
