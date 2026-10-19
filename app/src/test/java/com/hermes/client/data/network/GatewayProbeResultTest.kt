package com.hermes.client.data.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayProbeResultTest {
    @Test fun connected_maps_to_connected() {
        assertEquals("Connected", GatewayProbeResult.Connected.message())
    }

    @Test fun wrong_credentials_maps_to_wrong_username_or_password() {
        assertEquals(
            "Wrong username or password",
            GatewayProbeResult.WrongCredentials.message(),
        )
    }

    @Test fun token_rejected_maps_to_token_rejected() {
        assertEquals("Token rejected", GatewayProbeResult.TokenRejected.message())
    }

    @Test fun unreachable_maps_to_unreachable() {
        assertEquals("Unreachable", GatewayProbeResult.Unreachable.message())
    }

    @Test fun unexpected_includes_the_http_code() {
        assertEquals(
            "Unexpected response (HTTP 418)",
            GatewayProbeResult.Unexpected(418).message(),
        )
    }

    @Test fun unexpected_without_a_code_omits_it() {
        assertEquals("Unexpected response", GatewayProbeResult.Unexpected().message())
    }

    @Test fun io_failures_are_unreachable() {
        assertEquals(GatewayProbeResult.Unreachable, IOException().toProbeFailure())
        assertEquals(GatewayProbeResult.Unreachable, UnknownHostException().toProbeFailure())
        assertEquals(GatewayProbeResult.Unreachable, SocketTimeoutException().toProbeFailure())
    }

    @Test fun other_failures_are_unexpected() {
        assertEquals(GatewayProbeResult.Unexpected(), IllegalArgumentException().toProbeFailure())
    }
}
