package com.yyang.jadxmcp.analysis.search

import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.data.impl.JadxCodeComment
import jadx.api.data.impl.JadxCodeData
import jadx.api.data.impl.JadxNodeRef
import jadx.api.plugins.input.insns.InsnData
import jadx.api.plugins.input.insns.InsnIndexType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Proxy

class SearchServiceTest {
    private val context get() = CallContext("search-test", 3, System.currentTimeMillis() + 60_000)

    private fun loaded(): JadxDecompiler = JadxDecompiler(JadxArgs().apply {
        inputFiles = listOf(File("../app/src/test/resources/sample.jar").takeIf { it.isFile }
            ?: File("app/src/test/resources/sample.jar"))
        isDeobfuscationOn = false
    }).also { it.load() }

    private fun service(decompiler: JadxDecompiler) = SearchService(object : WorkspaceAccess<JadxDecompiler> {
        override val ownership = WorkspaceOwnership.BORROWED
        override val projectEpoch = 3L
        override fun <T> withDecompiler(context: CallContext, action: (JadxDecompiler) -> T): T {
            context.checkActive(); context.requireEpoch(projectEpoch)
            return action(decompiler).also { context.checkActive() }
        }
        override fun close() = Unit
    })

    @Test fun namesUseRawMetadataWithoutDecompilationAndRespectPackageBoundaries() {
        loaded().use { decompiler ->
            val service = service(decompiler)
            val before = decompiler.classesWithInners.associate { it.rawName to it.classNode.state }
            val methods = service.search(SearchRequest("s", SearchScope.METHOD_NAME, "echo",
                packageName = "com.yyang.sample", budget = SearchBudget(500, 0)), context)
            assertEquals(setOf("(I)Ljava/lang/String;", "(Ljava/lang/String;)Ljava/lang/String;"),
                methods.hits.map { it.symbol.descriptor }.toSet())
            assertEquals(0, methods.stats.decompiled)
            assertTrue(methods.stats.complete)
            assertEquals(before, decompiler.classesWithInners.associate { it.rawName to it.classNode.state })
            val fields = service.search(SearchRequest("s", SearchScope.FIELD, "magic"), context)
            assertEquals(SymbolRef("com.yyang.sample.HelloSample", "MAGIC", "Ljava/lang/String;"), fields.hits.single().symbol)
            val names = service.search(SearchRequest("s", SearchScope.CLASS, "sample", "com.yyang.sample"), context)
            assertEquals(2, names.hits.size)
            assertTrue(names.hits.none { it.symbol.className.startsWith("com.yyang.sample2.") })
            val first = service.search(SearchRequest("s", SearchScope.METHOD_NAME, "echo", page = PageRequest(0, 1)), context)
            val next = service.search(SearchRequest("s", SearchScope.METHOD_NAME, "echo", page = PageRequest(1, 1)), context)
            assertEquals(methods.hits, first.hits + next.hits)
            assertEquals(1, first.page.nextOffset)
            assertNull(next.page.nextOffset)
            assertEquals(2, next.page.total)
            assertTrue(service.search(SearchRequest("s", SearchScope.CLASS, "sample", page = PageRequest(Int.MAX_VALUE, 1)), context).hits.isEmpty())
        }
    }

    @Test fun aliasesAreSearchableButResultsKeepExactRawSignatures() {
        loaded().use { decompiler ->
            val cls = decompiler.classesWithInners.first { it.rawName.endsWith("Overloads") }
            val method = cls.classNode.methods.first { it.methodInfo.shortId.startsWith("echo(I)") }
            method.methodInfo.alias = "LatestEcho"
            cls.classNode.classInfo.changeShortName("LatestOverloads")
            assertEquals("echo", service(decompiler).search(SearchRequest("s", SearchScope.METHOD_NAME, "LatestEcho"), context).hits.single().symbol.memberName)
            assertTrue(service(decompiler).search(SearchRequest("s", SearchScope.CLASS, "LatestOverloads"), context).hits.single().symbol.className.endsWith("Overloads"))
        }
    }

    @Test fun jarCannotPretendToHaveDexConstantsAndExplicitSourceIsBounded() {
        loaded().use { decompiler ->
            val service = service(decompiler)
            val error = assertThrows(ProtocolException::class.java) {
                service.search(SearchRequest("s", SearchScope.STRING, "MAGIC"), context)
            }
            assertEquals(ErrorCode.UNSUPPORTED, error.error.code)
            val zero = service.search(SearchRequest("s", SearchScope.STRING, "MAGIC",
                stringMode = StringSearchMode.SOURCE_TEXT, budget = SearchBudget(500, 0)), context)
            assertFalse(zero.stats.complete)
            assertEquals(ScanStats(0, 0, false), zero.stats)
            assertNull(zero.page.total)
            val source = service.search(SearchRequest("s", SearchScope.STRING, "JADX_CORE_MCP_SAMPLE_MAGIC",
                stringMode = StringSearchMode.SOURCE_TEXT, budget = SearchBudget(500, 1)), context)
            assertTrue(source.hits.isNotEmpty())
            assertTrue(source.hits.all { it.line!! > 0 })
            assertEquals(1, source.stats.decompiled)
            assertFalse(source.stats.complete)
            assertNull(source.page.total)
            val scan = service.search(SearchRequest("s", SearchScope.CLASS, "sample", budget = SearchBudget(1, 0)), context)
            assertEquals(ScanStats(1, 0, false), scan.stats)
            assertNull(scan.page.total)
        }
    }

    @Test fun cancellationDeadlineAndValidationAreProtocolErrors() {
        loaded().use { decompiler ->
            val service = service(decompiler)
            for ((ctx, expected) in listOf(context.copy(cancellation = CancellationSignal { true }) to ErrorCode.CANCELLED,
                context.copy(deadlineEpochMillis = 1) to ErrorCode.DEADLINE_EXCEEDED)) {
                assertEquals(expected, assertThrows(ProtocolException::class.java) {
                    service.search(SearchRequest("s", term = "x"), ctx)
                }.error.code)
            }
            assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(ProtocolException::class.java) {
                service.search(SearchRequest("s", term = "x", budget = SearchBudget(0, 0)), context)
            }.error.code)
            var checks = 0
            assertEquals(ErrorCode.CANCELLED, assertThrows(ProtocolException::class.java) {
                service.search(SearchRequest("s", SearchScope.METHOD_NAME, "echo"),
                    context.copy(cancellation = CancellationSignal { ++checks > 4 }))
            }.error.code)
            assertTrue(checks > 4)
        }
    }

    @Test fun codeAndAttachedCommentSearchUseExplicitBoundedSourcePath() {
        loaded().use { decompiler ->
            decompiler.args.codeData = JadxCodeData().apply {
                comments = listOf(JadxCodeComment(JadxNodeRef.forCls("com.yyang.sample.HelloSample"), "unique-search-comment"))
            }
            decompiler.reloadCodeData()
            val service = service(decompiler)
            val comments = service.search(SearchRequest("s", SearchScope.COMMENT, "unique-search-comment"), context)
            assertEquals(1, comments.hits.size)
            assertTrue(comments.hits.single().line!! > 0)
            assertTrue(comments.stats.complete)
            val code = service.search(SearchRequest("s", SearchScope.CODE, "return",
                packageName = "com.yyang.sample", budget = SearchBudget(500, 1)), context)
            assertTrue(code.hits.isNotEmpty())
            assertEquals(1, code.stats.decompiled)
            assertFalse(code.stats.complete)
            val missingPackage = service.search(SearchRequest("s", SearchScope.CODE, "return", "not.present"), context)
            assertEquals(ScanStats(), missingPackage.stats)
        }
    }

    @Test fun dexInstructionDecoderPreservesTailAndDoesNotDecodeOtherIndexes() {
        val full = "x".repeat(300) + "needle-tail"
        var decodes = 0
        fun instruction(type: InsnIndexType) = Proxy.newProxyInstance(InsnData::class.java.classLoader,
            arrayOf(InsnData::class.java)) { _, method, _ -> when (method.name) {
                "getIndexType" -> type
                "decode" -> { decodes++; null }
                "getIndexAsString" -> full
                else -> throw AssertionError(method.name)
            } } as InsnData
        assertEquals(full, DexConstants.stringValue(instruction(InsnIndexType.STRING_REF)))
        assertEquals(1, decodes)
        assertNull(DexConstants.stringValue(instruction(InsnIndexType.TYPE_REF)))
        assertEquals(1, decodes)
    }

    @Test fun commentLexerIgnoresLiteralsAndKeepsInlineMultilineAndTextBlocks() {
        val text = "String url = \"http://host/*not*/\"; // real\n/* two\n lines */ char c = '/'; \"\"\"\n// text block\n\"\"\"; // last"
        assertEquals(listOf("// real", "/* two\n lines */", "// last"), commentRanges(text).map { text.substring(it) })
    }
}
