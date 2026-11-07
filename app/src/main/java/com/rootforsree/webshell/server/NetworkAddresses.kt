package com.rootforsree.webshell.server

import java.net.Inet4Address
import java.net.NetworkInterface

/** One reachable IPv4 address on the phone, with a human label. */
data class LanAddress(
    val label: String,
    val host: String,
)

/** Labels for the address list: Wi-Fi first, then Tailscale, then anything else. */
object NetworkAddresses {
    /** Tailscale hands out addresses in the CGNAT range 100.64.0.0/10. */
    fun isTailscaleAddress(host: String): Boolean {
        val first = host.substringBefore('.').toIntOrNull() ?: return false
        val second = host.substringAfter('.', "").substringBefore('.').toIntOrNull() ?: return false
        return first == 100 && second in 64..127
    }

    fun classify(interfaceName: String, host: String): String {
        return when {
            interfaceName.startsWith("wlan") -> "Wi-Fi"
            interfaceName == "tailscale0" || isTailscaleAddress(host) -> "Tailscale"
            else -> interfaceName
        }
    }

    /** Every non-loopback IPv4 address on the phone, Wi-Fi first. */
    fun list(): List<LanAddress> {
        val found = mutableListOf<LanAddress>()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in iface.inetAddresses.toList()) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    found.add(LanAddress(classify(iface.name, address.hostAddress), address.hostAddress))
                }
            }
        }
        return found.sortedWith(compareBy({ it.label != "Wi-Fi" }, { it.label != "Tailscale" }, { it.host }))
    }
}

/** The exact URL the browser opens: host, port, and the per-start token. */
fun buildShellUrl(address: LanAddress, port: Int, token: String): String =
    "http://${address.host}:$port/?token=$token"
