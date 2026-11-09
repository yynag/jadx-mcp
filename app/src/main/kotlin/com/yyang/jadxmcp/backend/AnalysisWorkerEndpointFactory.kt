package com.yyang.jadxmcp.backend

import com.yyang.jadxmcp.analysis.metadata.ManifestService
import com.yyang.jadxmcp.analysis.metadata.MetadataService
import com.yyang.jadxmcp.analysis.resource.ResourceService
import com.yyang.jadxmcp.analysis.source.JadxSourceService
import com.yyang.jadxmcp.analysis.workspace.JadxWorkspace
import com.yyang.jadxmcp.analysis.search.SearchService
import com.yyang.jadxmcp.analysis.reference.ReferenceService
import com.yyang.jadxmcp.analysis.project.JadxProjectService
import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.worker.WorkerEndpointFactory
import jadx.api.JadxArgs
import jadx.api.args.GeneratedRenamesMappingFileMode
import jadx.api.args.UserRenamesMappingsMode
import jadx.core.plugins.files.IJadxFilesGetter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Public no-arg child SPI for P10; no legacy JadxEngine, project persistence, or GUI dependency. */
class AnalysisWorkerEndpointFactory : WorkerEndpointFactory {
    override fun open(request: CreateSessionRequest, context: CallContext, generatedCache: Path): BackendEndpoint {
        request.validate()
        context.checkActive()
        context.requireEpoch(0)
        val input = Path.of(request.apkPath).toAbsolutePath().normalize()
        if (!Files.isRegularFile(input) || !Files.isReadable(input))
            throw fail(ErrorCode.NOT_FOUND, "Input is not a readable file: $input")
        Files.createDirectories(generatedCache)
        val config = Files.createDirectories(generatedCache.resolve("plugin-config"))
        val cache = Files.createDirectories(generatedCache.resolve("plugin-cache"))
        val temp = Files.createDirectories(generatedCache.resolve("temp"))
        val args = JadxArgs().apply {
            inputFiles = listOf(input.toFile())
            isDeobfuscationOn = request.deobf
            threadsCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            setRootDir(generatedCache.resolve("output").toFile())
            // Generated aliases are expendable and exclusively worker-owned. Never discover/read
            // or autosave user rename maps/codeData next to the input while persistence is pending.
            generatedRenamesMappingFile = generatedCache.resolve("generated-aliases.jobf").toFile()
            generatedRenamesMappingFileMode = GeneratedRenamesMappingFileMode.READ_OR_SAVE
            userRenamesMappingsMode = UserRenamesMappingsMode.IGNORE
            filesGetter = object : IJadxFilesGetter {
                override fun getConfigDir() = config
                override fun getCacheDir() = cache
                override fun getTempDir() = temp
            }
        }
        val workspace = JadxWorkspace.owned(args, context)
        return try { AnalysisBackend(workspace, context) } catch (failure: Throwable) {
            workspace.close()
            throw failure
        }
    }

    private fun fail(code: ErrorCode, message: String) = ProtocolException(ProtocolError(code, message))
}

/** One owned workspace shared by the real metadata/source/resource services. */
class AnalysisBackend internal constructor(private val workspace: JadxWorkspace, context: CallContext) :
    BackendEndpoint, ProjectDataPort, ProjectEditPort {
    private val metadata = MetadataService(workspace)
    private val manifest = ManifestService(workspace)
    private val project = JadxProjectService.open(workspace, context) // No store: applied != persisted.
    private val source = JadxSourceService(workspace, revision = { project.revision })
    private val resource = ResourceService(workspace)
    private val search = SearchService(workspace)
    private val references = ReferenceService(workspace)
    private val closed = AtomicBoolean()
    override val capabilities = BackendCapabilities(BackendKind.HEADLESS,
        operations = Operation.entries.filter { it.scope != OperationScope.MANAGEMENT }.map { operation ->
            val supported = operation in SUPPORTED
            OperationCapability(operation, supported, reason = if (supported) null
                else if (operation.scope == OperationScope.GUI) "Requires GUI backend"
                else "Not assembled in the read-only worker yet")
        }, stringSearchModes = workspace.withDecompiler(context) { decompiler ->
            // Same native-format discriminator used by P5, not the input's filename/extension.
            val dex = decompiler.classesWithInners.any {
                it.classNode.clsData?.javaClass?.name == "jadx.plugins.input.dex.sections.DexClassData"
            }
            if (dex) setOf(StringSearchMode.DEX_CONSTANTS, StringSearchMode.SOURCE_TEXT)
            else setOf(StringSearchMode.SOURCE_TEXT)
        })

    override fun execute(request: BackendRequest, context: CallContext): OperationResult {
        request.validate()
        context.checkActive()
        context.requireEpoch(workspace.projectEpoch)
        if (closed.get()) throw fail(ErrorCode.CLOSED, "Analysis workspace closed")
        if (!request.operation.requestType.isInstance(request)) throw fail(ErrorCode.INVALID_ARGUMENT, "Wrong request type")
        if (!request.operation.allowsBackend(BackendKind.HEADLESS))
            throw fail(ErrorCode.UNSUPPORTED, "${request.operation.wireName} is GUI-only in the current wire contract")
        return when (request) {
            is SummaryRequest -> metadata.summary(request, context)
            is ClassRequest -> metadata.classes(request, context)
            is ManifestRequest -> manifest.manifest(request, context)
            is DecompileRequest -> source.decompile(request, context)
            is ResourceRequest -> resource.resource(request, context)
            is SearchRequest -> search.search(request, context)
            is XrefRequest -> references.references(request, context)
            is RenameRequest -> rename(request, context)
            else -> throw fail(ErrorCode.UNSUPPORTED, "${request.operation.wireName} is not assembled in the worker")
        }
    }

    // Local ports are usable by an in-process owner. Snapshot/import and GUI-scoped edit requests
    // have no permitted headless wire route today; never tunnel them through unrelated DTO fields.
    override fun snapshot(context: CallContext) = project.snapshot(context)
    override fun importData(snapshot: ProjectDataSnapshot, expectedRevision: String, context: CallContext) =
        project.importData(snapshot, expectedRevision, context)
    override fun rename(request: RenameRequest, context: CallContext) = project.rename(request, context)
    override fun renamePackage(request: RenamePackageRequest, context: CallContext) = project.renamePackage(request, context)
    override fun renameVariable(request: RenameVariableRequest, context: CallContext) = project.renameVariable(request, context)
    override fun comment(request: AddCommentRequest, context: CallContext) = project.comment(request, context)
    override fun comments(request: ListCommentsRequest, context: CallContext) = project.comments(request, context)

    override fun close() {
        if (closed.compareAndSet(false, true)) workspace.close()
        // WorkerBackend owns disk cleanup after child termination, never the input/codeData tree.
    }

    private fun fail(code: ErrorCode, message: String) = ProtocolException(ProtocolError(code, message))
    companion object {
        private val SUPPORTED = setOf(Operation.META_SUMMARY, Operation.META_CLASS, Operation.META_MANIFEST,
            Operation.DECOMPILE, Operation.RESOURCE, Operation.SEARCH, Operation.XREF, Operation.RENAME)
    }
}
