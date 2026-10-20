package com.hermes.client.ui.netops

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.auth.BridgeConfigStore
import com.hermes.client.data.auth.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

enum class Reach { UNKNOWN, UP, DOWN, CHECKING }

data class Target(
    val label: String,
    val host: String,
    val port: Int,
    val status: Reach = Reach.UNKNOWN,
    val latencyMs: Long? = null,
    /** True for the gateway/bridge rows derived from app config — not user-deletable. */
    val builtin: Boolean = false,
)

data class NetOpsState(val targets: List<Target> = emptyList())

/**
 * A homelab reachability board: TCP-connect checks against services the user owns. This is the
 * "is my own stuff up and am I exposing anything I didn't mean to" console — defensive, scoped to
 * infrastructure the user controls, not a general scanner. Checks run from the phone so they
 * reflect what the phone can actually reach (useful for verifying Tailscale/LAN posture).
 *
 * The gateway and bridge rows are derived from the app's own config; extra rows are user-defined
 * and persisted as JSON in prefs.
 */
@HiltViewModel
class NetOpsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialStore: CredentialStore,
    private val bridgeStore: BridgeConfigStore,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("hermes_netops", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(NetOpsState())
    val state: StateFlow<NetOpsState> = _state.asStateFlow()

    init { rebuild() }

    private fun rebuild() {
        val builtins = buildList {
            credentialStore.load()?.baseUrl?.let { parseUrl(it) }?.let { (h, p) ->
                add(Target("Hermes gateway", h, p, builtin = true))
            }
            val bridge = bridgeStore.load()
            if (bridge.isConfigured) parseUrl(bridge.baseUrl)?.let { (h, p) ->
                add(Target("Dispatch bridge", h, p, builtin = true))
            }
        }
        _state.value = NetOpsState(builtins + loadCustom())
    }

    fun checkAll() {
        _state.value = NetOpsState(_state.value.targets.map { it.copy(status = Reach.CHECKING) })
        _state.value.targets.forEachIndexed { i, _ -> check(i) }
    }

    private fun check(index: Int) {
        val t = _state.value.targets.getOrNull(index) ?: return
        viewModelScope.launch {
            val (up, ms) = withContext(Dispatchers.IO) { tcpProbe(t.host, t.port) }
            update(index) { it.copy(status = if (up) Reach.UP else Reach.DOWN, latencyMs = ms) }
        }
    }

    fun addTarget(label: String, host: String, port: Int) {
        val custom = loadCustom() + Target(label.ifBlank { "$host:$port" }, host.trim(), port)
        saveCustom(custom)
        rebuild()
    }

    fun removeTarget(target: Target) {
        if (target.builtin) return
        saveCustom(loadCustom().filterNot { it.host == target.host && it.port == target.port && it.label == target.label })
        rebuild()
    }

    private fun update(index: Int, f: (Target) -> Target) {
        _state.value = NetOpsState(_state.value.targets.mapIndexed { i, t -> if (i == index) f(t) else t })
    }

    private fun tcpProbe(host: String, port: Int, timeoutMs: Int = 2500): Pair<Boolean, Long?> {
        val start = System.currentTimeMillis()
        return runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true to (System.currentTimeMillis() - start)
        }.getOrDefault(false to null)
    }

    /** host + port from a URL or a bare "host:port"; defaults to 80/443 by scheme. */
    private fun parseUrl(raw: String): Pair<String, Int>? {
        val s = raw.trim().ifBlank { return null }
        return runCatching {
            if (s.contains("://")) {
                val u = java.net.URI(s)
                val port = if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80
                (u.host ?: return null) to port
            } else {
                val parts = s.removePrefix("http://").removePrefix("https://").split(":")
                parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 80)
            }
        }.getOrNull()
    }

    private fun loadCustom(): List<Target> {
        val raw = prefs.getString("targets", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Target(o.getString("label"), o.getString("host"), o.getInt("port"))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveCustom(targets: List<Target>) {
        val arr = JSONArray()
        targets.forEach { t ->
            arr.put(JSONObject().put("label", t.label).put("host", t.host).put("port", t.port))
        }
        prefs.edit().putString("targets", arr.toString()).apply()
    }
}
