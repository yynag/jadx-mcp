package com.yyang.jadxmcp.bridge

import com.yyang.jadxmcp.protocol.ProtocolError

private const val CATEGORIES = "(registration metadata rejected|server rejected JSON content type|server rejected poll deadline|response exceeds configured limit|unexpected response fields|invalid response JSON/schema|protocol validation failed \\([A-Z_]+\\); inspect server diagnostics)"
private const val HTTP_CATEGORY = "HTTP [1-5][0-9]{2} (response is not a typed GUI error|typed GUI error: $CATEGORIES)"

/** Never echo peer messages: an untrusted peer can put credentials or HTML in an error.
 * These closed-vocabulary summaries distinguish validation from wire parsing without payload logs.
 */
internal fun safeGuiErrorSummary(error: ProtocolError): String {
    val message = error.message
    return when {
        message.matches(Regex("GUI channel (register|poll|heartbeat|complete|unregister): ($CATEGORIES|$HTTP_CATEGORY)")) -> message
        else -> "${error.code} (details withheld; inspect server diagnostics)"
    }
}

internal fun guiFailureCategory(error: ProtocolError): String = when {
    error.message.matches(Regex(HTTP_CATEGORY)) -> error.message
    error.message == "Invalid GUI registration" -> "registration metadata rejected"
    error.message == "Content-Type must be application/json" -> "server rejected JSON content type"
    error.message == "Poll deadline exceeds hard cap" -> "server rejected poll deadline"
    error.message == "GUI response exceeds size limit" -> "response exceeds configured limit"
    error.message == "Unknown GUI transport fields" || error.message.startsWith("Unknown fields") -> "unexpected response fields"
    error.message.contains("JSON", ignoreCase = true) || error.message.contains("object", ignoreCase = true) -> "invalid response JSON/schema"
    else -> "protocol validation failed (${error.code}); inspect server diagnostics"
}
