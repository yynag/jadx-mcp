package com.yyang.jadxmcp.debug

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Inspect actual cached 1.5.6 bytecode without initializing GUI classes or requiring a display.
 * This verifies structural compatibility, NOT a real attach/pause/VM read. An APK cannot prove that.
 * No dependency download, GUI startup, device access, or Gradle build modification is needed.
 */
class Jadx156DebuggerCompatibilityTest {
    @Test fun cached156BytecodeMatchesEveryVersionSensitiveFieldAndGetter() {
        val cache = Path.of(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/io.github.skylot/jadx-gui/1.5.6")
        val jar = Files.walk(cache).use { paths ->
            paths.filter { it.fileName.toString() == "jadx-gui-1.5.6.jar" }.findFirst().orElseThrow {
                IllegalStateException("Cached JADX GUI 1.5.6 jar is required; do not download for this test")
            }
        }
        JarFile(jar.toFile()).use { archive ->
            fun members(name: String): Symbols {
                val entry = archive.getJarEntry(name.replace('.', '/') + ".class")
                assertNotNull(entry, "Missing 1.5.6 class $name")
                return DataInputStream(archive.getInputStream(entry)).use(::readSymbols)
            }
            val panel = members("jadx.gui.ui.panel.JDebuggerPanel")
            mapOf(
                "controller" to "Ljadx/gui/ui/panel/IDebugController;",
                "threadBox" to "Ljavax/swing/JComboBox;", "stackFrameList" to "Ljavax/swing/JList;",
                "regTreeNode" to "Ljavax/swing/tree/DefaultMutableTreeNode;",
                "thisTreeNode" to "Ljavax/swing/tree/DefaultMutableTreeNode;"
            ).forEach { (name, descriptor) -> assertEquals(descriptor, panel.fields[name], name) }
            val controller = members("jadx.gui.ui.panel.IDebugController")
            listOf("isDebugging", "isSuspended").forEach { assertEquals("()Z", controller.methods[it]) }
            val thread = members("jadx.gui.device.debugger.DebugController\$ThreadBoxElement")
            assertEquals("J", thread.fields["threadID"])
            assertEquals("Ljava/lang/String;", thread.fields["name"])
            assertEquals("()J", thread.methods["getThreadID"])
            members("jadx.gui.device.debugger.DebugController\$FrameNode")
            val node = members("jadx.gui.ui.panel.JDebuggerPanel\$ValueTreeNode")
            listOf("getName", "getValue", "getType").forEach { assertEquals("()Ljava/lang/String;", node.methods[it]) }
            assertEquals("()J", node.methods["getTypeID"])
            assertEquals("()Z", node.methods["isUpdated"])
            assertEquals("()Ljadx/gui/ui/panel/JDebuggerPanel;", members("jadx.gui.ui.MainWindow").methods["getDebuggerPanel"])
        }
    }

    private data class Symbols(val fields: Map<String, String>, val methods: Map<String, String>)

    /** Minimal classfile reader: descriptors suffice; do not load/initialize Swing/JADX GUI. */
    private fun readSymbols(input: DataInputStream): Symbols {
        assertEquals(0xCAFEBABE.toInt(), input.readInt())
        input.readUnsignedShort(); input.readUnsignedShort()
        val strings = arrayOfNulls<String>(input.readUnsignedShort())
        var i = 1
        while (i < strings.size) {
            when (val tag = input.readUnsignedByte()) {
                1 -> strings[i] = input.readUTF()
                3, 4 -> input.skipNBytes(4)
                5, 6 -> { input.skipNBytes(8); i++ }
                7, 8, 16, 19, 20 -> input.skipNBytes(2)
                9, 10, 11, 12, 17, 18 -> input.skipNBytes(4)
                15 -> input.skipNBytes(3)
                else -> error("Unexpected classfile constant tag $tag")
            }
            i++
        }
        input.skipNBytes(6)
        repeat(input.readUnsignedShort()) { input.skipNBytes(2) }
        fun readMembers(): Map<String, String> = buildMap {
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort()
                val name = strings[input.readUnsignedShort()]!!
                val descriptor = strings[input.readUnsignedShort()]!!
                put(name, descriptor)
                repeat(input.readUnsignedShort()) {
                    input.readUnsignedShort()
                    input.skipNBytes(input.readInt().toLong() and 0xffffffffL)
                }
            }
        }
        return Symbols(readMembers(), readMembers())
    }
}
