package com.yyang.jadxmcp.project

import com.yyang.jadxmcp.analysis.project.InputIdentity
import com.yyang.jadxmcp.analysis.project.ProjectDataCodec
import com.yyang.jadxmcp.analysis.project.ProjectDataEdits
import com.yyang.jadxmcp.analysis.project.ProjectSymbols
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxDecompiler
import jadx.api.data.impl.JadxCodeData
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Concrete P6 wiring: reuse its canonical codec, addition-only import planner and symbol validation.
 * GUI revisions are opaque content-CAS tokens (including inputs), NOT P6 service history tokens.
 * Therefore ABA with identical canonical contents is accepted. Incoming revision is an origin token,
 * not an attestation: imported refs are validated locally and collisions fail via mergeAdditions.
 */
class P6GuiProjectCodec(private val workspace: WorkspaceAccess<JadxDecompiler>) :
    NativeGuiCodeDataCodec, GuiProjectSnapshotCodec {
    override fun fromNative(data: JadxCodeData): CodeData = ProjectDataCodec.fromNative(data)
    override fun toNative(data: CodeData): JadxCodeData = ProjectDataCodec.toNative(data)
    override fun build(inputs: List<InputFingerprint>, data: CodeData): ProjectDataSnapshot {
        val canonicalInputs = InputIdentity.canonical(inputs)
        val canonicalData = ProjectDataCodec.canonical(data)
        // Length-prefixed canonical fields prevent delimiter collisions; no project JSON or paths.
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String) { val utf8 = value.toByteArray(Charsets.UTF_8); out.writeInt(utf8.size); out.write(utf8) }
            fun attachment(node: NodeReference, code: CodeReference?) {
                text(node.type.name); text(node.declaringClass); text(node.shortId)
                out.writeBoolean(code != null)
                if (code != null) { text(code.attachType.name); out.writeInt(code.index) }
            }
            out.writeInt(canonicalInputs.size)
            canonicalInputs.forEach { text(it.sha256); out.writeLong(it.sizeBytes) }
            out.writeInt(canonicalData.comments.size)
            canonicalData.comments.forEach { attachment(it.nodeRef, it.codeRef); text(it.comment); text(it.style.name) }
            out.writeInt(canonicalData.renames.size)
            canonicalData.renames.forEach { attachment(it.nodeRef, it.codeRef); text(it.newName) }
        }
        val revision = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
        return ProjectDataSnapshot(canonicalInputs, "gui:$revision", canonicalData)
    }

    override fun validateImport(incoming: ProjectDataSnapshot, current: ProjectDataSnapshot,
                                expectedRevision: String, context: CallContext): CodeData {
        incoming.validate(); current.validate()
        if (current.revision != expectedRevision || InputIdentity.canonical(incoming.inputs) != current.inputs)
            throw ProtocolException(ProtocolError(ErrorCode.CONFLICT, "GUI project CAS/input mismatch"))
        return validateReplacement(ProjectDataEdits.mergeAdditions(current.data, incoming.data), context)
    }

    override fun validateReplacement(data: CodeData, context: CallContext): CodeData {
        val canonical = ProjectDataCodec.canonical(data)
        workspace.withDecompiler(context) { ProjectSymbols(it).validate(canonical, context) }
        return canonical
    }
}
