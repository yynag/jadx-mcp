package com.yyang.jadxmcp.protocol

/** Transport-neutral contracts. No transport, GUI, filesystem, or JADX object crosses this boundary. */
interface OperationRequest {
    val operation: Operation
    fun validate()
}

interface OperationResult
interface SessionScoped { val sessionId: String }
interface BackendRequest : OperationRequest, SessionScoped

enum class BackendKind { HEADLESS, GUI }

enum class ErrorCode {
    INVALID_ARGUMENT, NOT_FOUND, UNSUPPORTED, CONFLICT, CLOSED, DISCONNECTED,
    DEADLINE_EXCEEDED, CANCELLED, BUSY, INTERNAL, PERSISTENCE_FAILED
}

data class ProtocolError(val code: ErrorCode, val message: String, val retryable: Boolean = false)

class ProtocolException(val error: ProtocolError) : RuntimeException(error.message)

internal fun invalid(message: String): Nothing =
    throw ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT, message))

internal fun nonBlank(value: String, field: String) {
    if (value.isBlank()) invalid("$field must not be blank")
}

internal fun session(value: String) = nonBlank(value, "sessionId")

data class PageRequest(val offset: Int = 0, val count: Int = 50) {
    /** A bounded, positive count is deliberate: count=0 no longer means unbounded output. */
    fun validate() {
        if (offset < 0 || count !in 1..10_000) invalid("offset must be non-negative; count must be 1..10000")
    }
}

data class PageInfo(
    val offset: Int = 0,
    val count: Int = 0,
    val total: Int? = null,
    val nextOffset: Int? = null
)

data class TextContent(
    val content: String = "",
    val truncated: Boolean = false,
    val totalChars: Int = 0,
    val mediaType: String = "text/x-java-source"
)

/**
 * maxDecompile bounds unique source-access attempts, including generated-code cache hits.
 * It is NOT a counter of new native decompilations. Zero disables all source access, even cached.
 * The existing wire name is retained to avoid a cross-module rename for a clarified meaning.
 */
data class SearchBudget(val maxScan: Int = 500, val maxDecompile: Int = 50) {
    fun validate() {
        if (maxScan < 1 || maxDecompile < 0) invalid("maxScan must be positive; maxDecompile must be non-negative")
    }
}

enum class StringSearchMode { DEX_CONSTANTS, SOURCE_TEXT }

data class OperationCapability(
    val operation: Operation,
    val supported: Boolean = true,
    val unsupportedParameters: Set<String> = emptySet(),
    val reason: String? = null
)

data class BackendCapabilities(
    val backend: BackendKind = BackendKind.HEADLESS,
    val operations: List<OperationCapability> = emptyList(),
    val stringSearchModes: Set<StringSearchMode> = emptySet(),
    val debuggerAvailable: Boolean = false
)
