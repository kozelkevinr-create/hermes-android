package com.hermes.client.data.matrix

import android.content.Context
import com.hermes.client.BuildConfig
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform
import org.matrix.rustsdk.Android

/** One-time native setup shared by the app client and the debug spike. */
object MatrixPlatform {
    private var initialised = false

    @Synchronized
    fun initOnce(context: Context) {
        if (initialised) return
        // JNI shim first: hands rustls-platform-verifier the JavaVM + Context.
        Android.init(context.applicationContext)
        // SDK tracing goes to logcat under "org.matrix.rust.sdk". Never TRACE: it logs HTTP bodies
        // (and so tokens/passwords).
        initPlatform(
            TracingConfiguration(
                logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO,
                traceLogPacks = emptyList(),
                extraTargets = emptyList(),
                writeToStdoutOrSystem = true,
                writeToFiles = null,
                sentryConfig = null,
            ),
            false,
        )
        initialised = true
    }
}
