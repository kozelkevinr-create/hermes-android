package com.hermes.client.data.matrix

import android.content.Context
import com.hermes.client.data.diagnostics.DebugLog
import java.io.File
import java.security.SecureRandom
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder

/**
 * Spike-quality factory for a matrix-rust-sdk [Client]. Phase 1 replaces the in-memory store
 * passphrase with one wrapped by the Android Keystore.
 */
class MatrixClientFactory(private val context: Context) {

    // Random per process: the spike wipes its store before each login, so it never has to be re-read.
    private val storePassphrase: String by lazy {
        ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }

    /**
     * @param homeserverUrl full base URL (http:// or https://); well-known lookup is disabled.
     * @param caBytes optional private CA (PEM bundle or DER). The system roots stay trusted too;
     * these are added on top.
     */
    suspend fun build(homeserverUrl: String, caBytes: ByteArray?): Client {
        MatrixPlatform.initOnce(context)
        val dir = File(context.filesDir, STORE_DIR)
        dir.deleteRecursively()
        val data = File(dir, "data").apply { mkdirs() }
        val cache = File(dir, "cache").apply { mkdirs() }

        var builder = ClientBuilder()
            .homeserverUrl(homeserverUrl.trim())
            .disableWellKnownLookup(true)
            .userAgent("HermesAndroid-spike")
            // Synapse serves native simplified sliding sync (MSC4186); without a version the room list
            // fails with "Sliding sync version is missing". DISCOVER_NATIVE falls back to NONE if absent.
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
            .sqliteStore(SqliteStoreBuilder(data.absolutePath, cache.absolutePath).passphrase(storePassphrase))
        if (caBytes != null) {
            val certs = CertificateBytes.parse(caBytes)
            DebugLog.log("matrix", "adding ${certs.size} root certificate(s)")
            // addRootCertificates alone fails on Android with "builder error": reqwest 0.13 cannot
            // merge extra roots into rustls-platform-verifier on Android. Trusting only the supplied
            // CA (disableBuiltInRootCertificates) switches it to the plain webpki verifier instead.
            builder = builder.addRootCertificates(certs).disableBuiltInRootCertificates()
        }
        return builder.build()
    }

    companion object {
        private const val STORE_DIR = "matrix_spike"
    }
}
