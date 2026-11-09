package com.yyang.jadxmcp.worker

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.Channels
import java.util.UUID

/** Fresh session data, never reused across sessions. Only this marked child is owned, not its root.
 * Cleanup runs after child termination, never follows links and checks ownership/ancestor identities
 * before each deletion. Portable Java path operations are not an atomic defense against a hostile
 * same-user process continually swapping filesystem entries between checks and deletes.
 */
class OwnedWorkerCache private constructor(
    val directory: Path,
    private val rootKey: Any,
    private val directoryKey: Any,
    private val marker: String
) {
    private val root = directory.parent
    private val markerPath = directory.resolve(MARKER)

    /** The owner must terminate its worker before calling this; filesystem ownership is checked again per delete. */
    @Synchronized fun clear() {
        checkRoot()
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        // Only the active traversal branch is trusted. Record each directory before descending,
        // then recheck its identity (and every ancestor) before deleting anything underneath it.
        val ancestorKeys = linkedMapOf(directory to directoryKey)
        checkOwnedBranch(directory, ancestorKeys)
        // Default walkFileTree does not FOLLOW_LINKS. The marker is removed last, permitting retry
        // after partial IO failure; the shared root and unrelated pre-existing data remain untouched.
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkOwnedBranch(if (dir == directory) directory else dir.parent, ancestorKeys)
                ancestorKeys[dir] = requireNotNull(attrs.fileKey())
                checkOwnedBranch(dir, ancestorKeys)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file != markerPath) {
                    checkOwnedBranch(file.parent, ancestorKeys)
                    Files.delete(file) // Delete a symlink itself, never its target.
                }
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                checkOwnedBranch(dir, ancestorKeys)
                if (dir == directory) Files.delete(markerPath)
                Files.delete(dir)
                ancestorKeys.remove(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** A matching marker is not enough: a replaced directory could contain a copied marker. */
    private fun checkOwnedBranch(parent: Path, ancestorKeys: Map<Path, Any>) {
        checkRoot()
        for ((path, expectedKey) in ancestorKeys) {
            if (!parent.startsWith(path)) continue
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(attributes.isDirectory && attributes.fileKey() == expectedKey) {
                "Owned cache directory identity changed: $path"
            }
        }
        val actualMarker = Files.newByteChannel(markerPath, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use {
            Channels.newReader(it, Charsets.UTF_8).readText()
        }
        check(actualMarker == marker) { "Owned cache marker changed" }
    }

    private fun checkRoot() {
        check(root.toRealPath() == root && !Files.isSymbolicLink(root)) { "Cache root path changed: $root" }
        val attrs = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attrs.isDirectory && attrs.fileKey() == rootKey) { "Cache root identity changed" }
    }

    companion object {
        const val SUFFIX = ".jadx_mcp_cache"
        private const val MARKER = ".jadx-mcp-owner"

        fun forInput(input: Path): OwnedWorkerCache {
            val canonical = input.toAbsolutePath().normalize().toRealPath()
            require(Files.isRegularFile(canonical) && Files.isReadable(canonical)) { "Input is not a readable file: $canonical" }
            return create(canonical.resolveSibling(canonical.fileName.toString() + SUFFIX))
        }

        /** Internal allocation primitive also used by test sandboxes, not a configurable cache root. */
        internal fun create(root: Path): OwnedWorkerCache {
            val absolute = root.toAbsolutePath().normalize()
            val parent = Files.createDirectories(absolute.parent).toRealPath()
            val canonical = parent.resolve(absolute.fileName)
            try { Files.createDirectory(canonical) } catch (_: FileAlreadyExistsException) { /* Another session may have just created the root. */ }
            require(!Files.isSymbolicLink(canonical) && Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)) { "Cache root must be a real directory: $canonical" }
            require(Files.isWritable(canonical)) { "Cache root is not writable: $canonical" }
            val rootKey = requireNotNull(Files.readAttributes(canonical, BasicFileAttributes::class.java).fileKey())
            val directory = Files.createTempDirectory(canonical, "session-")
            val marker = UUID.randomUUID().toString()
            Files.writeString(directory.resolve(MARKER), marker, StandardOpenOption.CREATE_NEW)
            return OwnedWorkerCache(directory, rootKey,
                requireNotNull(Files.readAttributes(directory, BasicFileAttributes::class.java).fileKey()), marker)
        }

    }
}
