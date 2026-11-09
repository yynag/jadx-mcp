package com.yyang.jadxmcp.analysis.source

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourcePresentationTest {
    @Test
    fun slimPreservesBodyTextAndOriginalLineNumbers() {
        val source = "package test;\n\nimport java.util.List;\n\nclass Test {\n    String value = \"\"\"\n\nimport literal;\n\"\"\";\n}"
        val result = SourcePresentation().render(source, slim = true, numbered = true)
        assertTrue(result.content.startsWith("1: package test;\n5: class Test {"), result.content)
        assertTrue(result.content.contains("7: \n8: import literal;"), result.content)
        assertFalse(result.content.contains("java.util.List"))
        assertFalse(result.truncated)
        assertEquals(result.content.length, result.totalChars)
    }

    @Test
    fun truncationDoesNotSplitSurrogatePairs() {
        val result = SourcePresentation(2).render("a\uD83D\uDE00z", false, false)
        assertEquals("a", result.content)
        assertEquals(4, result.totalChars)
        assertTrue(result.truncated)
    }
}
