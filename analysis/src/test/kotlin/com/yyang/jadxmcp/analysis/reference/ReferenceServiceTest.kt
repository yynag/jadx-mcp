package com.yyang.jadxmcp.analysis.reference

import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class ReferenceServiceTest {
    private val context get() = CallContext("reference-test", 5, System.currentTimeMillis() + 60_000)
    private fun loaded(fixture: Boolean = false): JadxDecompiler = JadxDecompiler(JadxArgs().apply {
        inputFiles = if (fixture) listOf(ParentFixture::class.java, ChildFixture::class.java, CallerFixture::class.java,
            GenericParentFixture::class.java, GenericChildFixture::class.java, GenericCallerFixture::class.java).map {
            File(it.getResource("/${it.name.replace('.', '/')}.class")!!.toURI())
        } else listOf(File("../app/src/test/resources/sample.jar").takeIf { it.isFile }
            ?: File("app/src/test/resources/sample.jar"))
        isDeobfuscationOn = false
    }).also { it.load() }

    private fun service(decompiler: JadxDecompiler) = ReferenceService(object : WorkspaceAccess<JadxDecompiler> {
        override val ownership = WorkspaceOwnership.BORROWED
        override val projectEpoch = 5L
        override fun <T> withDecompiler(context: CallContext, action: (JadxDecompiler) -> T): T {
            context.checkActive(); context.requireEpoch(projectEpoch)
            return action(decompiler).also { context.checkActive() }
        }
        override fun close() = Unit
    })

    @Test fun overloadNeedsDescriptorAndExactCallerHasMetadataSnippetAndMethodCode() {
        loaded().use { decompiler ->
            val service = service(decompiler)
            val target = SymbolRef("com.yyang.sample.Overloads", "echo")
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                service.references(XrefRequest("s", SymbolKind.METHOD, target), context)
            }.error.code)
            val result = service.references(XrefRequest("s", SymbolKind.METHOD, target.copy(descriptor = "(I)Ljava/lang/String;")), context)
            val caller = result.references.single()
            assertEquals(SymbolRef(target.className, "callBoth", "()V"), caller.source)
            assertEquals("method", caller.nodeType)
            assertTrue(caller.snippet!!.contains("42"), caller.snippet)
            // Snapshot the cross-module nullable property; it cannot be smart-cast in place.
            val methodCode = requireNotNull(caller.methodCode)
            assertTrue(methodCode.content.contains("callBoth"))
            assertFalse(methodCode.truncated)
            assertEquals(1, result.page.total)
            assertNull(result.page.nextOffset)
        }
    }

    @Test fun overrideCallersComeFromUsageIndexWithoutConfusingOverloads() {
        loaded(true).use { decompiler ->
            val service = service(decompiler)
            val target = SymbolRef(ChildFixture::class.java.name, "ping", "(I)Ljava/lang/String;")
            val result = service.references(XrefRequest("s", SymbolKind.METHOD, target), context)
            assertEquals(listOf("callInt"), result.references.map { it.source.memberName })
            assertTrue(result.references.single().snippet!!.contains("17"))
            // JADX may process dependency IR while generating caller code. That does not
            // mean target source was generated; inspect its source cache, not ProcessState.
            val targetCode = decompiler.classesWithInners.first { it.rawName == ChildFixture::class.java.name }
                .classNode.codeFromCache
            assertTrue(targetCode == null || targetCode.codeStr.isEmpty())
            val field = service.references(XrefRequest("s", SymbolKind.FIELD,
                SymbolRef(ChildFixture::class.java.name, "counter", "I")), context)
            assertTrue(field.references.any { it.source.memberName == "field" })
            val page = service.references(XrefRequest("s", SymbolKind.FIELD,
                SymbolRef(ChildFixture::class.java.name, "counter", "I"), PageRequest(0, 1)), context)
            assertEquals(field.references.size, page.page.total)
            assertEquals(field.references.take(1), page.references)
            assertEquals(1, page.page.nextOffset)
        }
    }

    @Test fun outOfRangePagesDoNotDecompileAndCancellationIsNotSwallowed() {
        loaded().use { decompiler ->
            val service = service(decompiler)
            val request = XrefRequest("s", SymbolKind.METHOD,
                SymbolRef("com.yyang.sample.Overloads", "echo", "(I)Ljava/lang/String;"), PageRequest(Int.MAX_VALUE, 1))
            val states = decompiler.classesWithInners.map { it.classNode.state }
            assertTrue(service.references(request, context).references.isEmpty())
            assertEquals(states, decompiler.classesWithInners.map { it.classNode.state })
            assertEquals(ErrorCode.CANCELLED, assertThrows(ProtocolException::class.java) {
                service.references(request, context.copy(cancellation = CancellationSignal { true }))
            }.error.code)
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProtocolException::class.java) {
                service.references(request.copy(target = request.target.copy(descriptor = "()V")), context)
            }.error.code)
        }
    }

    @Test fun classReferencesIncludeNativeMethodUsagesAndKeepPackageIdentity() {
        loaded(true).use { decompiler ->
            val result = service(decompiler).references(XrefRequest("s", SymbolKind.CLASS,
                SymbolRef(ChildFixture::class.java.name)), context)
            assertTrue(result.references.any { it.source.className == CallerFixture::class.java.name })
            assertEquals(result.references.distinctBy { it.source }, result.references)
        }
    }

    @Test fun genericBridgeUsesVerifiedUsageEdgesToReachInterfaceCallers() {
        loaded(true).use { decompiler ->
            val result = service(decompiler).references(XrefRequest("s", SymbolKind.METHOD,
                SymbolRef(GenericChildFixture::class.java.name, "convert", "(Ljava/lang/String;)Ljava/lang/String;")), context)
            assertTrue(result.references.any { it.source.className == GenericCallerFixture::class.java.name &&
                it.source.memberName == "call" && it.snippet?.contains("bridge-input") == true })
        }
    }

    @Test fun textTruncationCountsOriginalCharsAndPreservesSurrogatePairs() {
        assertEquals(TextContent("ab", true, 5), boundedText("ab\uD83D\uDE00z", 3))
        assertEquals(TextContent("abc", false, 3), boundedText("abc"))
    }
}
