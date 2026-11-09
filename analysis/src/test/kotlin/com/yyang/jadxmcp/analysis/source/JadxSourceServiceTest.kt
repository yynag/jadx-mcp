package com.yyang.jadxmcp.analysis.source

import com.yyang.jadxmcp.analysis.workspace.JadxWorkspace
import com.yyang.jadxmcp.analysis.metadata.MetadataService
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.ResourceFile
import jadx.api.ResourceType
import jadx.api.impl.SimpleCodeInfo
import jadx.core.xmlgen.ResContainer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class JadxSourceServiceTest {
    private fun context() = CallContext("source-test", 0, System.currentTimeMillis() + 60_000)
    private fun fixture(): File = File("../app/src/test/resources/sample.jar").also { assertTrue(it.isFile) }
    private fun load(input: File = fixture()) = JadxWorkspace.owned(JadxArgs().apply {
        inputFiles = listOf(input)
        isDeobfuscationOn = false
    }, context())
    private fun request(name: String = "com.yyang.sample.HelloSample", target: SourceTarget = SourceTarget.JAVA,
                        method: String? = null, signature: String? = null) =
        DecompileRequest("test", target, name, method, signature)

    @Test
    fun nativeCacheRefilledAfterInvalidationCannotPublishPreviousGenerationSource() {
        load().use { workspace ->
            val service = JadxSourceService(workspace)
            val original = service.decompile(request(), context())
            service.invalidate(context())
            workspace.withDecompiler(context()) { decompiler ->
                val cls = requireNotNull(decompiler.searchJavaClassByOrigFullName(request().className))
                // A GUI refresh already in flight can publish after our unload/cache removal.
                decompiler.args.codeCache.add(cls.rawName, SimpleCodeInfo("stale native host source"))
            }
            val refreshed = service.decompile(request(), context())
            assertEquals(original.text.content, refreshed.text.content)
            assertFalse(refreshed.text.content.contains("stale native host source"))
        }
    }

    @Test
    fun realJavaOverloadsTruncationAndPresentation() {
        load().use { workspace ->
            val service = JadxSourceService(workspace)
            val java = service.decompile(request(), context())
            assertTrue(java.text.content.contains("JADX_CORE_MCP_SAMPLE_MAGIC"))
            assertFalse(java.text.truncated)
            val ambiguous = assertThrows(ProtocolException::class.java) {
                service.decompile(request("com.yyang.sample.Overloads", SourceTarget.METHOD, "echo"), context())
            }
            assertEquals(ErrorCode.INVALID_ARGUMENT, ambiguous.error.code)
            val stringMethod = service.decompile(request("com.yyang.sample.Overloads", SourceTarget.METHOD,
                "echo", "(Ljava/lang/String;)Ljava/lang/String;"), context())
            assertTrue(stringMethod.text.content.contains("String echo("), stringMethod.text.content)
            assertFalse(stringMethod.text.content.contains("int echo("), stringMethod.text.content)
            assertFalse(stringMethod.text.content.contains("callBoth("), stringMethod.text.content)
            val integer = service.decompile(request("com.yyang.sample.Overloads", SourceTarget.METHOD,
                "echo", "echo(I)Ljava/lang/String;"), context())
            assertTrue(integer.text.content.contains("String echo(int "), integer.text.content)
            val clipped = JadxSourceService(workspace, presentation = SourcePresentation(40))
                .decompile(request(), context()).text
            assertEquals(40, clipped.content.length)
            assertTrue(clipped.truncated)
            assertEquals(java.text.totalChars, clipped.totalChars)
            val numbered = service.decompile(request().copy(slim = true, withLineNumbers = true), context()).text
            assertTrue(numbered.content.startsWith("1: package "), numbered.content)
            assertFalse(numbered.content.startsWith("1: package com.yyang.sample;\n2: \n"))
            val missing = assertThrows(ProtocolException::class.java) {
                service.decompile(DecompileRequest("test", SourceTarget.MAIN_ACTIVITY), context())
            }
            assertEquals(ErrorCode.NOT_FOUND, missing.error.code) // JAR is not an APK.
        }
    }

    @Test
    fun methodSliceUsesCurrentCodeMetadataNotMutableNativeDefinitionPosition() {
        load().use { workspace ->
            val service = JadxSourceService(workspace)
            workspace.withDecompiler(context()) { d ->
                val cls = requireNotNull(d.searchJavaClassByOrigFullName("com.yyang.sample.Overloads"))
                cls.codeInfo
                val methods = cls.methods.filter { it.name == "echo" }
                val string = methods.single { it.methodNode.methodInfo.shortId == "echo(Ljava/lang/String;)Ljava/lang/String;" }
                val integer = methods.single { it.methodNode.methodInfo.shortId == "echo(I)Ljava/lang/String;" }
                // GUI reload/disk-cache reuse can leave node positions from a different generation.
                // The code metadata still identifies the exact overloaded declaration in this text.
                string.methodNode.defPosition = integer.defPos
            }
            repeat(3) {
                val result = service.decompile(request("com.yyang.sample.Overloads", SourceTarget.METHOD,
                    "echo", "(Ljava/lang/String;)Ljava/lang/String;").copy(slim = it % 2 == 0, withLineNumbers = it == 2), context())
                assertEquals("echo(Ljava/lang/String;)Ljava/lang/String;", result.methodSignature)
                assertTrue(result.text.content.contains("String echo(String "), result.text.content)
                assertFalse(result.text.content.contains("String echo(int "), result.text.content)
                assertFalse(result.text.content.contains("callBoth("), result.text.content)
                if (it == 2) {
                    val declaration = result.text.content.lineSequence().single { line -> line.contains("String echo(String ") }
                    val lineNumber = declaration.substringBefore(':').toInt()
                    val full = service.decompile(request("com.yyang.sample.Overloads"), context()).text.content
                    assertEquals(declaration.substringAfter(": "), full.lines()[lineNumber - 1])
                }
            }
        }
    }

    @Test
    fun nestedClassUsesExactNativeMethodIdentity() {
        val output = Files.createTempDirectory(File("build").toPath(), "method-identity-").toFile()
        val resource = File(requireNotNull(javaClass.getResource("/source/p3/P3Nested.java")).toURI())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "--release", "11", "-d", output.absolutePath, resource.absolutePath))
        val inputs = output.walkTopDown().filter { it.extension == "class" }.toList()
        JadxWorkspace.owned(JadxArgs().apply { inputFiles = inputs; isDeobfuscationOn = false }, context()).use { workspace ->
            val service = JadxSourceService(workspace)
            for (name in listOf("p3.source.P3Nested.Inner", "p3.source.P3Nested\$Inner")) {
                val result = service.decompile(request(name, SourceTarget.METHOD, "echo", "(I)I"), context())
                assertTrue(result.text.content.contains("73"), result.text.content)
                assertFalse(result.text.content.contains("string:"), result.text.content)
                assertFalse(result.text.content.contains("other("), result.text.content)
            }
        }
    }

    @Test
    fun revisionRegeneratesInsteadOfReturningOldAlias() {
        load().use { workspace ->
            val revision = AtomicLong()
            val service = JadxSourceService(workspace, { revision.get().toString() })
            val original = service.decompile(request(), context()).text.content
            workspace.withDecompiler(context()) { decompiler ->
                requireNotNull(decompiler.searchJavaClassByOrigFullName("com.yyang.sample.HelloSample"))
                    .classNode.classInfo.changeShortName("CurrentAlias")
            }
            revision.incrementAndGet()
            val changed = service.decompile(request(), context()).text.content
            assertNotEquals(original, changed)
            assertTrue(changed.contains("class CurrentAlias"), changed)
            service.invalidate(context())
            assertEquals(changed, service.decompile(request(), context()).text.content)
        }
    }

    @Test
    fun smaliIsNativeOutputOrExplicitlyUnsupportedForJavaInput() {
        load().use { workspace ->
            try {
                val text = JadxSourceService(workspace).decompile(request(target = SourceTarget.SMALI), context()).text
                assertEquals("text/x-smali", text.mediaType)
                assertTrue(text.content.isNotBlank())
            } catch (failure: ProtocolException) {
                assertEquals(ErrorCode.UNSUPPORTED, failure.error.code)
            }
        }
    }

    @Test
    fun launcherActivityResolvesFromManifestWithoutGuessingClassNames() {
        // Add a separately named textual manifest to a new JAR; never mutate the shared sample.jar.
        val jar = Files.createTempFile(File("build").toPath(), "launcher-manifest-", ".jar").toFile()
        JarOutputStream(jar.outputStream()).use { output ->
            JarFile(fixture()).use { source ->
                source.entries().asSequence().forEach { entry ->
                    output.putNextEntry(JarEntry(entry.name))
                    if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            output.putNextEntry(JarEntry("AndroidManifest.xml"))
            requireNotNull(javaClass.getResourceAsStream("/source/p3/P3AndroidManifest.xml")).use { it.copyTo(output) }
            output.closeEntry()
        }
        load(jar).use { workspace ->
            val result = JadxSourceService(workspace).decompile(DecompileRequest("test", SourceTarget.MAIN_ACTIVITY), context())
            assertEquals("com.yyang.sample.HelloSample", result.className)
            assertTrue(result.text.content.contains("JADX_CORE_MCP_SAMPLE_MAGIC"))
        }
    }

    @Test
    fun launcherUsesBaseManifestAndAliasTargetLikeMetadata() {
        load().use { workspace ->
            // Split comes first deliberately; the base manifest must win, not resource order.
            addManifest(workspace, manifest("""
                <activity a:name=".Overloads">$launcherFilter</activity>
            """, "split=\"feature\""))
            addManifest(workspace, manifest("""
                <activity a:name=".HelloSample" a:enabled="false"/>
                <activity-alias a:name=".Launcher" a:targetActivity="HelloSample">$launcherFilter</activity-alias>
            """))
            assertLauncherMatchesMetadata(workspace, "com.yyang.sample.HelloSample")
        }
    }

    @Test
    fun launcherSkipsDisabledPrivateAndDividedFiltersLikeMetadata() {
        load().use { workspace ->
            addManifest(workspace, manifest("""
                <activity a:name=".Overloads" a:enabled="false">$launcherFilter</activity>
                <activity a:name=".Overloads" a:exported="false">$launcherFilter</activity>
                <activity a:name=".Overloads">
                    <intent-filter><action a:name="android.intent.action.MAIN"/></intent-filter>
                    <intent-filter><category a:name="android.intent.category.LAUNCHER"/></intent-filter>
                </activity>
                <activity a:name="HelloSample">$launcherFilter</activity>
            """))
            assertLauncherMatchesMetadata(workspace, "com.yyang.sample.HelloSample")
        }
    }

    @Test
    fun disabledApplicationHasNoLauncherAndUnresolvedBooleanFailsClosed() {
        for ((enabled, expectedError) in listOf("false" to ErrorCode.NOT_FOUND, "@bool/missing" to ErrorCode.UNSUPPORTED)) {
            load().use { workspace ->
                addManifest(workspace, manifest("""
                    <activity a:name=".HelloSample">$launcherFilter</activity>
                """, applicationAttributes = "a:enabled=\"$enabled\""))
                val error = assertThrows(ProtocolException::class.java) {
                    JadxSourceService(workspace).decompile(DecompileRequest("test", SourceTarget.MAIN_ACTIVITY), context())
                }
                assertEquals(expectedError, error.error.code)
                if (enabled == "false") {
                    assertNull(MetadataService(workspace).summary(SummaryRequest("test"), context()).mainActivity)
                } else {
                    val metadataError = assertThrows(ProtocolException::class.java) {
                        MetadataService(workspace).summary(SummaryRequest("test"), context())
                    }
                    assertEquals(expectedError, metadataError.error.code)
                }
            }
        }
    }

    private val launcherFilter = """<intent-filter><action a:name="android.intent.action.MAIN"/>
        <category a:name="android.intent.category.LAUNCHER"/></intent-filter>"""

    private fun manifest(components: String, rootAttributes: String = "", applicationAttributes: String = "") = """
        <manifest xmlns:a="http://schemas.android.com/apk/res/android" package="com.yyang.sample" $rootAttributes>
            <application $applicationAttributes>$components</application>
        </manifest>
    """.trimIndent()

    private fun addManifest(workspace: JadxWorkspace, xml: String) {
        workspace.withDecompiler(context()) { decompiler ->
            decompiler.resources.add(object : ResourceFile(decompiler, "AndroidManifest.xml", ResourceType.MANIFEST) {
                override fun loadContent() = ResContainer.textResource("AndroidManifest.xml", SimpleCodeInfo(xml))
            })
        }
    }

    private fun assertLauncherMatchesMetadata(workspace: JadxWorkspace, expectedClass: String) {
        val summary = MetadataService(workspace).summary(SummaryRequest("test"), context())
        val result = JadxSourceService(workspace).decompile(DecompileRequest("test", SourceTarget.MAIN_ACTIVITY), context())
        assertEquals(expectedClass, summary.mainActivity)
        assertEquals(summary.mainActivity, result.className)
        assertTrue(result.text.content.contains("JADX_CORE_MCP_SAMPLE_MAGIC"))
    }
}
