package com.yyang.jadxmcp.api

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.yyang.jadxmcp.protocol.*
import kotlin.test.*

class McpOutputTest {
    private val codec = ProtocolCodec()
    private fun render(operation: Operation, result: OperationResult): JsonElement =
        JsonParser.parseString(McpOutput.render(operation, codec.encodeResult(operation, result)))

    private fun assertJson(expected: String, actual: JsonElement) = assertEquals(JsonParser.parseString(expected), actual)

    @Test fun `all 26 operations have an explicit projection and no wire wrapper`() {
        val cases = mapOf<Operation, OperationResult>(
            Operation.SESSION_CREATE to SessionInfo(), Operation.SESSION_LIST to SessionListResult(),
            Operation.SESSION_CLOSE to CloseSessionResult(),
            Operation.META_SUMMARY to SummaryResult(), Operation.META_MANIFEST to ManifestResult(),
            Operation.META_CLASS to ClassResult(), Operation.DECOMPILE to SourceResult(),
            Operation.RESOURCE to ResourceResult(), Operation.XREF to XrefResult(),
            Operation.SEARCH to SearchResult(), Operation.RENAME to RenameResult(),
            Operation.FETCH_CURRENT_CLASS to SourceResult(), Operation.GET_SELECTED_TEXT to SelectionResult(),
            Operation.GET_METHOD_BY_NAME to SourceResult(), Operation.GET_MAIN_APPLICATION_CLASSES_NAMES to AppClassNamesResult(),
            Operation.GET_MAIN_APPLICATION_CLASSES_CODE to AppClassCodeResult(), Operation.GET_PACKAGE_TREE to PackageTreeResult(),
            Operation.GET_CACHE_STATS to CacheStatsResult(), Operation.CLEAR_CACHE to ClearCacheResult(),
            Operation.RENAME_PACKAGE to RenamePackageResult(), Operation.RENAME_VARIABLE to RenameVariableResult(),
            Operation.ADD_COMMENT to CommentMutationResult(), Operation.LIST_COMMENTS to CommentsResult(),
            Operation.DEBUG_GET_STACK_FRAMES to StackFramesResult(), Operation.DEBUG_GET_THREADS to ThreadsResult(),
            Operation.DEBUG_GET_VARIABLES to VariablesResult()
        )
        assertEquals(26, cases.size)
        assertEquals(Operation.entries.toSet(), cases.keys)
        cases.forEach { (operation, input) ->
            val output = render(operation, input)
            if (operation == Operation.SESSION_LIST) assertJson("[]", output)
            else listOf("version", "operation", "result").forEach { assertFalse(output.asJsonObject.has(it), operation.name) }
        }
    }

    @Test fun `session summaries are root arrays and headless names are basenames`() {
        val info = SessionInfo("s", BackendKind.HEADLESS, "/private/apps/demo.apk", SessionState.READY,
            7, 10, 20, BackendCapabilities(debuggerAvailable = false))
        val summary = """{"session_id":"s","backend":"HEADLESS","name":"demo.apk","state":"READY"}"""
        assertJson("[$summary]", render(Operation.SESSION_LIST, SessionListResult(listOf(info))))
        assertJson(summary, render(Operation.SESSION_CREATE, info))
        val gui = info.copy(backend = BackendKind.GUI, name = "project / title")
        assertEquals(gui.name, render(Operation.SESSION_LIST, SessionListResult(listOf(gui))).asJsonArray[0].asJsonObject["name"].asString)
        assertEquals("demo.apk", render(Operation.SESSION_CREATE, info.copy(name = "C:\\private\\demo.apk")).asJsonObject["name"].asString)
    }

    @Test fun `text length is conditional without losing content type or truncation`() {
        val plain = TextContent("", false, 0, "text/plain")
        val cut = TextContent("abc", true, 90, "text/x-smali")
        listOf(Operation.DECOMPILE, Operation.FETCH_CURRENT_CLASS, Operation.GET_METHOD_BY_NAME).forEach {
            assertJson("""{"class_name":"A","text":{"content":"","truncated":false,"media_type":"text/plain"}}""",
                render(it, SourceResult("A", text = plain)))
            assertEquals(90, render(it, SourceResult("A", text = cut)).asJsonObject.getAsJsonObject("text")["total_chars"].asInt)
        }
        val app = render(Operation.GET_MAIN_APPLICATION_CLASSES_CODE,
            AppClassCodeResult(listOf(SourceResult("A", text = plain), SourceResult("B", text = cut)))).asJsonObject
        val sources = app.getAsJsonArray("classes")
        assertFalse(sources[0].asJsonObject.getAsJsonObject("text").has("total_chars"))
        assertEquals(90, sources[1].asJsonObject.getAsJsonObject("text")["total_chars"].asInt)
    }

    @Test fun `page metadata keeps known zero and continuation but never invents total`() {
        val unknown = render(Operation.SEARCH, SearchResult(page = PageInfo(10, 2, null, 12),
            stats = ScanStats(0, 0, false), stringMode = StringSearchMode.SOURCE_TEXT)).asJsonObject
        assertJson("""{"next_offset":12}""", unknown["page"])
        assertFalse(unknown.has("string_mode"))
        assertJson("[]", unknown["hits"])
        assertJson("""{"scanned":0,"decompiled":0,"complete":false}""", unknown["stats"])
        assertJson("""{"total":0}""", render(Operation.SEARCH,
            SearchResult(page = PageInfo(total = 0))).asJsonObject["page"])
        assertFalse(render(Operation.SEARCH, SearchResult()).asJsonObject.has("page"))
    }

    @Test fun `manifest and class metadata drop only inapplicable branches`() {
        assertJson("""{"xml":"<manifest/>"}""", render(Operation.META_MANIFEST, ManifestResult(xml = "<manifest/>")))
        assertJson("""{"components":[]}""", render(Operation.META_MANIFEST,
            ManifestResult(componentType = ComponentType.ACTIVITY)))
        val components = render(Operation.META_MANIFEST, ManifestResult(components = listOf(
            ManifestComponent("A", exported = false, enabled = false)))).asJsonObject.getAsJsonArray("components")
        assertFalse(components[0].asJsonObject["exported"].asBoolean)
        assertFalse(components[0].asJsonObject["enabled"].asBoolean)
        assertJson("""{"class_name":"A","methods":[],"fields":[]}""", render(Operation.META_CLASS, ClassResult(className = "A")))
        assertJson("""{"classes":[],"page":{"total":0}}""", render(Operation.META_CLASS, ClassResult(page = PageInfo(total = 0))))
    }

    @Test fun `resource action chooses its result branch including empty results and binary metadata`() {
        assertJson("""{"files":[],"page":{"total":0}}""", render(Operation.RESOURCE,
            ResourceResult(ResourceAction.LIST, page = PageInfo(total = 0))))
        assertJson("""{"strings":[]}""", render(Operation.RESOURCE, ResourceResult(ResourceAction.STRINGS)))
        assertJson("""{"file":{"name":"empty.bin","binary":true,"size_bytes":0}}""", render(Operation.RESOURCE,
            ResourceResult(ResourceAction.FILE, file = ResourceFile("empty.bin", binary = true, sizeBytes = 0))))
        val file = render(Operation.RESOURCE, ResourceResult(ResourceAction.FILE,
            file = ResourceFile("a.xml", TextContent("", false, 0, "application/xml")))).asJsonObject.getAsJsonObject("file")
        assertFalse(file["binary"].asBoolean)
        assertJson("""{"content":"","truncated":false,"media_type":"application/xml"}""", file["text"])
        assertJson("""{"resource":{"id_hex":"0x00000000","value":""}}""", render(Operation.RESOURCE,
            ResourceResult(ResourceAction.ID, resource = ResourceIdResult("0x00000000", value = ""))))
    }

    @Test fun `xref retains excerpt full method and exact overloaded identity`() {
        val symbol = SymbolRef("A", "run", "(I)V")
        val result = render(Operation.XREF, XrefResult(listOf(ReferenceHit(symbol, "METHOD", "run(0)",
            TextContent("void run(int n) {}", false, 18))), PageInfo(0, 1, 2, 1))).asJsonObject
        val hit = result.getAsJsonArray("references")[0].asJsonObject
        assertEquals("run(0)", hit["snippet"].asString)
        assertEquals("(I)V", hit.getAsJsonObject("source")["descriptor"].asString)
        assertTrue(hit.has("method_code"))
        assertFalse(hit.getAsJsonObject("method_code").has("total_chars"))
        assertJson("""{"total":2,"next_offset":1}""", result["page"])
    }

    @Test fun `package count is removable only for a proven complete list`() {
        val complete = PackageTreeResult(listOf(PackageEntry("a", 0)), 1, 0)
        assertJson("""{"packages":[{"name":"a","class_count":0}],"total_classes":0,"truncated":false}""",
            render(Operation.GET_PACKAGE_TREE, complete))
        assertEquals(1, render(Operation.GET_PACKAGE_TREE, complete.copy(truncated = true)).asJsonObject["total_packages"].asInt)
        assertEquals(3, render(Operation.GET_PACKAGE_TREE, complete.copy(totalPackages = 3)).asJsonObject["total_packages"].asInt)
    }

    @Test fun `mutations keep actual identity names zero and separate persistence without private path`() {
        val state = MutationState(false, false, "r1", "/private/user/project.jadx")
        val symbol = SymbolRef("A", "run", "(I)V")
        val mutations = mapOf<Operation, OperationResult>(
            Operation.RENAME to RenameResult(symbol, "old", "new", state),
            Operation.RENAME_PACKAGE to RenamePackageResult(0, state),
            Operation.RENAME_VARIABLE to RenameVariableResult(symbol, "old", "new", 0, 0, state),
            Operation.ADD_COMMENT to CommentMutationResult(CommentAction.REMOVED, state, 1)
        )
        mutations.forEach { (operation, input) ->
            val output = render(operation, input).asJsonObject
            assertJson("""{"applied":false,"persisted":false,"revision":"r1"}""", output["state"])
            val original = JsonParser.parseString(codec.encodeResult(operation, input)).asJsonObject.getAsJsonObject("result")
            original.getAsJsonObject("state").remove("project_path")
            assertEquals(original, output)
        }
        assertJson("""{"cleared":false}""", render(Operation.CLEAR_CACHE, ClearCacheResult(false, CacheStatsResult(entries = 9))))
        assertJson("""{"closed":false}""", render(Operation.SESSION_CLOSE, CloseSessionResult("s", false)))
    }

    @Test fun `debug types IDs status nullability and nested empty arrays remain unchanged`() {
        val cases = mapOf<Operation, OperationResult>(
            Operation.DEBUG_GET_THREADS to ThreadsResult(listOf(DebugThread("0001", "main", false))),
            Operation.DEBUG_GET_STACK_FRAMES to StackFramesResult(listOf(DebugFrame(0, "frame", SymbolRef("A", "run", "()V")))),
            Operation.DEBUG_GET_VARIABLES to VariablesResult(listOf(DebugVariable("v", "0", "int", "I", false,
                listOf(DebugVariable("child", typeId = "0000")))))
        )
        cases.forEach { (operation, input) ->
            assertEquals(JsonParser.parseString(codec.encodeResult(operation, input)).asJsonObject["result"], render(operation, input))
        }
    }

    @Test fun `untrimmed operations retain all meaningful payload fields`() {
        val cases = mapOf<Operation, OperationResult>(
            Operation.META_SUMMARY to SummaryResult(0, "A", "a", false, listOf(PackageCount("a", 0))),
            Operation.GET_SELECTED_TEXT to SelectionResult("", "A"),
            Operation.GET_MAIN_APPLICATION_CLASSES_NAMES to AppClassNamesResult(listOf("a.A")),
            Operation.GET_CACHE_STATS to CacheStatsResult(0, 0, 7, 0, 19),
            Operation.LIST_COMMENTS to CommentsResult(listOf(CodeComment(
                NodeReference(NodeReferenceKind.METHOD, "A", "run()V"), CodeReference(index = 0), "note")))
        )
        cases.forEach { (operation, input) ->
            assertEquals(JsonParser.parseString(codec.encodeResult(operation, input)).asJsonObject["result"], render(operation, input))
        }
        val hit = SearchHit(SymbolRef("A", "run", "(I)V"), "0", "preview", 0)
        val search = render(Operation.SEARCH, SearchResult(hits = listOf(hit), stats = ScanStats(3, 0, true))).asJsonObject
        assertEquals("(I)V", search.getAsJsonArray("hits")[0].asJsonObject.getAsJsonObject("symbol")["descriptor"].asString)
        assertEquals(0, search.getAsJsonArray("hits")[0].asJsonObject["line"].asInt)
        assertTrue(search.getAsJsonObject("stats")["complete"].asBoolean)
    }

    @Test fun `actual adapter error shape stays small and retryable false survives`() {
        val encoded = GuiTransportCodec().encodeError(ProtocolError(ErrorCode.NOT_FOUND, "No such symbol", false))
        assertJson("""{"error":{"code":"NOT_FOUND","message":"No such symbol","retryable":false}}""",
            JsonParser.parseString(McpOutput.render(Operation.DECOMPILE, encoded)))
    }
}
