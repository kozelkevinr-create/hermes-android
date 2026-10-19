package com.hermes.client.data.matrix

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CertificateBytesTest {
    private val der1 = byteArrayOf(0x30, 0x03, 1, 2, 3, 4)
    private val der2 = byteArrayOf(0x30, 0x05, 9, 8, 7, 6, 5, 4)

    private fun pem(b: ByteArray): String {
        val enc = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(b)
        return "-----BEGIN CERTIFICATE-----\n$enc\n-----END CERTIFICATE-----\n"
    }

    @Test fun raw_der_passes_through() {
        val out = CertificateBytes.parse(der1)
        assertEquals(1, out.size)
        assertArrayEquals(der1, out[0])
    }

    @Test fun single_pem_is_decoded() {
        assertArrayEquals(der1, CertificateBytes.parse(pem(der1).toByteArray())[0])
    }

    @Test fun pem_bundle_is_split_in_order() {
        val out = CertificateBytes.parse(("junk\n" + pem(der1) + pem(der2)).toByteArray())
        assertEquals(2, out.size)
        assertArrayEquals(der1, out[0])
        assertArrayEquals(der2, out[1])
    }

    @Test fun garbage_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) { CertificateBytes.parse("hello".toByteArray()) }
    }

    @Test fun unterminated_pem_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CertificateBytes.parse("-----BEGIN CERTIFICATE-----\nAAAA".toByteArray())
        }
    }
}
