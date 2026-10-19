package com.hermes.client.data.matrix

import java.util.Base64

/**
 * Splits a user-supplied CA file into individual DER certificates.
 *
 * Accepts a PEM bundle (one or more `-----BEGIN CERTIFICATE-----` blocks) or a single raw DER
 * certificate. The SDK's own `addRootCertificates` takes one DER-or-PEM certificate per list
 * entry, so a multi-cert PEM bundle must be split before it is handed over.
 */
object CertificateBytes {
    private const val BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val END = "-----END CERTIFICATE-----"

    fun parse(input: ByteArray): List<ByteArray> {
        val text = input.toString(Charsets.ISO_8859_1)
        if (!text.contains(BEGIN)) {
            require(looksLikeDer(input)) { "Not a PEM or DER certificate" }
            return listOf(input)
        }
        val mime = Base64.getMimeDecoder()
        val certs = mutableListOf<ByteArray>()
        var from = 0
        while (true) {
            val start = text.indexOf(BEGIN, from)
            if (start < 0) break
            val end = text.indexOf(END, start)
            require(end >= 0) { "Unterminated PEM certificate" }
            val body = text.substring(start + BEGIN.length, end)
            certs += mime.decode(body)
            from = end + END.length
        }
        require(certs.isNotEmpty()) { "No certificates found" }
        return certs
    }

    // A DER certificate is an ASN.1 SEQUENCE: tag 0x30 followed by a length encoding.
    private fun looksLikeDer(b: ByteArray) = b.size > 4 && b[0] == 0x30.toByte()
}
