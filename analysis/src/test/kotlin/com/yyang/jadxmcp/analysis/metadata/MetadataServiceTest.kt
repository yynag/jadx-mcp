package com.yyang.jadxmcp.analysis.metadata

import RawIdentityFixture
import com.yyang.jadxmcp.analysis.project.JadxProjectService
import com.yyang.jadxmcp.analysis.project.ProjectSymbols
import com.yyang.jadxmcp.analysis.source.JadxSourceService
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.ResourceFile
import jadx.api.ResourceType
import jadx.api.impl.SimpleCodeInfo
import jadx.core.xmlgen.ResContainer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class MetadataServiceTest {
    class Outer { class Inner }

    private val context get() = CallContext("metadata-test", 7, System.currentTimeMillis() + 60_000)

    private class FakeWorkspace(val decompiler: JadxDecompiler) : WorkspaceAccess<JadxDecompiler> {
        override val ownership = WorkspaceOwnership.BORROWED
        override val projectEpoch = 7L
        var calls = 0
        override fun <T> withDecompiler(context: CallContext, action: (JadxDecompiler) -> T): T {
            context.checkActive()
            context.requireEpoch(projectEpoch)
            calls++
            return action(decompiler)
        }
        override fun close() = Unit
    }

    private fun loaded(extraClasses: Boolean = false): JadxDecompiler {
        val args = JadxArgs().apply {
            inputFiles = mutableListOf(File("../app/src/test/resources/sample.jar").takeIf { it.isFile }
                ?: File("app/src/test/resources/sample.jar"))
            isDeobfuscationOn = false
        }
        if (extraClasses) {
            for (type in listOf(Outer::class.java, Outer.Inner::class.java, RawIdentityFixture::class.java)) {
                args.inputFiles.add(File(type.getResource("/${type.name.replace('.', '/')}.class")!!.toURI()))
            }
        }
        return JadxDecompiler(args).also { it.load() }
    }

    @Test fun sampleJarMetadataHasExactDescriptorsAndStablePagination() {
        loaded().use { decompiler ->
            val access = FakeWorkspace(decompiler)
            val service = MetadataService(access)
            val all = service.classes(ClassRequest("s"), context)
            assertEquals(all.classes.sorted(), all.classes)
            assertEquals(3, all.page.total)
            val first = service.classes(ClassRequest("s", page = PageRequest(0, 1)), context)
            val rest = service.classes(ClassRequest("s", page = PageRequest(1, 10)), context)
            assertEquals(all.classes, first.classes + rest.classes)
            assertEquals(1, first.page.nextOffset)
            assertNull(rest.page.nextOffset)
            assertEquals(0, service.classes(ClassRequest("s", page = PageRequest(Int.MAX_VALUE, 1)), context).page.count)
            val filtered = service.classes(ClassRequest("s", packageName = "com.yyang.sample"), context)
            assertEquals(2, filtered.classes.size)
            assertTrue(filtered.classes.none { it.startsWith("com.yyang.sample2.") })
            val details = service.classes(ClassRequest("s", className = "com.yyang.sample.Overloads"), context)
            assertEquals(setOf("(I)Ljava/lang/String;", "(Ljava/lang/String;)Ljava/lang/String;"),
                details.methods.filter { it.name == "echo" }.map { it.descriptor }.toSet())
            assertTrue(access.calls >= 6)
            val summary = service.summary(SummaryRequest("s"), context)
            assertEquals(3, summary.classesCount)
            assertEquals(PackageCount("com.yyang.sample", 2), summary.topPackages.first())
            val echo = decompiler.searchJavaClassByOrigFullName("com.yyang.sample.Overloads")!!.methods
                .first { it.name == "echo" && it.methodNode.methodInfo.shortId.startsWith("echo(I)") }
            echo.methodNode.methodInfo.alias = "renamedEcho"
            val renamed = service.classes(ClassRequest("s", className = "com.yyang.sample.Overloads"), context)
            assertEquals("(I)Ljava/lang/String;", renamed.methods.single { it.name == "renamedEcho" }.descriptor)
        }
    }

    @Test fun rawAliasAndInnerNamesResolveWithoutStaleAliasCache() {
        loaded(true).use { decompiler ->
            val access = FakeWorkspace(decompiler)
            val index = ClassIndex(access)
            val raw = Outer.Inner::class.java.name
            val inner = index.read(context) { it.find(raw)!! }
            assertTrue(inner.isInner)
            val info = inner.classNode.classInfo
            info.changeShortName("RenamedInner")
            index.read(context) {
                assertEquals(inner, it.find(raw))
                assertEquals(inner, it.find(info.aliasFullName))
                assertEquals(inner, it.find(info.makeAliasRawFullName()))
            }
            info.changeShortName("LatestInner")
            index.read(context) { assertEquals(inner, it.find(info.aliasFullName)) }
            assertTrue(index.read(context) { it.classes.any { cls -> cls.rawName == raw } })
            // Cross-service edits reload native code: persist aliases as native rename mappings,
            // rather than expecting an ad-hoc ClassInfo mutation to survive that reload.
            val project = JadxProjectService.open(access, context)
            project.rename(RenameRequest("s", SymbolKind.CLASS, SymbolRef(raw), "LatestInner"), context)
            assertAcrossServices(decompiler, raw,
                listOf(raw, info.aliasFullName, info.makeAliasRawFullName()))
            val top = decompiler.searchJavaClassByOrigFullName(RawIdentityFixture::class.java.name)!!
            top.classNode.classInfo.changeShortName("Bare")
            index.read(context) {
                assertEquals(top, it.find("p000.Bare"))
                assertEquals(top, it.find("defpackage.Bare"))
                assertEquals(top, it.find("Bare"))
            }
            project.refreshFromHost(context)
            project.rename(RenameRequest("s", SymbolKind.CLASS, SymbolRef(top.rawName), "Bare"), context)
            assertAcrossServices(decompiler, top.rawName,
                listOf(top.rawName, "Bare", "p000.Bare", "defpackage.Bare"))
        }
    }

    @Test fun exactRawPackageNamesWinOverDefaultPackageSpellings() {
        val types = listOf(RawIdentityFixture::class.java, p000.RawIdentityFixture::class.java,
            defpackage.RawIdentityFixture::class.java)
        JadxDecompiler(JadxArgs().apply {
            inputFiles = types.map { File(requireNotNull(it.getResource("/${it.name.replace('.', '/')}.class")).toURI()) }
            isDeobfuscationOn = false
        }).use { decompiler ->
            decompiler.load()
            val index = ClassIndex.Snapshot(decompiler.classesWithInners)
            for (type in types) {
                assertEquals(type.name, index.find(type.name)?.rawName)
                assertEquals(type.name, MetadataService(FakeWorkspace(decompiler))
                    .classes(ClassRequest("s", className = type.name), context).className)
                assertAcrossServices(decompiler, type.name, listOf(type.name))
            }
        }
    }

    /** Compare native identity, presentation and stored attachment identity, not alias strings alone. */
    private fun assertAcrossServices(decompiler: JadxDecompiler, raw: String, names: List<String>) {
        val access = FakeWorkspace(decompiler)
        val metadata = MetadataService(access)
        val project = JadxProjectService.open(access, context)
        val source = JadxSourceService(access, { project.revision })
        val symbols = ProjectSymbols(decompiler)
        for (name in names.distinct()) {
            assertEquals(raw, metadata.classes(ClassRequest("s", className = name), context).className)
            assertEquals(raw, symbols.reference(symbols.clazz(name)).declaringClass)
            val result = source.decompile(DecompileRequest("s", className = name), context)
            assertEquals(source.decompile(DecompileRequest("s", className = raw), context), result)
            assertEquals(symbols.clazz(raw).fullName, result.className)
            project.comment(AddCommentRequest("s", name, "cross-interface-identity"), context)
            assertEquals(raw, project.snapshot(context).data.comments.single().nodeRef.declaringClass)
            project.comment(AddCommentRequest("s", name, ""), context)
        }
    }

    @Test fun missingAndAmbiguousClassErrorsAgreeAcrossServices() {
        loaded().use { decompiler ->
            for (cls in decompiler.classesWithInners.filter { it.rawName.startsWith("com.yyang.sample.") }) {
                cls.classNode.classInfo.changePkg("")
                cls.classNode.classInfo.changeShortName("Ambiguous")
            }
            val access = FakeWorkspace(decompiler)
            val project = JadxProjectService.open(access, context)
            val source = JadxSourceService(access)
            for ((name, expected) in listOf("not.present.Class" to ErrorCode.NOT_FOUND, "Ambiguous" to ErrorCode.CONFLICT)) {
                val queries = listOf<() -> Any>(
                    { MetadataService(access).classes(ClassRequest("s", className = name), context) },
                    { source.decompile(DecompileRequest("s", className = name), context) },
                    { project.comment(AddCommentRequest("s", name, "must-not-apply"), context) })
                for (query in queries) {
                    assertEquals(expected, assertThrows(ProtocolException::class.java) { query() }.error.code)
                }
                assertTrue(project.snapshot(context).data.comments.isEmpty())
            }
        }
    }

    @Test fun manifestSummaryAndFilteringUseWorkspaceWithoutConcreteImplementation() {
        val xml = """<manifest xmlns:a="http://schemas.android.com/apk/res/android" package="com.yyang.sample">
          <application><activity a:name=".HelloSample"/><activity-alias a:name=".Launcher" a:targetActivity=".HelloSample">
          <intent-filter><action a:name="android.intent.action.MAIN"/><category a:name="android.intent.category.LAUNCHER"/></intent-filter>
          </activity-alias></application></manifest>"""
        loaded().use { decompiler ->
            decompiler.resources.add(object : ResourceFile(decompiler, "AndroidManifest.xml", ResourceType.MANIFEST) {
                override fun loadContent() = ResContainer.textResource("AndroidManifest.xml", SimpleCodeInfo(xml))
            })
            val access = FakeWorkspace(decompiler)
            val summary = MetadataService(access).summary(SummaryRequest("s"), context)
            assertEquals("com.yyang.sample.HelloSample", summary.mainActivity)
            assertEquals("com.yyang.sample", summary.mainPackage)
            assertFalse(summary.deobf)
            assertEquals(2, MetadataService(access).classes(ClassRequest("s", mainApp = true), context).page.total)
            val manifests = ManifestService(access)
            assertEquals(xml, manifests.manifest(ManifestRequest("s"), context).xml)
            val result = manifests.manifest(ManifestRequest("s", ComponentType.ACTIVITY, true), context)
            assertEquals("com.yyang.sample.Launcher", result.components.single().aliasName)
        }
    }

    @Test fun packageBoundariesAndCancellationAreEnforced() {
        assertTrue(ClassIndex.matchesPackage("a.b.child", "a.b"))
        assertFalse(ClassIndex.matchesPackage("a.bc", "a.b"))
        assertTrue(ClassIndex.matchesPackage("", ""))
        loaded().use { decompiler ->
            val service = MetadataService(FakeWorkspace(decompiler))
            val error = assertThrows(ProtocolException::class.java) {
                service.classes(ClassRequest("s"), context.copy(cancellation = CancellationSignal { true }))
            }
            assertEquals(ErrorCode.CANCELLED, error.error.code)
            assertThrows(ProtocolException::class.java) { service.classes(ClassRequest("s", page = PageRequest(-1, 1)), context) }
        }
    }
}
