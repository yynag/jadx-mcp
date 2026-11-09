package com.yyang.jadxmcp.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/** Real cached GUI bytecode, not fake reflection classes; no GUI initialization or downloads. */
class Gui156CompatibilityTest {
    @Test fun nativeOwnerEditorRenameAndBackgroundRefreshDescriptorsMatch156() {
        val cache = Path.of(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/io.github.skylot/jadx-gui/1.5.6")
        val jar = Files.walk(cache).use { paths -> paths.filter { it.fileName.toString() == "jadx-gui-1.5.6.jar" }.findFirst().orElseThrow() }
        JarFile(jar.toFile()).use { archive ->
            fun methods(name: String): Set<Pair<String, String>> =
                DataInputStream(archive.getInputStream(archive.getJarEntry(name.replace('.', '/') + ".class"))).use(::readMethods)
            fun has(type: String, name: String, descriptor: String) =
                assertTrue(methods(type).contains(name to descriptor), "$type.$name$descriptor")
            val owner = "jadx.gui.settings.JadxProject"
            has(owner, "getProjectPath", "()Ljava/nio/file/Path;")
            has(owner, "getCodeData", "()Ljadx/api/data/impl/JadxCodeData;")
            has(owner, "setCodeData", "(Ljadx/api/data/impl/JadxCodeData;)V")
            has(owner, "save", "()V"); has(owner, "isSaved", "()Z")
            val rename = "jadx.gui.treemodel.JRenameNode"
            has(rename, "replace", "()Ljadx/gui/treemodel/JRenameNode;")
            has(rename, "buildCodeRename", "(Ljava/lang/String;Ljava/util/Set;)Ljadx/api/data/ICodeRename;")
            has(rename, "isValidName", "(Ljava/lang/String;)Z")
            has("jadx.gui.treemodel.JVariable", "buildCodeRename", "(Ljava/lang/String;Ljava/util/Set;)Ljadx/api/data/ICodeRename;")
            has("jadx.gui.utils.pkgs.JRenamePackage", "<init>", "(Ljadx/api/JavaPackage;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
            has("jadx.gui.ui.codearea.ClassCodeContentPanel", "getCurrentCodeArea", "()Ljadx/gui/ui/codearea/AbstractCodeArea;")
            has("jadx.gui.ui.codearea.CodeArea", "backgroundRefreshClass", "()V")
            has("jadx.gui.events.types.JadxGuiEventsImpl", "global", "()Ljadx/api/plugins/events/IJadxEvents;")
            has("jadx.gui.ui.MainWindow", "rebuildPackagesTree", "()V")
            has("jadx.gui.ui.MainWindow", "reloadTreePreservingState", "()V")
            assertNotNull(archive.getJarEntry("jadx/gui/events/services/RenameService.class"))
        }
    }

    private fun readMethods(input: DataInputStream): Set<Pair<String, String>> {
        assertEquals(0xCAFEBABE.toInt(), input.readInt())
        input.readUnsignedShort(); input.readUnsignedShort()
        val strings = arrayOfNulls<String>(input.readUnsignedShort())
        var index = 1
        while (index < strings.size) {
            when (val tag = input.readUnsignedByte()) {
                1 -> strings[index] = input.readUTF()
                3, 4 -> input.skipNBytes(4)
                5, 6 -> { input.skipNBytes(8); index++ }
                7, 8, 16, 19, 20 -> input.skipNBytes(2)
                9, 10, 11, 12, 17, 18 -> input.skipNBytes(4)
                15 -> input.skipNBytes(3)
                else -> error("Unexpected constant-pool tag $tag")
            }
            index++
        }
        input.skipNBytes(6)
        repeat(input.readUnsignedShort()) { input.skipNBytes(2) }
        fun members(): Set<Pair<String, String>> = buildSet {
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort()
                add(strings[input.readUnsignedShort()]!! to strings[input.readUnsignedShort()]!!)
                repeat(input.readUnsignedShort()) {
                    input.readUnsignedShort(); input.skipNBytes(input.readInt().toLong() and 0xffffffffL)
                }
            }
        }
        members() // fields
        return members()
    }
}
