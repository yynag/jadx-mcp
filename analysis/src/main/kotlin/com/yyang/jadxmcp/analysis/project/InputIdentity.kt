package com.yyang.jadxmcp.analysis.project

import com.yyang.jadxmcp.protocol.*
import java.io.File
import java.security.MessageDigest

/** Sorted multiset of content hashes; filenames, aliases and input ordering are not identity. */
object InputIdentity {
    fun fingerprint(files: List<File>, context: CallContext): List<InputFingerprint> {
        if (files.isEmpty()) projectError(ErrorCode.INVALID_ARGUMENT, "No project input files")
        val values = files.map { file ->
            context.checkActive()
            if (!file.isFile) projectError(ErrorCode.INVALID_ARGUMENT, "Input must be a regular file: $file")
            val digest = MessageDigest.getInstance("SHA-256")
            val initialSize = file.length()
            val initialModified = file.lastModified()
            var size = 0L
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    context.checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    size += count
                }
            }
            if (file.length() != initialSize || file.lastModified() != initialModified || size != initialSize)
                projectError(ErrorCode.CONFLICT, "Input changed while hashing: $file")
            InputFingerprint(digest.digest().joinToString("") { "%02x".format(it) }, size)
        }
        context.checkActive()
        return canonical(values)
    }

    fun canonical(inputs: List<InputFingerprint>): List<InputFingerprint> {
        ProjectDataSnapshot(inputs, "validation").validate()
        return java.util.Collections.unmodifiableList(inputs.sortedWith(compareBy({ it.sha256 }, { it.sizeBytes })))
    }
}
