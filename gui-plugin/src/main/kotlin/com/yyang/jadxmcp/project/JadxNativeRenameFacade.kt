package com.yyang.jadxmcp.project

import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.analysis.project.ProjectSymbols
import com.yyang.jadxmcp.ui.requireGuiWorker
import jadx.api.*
import jadx.api.data.ICodeRename
import jadx.api.plugins.events.JadxEvents
import jadx.api.plugins.events.types.NodeRenamedByUser
import jadx.gui.settings.JadxProject
import jadx.gui.settings.data.SaveOptionEnum
import jadx.gui.treemodel.JRenameNode
import jadx.gui.ui.MainWindow
import jadx.gui.utils.pkgs.JRenamePackage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Uses native JRenameNode.buildCodeRename, not a parallel rename algorithm. Class/method/field and
 * variable nodes come from JNodeCache; package nodes match PackageHelper.getRenameNodes 55-73.
 * RenameDialog.sendRenameEvent 114-119 and RenameService 60-151 are the native workflow.
 * Requires a fully initialized MainWindow (RenameService registered BEFORE this facade is used).
 * Awaiting, source loading and persistence confirmation are worker/event-thread-only, not EDT.
 * Native project codeData confirmation is the applied-state boundary, not event submission.
 * RenameService then refreshes source on the host BackgroundExecutor; its event callback returning
 * does not imply that asynchronous refresh finished. Source readback requires a separate regression.
 * Persistence is a separate confirmation governed by host save policy; a save failure never rolls
 * back an already-applied rename. Cancellation stops waiting/admission, not past native side effects.
 */
class JadxNativeRenameFacade(
    private val window: MainWindow,
    private val workspace: WorkspaceAccess<JadxDecompiler>,
    private val ui: UiExecutor,
    private val projectData: ProjectDataPort,
    private val epoch: () -> Long,
    private val invalidated: () -> Unit
) : GuiNativeRenameFacade {
    private fun check(context: CallContext) {
        context.checkActive(); context.requireEpoch(workspace.projectEpoch); context.requireEpoch(epoch())
    }
    private fun fail(code: ErrorCode, message: String): Nothing = throw ProtocolException(ProtocolError(code, message))

    override fun rename(request: RenameRequest, context: CallContext): NativeRenameReceipt {
        request.validate(); requireGuiWorker(); check(context)
        val node = workspace.withDecompiler(context) { d ->
            val cls = resolveClass(d, request.target.className)
            val node = when (request.targetType) {
                SymbolKind.CLASS -> cls
                SymbolKind.METHOD -> single(cls.methods.filter { m ->
                    (m.name == request.target.memberName || m.methodNode.methodInfo.name == request.target.memberName) &&
                        matches(m, request.target.descriptor)
                }, "method")
                SymbolKind.FIELD -> single(cls.fields.filter { f ->
                    (f.name == request.target.memberName || f.fieldNode.fieldInfo.name == request.target.memberName) &&
                        (request.target.descriptor == null || request.target.descriptor == f.fieldNode.fieldInfo.type.toString() ||
                            request.target.descriptor == f.fieldNode.fieldInfo.shortId.substringAfter(':'))
                }, "field")
            }
            val symbols = ProjectSymbols(d)
            symbols.requireRenameAvailable(symbols.reference(node), request.newName)
            node
        }
        val rename = ui.readOrEdit(context) {
            check(context)
            (window.cacheObject.nodeCache.makeFrom(node) as? JRenameNode)?.replace()
                ?: fail(ErrorCode.UNSUPPORTED, "GUI node does not support native rename")
        }
        return apply(rename, request.newName, symbol(node), context)
    }

    override fun renamePackage(request: RenamePackageRequest, context: CallContext): NativeRenameReceipt {
        request.validate(); requireGuiWorker(); check(context)
        val (pkg, count) = workspace.withDecompiler(context) { d ->
            val pkg = single(d.packages.filter { it.rawFullName == request.oldPackageName || it.fullName == request.oldPackageName }, "package")
            val symbols = ProjectSymbols(d)
            symbols.requireRenameAvailable(symbols.packageReference(pkg.rawFullName), request.newPackageName)
            pkg to d.classesWithInners.count { cls ->
                context.checkActive()
                val raw = cls.classNode.classInfo.`package`
                raw == pkg.rawFullName || raw.startsWith("${pkg.rawFullName}.")
            }
        }
        val node = ui.readOrEdit(context) {
            check(context)
            JRenamePackage(pkg, pkg.rawFullName, pkg.fullName, pkg.name)
        }
        return apply(node, request.newPackageName, SymbolRef(), context).copy(renamed = count)
    }

    override fun renameVariable(request: RenameVariableRequest, context: CallContext): NativeRenameReceipt {
        request.validate(); requireGuiWorker(); check(context)
        val variable = workspace.withDecompiler(context) { d ->
            val cls = resolveClass(d, request.method.className)
            val method = single(cls.methods.filter {
                (it.name == request.method.memberName || it.methodNode.methodInfo.name == request.method.memberName) &&
                    matches(it, request.method.descriptor)
            }, "method")
            val symbols = ProjectSymbols(d)
            val variable = symbols.variable(method, request.variableName, request.reg, request.ssa)
            if (symbols.variables(method).any { it != variable && it.name == request.newName })
                fail(ErrorCode.CONFLICT, "Variable name already exists in method")
            d.getJavaNodeByRef(variable) as? JavaVariable ?: fail(ErrorCode.NOT_FOUND, "Native variable was not resolved")
        }
        val node = ui.readOrEdit(context) {
            check(context)
            (window.cacheObject.nodeCache.makeFrom(variable) as? JRenameNode)?.replace()
                ?: fail(ErrorCode.UNSUPPORTED, "GUI variable node cannot be renamed")
        }
        return apply(node, request.newName, symbol(variable.mth), context).copy(reg = variable.reg, ssa = variable.ssa)
    }

    private fun apply(node: JRenameNode, newName: String, target: SymbolRef, context: CallContext): NativeRenameReceipt {
        check(context)
        val completed = CompletableFuture<Boolean>()
        val expected = AtomicReference<ICodeRename>()
        val owner = AtomicReference<JadxProject>()
        val guarded = object : JRenameNode by node {
            override fun buildCodeRename(name: String, renames: MutableSet<ICodeRename>): ICodeRename {
                // Native events are asynchronous. Fence again at the actual mutation, not just send.
                check(context)
                return node.buildCodeRename(name, renames).also(expected::set)
            }
        }
        val oldName = ui.readOrEdit(context) {
            check(context)
            if (!node.canRename() || !node.isValidName(newName)) fail(ErrorCode.INVALID_ARGUMENT, "Invalid native rename")
            owner.set(window.project)
            node.name
        }
        val (event, globalEvents) = ui.readOrEdit(context) {
            check(context)
            if (window.project !== owner.get()) fail(ErrorCode.CONFLICT, "GUI project owner changed before dispatch")
            NodeRenamedByUser(node.javaNode.codeNodeRef, oldName, newName).apply { renameNode = guarded } to window.events().global()
        }
        val listener = Consumer<NodeRenamedByUser> { observed ->
            if (observed === event) {
                try {
                    check(context)
                    // Global events use one FIFO thread. This observer runs after RenameService;
                    // merely sending the event is never interpreted as a successful application.
                    val project = requireNotNull(owner.get())
                    val shouldSave = confirmProjectMutation(project, expected.get(), context)
                    completed.complete(shouldSave && confirmPersistence(project, context))
                } catch (e: Throwable) { completed.completeExceptionally(e) }
            }
        }
        var acknowledged = false
        try {
            ui.readOrEdit(context) {
                check(context)
                globalEvents.addListener(JadxEvents.NODE_RENAMED_BY_USER, listener)
                window.events().send(event)
            }
            val persisted = await(completed, context)
            acknowledged = true
            invalidated()
            val snapshot = projectData.snapshot(context)
            val path = ui.readOrEdit(context) { check(context); window.project.projectPath?.toString() }
            return NativeRenameReceipt(oldName, newName, MutationState(true, persisted, snapshot.revision, path), target)
        } finally {
            // Listener registry is thread-safe. Do not enqueue cleanup behind an expired EDT request.
            globalEvents.removeListener(JadxEvents.NODE_RENAMED_BY_USER, listener)
            if (!acknowledged && expected.get() != null) invalidated() // native mutation may have outlived the caller
        }
    }

    /** Inspect the exact native rename, never infer success from a sent event or stale isSaved. */
    private fun confirmProjectMutation(project: JadxProject, rename: ICodeRename?, context: CallContext): Boolean =
        ui.readOrEdit(context) {
            check(context)
            if (window.project !== project) fail(ErrorCode.CONFLICT, "GUI project owner changed")
            if (rename == null) fail(ErrorCode.INTERNAL, "Native rename handler did not run")
            val present = project.codeData?.renames?.any {
                it.nodeRef == rename.nodeRef && it.codeRef == rename.codeRef && it.newName == rename.newName
            } == true
            if (!present) fail(ErrorCode.INTERNAL, "Native rename was not applied")
            window.settings.saveOption == SaveOptionEnum.ALWAYS && project.projectPath != null
        }

    /** Runs on the owner event thread. ASK/no-path never causes Save As or a forced disk write. */
    private fun confirmPersistence(project: JadxProject, context: CallContext): Boolean {
        check(context)
        return try {
            project.save()
            true
        } catch (_: RuntimeException) {
            false // Preserve the successful in-memory edit, but do not claim it reached disk.
        }
    }

    private fun await(future: CompletableFuture<Boolean>, context: CallContext): Boolean {
        // No automatic resubmission on deadline: the native event may already have applied.
        while (true) {
            check(context)
            try { return future.get(25, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { }
            catch (e: ExecutionException) { throw (e.cause ?: e) }
            catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); fail(ErrorCode.CANCELLED, "Native rename wait interrupted")
            }
        }
    }
    private fun resolveClass(d: JadxDecompiler, name: String): JavaClass =
        d.searchJavaClassByOrigFullName(name) ?: d.searchJavaClassByAliasFullName(name)
        ?: fail(ErrorCode.NOT_FOUND, "Class '$name' not found")
    private fun <T> single(values: List<T>, kind: String): T {
        if (values.isEmpty()) fail(ErrorCode.NOT_FOUND, "$kind not found")
        if (values.size > 1) fail(ErrorCode.INVALID_ARGUMENT, "Ambiguous $kind")
        return values.single()
    }
    private fun matches(method: JavaMethod, descriptor: String?): Boolean {
        val info = method.methodNode.methodInfo
        return descriptor == null || descriptor == info.shortId || descriptor == info.shortId.substringAfter(info.name) ||
            descriptor == info.makeSignature(true, true)
    }
    private fun symbol(node: JavaNode): SymbolRef = when (node) {
        is JavaMethod -> SymbolRef(node.declaringClass.rawName, node.methodNode.methodInfo.name,
            node.methodNode.methodInfo.shortId.substringAfter(node.methodNode.methodInfo.name))
        is JavaField -> SymbolRef(node.declaringClass.rawName, node.fieldNode.fieldInfo.name, node.fieldNode.fieldInfo.shortId.substringAfter(':'))
        is JavaClass -> SymbolRef(node.rawName)
        else -> SymbolRef(node.fullName)
    }
}
