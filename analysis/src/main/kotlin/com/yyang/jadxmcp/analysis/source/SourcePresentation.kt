package com.yyang.jadxmcp.analysis.source

import com.yyang.jadxmcp.protocol.TextContent

/** Presentation is applied after generation, so cached source is never truncated or renumbered. */
class SourcePresentation(private val maxChars: Int = 80_000) {
    init { require(maxChars > 0) }

    fun render(source: String, slim: Boolean, numbered: Boolean, firstLine: Int = 1,
               mediaType: String = "text/x-java-source"): TextContent {
        val preambleEnd = if (slim) preambleEnd(source) else 0
        val lines = source.lines().mapIndexedNotNull { index, line ->
            // Conservative slim: only simplify the compilation-unit preamble. Bodies (including
            // multiline literals and comment attachments) remain byte-for-byte unchanged.
            if (slim && index < preambleEnd &&
                (line.isBlank() || line.trimStart().startsWith("import "))) null
            else if (numbered) "${firstLine + index}: $line" else line
        }
        val text = lines.joinToString("\n")
        var end = minOf(maxChars, text.length)
        // Do not split a surrogate pair at the output boundary.
        if (end in 1 until text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return TextContent(text.substring(0, end), end < text.length, text.length, mediaType)
    }

    private fun preambleEnd(source: String): Int {
        // Stop at the first non-package/import line rather than guessing Java syntax in method bodies.
        return source.lineSequence().indexOfFirst { line ->
            val value = line.trim()
            value.isNotEmpty() && !value.startsWith("package ") && !value.startsWith("import ")
        }.let { if (it < 0) source.lines().size else it }
    }
}
