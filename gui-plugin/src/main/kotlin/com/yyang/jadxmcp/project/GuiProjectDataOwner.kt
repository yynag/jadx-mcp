package com.yyang.jadxmcp.project

import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.ui.requireGuiWorker
import com.yyang.jadxmcp.ui.awaitGuiFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

enum class NativeSavePolicy { ASK, ALWAYS, NEVER }
data class GuiProjectView(
    val identity: Any, val data: CodeData, val path: String?, val policy: NativeSavePolicy
)
data class NativeApplyResult(val applied: Boolean, val persisted: Boolean)

/** Methods run on EDT. A host implementation must keep the current GUI project as sole owner. */
interface GuiProjectAccess {
    fun read(): GuiProjectView
    fun replace(expectedIdentity: Any, data: CodeData): NativeApplyResult
}

/**
 * P6 seam: adapt its canonical codec/revision/input-validation/CAS functions here, not JSON files.
 * build and validate run off EDT; validate must check expectedRevision, exact input multiset,
 * incoming canonical data/reference validity. Origin revisions remain opaque. The owner also fences CAS at commit.
 */
interface GuiProjectSnapshotCodec {
    fun build(inputs: List<InputFingerprint>, data: CodeData): ProjectDataSnapshot
    fun validateImport(incoming: ProjectDataSnapshot, current: ProjectDataSnapshot, expectedRevision: String, context: CallContext): CodeData
    fun validateReplacement(data: CodeData, context: CallContext): CodeData
}

/**
 * Explicit single-owner snapshot/import primitives only. No synchronization registration, sidecars,
 * SaveAs, external <apk>.jadx writes, or borrowed decompiler lifetime management.
 * Input hashes must be supplied/cached off EDT by the host/P6; native inputsHash is NOT SHA-256.
 */
class GuiProjectDataOwner(
    private val access: GuiProjectAccess,
    private val codec: GuiProjectSnapshotCodec,
    private val inputs: (CallContext) -> List<InputFingerprint>,
    private val ui: UiExecutor,
    private val epoch: () -> Long,
    private val invalidated: (CallContext) -> Unit
) : ProjectDataPort {
    private fun check(context: CallContext) { context.checkActive(); context.requireEpoch(epoch()) }
    private fun read(context: CallContext) = ui.readOrEdit(context) { check(context); access.read() }

    override fun snapshot(context: CallContext): ProjectDataSnapshot {
        requireGuiWorker(); check(context)
        val identities = inputs(context).toList()
        val view = read(context)
        val snapshot = codec.build(identities, view.data).also { it.validate() }
        // Do not publish a project that changed during hashing/canonicalization.
        ui.readOrEdit(context) {
            check(context)
            val now = access.read()
            if (now.identity !== view.identity || now.data != view.data) conflict("GUI project changed during snapshot")
        }
        check(context)
        return snapshot
    }

    override fun importData(snapshot: ProjectDataSnapshot, expectedRevision: String, context: CallContext): MutationState =
        commit(snapshot, expectedRevision, context, replace = false)

    /** Explicit local comment editing primitive. Symbol/variable renames stay with native events. */
    fun replaceData(data: CodeData, expectedRevision: String, context: CallContext): MutationState {
        requireGuiWorker(); check(context)
        val incoming = codec.build(inputs(context), data)
        return commit(incoming, expectedRevision, context, replace = true)
    }

    private fun commit(snapshot: ProjectDataSnapshot, expectedRevision: String, context: CallContext, replace: Boolean): MutationState {
        requireGuiWorker(); check(context); snapshot.validate()
        val identities = inputs(context).toList()
        val before = read(context)
        val current = codec.build(identities, before.data).also { it.validate() }
        if (expectedRevision != current.revision) conflict("GUI project revision changed")
        if (snapshot.inputs.sortedWith(inputOrder) != current.inputs.sortedWith(inputOrder)) conflict("Project inputs differ")
        if (replace && snapshot.data.renames != before.data.renames) {
            throw ProtocolException(ProtocolError(ErrorCode.UNSUPPORTED, "Route rename replacement/removal through native GUI events"))
        }
        val replacement = if (replace) codec.validateReplacement(snapshot.data, context)
            else codec.validateImport(snapshot, current, expectedRevision, context)
        check(context)
        if (replacement == before.data) return MutationState(false, false, current.revision, before.path)
        val revision = codec.build(identities, replacement).revision
        val repair = AtomicReference<CompletableFuture<Unit>>()
        val applied = ui.readOrEdit(context) {
            check(context)
            val now = access.read()
            if (now.identity !== before.identity || now.data != before.data) conflict("GUI project changed before import")
            access.replace(before.identity, replacement).also {
                // Queue before leaving EDT, so a slow ALWAYS save / late caller timeout cannot
                // skip generation invalidation. Host repairs ignore request cancellation, not epoch.
                if (it.applied) repair.set(CompletableFuture.supplyAsync { invalidated(context) })
            }
        }
        repair.get()?.let { awaitGuiFuture(it, context, epoch) }
        check(context)
        return MutationState(applied.applied, applied.persisted, if (applied.applied) revision else current.revision, before.path)
    }

    private fun conflict(message: String): Nothing = throw ProtocolException(ProtocolError(ErrorCode.CONFLICT, message))
    private companion object {
        val inputOrder = compareBy<InputFingerprint> { it.sha256 }.thenBy { it.sizeBytes }
    }
}
