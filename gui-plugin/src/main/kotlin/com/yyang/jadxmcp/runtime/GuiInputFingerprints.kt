package com.yyang.jadxmcp.runtime

import com.yyang.jadxmcp.analysis.project.InputIdentity
import com.yyang.jadxmcp.protocol.*
import com.yyang.jadxmcp.ui.requireGuiWorker
import java.io.File

fun interface GuiInputFingerprinter {
    fun fingerprint(files: List<File>, context: CallContext): List<InputFingerprint>
}

/** Input hashing checks cancellation/deadline at each 64KiB block. Regular-file I/O is cooperatively bounded,
 * not claimed interruptible in the middle of an OS read. No hashing or background waiting on EDT. */
class BoundedGuiFingerprints(
    private val timeoutMillis: Long = 10_000,
    private val fingerprinter: GuiInputFingerprinter = GuiInputFingerprinter(InputIdentity::fingerprint),
    private val now: () -> Long = System::currentTimeMillis
) {
    init { require(timeoutMillis in 1..300_000) }
    fun read(files: List<File>, context: CallContext): List<InputFingerprint> {
        requireGuiWorker(); context.checkActive(now())
        val start = now()
        val deadline = if (start > Long.MAX_VALUE - timeoutMillis) Long.MAX_VALUE else start + timeoutMillis
        val limited = context.copy(deadlineEpochMillis = minOf(context.deadlineEpochMillis, deadline))
        limited.checkActive(now())
        val result = fingerprinter.fingerprint(files.toList(), limited)
        limited.checkActive(now())
        return InputIdentity.canonical(result)
    }
}
