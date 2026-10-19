package org.matrix.rustsdk

import android.content.Context

/**
 * JNI entry point expected by matrix-sdk-ffi on Android. It hands the SDK the JavaVM and the
 * application Context so its rustls-platform-verifier can call into Android's trust manager.
 * Must run before the SDK's `initPlatform`. The package/class/method name is fixed by the native lib.
 */
object Android {
    init {
        System.loadLibrary("matrix_sdk_ffi")
    }

    @JvmStatic
    external fun init(context: Context)
}
