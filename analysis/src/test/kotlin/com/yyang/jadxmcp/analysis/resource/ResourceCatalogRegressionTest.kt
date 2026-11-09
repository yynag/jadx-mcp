package com.yyang.jadxmcp.analysis.resource

import com.yyang.jadxmcp.analysis.metadata.ManifestModel
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.ResourceFile as JadxResourceFile
import jadx.api.ResourceType
import jadx.api.impl.SimpleCodeInfo
import jadx.core.xmlgen.ResContainer
import jadx.core.xmlgen.entry.RawValue
import jadx.core.xmlgen.entry.ResourceEntry
import jadx.zip.IZipEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** In-memory native entries/containers and archive handles; not a binary-XML/ARSC APK integration test. */
class ResourceCatalogRegressionTest {
    private val context get() = CallContext("resource-regression", 0, System.currentTimeMillis() + 60_000)

    private class Access(val decompiler: JadxDecompiler) : WorkspaceAccess<JadxDecompiler> {
        override val ownership = WorkspaceOwnership.BORROWED
        override val projectEpoch = 0L
        override fun <T> withDecompiler(context: CallContext, action: (JadxDecompiler) -> T): T {
            context.checkActive()
            context.requireEpoch(projectEpoch)
            return action(decompiler)
        }
        override fun close() = Unit
    }

    private fun loaded(): JadxDecompiler = JadxDecompiler(JadxArgs().apply {
        inputFiles = mutableListOf(File("../app/src/test/resources/sample.jar").takeIf { it.isFile }
            ?: File("app/src/test/resources/sample.jar"))
        isDeobfuscationOn = false
    }).also { it.load(); it.resources.clear() }

    private class ZipEntry(
        private val archive: File, private val path: String, private val size: Long = 0,
        private val stream: () -> InputStream = { ByteArrayInputStream(byteArrayOf()) }
    ) : IZipEntry {
        override fun getName(): String = path
        override fun getBytes(): ByteArray = error("Unbounded getBytes must not be used")
        override fun getInputStream(): InputStream = stream()
        override fun getCompressedSize(): Long = size
        override fun getUncompressedSize(): Long = size
        override fun isDirectory(): Boolean = false
        override fun getZipFile(): File = archive
        override fun preferBytes(): Boolean = false
    }

    private fun add(
        decompiler: JadxDecompiler, archive: String, name: String, type: ResourceType,
        container: ResContainer, zip: IZipEntry = ZipEntry(File(archive), name)
    ): JadxResourceFile = object : JadxResourceFile(decompiler, name, type) {
        override fun getZipEntry(): IZipEntry = zip
        override fun loadContent(): ResContainer = container
    }.also { decompiler.resources.add(it) }

    private fun entry(id: Int, name: String, data: Int, type: Int = 0x12, config: String = "") =
        ResourceEntry(id, "example.app", "bool", name, config).apply { simpleValue = RawValue(type, data) }

    private fun manifest(appEnabled: String = "true", enabled: String = "true", exported: String = "true") = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.app">
        <application android:enabled="$appEnabled"><activity android:name=".Target" android:enabled="false"/>
        <activity-alias android:name=".Launcher" android:targetActivity=".Target" android:enabled="$enabled" android:exported="$exported">
        <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
        </activity-alias></application></manifest>
    """.trimIndent()

    @Test fun nativeBooleanReferencesAndNumericIdsNeverDefaultUnknownToTrue() {
        val resolver = NativeBooleanResolver { listOf(entry(0x7f010001, "off", 0),
            entry(0x7f010002, "forward", 0x7f010001, 0x01), entry(0x7f010003, "on", 1)) }
        for (reference in listOf("@bool/off", "@example.app:bool/off", "@0x7f010001", "@2130771969", "@bool/forward")) {
            assertFalse(resolver.resolve(reference))
            val model = ManifestModel.parse(manifest(enabled = reference), resolver::resolve)
            assertFalse(model.components.single { it.aliasName != null }.enabled)
            assertNull(model.mainActivity)
            val exported = ManifestModel.parse(manifest(exported = reference), resolver::resolve)
            assertFalse(exported.components.single { it.aliasName != null }.exported)
            assertNull(exported.mainActivity)
        }
        val disabledApp = ManifestModel.parse(manifest(appEnabled = "@bool/off", enabled = "@bool/on"), resolver::resolve)
        assertTrue(disabledApp.components.none { it.enabled })
        assertNull(disabledApp.mainActivity)
        // An alias has its own flags, while application enabled=false always gates it.
        assertEquals("example.app.Target", ManifestModel.parse(manifest(enabled = "@bool/on"), resolver::resolve).mainActivity)
        assertNull(ManifestModel.parse(manifest(exported = "0"), resolver::resolve).mainActivity)
        assertEquals(ErrorCode.UNSUPPORTED, assertThrows(ProtocolException::class.java) {
            ManifestModel.parse(manifest(enabled = "@bool/missing"), resolver::resolve)
        }.error.code)
    }

    @Test fun nativeBooleanCyclesMissingAndConfigurationConflictsAreExplicit() {
        val entries = listOf(entry(1, "cycleA", 2, 0x01), entry(2, "cycleB", 1, 0x01),
            entry(3, "configured", 0), entry(3, "configured", 1, config = "-night"),
            entry(4, "stable", 0), entry(4, "stable", 0, config = "-night"),
            entry(5, "nightOnly", 1, config = "-night"))
        val resolver = NativeBooleanResolver { entries }
        assertFalse(resolver.resolve("@bool/stable"))
        for (ref in listOf("@bool/cycleA", "@bool/configured", "@bool/unknown", "@0xffffffff", "@bool/nightOnly")) {
            assertEquals(ErrorCode.UNSUPPORTED, assertThrows(ProtocolException::class.java) {
                ManifestModel.parse(manifest(exported = ref), resolver::resolve)
            }.error.code)
        }
    }

    @Test fun splitResourceIdentitiesRoundTripAndStringsAggregateWithoutDroppingArchives() {
        loaded().use { decompiler ->
            for ((archive, value) in listOf("base.apk" to "base", "feature.apk" to "feature")) {
                val xml = "<resources><string name=\"shared\">$value</string></resources>"
                add(decompiler, archive, "resources.arsc", ResourceType.ARSC,
                    ResContainer.resourceTable("resources.arsc", listOf(ResContainer.textResource(
                        "res/values/strings.xml", SimpleCodeInfo(xml))), SimpleCodeInfo("table-$value")))
            }
            val service = ResourceService(Access(decompiler))
            val files = service.resource(ResourceRequest("s"), context).files
            assertEquals(4, files.size)
            assertEquals(4, files.toSet().size)
            assertTrue(files.all { it.startsWith("archive:file:") })
            val contents = files.map { id ->
                val file = service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = id), context).file!!
                assertEquals(id, file.name)
                file.text!!.content
            }
            assertTrue(contents.containsAll(listOf("table-base", "table-feature")))
            decompiler.resources.reverse()
            assertEquals(files, service.resource(ResourceRequest("s"), context).files)
            assertEquals(files.take(2), service.resource(ResourceRequest("s", page = PageRequest(0, 2)), context).files)
            for (ambiguous in listOf("resources.arsc", "res/values/strings.xml")) {
                assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                    service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = ambiguous), context)
                }.error.code)
            }
            val strings = service.resource(ResourceRequest("s", ResourceAction.STRINGS), context).strings
            assertEquals(setOf("base", "feature"), strings.map { it.value }.toSet())
            assertEquals(2, strings.map { it.name }.toSet().size)
            assertTrue(strings.all { it.name.startsWith("archive:") })
            add(decompiler, "feature.apk", "assets/unique.txt", ResourceType.UNKNOWN,
                ResContainer.textResource("assets/unique.txt", SimpleCodeInfo("unique")))
            val uniqueId = service.resource(ResourceRequest("s"), context).files.single { it.contains("assets/unique.txt") }
            assertTrue(uniqueId.startsWith("archive:"))
            assertEquals("unique", service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = uniqueId), context).file!!.text!!.content)
            decompiler.resources.removeAt(decompiler.resources.lastIndex)
            // A unique path identifies the resource directly when there is only one archive.
            decompiler.resources.removeAt(0)
            assertTrue(service.resource(ResourceRequest("s"), context).files.contains("res/values/strings.xml"))
            assertNotNull(service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = "res/values/strings.xml"), context).file)
        }
    }

    @Test fun allRealTextContainersEnforceExactPresentationBoundaryAndUtf8Size() {
        for (length in listOf(199_999, 200_000, 200_001, 250_001)) {
            val value = "é".repeat(length)
            for (container in listOf(ResContainer.textResource("decoded.xml", SimpleCodeInfo(value)),
                ResContainer.resourceTable("resources.arsc", emptyList(), SimpleCodeInfo(value)))) {
                val file = ResourceCatalog.describe("decoded.xml", container)
                assertEquals(length.coerceAtMost(200_000), file.text!!.content.length)
                assertEquals(length, file.text!!.totalChars)
                assertEquals(length > 200_000, file.text!!.truncated)
                assertEquals(length * 2L, file.sizeBytes)
            }
        }
        val special = "aé中\uD83D\uDE00\uD800"
        assertEquals(special.toByteArray(Charsets.UTF_8).size.toLong(),
            ResourceCatalog.describe("special.xml", ResContainer.textResource("special.xml", SimpleCodeInfo(special))).sizeBytes)
        val binary = ResourceCatalog.describe("decoded.bin", ResContainer.decodedData("decoded.bin", ByteArray(250_001)))
        assertTrue(binary.binary)
        assertNull(binary.text)
        assertEquals(250_001L, binary.sizeBytes)
        loaded().use { decompiler ->
            val xml = "<resources><string name=\"long\">${"x".repeat(250_001)}</string></resources>"
            add(decompiler, "base.apk", "resources.arsc", ResourceType.ARSC, ResContainer.resourceTable("resources.arsc",
                listOf(ResContainer.textResource("res/values/strings.xml", SimpleCodeInfo(xml))), SimpleCodeInfo("table")))
            val service = ResourceService(Access(decompiler))
            add(decompiler, "base.apk", "res/xml/decoded.xml", ResourceType.XML,
                ResContainer.textResource("res/xml/decoded.xml", SimpleCodeInfo("x".repeat(250_001))))
            val decoded = service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = "res/xml/decoded.xml"), context).file!!
            assertEquals(200_000, decoded.text!!.content.length)
            assertEquals(250_001, decoded.text!!.totalChars)
            assertTrue(decoded.text!!.truncated)
            val file = service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = "res/values/strings.xml"), context).file!!
            assertEquals(200_000, file.text!!.content.length)
            assertEquals(xml.length, file.text!!.totalChars)
            assertTrue(file.text!!.truncated)
            assertEquals(250_001, service.resource(ResourceRequest("s", ResourceAction.STRINGS), context).strings.single().value.length)
        }
    }

    @Test fun textAndStreamBoundariesNeverSplitSupplementaryCharacters() {
        val value = "a".repeat(199_999) + "\uD83D\uDE00" + "z"
        for (container in listOf(ResContainer.textResource("decoded.xml", SimpleCodeInfo(value)),
            ResContainer.resourceTable("resources.arsc", emptyList(), SimpleCodeInfo(value)))) {
            val file = ResourceCatalog.describe("decoded.xml", container)
            assertEquals("a".repeat(199_999), file.text!!.content)
            assertEquals(value.length, file.text!!.totalChars)
            assertTrue(file.text!!.truncated)
            assertEquals(value.toByteArray(Charsets.UTF_8).size.toLong(), file.sizeBytes)
        }
        loaded().use { decompiler ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            val zip = ZipEntry(File("base.apk"), "assets/unicode.txt", bytes.size.toLong()) { ByteArrayInputStream(bytes) }
            val link = object : JadxResourceFile(decompiler, "assets/unicode.txt", ResourceType.UNKNOWN) {
                override fun getZipEntry(): IZipEntry = zip
            }
            add(decompiler, "base.apk", "assets/unicode.txt", ResourceType.UNKNOWN, ResContainer.resourceFileLink(link), zip)
            val file = ResourceService(Access(decompiler)).resource(
                ResourceRequest("s", ResourceAction.FILE, fileName = "assets/unicode.txt"), context).file!!
            assertEquals("a".repeat(199_999), file.text!!.content)
            assertEquals(value.length, file.text!!.totalChars)
            assertTrue(file.text!!.truncated)
            assertEquals(bytes.size.toLong(), file.sizeBytes)
        }
    }

    @Test fun realDecodeStreamLinksAreBoundedAndDoNotReadBinaryPayloadsForDescriptions() {
        loaded().use { decompiler ->
            val bytes = "é".repeat(250_001).toByteArray(Charsets.UTF_8)
            val zip = ZipEntry(File("base.apk"), "assets/large.txt", bytes.size.toLong()) { ByteArrayInputStream(bytes) }
            val link = object : JadxResourceFile(decompiler, "assets/large.txt", ResourceType.UNKNOWN) {
                override fun getZipEntry(): IZipEntry = zip
            }
            add(decompiler, "base.apk", "assets/large.txt", ResourceType.UNKNOWN, ResContainer.resourceFileLink(link), zip)
            val binaryZip = ZipEntry(File("base.apk"), "res/raw/huge.bin", Long.MAX_VALUE) {
                object : InputStream() {
                    override fun read(): Int = error("Binary payload must not be read to describe it")
                }
            }
            val binary = object : JadxResourceFile(decompiler, "res/raw/huge.bin", ResourceType.UNKNOWN) {
                override fun getZipEntry(): IZipEntry = binaryZip
            }
            add(decompiler, "base.apk", "res/raw/huge.bin", ResourceType.UNKNOWN, ResContainer.resourceFileLink(binary), binaryZip)
            val service = ResourceService(Access(decompiler))
            val text = service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = "assets/large.txt"), context).file!!
            assertEquals(200_000, text.text!!.content.length)
            assertEquals(250_001, text.text!!.totalChars)
            assertTrue(text.text!!.truncated)
            assertEquals(bytes.size.toLong(), text.sizeBytes)
            val description = service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = "res/raw/huge.bin"), context).file!!
            assertTrue(description.binary)
            assertNull(description.text)
            assertEquals(Long.MAX_VALUE, description.sizeBytes)

            var consumed = 0
            val endlessZip = ZipEntry(File("base.apk"), "assets/unknown", 20L * 1024 * 1024) {
                object : InputStream() {
                    override fun read(): Int { consumed++; return 'a'.code }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        java.util.Arrays.fill(bytes, offset, offset + length, 'a'.code.toByte())
                        consumed += length
                        return length
                    }
                }
            }
            val endless = object : JadxResourceFile(decompiler, "assets/unknown", ResourceType.UNKNOWN) {
                override fun getZipEntry(): IZipEntry = endlessZip
            }
            add(decompiler, "base.apk", "assets/unknown", ResourceType.UNKNOWN, ResContainer.resourceFileLink(endless), endlessZip)
            assertEquals(ErrorCode.UNSUPPORTED, assertThrows(ProtocolException::class.java) {
                service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = "assets/unknown"), context)
            }.error.code)
            assertTrue(consumed <= 16 * 1024 * 1024 + 1)
        }
    }
}
