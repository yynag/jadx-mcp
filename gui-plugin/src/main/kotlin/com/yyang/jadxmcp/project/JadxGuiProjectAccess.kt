package com.yyang.jadxmcp.project

import com.yyang.jadxmcp.protocol.*
import jadx.api.data.impl.JadxCodeData
import jadx.gui.settings.data.SaveOptionEnum
import jadx.gui.ui.MainWindow
import javax.swing.SwingUtilities

/** Native code-data conversion shared by the GUI owner and its snapshot codec. */
interface NativeGuiCodeDataCodec {
    fun fromNative(data: JadxCodeData): CodeData
    fun toNative(data: CodeData): JadxCodeData
}

/**
 * Public native setter is authoritative. Evidence: JadxProject.setCodeData 174-177, changed 291-300,
 * save 324-335. ALWAYS may synchronously write inside setCodeData (upstream behavior); ASK does not.
 * A save failure may occur AFTER the in-memory replacement: never infer persistence from stale isSaved.
 */
class JadxGuiProjectAccess(
    private val window: MainWindow,
    private val codec: NativeGuiCodeDataCodec,
    private val persistenceFailure: (Throwable) -> Unit = {}
) : GuiProjectAccess {
    override fun read(): GuiProjectView {
        check(SwingUtilities.isEventDispatchThread())
        val project = window.project
        val policy = when (window.settings.saveOption) {
            SaveOptionEnum.ALWAYS -> NativeSavePolicy.ALWAYS
            SaveOptionEnum.ASK -> NativeSavePolicy.ASK
            else -> NativeSavePolicy.NEVER
        }
        return GuiProjectView(project, codec.fromNative(project.codeData ?: JadxCodeData()), project.projectPath?.toString(), policy)
    }

    override fun replace(expectedIdentity: Any, data: CodeData): NativeApplyResult {
        check(SwingUtilities.isEventDispatchThread())
        val project = window.project
        if (project !== expectedIdentity) throw ProtocolException(ProtocolError(ErrorCode.CONFLICT, "GUI project owner changed"))
        // Validation/conversion errors are not save failures and must retain their protocol code.
        val nativeData = codec.toNative(data)
        var nativeSaveSucceeded = true
        try { project.codeData = nativeData }
        catch (e: RuntimeException) {
            nativeSaveSucceeded = false
            persistenceFailure(e)
        }
        val applied = codec.fromNative(project.codeData ?: JadxCodeData()) == data
        return NativeApplyResult(applied, applied && nativeSaveSucceeded &&
            window.settings.saveOption == SaveOptionEnum.ALWAYS && project.projectPath != null && project.isSaved)
    }
}
