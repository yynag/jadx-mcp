package com.yyang.jadxmcp.runtime

import java.net.URI
import java.net.InetAddress
import java.util.prefs.Preferences

/** Numeric IP only; origin conversion is internal, never a URL input mode. */
data class MasterConnectionSettings private constructor(val ip: String, val port: Int) {
    val baseUrl: String get() = "http://${if (':' in ip) "[$ip]" else ip}:$port"
    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:8650"
        private const val PREF_KEY = "masterUrl"

        fun of(ip: String, port: String): MasterConnectionSettings {
            val value = ip.trim()
            val ipv4 = value.split('.').let { parts -> parts.size == 4 && parts.all {
                it.matches(Regex("[0-9]{1,3}")) && (it.length == 1 || it[0] != '0') && it.toInt() <= 255
            } }
            // Guard before InetAddress: only numeric colon syntax reaches the parser, never DNS.
            val ipv6 = ':' in value && value.matches(Regex("[0-9a-fA-F:.]+")) &&
                runCatching { InetAddress.getByName(value) }.isSuccess
            require(ipv4 || ipv6) { "Enter a valid IPv4 or IPv6 address" }
            val number = port.trim().takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
            require(number != null && number in 1..65535) { "Port must be a number from 1 to 65535" }
            return MasterConnectionSettings(value, number)
        }

        /** Read the existing origin setting without silently downgrading HTTPS/hostnames. */
        fun parse(input: String): MasterConnectionSettings {
            return try {
                val uri = URI(input)
                require(uri.scheme.equals("http", true) && uri.rawUserInfo == null && uri.rawQuery == null &&
                    uri.rawFragment == null && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"))
                of(requireNotNull(uri.host).removePrefix("[").removeSuffix("]"),
                    (if (uri.port == -1) 80 else uri.port).toString())
            } catch (_: Exception) {
                throw IllegalArgumentException("Saved address cannot use IP/port fields; enter an IP and port again (HTTP only)")
            }
        }

        private fun pluginPreferences() = Preferences.userRoot().node("com/yyang/jadxmcp/plugins/jadx-mcp-gui")

        fun load(preferences: Preferences = pluginPreferences()): MasterConnectionSettings {
            preferences.get(PREF_KEY, null)?.let { saved -> return parse(saved) }
            val configuredOrigin = System.getProperty("jadx.mcp.url") ?: System.getProperty("jadx-mcp.master")
            return parse(configuredOrigin ?: DEFAULT_URL)
        }

        fun save(value: MasterConnectionSettings, preferences: Preferences = pluginPreferences()) {
            preferences.put(PREF_KEY, value.baseUrl)
            preferences.flush()
        }

        fun loadDiagnostics(preferences: Preferences = pluginPreferences()): Boolean =
            preferences.getBoolean("diagnosticLog", false)

        fun saveDiagnostics(enabled: Boolean, preferences: Preferences = pluginPreferences()) {
            preferences.putBoolean("diagnosticLog", enabled)
            preferences.flush()
        }
    }
}
