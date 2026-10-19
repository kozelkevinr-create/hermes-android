package com.hermes.client.data.matrix

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Human-readable "subject, expires DATE" for an imported CA, for the setup screen. */
object CertificateSummary {
    fun describe(der: ByteArray): String {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "${cert.subjectX500Principal.name}, expires ${fmt.format(cert.notAfter)}"
    }
}
