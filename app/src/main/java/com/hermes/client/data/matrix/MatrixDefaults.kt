package com.hermes.client.data.matrix

/**
 * Debug-build convenience prefills for the Matrix setup form, sourced from the gitignored
 * `matrix.defaults.properties` (see app/build.gradle.kts). Never holds a password.
 */
data class MatrixDefaults(
    val homeserver: String = "",
    val username: String = "",
    val botMxid: String = "",
) {
    companion object {
        /** Trims whitespace and surrounding quotes; absent/blank values become "". */
        fun of(homeserver: String?, username: String?, botMxid: String?) =
            MatrixDefaults(clean(homeserver), clean(username), clean(botMxid))

        /** From `java.util.Properties`-style text (`key=value` lines); unknown keys (e.g. a password) are ignored. */
        fun parse(text: String): MatrixDefaults {
            val p = java.util.Properties().apply { load(text.reader()) }
            return of(p.getProperty("homeserver"), p.getProperty("username"), p.getProperty("botMxid"))
        }

        private fun clean(v: String?) = v?.trim()?.trim('"', '\'')?.trim().orEmpty()
    }
}
