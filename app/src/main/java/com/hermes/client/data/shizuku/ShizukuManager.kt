package com.hermes.client.data.shizuku

import rikka.shizuku.Shizuku

/**
 * Thin wrapper over Shizuku for the one privileged thing we need: toggling wireless debugging
 * without root. Shizuku hands an app ADB-level shell access after the user authorizes it once, so
 * `settings put global adb_wifi_enabled 1` runs with the same rights `adb shell` would have.
 *
 * `newProcess` is a hidden Shizuku static, invoked via reflection so the app doesn't need to link
 * the hidden-API stubs. Everything is wrapped so a missing/denied Shizuku never crashes the caller.
 */
object ShizukuManager {

    const val PERMISSION_REQUEST_CODE = 4231

    enum class Status { UNAVAILABLE, NEEDS_PERMISSION, READY }

    /** Shizuku app installed and its service running (binder alive)? */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun status(): Status = when {
        !isBinderAlive() -> Status.UNAVAILABLE
        !hasPermission() -> Status.NEEDS_PERMISSION
        else -> Status.READY
    }

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission() = runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }

    /** Current wireless-debugging state, read via a privileged shell. Null if unknown. */
    fun isWirelessDebuggingOn(): Boolean? =
        exec("settings get global adb_wifi_enabled").getOrNull()?.trim()?.let { it == "1" }

    /** Turn wireless debugging on (true) or off (false). Returns the command's success. */
    fun setWirelessDebugging(on: Boolean): Result<Boolean> =
        exec("settings put global adb_wifi_enabled ${if (on) 1 else 0}").map { true }

    /** Run a shell command through Shizuku's privileged process; returns stdout (+stderr). */
    private fun exec(command: String): Result<String> = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val process = method.invoke(
            null, arrayOf("sh", "-c", command), null, null,
        ) as Process
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        process.waitFor()
        (out + err).trim()
    }
}
