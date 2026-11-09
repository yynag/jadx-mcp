package com.yyang.jadxmcp.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProtocolCodecTest {
    private val codec = ProtocolCodec()
    private val symbol = SymbolRef("example.App", "run", "run(I)V")
    private val requests: List<OperationRequest> = listOf(
        CreateSessionRequest("/sample.apk", "4g", false), ListSessionsRequest(),
        CloseSessionRequest("s", true), SummaryRequest("s"),
        ManifestRequest("s", ComponentType.ACTIVITY, true),
        ClassRequest("s", packageName = "example", page = PageRequest(2, 3)),
        DecompileRequest("s", SourceTarget.METHOD, "example.App", "run", "run(I)V", true),
        ResourceRequest("s", ResourceAction.ID, resourceId = "0x7f010001"),
        XrefRequest("s", SymbolKind.METHOD, symbol, PageRequest(1, 4)),
        SearchRequest("s", SearchScope.STRING, "hello", "example", StringSearchMode.DEX_CONSTANTS, budget = SearchBudget(9, 2)),
        RenameRequest("s", SymbolKind.METHOD, symbol, "execute"),
        CurrentClassRequest("s", true), SelectedTextRequest("s"),
        MethodByNameRequest("s", "run", "example.App", "run(I)V", true),
        AppClassNamesRequest("s"), AppClassCodeRequest("s", PageRequest(1, 2), true),
        PackageTreeRequest("s"), CacheStatsRequest("s"), ClearCacheRequest("s"),
        RenamePackageRequest("s", "example", "renamed"),
        RenameVariableRequest("s", symbol, "arg", "count", 1, 2),
        AddCommentRequest("s", "example.App", "hello\n世界", "run", "run(I)V", line = 10, style = CommentStyle.JAVADOC),
        ListCommentsRequest("s", "example.App"), StackFramesRequest("s"), ThreadsRequest("s"), VariablesRequest("s")
    )

    @Test fun all26RequestsRoundTripWithTypedNestedValues() {
        assertEquals(26, Operation.entries.size)
        assertEquals(Operation.entries.toSet(), requests.map { it.operation }.toSet())
        for (request in requests) {
            val encoded = codec.encodeRequest(request)
            assertEquals(request, codec.decodeRequest(encoded), request.operation.wireName)
            assertFalse(encoded.contains("apk_id"))
        }
    }

    @Test fun all26ResultsRoundTripUsingTheirRegisteredType() {
        val source = SourceResult("example.App", text = TextContent("class App {}", false, 12))
        val state = MutationState(true, false, "r2")
        for (operation in Operation.entries) {
            val result: OperationResult = when (operation) {
                Operation.SESSION_CREATE -> SessionInfo("s", BackendKind.GUI, "window", SessionState.READY, 2)
                Operation.SESSION_LIST -> SessionListResult(listOf(SessionInfo("s")))
                Operation.SESSION_CLOSE -> CloseSessionResult("s", true)
                Operation.META_SUMMARY -> SummaryResult(12, "example.App", "example", true, listOf(PackageCount("example", 12)))
                Operation.META_MANIFEST -> ManifestResult(components = listOf(ManifestComponent("example.App", aliasName = "example.Alias")))
                Operation.META_CLASS -> ClassResult("example.App", methods = listOf(MethodInfo("run", "example.App.run", "run(I)V", "void", listOf("int"))))
                Operation.DECOMPILE, Operation.FETCH_CURRENT_CLASS, Operation.GET_METHOD_BY_NAME -> source
                Operation.RESOURCE -> ResourceResult(ResourceAction.STRINGS, strings = listOf(StringResource("hello", "world")))
                Operation.XREF -> XrefResult(listOf(ReferenceHit(symbol, "method", "run(1)")))
                Operation.SEARCH -> SearchResult(listOf(SearchHit(symbol, "hello", "preview", 10)), stats = ScanStats(4, 2, false))
                Operation.RENAME -> RenameResult(symbol, "run", "execute", state)
                Operation.GET_SELECTED_TEXT -> SelectionResult("世界", "example.App")
                Operation.GET_MAIN_APPLICATION_CLASSES_NAMES -> AppClassNamesResult(listOf("example.App"))
                Operation.GET_MAIN_APPLICATION_CLASSES_CODE -> AppClassCodeResult(listOf(source))
                Operation.GET_PACKAGE_TREE -> PackageTreeResult(listOf(PackageEntry("example", 12)), 1, 12)
                Operation.GET_CACHE_STATS -> CacheStatsResult(2, 4, 3, 400, 800)
                Operation.CLEAR_CACHE -> ClearCacheResult(true)
                Operation.RENAME_PACKAGE -> RenamePackageResult(3, state)
                Operation.RENAME_VARIABLE -> RenameVariableResult(symbol, "arg", "count", 1, 2, state)
                Operation.ADD_COMMENT -> CommentMutationResult(CommentAction.UPDATED, state, 10)
                Operation.LIST_COMMENTS -> CommentsResult(listOf(CodeComment(NodeReference(declaringClass = "example.App"), comment = "note")))
                Operation.DEBUG_GET_STACK_FRAMES -> StackFramesResult(listOf(DebugFrame(0, "App.run", symbol)))
                Operation.DEBUG_GET_THREADS -> ThreadsResult(listOf(DebugThread("1", "main", true)))
                Operation.DEBUG_GET_VARIABLES -> VariablesResult(listOf(DebugVariable("a", "1", "int", children = listOf(DebugVariable("b", "2")))))
            }
            assertEquals(result, codec.decodeResult(operation, codec.encodeResult(operation, result)), operation.wireName)
        }
    }

    @Test fun defaultsSurviveDecodingRatherThanGsonZeroInitialization() {
        val request = codec.decodeRequest("""{"version":1,"operation":"session_create","params":{"apk_path":"/a.apk"}}""") as CreateSessionRequest
        assertTrue(request.deobf)
        val query = codec.decodeRequest("""{"version":1,"operation":"meta_class","params":{"session_id":"s"}}""") as ClassRequest
        assertEquals(PageRequest(0, 50), query.page)
    }

    @Test fun invalidWireInputsAreRejectedWithoutPrimitiveCoercion() {
        val invalid = listOf(
            "[]", "{", """{"version":2,"operation":"session_list","params":{}}""",
            """{"version":1,"operation":"apk_load","params":{}}""",
            """{"version":1,"operation":"meta_summary","params":{}}""",
            """{"version":1,"operation":"meta_summary","params":{"session_id":null}}""",
            """{"version":1,"operation":"meta_summary","params":{"session_id":123}}""",
            """{"version":1,"operation":"session_create","params":{"apk_path":"/a","deobf":"false"}}""",
            """{"version":1,"operation":"search","params":{"session_id":"s","term":"x","scope":"NOPE"}}""",
            """{"version":1,"operation":"meta_class","params":{"session_id":"s","page":{"count":1.2}}}""",
            """{"version":1,"operation":"meta_class","params":{"session_id":"s","page":{"count":0}}}""",
            """{"version":1,"operation":"meta_class","params":{"session_id":"s","page":{"offset":2147483648}}}""",
            """{"version":1,"operation":"meta_class","params":{"session_id":"s","unknown":true}}""",
            """{"version":1,"operation":"add_comment","params":{"session_id":"s","class_name":"App"}}"""
        )
        for (json in invalid) {
            val failure = assertThrows(ProtocolException::class.java) { codec.decodeRequest(json) }
            assertEquals(ErrorCode.INVALID_ARGUMENT, failure.error.code, json)
        }
    }

    @Test fun dependentArgumentsAndMutationTargetsAreValidated() {
        val invalid = listOf(
            CreateSessionRequest("/a", type = BackendKind.GUI), CreateSessionRequest("/a", "-Xmx4g"),
            DecompileRequest("s", SourceTarget.METHOD, "App"),
            DecompileRequest("s", SourceTarget.SMALI, "App", slim = true),
            ResourceRequest("s", ResourceAction.ID, resourceId = "0x100000000"),
            SearchRequest("s", term = "x", budget = SearchBudget(0, 0)),
            XrefRequest("s", SymbolKind.METHOD, SymbolRef("App")),
            RenameVariableRequest("s", symbol, "a", "b", -1),
            AddCommentRequest("s", "App", "x", fieldName = "field", line = 1)
        )
        invalid.forEach { request -> assertThrows(ProtocolException::class.java) { codec.encodeRequest(request) } }
        val delete = AddCommentRequest("s", "App", "")
        assertEquals(delete, codec.decodeRequest(codec.encodeRequest(delete)))
    }

    @Test fun nestedCollectionsKeepStrictElementAndObjectShapes() {
        val operation = Operation.GET_MAIN_APPLICATION_CLASSES_CODE
        val invalidResults = listOf(
            """{"classes":{}}""",
            """{"classes":[null]}""",
            """{"classes":[{"text":{"content":123}}]}""",
            """{"classes":[{"text":{"unknown":true}}]}"""
        )
        for (result in invalidResults) {
            val json = """{"version":1,"operation":"${operation.wireName}","result":$result}"""
            val failure = assertThrows(ProtocolException::class.java) { codec.decodeResult(operation, json) }
            assertEquals(ErrorCode.INVALID_ARGUMENT, failure.error.code, json)
        }
        val names = Operation.GET_MAIN_APPLICATION_CLASSES_NAMES
        assertThrows(ProtocolException::class.java) {
            codec.decodeResult(names, """{"version":1,"operation":"${names.wireName}","result":{"classes":[123]}}""")
        }
    }

    @Test fun resultTypeAndOperationMismatchAreRejected() {
        assertThrows(ProtocolException::class.java) { codec.encodeResult(Operation.META_SUMMARY, SelectionResult()) }
        assertThrows(ProtocolException::class.java) {
            codec.decodeResult(Operation.META_SUMMARY, codec.encodeResult(Operation.GET_SELECTED_TEXT, SelectionResult()))
        }
    }

    @Test fun frameAndSuccessOrErrorCompletionRoundTrip() {
        val frame = RequestFrame(SummaryRequest("s"), CallContext("q", 9, 123456))
        val decoded = codec.decodeFrame(codec.encodeFrame(frame))
        assertEquals(frame.request, decoded.request)
        assertEquals(frame.context, decoded.context)
        val success = CommandCompletion("q", 9, Operation.META_SUMMARY, SummaryResult(12))
        val failure = CommandCompletion("q", 9, Operation.META_SUMMARY, error = ProtocolError(ErrorCode.DISCONNECTED, "lost", true))
        assertEquals(success, codec.decodeCompletion(codec.encodeCompletion(success)))
        assertEquals(failure, codec.decodeCompletion(codec.encodeCompletion(failure)))
        assertThrows(ProtocolException::class.java) { CommandCompletion("q", 9, Operation.META_SUMMARY) }
    }

    @Test fun cancellationDeadlineAndEpochAreDistinctFailures() {
        val signal = CancellationSource()
        val context = CallContext("q", 2, 100, signal)
        context.checkActive(99)
        assertEquals(ErrorCode.DEADLINE_EXCEEDED, assertThrows(ProtocolException::class.java) { context.checkActive(100) }.error.code)
        assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) { context.requireEpoch(3) }.error.code)
        signal.cancel()
        assertEquals(ErrorCode.CANCELLED, assertThrows(ProtocolException::class.java) { context.checkActive(1) }.error.code)
    }

    @Test fun guiScopeAndRegisteredOperationNamesAreExplicit() {
        assertEquals(15, Operation.entries.count { it.scope == OperationScope.GUI })
        assertFalse(Operation.GET_SELECTED_TEXT.allowsBackend(BackendKind.HEADLESS))
        assertTrue(Operation.SEARCH.allowsBackend(BackendKind.GUI))
        assertThrows(ProtocolException::class.java) { Operation.fromWireName("sync_project_data") }
    }

    @Test fun projectSnapshotPreservesNativeReferenceShapeWithoutChoosingSyncPolicy() {
        val node = NodeReference(NodeReferenceKind.METHOD, "example.App", "run(I)V")
        val snapshot = ProjectDataSnapshot(
            listOf(InputFingerprint("a".repeat(64), 123)), "r1",
            CodeData(
                listOf(CodeComment(node, CodeReference(CodeAttachmentKind.INSN, 20), "hello", CommentStyle.BLOCK)),
                listOf(CodeRename(node, CodeReference(CodeAttachmentKind.VAR, (2 shl 16) or 3), "value"))
            )
        )
        assertEquals(snapshot, codec.decodeSnapshot(codec.encodeSnapshot(snapshot)))
        assertThrows(ProtocolException::class.java) { codec.encodeSnapshot(snapshot.copy(inputs = emptyList())) }
        assertThrows(ProtocolException::class.java) { codec.decodeSnapshot("""{"inputs":[{"sha256":"bad"}],"revision":"r1"}""") }
    }
}
