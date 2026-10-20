package com.hermes.client.ui.netops

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection
import java.net.URL

data class NetToolsState(
    val running: Boolean = false,
    val output: String = "",
)

/**
 * Client-side network diagnostics run over the phone's own connection — "what can this phone see,
 * and is my own stuff reachable/healthy from here." Defensive, scoped to infrastructure the user
 * owns. ICMP ping needs root on Android, so reachability uses TCP connect instead.
 */
@HiltViewModel
class NetToolsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
) : ViewModel() {
    private val _state = MutableStateFlow(NetToolsState())
    val state: StateFlow<NetToolsState> = _state.asStateFlow()

    private fun run(title: String, block: suspend () -> String) {
        if (_state.value.running) return
        _state.value = NetToolsState(running = true, output = "> $title\n")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { "error: ${it.message}" } }
            _state.value = NetToolsState(running = false, output = _state.value.output + result + "\n")
        }
    }

    /** Local IP/interface, whether on Wi-Fi/Tailscale, and the public egress IP. */
    fun myNetwork() = run("my_network") {
        val sb = StringBuilder()
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses.toList().filter { !it.isLoopbackAddress }
            if (addrs.isEmpty()) continue
            sb.append("${nif.name}: ").append(addrs.joinToString(", ") { it.hostAddress ?: "" }).append("\n")
        }
        // Tailscale shows up as a "tun"/"tailscale" interface in the 100.64/10 CGNAT range.
        val onTailscale = interfaces.any { it.isUp && it.inetAddresses.toList().any { a -> a.hostAddress?.startsWith("100.") == true } }
        sb.append("tailscale: ${if (onTailscale) "connected" else "not detected"}\n")
        val publicIp = runCatching {
            okHttp.newCall(Request.Builder().url("https://api.ipify.org").build()).execute().use { it.body?.string()?.trim() }
        }.getOrNull()
        sb.append("public IP: ${publicIp ?: "(unavailable)"}")
        sb.toString()
    }

    /** Forward + reverse DNS for a host. */
    fun dnsLookup(host: String) = run("dns $host") {
        val clean = host.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        val addrs = InetAddress.getAllByName(clean)
        buildString {
            addrs.forEach { a ->
                append("${a.hostAddress}")
                val rev = runCatching { a.canonicalHostName }.getOrNull()
                if (rev != null && rev != a.hostAddress) append("  ($rev)")
                append("\n")
            }
        }.trimEnd()
    }

    /** HTTP status + TLS certificate expiry for a URL. */
    fun httpCheck(rawUrl: String) = run("http $rawUrl") {
        val url = if (rawUrl.contains("://")) rawUrl else "https://$rawUrl"
        val u = URL(url)
        val conn = u.openConnection()
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val sb = StringBuilder()
        if (conn is HttpsURLConnection) {
            conn.requestMethod = "HEAD"
            conn.connect()
            sb.append("HTTP ${conn.responseCode}\n")
            val certs = conn.serverCertificates
            (certs.firstOrNull() as? java.security.cert.X509Certificate)?.let { c ->
                val days = (c.notAfter.time - System.currentTimeMillis()) / 86_400_000
                sb.append("TLS cert: ${c.subjectX500Principal.name.substringAfter("CN=").substringBefore(',')}\n")
                sb.append("expires: ${c.notAfter} (${days}d)")
            }
            conn.disconnect()
        } else {
            (conn as java.net.HttpURLConnection).requestMethod = "HEAD"
            conn.connect()
            sb.append("HTTP ${conn.responseCode} (no TLS)")
            conn.disconnect()
        }
        sb.toString()
    }

    /** TCP-sweep the phone's own /24 on a port to see which hosts answer. */
    fun subnetSweep(port: Int = 80) = run("sweep /24 :$port") {
        val local = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.hostAddress?.startsWith("192.168.") == true }
            ?.hostAddress ?: return@run "no LAN IPv4 address found"
        val prefix = local.substringBeforeLast('.')
        val found = mutableListOf<String>()
        val jobs = (1..254).map { i ->
            viewModelScope.launch(Dispatchers.IO) {
                val host = "$prefix.$i"
                if (runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 350) }; true }.getOrDefault(false)) {
                    synchronized(found) { found.add(host) }
                }
            }
        }
        jobs.forEach { it.join() }
        if (found.isEmpty()) "no hosts answered on :$port in $prefix.0/24"
        else "live on :$port —\n" + found.sortedBy { it.substringAfterLast('.').toInt() }.joinToString("\n")
    }

    fun clear() { _state.value = NetToolsState() }
}
