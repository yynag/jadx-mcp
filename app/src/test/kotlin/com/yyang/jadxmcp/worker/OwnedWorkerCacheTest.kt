package com.yyang.jadxmcp.worker

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

/** Real filesystem boundaries: canonical input naming, no shared session writes, fail-closed deletion. */
class OwnedWorkerCacheTest {
    private fun sandbox() = OwnedWorkerCache.create(Path.of("build", "cache-layout-tests"))

    @Test fun `full input name and dot suffix work for relative normalized unicode and space paths`() {
        val sandbox = sandbox()
        try {
            val parent = Files.createDirectories(sandbox.directory.resolve("中文 空格"))
            for (name in listOf("sample.apk", "sample.jar", "classes.dex", "source.class", "archive.zip")) {
                val input = Files.writeString(parent.resolve(name), "input")
                val relative = Path.of("").toAbsolutePath().relativize(input).parent.resolve(".").resolve(name)
                val cache = OwnedWorkerCache.forInput(relative)
                assertEquals(input.resolveSibling(name + ".jadx_mcp_cache"), cache.directory.parent)
                assertTrue(cache.directory.fileName.toString().startsWith("session-"))
                cache.clear()
                assertEquals("input", Files.readString(input))
            }
        } finally { sandbox.clear() }
    }

    @Test fun `old suffix cache is neither reused migrated nor deleted`() {
        val sandbox = sandbox()
        try {
            val input = Files.writeString(sandbox.directory.resolve("demo.apk"), "APK")
            val oldRoot = Files.createDirectory(input.resolveSibling("demo.apk_jadx_cache"))
            val oldData = Files.writeString(oldRoot.resolve("generated-aliases.jobf"), "old cache")
            val cache = OwnedWorkerCache.forInput(input)
            assertEquals(input.resolveSibling("demo.apk.jadx_mcp_cache"), cache.directory.parent)
            assertFalse(Files.exists(cache.directory.resolve("generated-aliases.jobf")))
            assertEquals("old cache", Files.readString(oldData))
            cache.clear()
            assertEquals("old cache", Files.readString(oldData))
            assertEquals("APK", Files.readString(input))
        } finally { sandbox.clear() }
    }

    @Test fun `same input concurrent sessions are exclusive and cleanup preserves others input and native project`() {
        val sandbox = sandbox()
        try {
            val input = Files.writeString(sandbox.directory.resolve("sample.apk"), "APK")
            val native = Files.writeString(input.resolveSibling("sample.apk.jadx"), "native")
            val caches = Executors.newFixedThreadPool(4).use { executor ->
                executor.invokeAll((1..8).map { Callable { OwnedWorkerCache.forInput(input) } }).map { it.get() }
            }
            assertEquals(8, caches.map { it.directory }.toSet().size)
            caches.forEachIndexed { i, cache -> Files.writeString(cache.directory.resolve("generated-aliases.jobf"), "$i") }
            caches.first().clear()
            caches.first().clear()
            caches.drop(1).forEachIndexed { i, cache ->
                assertEquals("${i + 1}", Files.readString(cache.directory.resolve("generated-aliases.jobf")))
            }
            val fresh = OwnedWorkerCache.forInput(input)
            assertFalse(Files.exists(fresh.directory.resolve("generated-aliases.jobf")), "New sessions do not reuse aliases")
            assertEquals("APK", Files.readString(input))
            assertEquals("native", Files.readString(native))
            caches.drop(1).forEach { it.clear() }
            fresh.clear()
        } finally { sandbox.clear() }
    }

    @Test fun `root symlink or file and unwritable input parent fail with no fallback`() {
        val sandbox = sandbox()
        try {
            val input = Files.writeString(sandbox.directory.resolve("sample.jar"), "input")
            val root = input.resolveSibling("sample.jar.jadx_mcp_cache")
            val outside = Files.createDirectory(sandbox.directory.resolve("user-data"))
            val sentinel = Files.writeString(outside.resolve("keep"), "user")
            Files.createSymbolicLink(root, outside)
            assertFailsWith<IllegalArgumentException> { OwnedWorkerCache.forInput(input) }
            Files.delete(root)
            Files.writeString(root, "not a directory")
            assertFailsWith<IllegalArgumentException> { OwnedWorkerCache.forInput(input) }
            Files.delete(root)
            val permissions = Files.getPosixFilePermissions(sandbox.directory)
            try {
                Files.setPosixFilePermissions(sandbox.directory, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
                assertFalse(Files.isWritable(sandbox.directory), "Run this permission boundary test as a non-root user")
                assertFailsWith<java.nio.file.AccessDeniedException> { OwnedWorkerCache.forInput(input) }
                assertFalse(Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            } finally { Files.setPosixFilePermissions(sandbox.directory, permissions) }
            assertEquals("user", Files.readString(sentinel))
        } finally { sandbox.clear() }
    }

    @Test fun `marker tampering and session or root symlink replacement refuse deletion`() {
        val sandbox = sandbox()
        try {
            val input = Files.writeString(sandbox.directory.resolve("sample.apk"), "input")
            val outside = Files.createDirectory(sandbox.directory.resolve("user-data"))
            val sentinel = Files.writeString(outside.resolve("keep"), "user")
            val cache = OwnedWorkerCache.forInput(input)
            val marker = cache.directory.resolve(".jadx-mcp-owner")
            val original = Files.readString(marker)
            Files.writeString(marker, "not this owner")
            assertFailsWith<IllegalStateException> { cache.clear() }
            Files.writeString(marker, original)
            val moved = cache.directory.resolveSibling("moved-session")
            Files.move(cache.directory, moved)
            Files.createSymbolicLink(cache.directory, outside)
            assertFailsWith<IllegalStateException> { cache.clear() }
            Files.delete(cache.directory)
            Files.move(moved, cache.directory)
            val root = cache.directory.parent
            val movedRoot = root.resolveSibling("moved-root")
            Files.move(root, movedRoot)
            Files.createSymbolicLink(root, outside)
            assertFailsWith<IllegalStateException> { cache.clear() }
            Files.delete(root)
            Files.move(movedRoot, root)
            Files.createSymbolicLink(cache.directory.resolve("nested-link"), outside)
            cache.clear()
            assertEquals("user", Files.readString(sentinel))
            assertEquals("input", Files.readString(input))
        } finally { sandbox.clear() }
    }

    @Test fun `input symlink resolves canonical target and deletion of input has no automatic cache effect`() {
        val sandbox = sandbox()
        try {
            val target = Files.writeString(sandbox.directory.resolve("target.jar"), "input")
            val link = Files.createSymbolicLink(sandbox.directory.resolve("link.jar"), target)
            val cache = OwnedWorkerCache.forInput(link)
            assertEquals(target.resolveSibling("target.jar.jadx_mcp_cache"), cache.directory.parent)
            Files.delete(target)
            assertTrue(Files.isDirectory(cache.directory), "There is no input deletion watcher")
            cache.clear()
        } finally { sandbox.clear() }
    }
}
