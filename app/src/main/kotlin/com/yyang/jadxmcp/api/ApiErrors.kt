package com.yyang.jadxmcp.api

import com.yyang.jadxmcp.protocol.*
import io.ktor.http.HttpStatusCode
import java.util.concurrent.TimeoutException

internal fun apiError(error: Throwable): ProtocolError = when (error) {
    is ProtocolException -> error.error
    is InterruptedException -> ProtocolError(ErrorCode.CANCELLED, "Request interrupted")
    is TimeoutException -> ProtocolError(ErrorCode.DEADLINE_EXCEEDED, "Request deadline exceeded")
    else -> ProtocolError(ErrorCode.INTERNAL, "Internal request failure")
}

internal fun status(error: ProtocolError): HttpStatusCode = when (error.code) {
    ErrorCode.INVALID_ARGUMENT -> HttpStatusCode.BadRequest
    ErrorCode.NOT_FOUND -> HttpStatusCode.NotFound
    ErrorCode.UNSUPPORTED -> HttpStatusCode.UnprocessableEntity
    ErrorCode.CONFLICT -> HttpStatusCode.Conflict
    ErrorCode.CLOSED -> HttpStatusCode.Gone
    ErrorCode.DISCONNECTED -> HttpStatusCode.ServiceUnavailable
    ErrorCode.DEADLINE_EXCEEDED -> HttpStatusCode.GatewayTimeout
    ErrorCode.CANCELLED -> HttpStatusCode.RequestTimeout
    ErrorCode.BUSY -> HttpStatusCode.TooManyRequests
    ErrorCode.INTERNAL, ErrorCode.PERSISTENCE_FAILED -> HttpStatusCode.InternalServerError
}
