package com.yyang.jadxmcp.project

import com.yyang.jadxmcp.protocol.CallContext
import com.yyang.jadxmcp.ui.requireGuiWorker
import jadx.api.JadxDecompiler
import jadx.api.data.ICodeData
import jadx.core.dex.visitors.rename.UserRenames

/**
 * Native binding glue, called under the borrowed workspace lease, never EDT.
 * No rename rollback/planning algorithm: imports add entries, local replacements edit comments;
 * native rename/reset events own existing symbol alias changes. Keep the GUI-owned data instance!
 * Evidence: RenameService.updateCodeRenames mutates it, JadxWrapper.reloadCodeData only notifies.
 */
object NativeGuiCodeDataBinding {
    fun apply(decompiler: JadxDecompiler, data: ICodeData?, context: CallContext) {
        requireGuiWorker(); context.checkActive()
        decompiler.args.codeData = data
        UserRenames.apply(decompiler.root)
        decompiler.root.runPackagesUpdate()
        decompiler.reloadCodeData()
        decompiler.classes.forEach { context.checkActive(); it.unload() }
    }
}
