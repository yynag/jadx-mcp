package com.yyang.jadxmcp.analysis.project

import com.yyang.jadxmcp.analysis.workspace.JadxWorkspace
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class ProjectOverrideRenameTest {
    private fun context(signal: CancellationSignal = CancellationSignal.NONE) =
        CallContext("project-override", 0, System.currentTimeMillis() + 60_000, signal)
    private val parent = ProjectParentFixture::class.java.name
    private val child = ProjectChildFixture::class.java.name
    private fun ref(owner: String, signature: String = "ping(I)Ljava/lang/String;") =
        NodeReference(NodeReferenceKind.METHOD, owner, signature)
    private fun rename(owner: String, name: String, signature: String = "ping(I)Ljava/lang/String;") =
        CodeRename(ref(owner, signature), newName = name)

    private fun workspace(): JadxWorkspace = JadxWorkspace.owned(JadxArgs().apply {
        inputFiles = listOf(ProjectParentFixture::class.java, ProjectChildFixture::class.java,
            ProjectGenericParentFixture::class.java, ProjectGenericChildFixture::class.java).map {
            File(requireNotNull(it.getResource("/${it.name.replace('.', '/')}.class")).toURI())
        }
        isDeobfuscationOn = false
    }, context())

    private fun aliases(decompiler: JadxDecompiler): Map<NodeReference, String> = buildMap {
        val symbols = ProjectSymbols(decompiler)
        for (cls in decompiler.classesWithInners) for (method in cls.methods) put(symbols.reference(method), method.name)
    }

    @Test
    fun conflictingOverrideNamesRejectWholeTransactionAndPreserveRevisionAndNativeAliases() {
        workspace().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val before = service.snapshot(context())
            val original = workspace.withDecompiler(context()) { decompiler ->
                val symbols = ProjectSymbols(decompiler)
                val group = symbols.methodRenameGroup(symbols.nativeMethod(ref(parent)))
                assertTrue(symbols.nativeMethod(ref(child)) in group, "Fixture must expose the native override group")
                aliases(decompiler)
            }
            // Include a valid class rename first: group rejection must precede ALL native mutations.
            val data = CodeData(renames = listOf(
                CodeRename(NodeReference(NodeReferenceKind.CLASS, parent), newName = "MustNotBeApplied"),
                rename(parent, "leftName"), rename(child, "rightName")))
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                service.replaceData(data, before.revision, context())
            }.error.code)
            assertEquals(before, service.snapshot(context()))
            workspace.withDecompiler(context()) { decompiler ->
                assertEquals(original, aliases(decompiler))
                assertEquals("ProjectParentFixture", ProjectSymbols(decompiler).clazz(parent).name)
                assertEquals(before.data, ProjectDataCodec.fromNative(decompiler.args.codeData))
            }
        }
    }

    @Test
    fun successiveSingleRenamesThroughDifferentOverrideMembersUpdateTheSameGroup() {
        workspace().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val initial = service.snapshot(context())
            val original = workspace.withDecompiler(context(), ::aliases)
            for ((owner, name) in listOf(parent to "firstName", child to "secondName", parent to "thirdName")) {
                val previousRevision = service.revision
                val result = service.rename(RenameRequest("s", SymbolKind.METHOD,
                    SymbolRef(owner, "ping", "(I)Ljava/lang/String;"), name), context())
                assertTrue(result.state.applied)
                assertNotEquals(previousRevision, result.state.revision)
                val snapshot = service.snapshot(context())
                assertTrue(snapshot.data.renames.all { it.newName == name }, "owner=$owner name=$name mappings=${snapshot.data.renames}")
                workspace.withDecompiler(context()) { decompiler ->
                    val symbols = ProjectSymbols(decompiler)
                    for (member in listOf(parent, child)) {
                        assertEquals(name, symbols.nativeMethod(ref(member)).alias)
                        assertEquals("ping", symbols.nativeMethod(ref(member, "ping(Ljava/lang/String;)Ljava/lang/String;")).alias)
                    }
                    assertEquals(snapshot.data, ProjectDataCodec.fromNative(decompiler.args.codeData))
                }
            }
            service.replaceData(initial.data, service.revision, context())
            workspace.withDecompiler(context()) { assertEquals(original, aliases(it)) }
        }
    }

    @Test
    fun matchingOverrideNamesSucceedAndDistinctOverloadNamesStayIndependentThenRestore() {
        workspace().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val before = service.snapshot(context())
            val original = workspace.withDecompiler(context(), ::aliases)
            val stringSignature = "ping(Ljava/lang/String;)Ljava/lang/String;"
            val data = CodeData(renames = listOf(rename(parent, "integerName"), rename(child, "integerName"),
                rename(parent, "stringName", stringSignature), rename(child, "stringName", stringSignature)))
            val state = service.replaceData(data, before.revision, context())
            assertTrue(state.applied)
            assertNotEquals(before.revision, state.revision)
            val saved = service.snapshot(context())
            assertEquals(ProjectDataCodec.canonical(data), saved.data)
            workspace.withDecompiler(context()) { decompiler ->
                val symbols = ProjectSymbols(decompiler)
                for (entry in saved.data.renames) assertEquals(entry.newName, symbols.nativeMethod(entry.nodeRef).alias)
                assertEquals(saved.data, ProjectDataCodec.fromNative(decompiler.args.codeData))
                // Verify source regeneration did not change the group semantics after the CAS commit.
                decompiler.classes.forEach { it.decompile() }
                for (entry in saved.data.renames) assertEquals(entry.newName, symbols.nativeMethod(entry.nodeRef).alias)
            }
            service.replaceData(before.data, saved.revision, context())
            workspace.withDecompiler(context()) { assertEquals(original, aliases(it)) }
        }
    }

    @Test
    fun nativeGenericBridgeGroupRejectsConflictingAliasesAndAcceptsMatchingOnes() {
        workspace().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val owner = ProjectGenericChildFixture::class.java.name
            val refs = workspace.withDecompiler(context()) { decompiler ->
                val symbols = ProjectSymbols(decompiler)
                val methods = symbols.clazz(owner).classNode.methods
                val bridge = methods.single { it.accessFlags.isBridge }
                val group = symbols.methodRenameGroup(bridge)
                val parentMethod = symbols.clazz(ProjectGenericParentFixture::class.java.name).classNode.methods
                    .single { it.methodInfo.name == "convert" }
                assertTrue(parentMethod in group, "Fixture must expose the native erased override group of the bridge")
                listOf(bridge, parentMethod).map { ref(it.declaringClass.classInfo.rawName, it.methodInfo.shortId) }
            }
            val before = service.snapshot(context())
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                service.replaceData(CodeData(renames = refs.mapIndexed { index, ref ->
                    CodeRename(ref, newName = "conflict$index")
                }), before.revision, context())
            }.error.code)
            assertEquals(before, service.snapshot(context()))
            service.replaceData(CodeData(renames = refs.map { CodeRename(it, newName = "convertedName") }), before.revision, context())
            workspace.withDecompiler(context()) { decompiler ->
                refs.forEach { assertEquals("convertedName", ProjectSymbols(decompiler).nativeMethod(it).alias) }
            }
        }
    }

    @Test
    fun cancelledGroupRenameRestoresAllNativeMembersBeforeReturning() {
        workspace().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val before = service.snapshot(context())
            val original = workspace.withDecompiler(context(), ::aliases)
            val cancellation = CancellationSignal {
                workspace.withDecompiler(context()) { decompiler ->
                    ProjectSymbols(decompiler).nativeMethod(ref(child)).alias == "cancelGroupName"
                }
            }
            assertEquals(ErrorCode.CANCELLED, assertThrows(ProtocolException::class.java) {
                service.replaceData(CodeData(renames = listOf(rename(parent, "cancelGroupName"), rename(child, "cancelGroupName"))),
                    before.revision, context(cancellation))
            }.error.code)
            assertEquals(before, service.snapshot(context()))
            workspace.withDecompiler(context()) { assertEquals(original, aliases(it)) }
        }
    }
}
