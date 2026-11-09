package com.yyang.jadxmcp.analysis.project

import com.yyang.jadxmcp.analysis.workspace.JadxWorkspace
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.metadata.annotations.InsnCodeOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class JadxProjectServiceTest {
    private val hello = "com.yyang.sample.HelloSample"
    private val overloads = "com.yyang.sample.Overloads"
    private fun context(signal: CancellationSignal = CancellationSignal.NONE) =
        CallContext("project-test", 0, System.currentTimeMillis() + 60_000, signal)
    private fun fixture() = File("../app/src/test/resources/sample.jar")
    private fun args(input: File = fixture(), data: CodeData = CodeData()) = JadxArgs().apply {
        inputFiles = listOf(input)
        isDeobfuscationOn = false
        codeData = ProjectDataCodec.toNative(data)
    }
    private fun load(input: File = fixture(), data: CodeData = CodeData()) = JadxWorkspace.owned(args(input, data), context())

    @Test
    fun realClassMethodFieldRenamesUseRawReferencesAndSurviveReload() {
        lateinit var snapshot: ProjectDataSnapshot
        load().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            service.rename(RenameRequest("s", SymbolKind.CLASS, SymbolRef(hello), "RenamedHello"), context())
            val rejected = assertThrows(ProtocolException::class.java) {
                service.rename(RenameRequest("s", SymbolKind.METHOD, SymbolRef(overloads, "echo"), "renamed"), context())
            }
            assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.error.code)
            service.rename(RenameRequest("s", SymbolKind.METHOD,
                SymbolRef(overloads, "echo", "(Ljava/lang/String;)Ljava/lang/String;"), "stringEcho"), context())
            val field = workspace.withDecompiler(context()) { ProjectSymbols(it).clazz(hello).fields.first().name }
            service.rename(RenameRequest("s", SymbolKind.FIELD, SymbolRef(hello, field), "RENAMED_FIELD"), context())
            snapshot = service.snapshot(context())
            assertTrue(snapshot.data.renames.all { it.nodeRef.declaringClass in setOf(hello, overloads) })
            assertTrue(snapshot.data.renames.any { it.nodeRef.shortId == "echo(Ljava/lang/String;)Ljava/lang/String;" })
            workspace.withDecompiler(context()) {
                assertTrue(ProjectSymbols(it).clazz(hello).code.contains("class RenamedHello"))
                assertTrue(ProjectSymbols(it).clazz(overloads).code.contains("stringEcho("))
                assertEquals(1, ProjectSymbols(it).clazz(overloads).methods.count { m -> m.name == "echo" })
            }
        }
        load(data = snapshot.data).use { workspace ->
            workspace.withDecompiler(context()) {
                val code = ProjectSymbols(it).clazz(hello).code
                assertTrue(code.contains("class RenamedHello"), code)
                assertTrue(code.contains("RENAMED_FIELD"), code)
                assertTrue(ProjectSymbols(it).clazz(overloads).code.contains("stringEcho("))
            }
        }
    }

    @Test
    fun repeatedAndDeletedCommentsUpdateNativeSourceAndMutationState() {
        load().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val request = AddCommentRequest("s", hello, "initial-comment")
            val first = service.comment(request, context())
            assertTrue(first.state.applied)
            assertFalse(first.state.persisted)
            val repeated = service.comment(request, context())
            assertFalse(repeated.state.applied)
            assertEquals(first.state.revision, repeated.state.revision)
            val updated = service.comment(request.copy(comment = "updated-comment", style = CommentStyle.BLOCK), context())
            assertEquals(CommentAction.UPDATED, updated.action)
            assertEquals(1, service.comments(ListCommentsRequest("s"), context()).comments.size)
            workspace.withDecompiler(context()) { assertTrue(ProjectSymbols(it).clazz(hello).code.contains("updated-comment")) }
            val removed = service.comment(request.copy(comment = ""), context())
            assertEquals(CommentAction.REMOVED, removed.action)
            assertTrue(removed.state.applied)
            workspace.withDecompiler(context()) { assertFalse(ProjectSymbols(it).clazz(hello).code.contains("updated-comment")) }
            assertFalse(service.comment(request.copy(comment = ""), context()).state.applied)
        }
    }

    @Test
    fun nativeVariableRegSsaRenameAndInstructionLineAttachment() {
        load().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val variable = workspace.withDecompiler(context()) { decompiler ->
                val symbols = ProjectSymbols(decompiler)
                val method = symbols.method(SymbolRef(overloads, "echo", "(Ljava/lang/String;)Ljava/lang/String;"))
                val value = symbols.variables(method).first { it.name != "this" }
                println("Native echo(String) variable: name=${value.name}, reg=${value.reg}, ssa=${value.ssa}")
                Triple(requireNotNull(value.name), value.reg, value.ssa)
            }
            val result = service.renameVariable(RenameVariableRequest("s",
                SymbolRef(overloads, "echo", "(Ljava/lang/String;)Ljava/lang/String;"), variable.first,
                "renamedArgument", variable.second, variable.third), context())
            assertEquals(variable.second, result.reg)
            assertEquals(variable.third, result.ssa)
            workspace.withDecompiler(context()) { assertTrue(ProjectSymbols(it).clazz(overloads).code.contains("renamedArgument")) }
            val lineAndOffset = workspace.withDecompiler(context()) { decompiler ->
                val symbols = ProjectSymbols(decompiler)
                val method = symbols.method(SymbolRef(overloads, "callBoth"))
                val info = method.topParentClass.codeInfo
                info.codeMetadata.asMap.entries.filter { (pos, ann) ->
                    ann is InsnCodeOffset && decompiler.getEnclosingNode(info, pos) == method
                }.groupBy { (pos, _) -> info.codeStr.take(pos).count { it == '\n' } + 1 }
                    .entries.first { entry -> entry.value.map { (it.value as InsnCodeOffset).offset }.distinct().size == 1 }
                    .let { it.key to (it.value.first().value as InsnCodeOffset).offset }
            }
            service.comment(AddCommentRequest("s", overloads, "instruction-comment", methodName = "callBoth", line = lineAndOffset.first), context())
            println("Native callBoth instruction: sourceLine=${lineAndOffset.first}, offset=${lineAndOffset.second}")
            val stored = service.snapshot(context()).data.comments.single()
            assertEquals(CodeReference(CodeAttachmentKind.INSN, lineAndOffset.second), stored.codeRef)
            workspace.withDecompiler(context()) { assertTrue(ProjectSymbols(it).clazz(overloads).code.contains("instruction-comment")) }
        }
    }

    @Test
    fun packageRenameRespectsSegmentBoundaries() {
        load().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val result = service.renamePackage(RenamePackageRequest("s", "com.yyang.sample", "project.renamed"), context())
            assertEquals(2, result.renamed)
            workspace.withDecompiler(context()) {
                assertEquals("project.renamed", ProjectSymbols(it).clazz(hello).`package`)
                assertEquals("com.yyang.sample2", ProjectSymbols(it).clazz("com.yyang.sample2.Extra").`package`)
            }
        }
    }

    @Test
    fun concurrentCasHasOneWinnerAndNoLostUpdate() {
        load().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val revision = service.revision
            val executor = Executors.newFixedThreadPool(2)
            val gate = CountDownLatch(1)
            try {
                val calls = (1..2).map { index -> executor.submit<Boolean> {
                    check(gate.await(5, TimeUnit.SECONDS))
                    try {
                        service.replaceData(CodeData(comments = listOf(CodeComment(
                            NodeReference(NodeReferenceKind.CLASS, hello), comment = "writer-$index"))), revision, context())
                        true
                    } catch (failure: ProtocolException) { assertEquals(ErrorCode.CONFLICT, failure.error.code); false }
                } }
                gate.countDown()
                assertEquals(1, calls.count { it.get(10, TimeUnit.SECONDS) })
                assertEquals(1, service.snapshot(context()).data.comments.size)
            } finally { executor.shutdownNow() }
        }
    }

    @Test
    fun importsRefuseConflictsWrongInputsAndForgedReferencesWithoutMutation() {
        load().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            service.comment(AddCommentRequest("s", hello, "local"), context())
            val before = service.snapshot(context())
            val incoming = before.copy(revision = "remote", data = before.data.copy(comments = before.data.comments.map { it.copy(comment = "incoming") }))
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                service.importData(incoming, before.revision, context())
            }.error.code)
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                service.importData(before.copy(inputs = listOf(InputFingerprint("0".repeat(64), 1))), before.revision, context())
            }.error.code)
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProtocolException::class.java) {
                service.replaceData(CodeData(renames = listOf(CodeRename(
                    NodeReference(NodeReferenceKind.METHOD, overloads, "echo(Z)Z"), newName = "bad"))), before.revision, context())
            }.error.code)
            assertEquals(before, service.snapshot(context()))
        }
    }

    @Test
    fun mergeUsesAuthenticBaseAndCombinesIndependentChangesIncludingDeletion() {
        load().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            service.comment(AddCommentRequest("s", hello, "base"), context())
            val base = service.snapshot(context())
            service.rename(RenameRequest("s", SymbolKind.CLASS, SymbolRef(hello), "LocalName"), context())
            val incoming = base.copy(revision = "remote", data = CodeData())
            service.mergeData(base, incoming, service.revision, context())
            val merged = service.snapshot(context())
            assertTrue(merged.data.comments.isEmpty())
            assertEquals("LocalName", merged.data.renames.single().newName)
            assertThrows(ProtocolException::class.java) {
                service.mergeData(base.copy(data = CodeData()), incoming, service.revision, context())
            }
        }
    }

    @Test
    fun cancellationRejectsBeforeCommitAndOwnerSaveIsExplicitAndAccurate() {
        load().use { workspace ->
            val saves = AtomicInteger()
            val service = JadxProjectService.open(workspace, context(), ProjectOwnerStore { snapshot, _ ->
                saves.incrementAndGet()
                OwnerSaveReceipt(snapshot.revision, "private-owner-project")
            })
            val before = service.snapshot(context())
            val cancelled = CancellationSource().apply { cancel() }
            assertEquals(ErrorCode.CANCELLED, assertThrows(ProtocolException::class.java) {
                service.comment(AddCommentRequest("s", hello, "cancelled"), context(cancelled))
            }.error.code)
            assertEquals(before, service.snapshot(context()))
            val mutation = service.comment(AddCommentRequest("s", hello, "not yet saved"), context()).state
            assertFalse(mutation.persisted)
            assertEquals(0, saves.get())
            val saved = service.persist(mutation.revision, context())
            assertFalse(saved.applied)
            assertTrue(saved.persisted)
            assertEquals("private-owner-project", saved.projectPath)
            assertEquals(1, saves.get())
            assertTrue(service.comment(AddCommentRequest("s", hello, "not yet saved"), context()).state.persisted)
            assertFalse(service.comment(AddCommentRequest("s", hello, "changed again"), context()).state.persisted)
        }
    }

    @Test
    fun nativeHostChangesRequireExplicitRefreshAndBorrowedCannotUseExternalStore() {
        val decompiler = JadxDecompiler(args()).apply { load() }
        val workspace = JadxWorkspace.borrowed(decompiler) { 0 }
        try {
            assertThrows(ProtocolException::class.java) {
                JadxProjectService.open(workspace, context(), ProjectOwnerStore { snapshot, _ -> OwnerSaveReceipt(snapshot.revision) })
            }
            val service = JadxProjectService.open(workspace, context())
            workspace.withDecompiler(context()) {
                it.args.codeData = ProjectDataCodec.toNative(CodeData(comments = listOf(CodeComment(
                    NodeReference(NodeReferenceKind.CLASS, hello), comment = "host edit"))))
            }
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) { service.snapshot(context()) }.error.code)
            assertEquals("host edit", service.refreshFromHost(context()).data.comments.single().comment)
            assertEquals(ErrorCode.UNSUPPORTED, assertThrows(ProtocolException::class.java) {
                service.persist(service.revision, context())
            }.error.code)
        } finally { workspace.close(); decompiler.close() }
    }

    @Test
    fun inputFingerprintsAreContentMultisetsAndDetectChangedInput() {
        val directory = Files.createTempDirectory(File("build").toPath(), "project-input-").toFile()
        val first = fixture().copyTo(directory.resolve("first.jar"))
        val second = fixture().copyTo(directory.resolve("different-name.jar"))
        assertEquals(InputIdentity.fingerprint(listOf(first), context()), InputIdentity.fingerprint(listOf(second), context()))
        assertEquals(2, InputIdentity.fingerprint(listOf(first, second), context()).size)
        load(first).use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            first.appendBytes(byteArrayOf(1))
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) { service.snapshot(context()) }.error.code)
        }
    }

    @Test
    fun cancellationAfterNativeApplicationRollsBackBeforePublishingRevision() {
        load().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val before = service.snapshot(context())
            val signal = CancellationSignal {
                workspace.withDecompiler(context()) { it.args.codeData?.comments.orEmpty().isNotEmpty() }
            }
            assertEquals(ErrorCode.CANCELLED, assertThrows(ProtocolException::class.java) {
                service.comment(AddCommentRequest("s", hello, "cancelled-comment"), context(signal))
            }.error.code)
            assertEquals(before, service.snapshot(context()))
            workspace.withDecompiler(context()) { assertFalse(ProjectSymbols(it).clazz(hello).code.contains("cancelled-comment")) }
        }
    }

    @Test
    fun renameRemovalRestoresAliasAndStagedDuplicateNamesAreRejected() {
        load().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val initial = service.snapshot(context())
            service.rename(RenameRequest("s", SymbolKind.CLASS, SymbolRef(hello), "TemporaryAlias"), context())
            service.replaceData(initial.data, service.revision, context())
            workspace.withDecompiler(context()) { assertEquals("HelloSample", ProjectSymbols(it).clazz(hello).name) }
            val before = service.snapshot(context())
            val collision = CodeData(renames = listOf(hello, overloads).map {
                CodeRename(NodeReference(NodeReferenceKind.CLASS, it), newName = "SameName")
            })
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                service.replaceData(collision, service.revision, context())
            }.error.code)
            assertEquals(before, service.snapshot(context()))
        }
    }

    @Test
    fun failedOwnerSaveDoesNotLoseAppliedMemoryChangesOrClaimPersistence() {
        load().use { workspace ->
            val service = JadxProjectService.open(workspace, context(), ProjectOwnerStore { _, _ -> error("owner unavailable") })
            val mutation = service.comment(AddCommentRequest("s", hello, "memory survives"), context()).state
            assertEquals(ErrorCode.PERSISTENCE_FAILED, assertThrows(ProtocolException::class.java) {
                service.persist(mutation.revision, context())
            }.error.code)
            assertEquals(mutation.revision, service.revision)
            assertFalse(service.comment(AddCommentRequest("s", hello, "memory survives"), context()).state.persisted)
            assertEquals("memory survives", service.snapshot(context()).data.comments.single().comment)
        }
    }

    @Test
    fun unsupportedNativeCommentAttachmentsFailBeforeChangingCodeData() {
        load().use { workspace ->
            val service = JadxProjectService.open(workspace, context())
            val before = service.snapshot(context())
            for (type in listOf(CodeAttachmentKind.VAR, CodeAttachmentKind.MTH_ARG, CodeAttachmentKind.CATCH)) {
                val data = CodeData(comments = listOf(CodeComment(
                    NodeReference(NodeReferenceKind.METHOD, overloads, "echo(Ljava/lang/String;)Ljava/lang/String;"),
                    CodeReference(type, 0), "unsupported")))
                assertEquals(ErrorCode.UNSUPPORTED, assertThrows(ProtocolException::class.java) {
                    service.replaceData(data, service.revision, context())
                }.error.code)
            }
            assertEquals(before, service.snapshot(context()))
        }
    }
}
