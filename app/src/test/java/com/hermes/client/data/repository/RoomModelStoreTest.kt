package com.hermes.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomModelStoreTest {
    @Test fun roundTripsProviderAndModelWithSeparatorsInside() {
        val encoded = encodeRoomModel("open-router", "anthropic/claude (fast):1.5")
        assertEquals("open-router" to "anthropic/claude (fast):1.5", decodeRoomModel(encoded))
    }

    @Test fun malformedValuesDecodeToNull() {
        assertNull(decodeRoomModel(null))
        assertNull(decodeRoomModel(""))
        assertNull(decodeRoomModel("only-provider"))
        assertNull(decodeRoomModel("\u0000model"))
        assertNull(decodeRoomModel("a\u0000b\u0000c"))
    }
}
