package com.hermes.client.data.network

import java.io.IOException

/**
 * Outcome of a one-off connectivity probe. A small sealed type keeps network failures distinct
 * from rejected credentials so the setup screen can show the right advice.
 */
sealed interface GatewayProbeResult {
    data object Connected : GatewayProbeResult
    data object WrongCredentials : GatewayProbeResult
    data object TokenRejected : GatewayProbeResult
    data object Unreachable : GatewayProbeResult
    data class Unexpected(val code: Int? = null) : GatewayProbeResult
}

/** Short, user-facing description of a probe outcome. Pure; never includes credentials. */
fun GatewayProbeResult.message(): String = when (this) {
    GatewayProbeResult.Connected -> "Connected"
    GatewayProbeResult.WrongCredentials -> "Wrong username or password"
    GatewayProbeResult.TokenRejected -> "Token rejected"
    GatewayProbeResult.Unreachable -> "Unreachable"
    is GatewayProbeResult.Unexpected ->
        code?.let { "Unexpected response (HTTP $it)" } ?: "Unexpected response"
}

/**
 * Only transport-level failures (connection refused/reset, timeout, unresolvable host) mean the
 * gateway is unreachable. Input/programming errors surface as an unexpected response instead.
 */
internal fun Throwable.toProbeFailure(): GatewayProbeResult =
    if (this is IOException) GatewayProbeResult.Unreachable else GatewayProbeResult.Unexpected()
