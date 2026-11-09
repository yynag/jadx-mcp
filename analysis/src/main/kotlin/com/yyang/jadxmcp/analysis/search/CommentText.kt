package com.yyang.jadxmcp.analysis.search

/** Small Java lexical scanner: URL/string/character/text-block contents are not comments.
 * Returned ranges preserve original line offsets, including inline and multiline comments. */
internal fun commentRanges(text: String, checkActive: () -> Unit = {}): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var i = 0
    while (i < text.length) {
        checkActive()
        when {
            text.startsWith("//", i) -> {
                val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                ranges += i until end
                i = end
            }
            text.startsWith("/*", i) -> {
                val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                ranges += i until end
                i = end
            }
            text.startsWith("\"\"\"", i) -> {
                i += 3
                while (i < text.length && !text.startsWith("\"\"\"", i)) {
                    checkActive()
                    i += if (text[i] == '\\') 2 else 1
                }
                i = (i + 3).coerceAtMost(text.length)
            }
            text[i] == '\"' || text[i] == '\'' -> {
                val quote = text[i++]
                while (i < text.length) {
                    checkActive()
                    val c = text[i++]
                    if (c == '\\') i = (i + 1).coerceAtMost(text.length)
                    else if (c == quote) break
                }
            }
            else -> i++
        }
    }
    return ranges
}
