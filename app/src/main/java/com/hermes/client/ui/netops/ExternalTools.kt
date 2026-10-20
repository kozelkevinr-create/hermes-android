package com.hermes.client.ui.netops

import android.content.Context
import android.content.Intent

/** A known network/analysis app we can hand off to; output comes back via Share → Hermes Local. */
data class ExternalTool(val label: String, val pkg: String, val blurb: String, val playId: String = pkg)

/**
 * Specialist tools we don't reimplement — we launch them and let their captures/results flow back
 * through the share-to-Hermes path (upload to the VM, then ask the agent to analyze).
 */
val EXTERNAL_TOOLS = listOf(
    ExternalTool("PCAPdroid", "com.emanuelef.remote_capture", "Packet capture over VpnService → export a .pcap, Share to Hermes Local."),
    ExternalTool("Termux", "com.termux", "A real shell on the phone (nmap, curl, dig…)."),
    ExternalTool("Fing", "com.overlook.android.fing", "LAN scanner — devices, open ports, vendors."),
    ExternalTool("Network Analyzer", "net.techet.netanalyzerlite.an", "Ping, traceroute, port scan, Wi-Fi info."),
)

fun isInstalled(context: Context, pkg: String): Boolean =
    runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

/** Launch the tool if installed, else open its Play Store page. */
fun launchOrStore(context: Context, tool: ExternalTool) {
    val launch = context.packageManager.getLaunchIntentForPackage(tool.pkg)
    val intent = launch ?: Intent(
        Intent.ACTION_VIEW,
        android.net.Uri.parse("https://play.google.com/store/apps/details?id=${tool.playId}"),
    )
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
