package com.yyang.jadxmcp.analysis.metadata

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
            for (type in listOf(Outer::class.java, Outer.Inner::class.java)) {
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
            val top = decompiler.searchJavaClassByOrigFullName("com.yyang.sample.HelloSample")!!
            top.classNode.classInfo.changePkg("")
            top.classNode.classInfo.changeShortName("Bare")
            index.read(context) {
                assertEquals(top, it.find("p000.Bare"))
                assertEquals(top, it.find("defpackage.Bare"))
                assertEquals(top, it.find("Bare"))
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
