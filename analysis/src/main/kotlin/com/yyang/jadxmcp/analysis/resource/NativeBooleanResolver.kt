package com.yyang.jadxmcp.analysis.resource

import com.yyang.jadxmcp.protocol.*
import jadx.core.xmlgen.entry.ResourceEntry

/** No device configuration is supplied by the protocol: accept only configuration-invariant values. */
internal class NativeBooleanResolver(private val entries: () -> List<ResourceEntry>) {
    fun resolve(value: String): Boolean = resolve(value.trim(), emptySet())

    private fun resolve(value: String, visited: Set<String>): Boolean {
        when (value) {
            "true", "1" -> return true
            "false", "0" -> return false
        }
        if (!value.startsWith('@') || value in visited || visited.size >= 64) unknown(value)
        val key = value.removePrefix("@").removePrefix("+")
        val numeric = when {
            key.startsWith("0x", true) -> key.drop(2).toLongOrNull(16)
            key.all { it.isDigit() } -> key.toLongOrNull()
            else -> null
        }
        val matches = if (numeric != null && numeric in 0..0xffff_ffffL) {
            entries().filter { it.id == numeric.toInt() }
        } else {
            val pkg = key.substringBefore(':', "")
            val symbol = key.substringAfter(':')
            entries().filter { (pkg.isEmpty() || it.pkgName == pkg) && "${it.typeName}/${it.keyName}" == symbol }
        }
        if (matches.isEmpty()) unknown(value)
        if (matches.none { it.config.isEmpty() } || matches.map { it.pkgName }.distinct().size != 1)
            unknown("$value (no default configuration or ambiguous package)")
        val values = matches.map { entry ->
            val raw = entry.simpleValue ?: unknown(value)
            when (raw.dataType) {
                0x12, 0x10, 0x11 -> raw.data != 0 // Android TYPE_INT_BOOLEAN / DEC / HEX.
                0x01 -> resolve("@0x%08x".format(raw.data), visited + value) // TYPE_REFERENCE.
                else -> unknown(value) // Includes proto/complex/dynamic values not safely interpretable here.
            }
        }.distinct()
        if (values.size != 1) unknown("$value (configuration/archive-dependent)")
        return values.single()
    }

    private fun unknown(value: String): Nothing = throw ProtocolException(ProtocolError(
        ErrorCode.UNSUPPORTED, "Cannot resolve manifest boolean without a unique native resource value: $value"))
}
