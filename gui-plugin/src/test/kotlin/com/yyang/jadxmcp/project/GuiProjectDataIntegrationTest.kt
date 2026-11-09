package com.yyang.jadxmcp.project

import com.yyang.jadxmcp.analysis.project.*
import com.yyang.jadxmcp.analysis.workspace.JadxWorkspace
import com.yyang.jadxmcp.analysis.source.JadxSourceService
import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.ui.GuiEdtExecutor
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.metadata.annotations.InsnCodeOffset
import jadx.api.data.impl.JadxCodeData
import jadx.api.data.impl.JadxCodeRename
import jadx.api.data.impl.JadxNodeRef
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import javax.swing.SwingUtilities

/** Real core/planners + fake GUI owner; this does NOT claim a real GUI window was exercised. */
class GuiProjectDataIntegrationTest {
    private fun context() = CallContext("gui-project", 1, System.currentTimeMillis() + 30_000)
    private val className = "com.yyang.sample.HelloSample"
    private fun fixture() = listOf(Path.of("../app/src/test/resources/sample.jar"), Path.of("app/src/test/resources/sample.jar"))
        .first { it.toFile().isFile }.toFile()

    @Test fun nativeBindingPreservesSharedOwnerIdentityAndAppliesNativeAliases() {
        JadxDecompiler(JadxArgs().apply { inputFiles = listOf(fixture()); threadsCount = 1 }).use { d ->
            d.load()
            val cls = d.searchJavaClassByOrigFullName(className)!!
            val shared = JadxCodeData()
            NativeGuiCodeDataBinding.apply(d, shared, context())
            assertSame(shared, d.args.codeData)
            shared.renames = listOf(JadxCodeRename(JadxNodeRef.forCls(cls), "NativeAlias"))
            NativeGuiCodeDataBinding.apply(d, shared, context())
            assertSame(shared, d.args.codeData)
            assertEquals("NativeAlias", cls.name)
            assertTrue(cls.code.contains("NativeAlias"))
        }
    }

    @Test fun nativeMethodMappingRestorationAppliesBeforeAnyHostBackgroundRefresh() {
        JadxDecompiler(JadxArgs().apply { inputFiles = listOf(fixture()); threadsCount = 1 }).use { d ->
            d.load()
            val cls = requireNotNull(d.searchJavaClassByOrigFullName(className))
            val ref = JadxNodeRef.forMth(cls.methods.single { it.methodNode.methodInfo.name == "greet" })
            val shared = JadxCodeData()
            for (name in listOf("temporaryAlias", "greet", "anotherAlias", "greet")) {
                shared.renames = listOf(JadxCodeRename(ref, name))
                NativeGuiCodeDataBinding.apply(d, shared, context())
                assertSame(shared, d.args.codeData)
                assertEquals(name, cls.methods.single { it.methodNode.methodInfo.name == "greet" }.name)
                assertTrue(cls.code.contains("String $name("))
            }
        }
    }

    @Test fun twoFieldRenameReadbackRestoreCyclesKeepCurrentNativeFieldIdentity() {
        JadxDecompiler(JadxArgs().apply { inputFiles = listOf(fixture()); threadsCount = 1 }).use { d ->
            d.load()
            val cls = requireNotNull(d.searchJavaClassByOrigFullName(className))
            val workspace = JadxWorkspace.borrowed(d) { 1 }
            val source = JadxSourceService(workspace)
            val shared = JadxCodeData()
            val ref = JadxNodeRef.forFld(cls.fields.single { it.fieldNode.fieldInfo.name == "MAGIC" })
            repeat(2) {
                for (name in listOf("RENAMED_READBACK_FIELD", "MAGIC")) {
                    shared.renames = listOf(JadxCodeRename(ref, name))
                    NativeGuiCodeDataBinding.apply(d, shared, context())
                    source.invalidate(context())
                    val text = source.decompile(DecompileRequest("s", className = className), context()).text.content
                    assertTrue(text.contains("String $name ="), text)
                    assertEquals(name, cls.fields.single { it.fieldNode.fieldInfo.name == "MAGIC" }.name)
                    assertSame(shared, d.args.codeData)
                }
            }
            workspace.close()
        }
    }

    @Test fun concreteCodecCanonicalizesNativeDataAndUsesInputContentCasWithoutDiskOwner() {
        JadxDecompiler(JadxArgs().apply { inputFiles = listOf(fixture()); threadsCount = 1 }).use { d ->
            d.load()
            val workspace = JadxWorkspace.borrowed(d) { 1 }
            val codec = GuiProjectDataCodec(workspace)
            val identities = InputIdentity.fingerprint(listOf(fixture()), context())
            val data = CodeData(comments = listOf(CodeComment(NodeReference(declaringClass = className), comment = "native roundtrip")))
            val one = codec.build(identities, data)
            assertEquals(data, codec.fromNative(codec.toNative(data)))
            assertEquals(one.revision, codec.build(identities, data).revision)
            assertNotEquals(one.revision, codec.build(identities, CodeData()).revision)
            assertEquals(data, codec.validateImport(one, codec.build(identities, CodeData()),
                codec.build(identities, CodeData()).revision, context()))
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                codec.validateImport(one, one, "stale", context())
            }.error.code)
            workspace.close()
            assertNotNull(d.searchJavaClassByOrigFullName(className)) // borrowed close did not close host
        }
    }

    @Test fun classFieldMethodInstructionAndDeclarationCommentsCommitThroughGuiOwner() {
        JadxDecompiler(JadxArgs().apply { inputFiles = listOf(fixture()); threadsCount = 1 }).use { d ->
            d.load()
            val workspace = JadxWorkspace.borrowed(d) { 1 }
            val codec = GuiProjectDataCodec(workspace)
            var data = CodeData()
            val identity = Any()
            var writes = 0
            val host = object : GuiProjectAccess {
                override fun read(): GuiProjectView {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    return GuiProjectView(identity, data, null, NativeSavePolicy.ASK)
                }
                override fun replace(expectedIdentity: Any, data: CodeData): NativeApplyResult {
                    assertTrue(SwingUtilities.isEventDispatchThread()); assertSame(identity, expectedIdentity)
                    writes++
                    // Keep GUI model ownership separate from the core generation state.
                    setData(data)
                    return NativeApplyResult(true, false)
                }
                private fun setData(value: CodeData) { data = value }
            }
            val inputs = InputIdentity.fingerprint(listOf(fixture()), context())
            val owner = GuiProjectDataOwner(host, codec, { inputs }, GuiEdtExecutor { 1 }, { 1 }) { ctx ->
                val copy = ctx.copy(cancellation = CancellationSignal.NONE, deadlineEpochMillis = System.currentTimeMillis() + 30_000)
                val after = GuiEdtExecutor { 1 }.readOrEdit(copy) { host.read().data }
                workspace.withDecompiler(copy) {
                    it.args.codeData = codec.toNative(after)
                    it.reloadCodeData()
                    it.classes.forEach { cls -> cls.unload() }
                }
            }
            val editor = GuiCommentEditor(workspace, owner, owner::replaceData)
            fun add(request: AddCommentRequest): CommentMutationResult = editor.comment(request, context()).also {
                assertTrue(it.state.applied); assertFalse(it.state.persisted); assertNull(it.state.projectPath)
            }
            add(AddCommentRequest("s", className, "class comment"))
            add(AddCommentRequest("s", className, "field comment", fieldName = "MAGIC"))
            add(AddCommentRequest("s", className, "method comment", methodName = "greet"))
            val declarationLine = workspace.withDecompiler(context()) {
                val method = ProjectSymbols(it).method(SymbolRef(className, "add"))
                method.topParentClass.code.take(method.defPos).count { c -> c == '\n' } + 1
            }
            add(AddCommentRequest("s", className, "declaration comment", line = declarationLine))
            val instructionLine = workspace.withDecompiler(context()) { core ->
                val info = ProjectSymbols(core).clazz(className).codeInfo
                val offset = info.codeMetadata.asMap.entries.first { (pos, ann) ->
                    ann is InsnCodeOffset && (core.getEnclosingNode(info, pos) as? jadx.api.JavaMethod)?.name == "greet"
                }.key
                info.codeStr.take(offset).count { it == '\n' } + 1
            }
            add(AddCommentRequest("s", className, "instruction comment", methodName = "greet", line = instructionLine))
            val stored = editor.comments(ListCommentsRequest("s", className), context()).comments
            assertEquals(5, stored.size)
            assertTrue(stored.any { it.nodeRef.type == NodeReferenceKind.FIELD })
            assertTrue(stored.any { it.codeRef?.attachType == CodeAttachmentKind.INSN })
            assertTrue(workspace.withDecompiler(context()) { ProjectSymbols(it).clazz(className).code.contains("instruction comment") })
            assertEquals(CommentAction.UPDATED, add(AddCommentRequest("s", className, "changed class")).action)
            assertEquals(CommentAction.REMOVED, add(AddCommentRequest("s", className, "")).action)
            assertEquals(7, writes)
            workspace.close()
        }
    }
}
