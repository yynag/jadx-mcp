package com.yyang.jadxmcp.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GuiProjectNameTest {
    @Test fun singleInputKeepsCompleteFilenameWithoutParentDirectory() {
        listOf("app.apk", "app.apkm", "app.xapk", "classes.dex", "library.debug.jar", "应用.apk").forEach { name ->
            val input = Path.of("private", "inputs", name)
            assertEquals(name, guiProjectName(listOf(input)))
            assertEquals(name, guiProjectName(listOf(input.toAbsolutePath())))
        }
    }

    @Test fun multipleInputsPreferBaseApkAndReportExtraFileCount() {
        assertEquals("base.apk (+2 files)", guiProjectName(listOf(
            Path.of("private/split_config.en.apk"), Path.of("private/base.apk"), Path.of("private/split_config.arm64.apk"))))
        assertEquals("first.dex (+1 file)", guiProjectName(listOf(Path.of("private/first.dex"), Path.of("private/second.dex"))))
    }

    @Test fun emptyOrNamelessInputsUseNonPathFallback() {
        assertEquals("jadx-gui", guiProjectName(emptyList()))
        assertEquals("jadx-gui", guiProjectName(listOf(Path.of(""))))
        assertEquals("jadx-gui", guiProjectName(listOf(Path.of("/"))))
    }
}
