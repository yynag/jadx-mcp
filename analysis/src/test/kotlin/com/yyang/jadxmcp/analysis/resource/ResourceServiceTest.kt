package com.yyang.jadxmcp.analysis.resource

import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.ResourceFile as JadxResourceFile
import jadx.api.ResourceType
import jadx.api.impl.SimpleCodeInfo
import jadx.core.xmlgen.ResContainer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class ResourceServiceTest {
    private val context get() = CallContext("resource-test", 0, System.currentTimeMillis() + 60_000)
    private val stringsXml = """<resources><string name="z">Hello &amp; <b>world</b></string>
        <string name="a"><![CDATA[<literal>]]></string><item type="string" name="item">item value</item></resources>"""

    private class FakeWorkspace(val decompiler: JadxDecompiler) : WorkspaceAccess<JadxDecompiler> {
        override val ownership = WorkspaceOwnership.BORROWED
        override val projectEpoch = 0L
        override fun <T> withDecompiler(context: CallContext, action: (JadxDecompiler) -> T): T {
            context.checkActive(); context.requireEpoch(projectEpoch)
            return action(decompiler)
        }
        override fun close() = Unit
    }

    private fun loaded(clearResources: Boolean = true): JadxDecompiler = JadxDecompiler(JadxArgs().apply {
        inputFiles = mutableListOf(File("../app/src/test/resources/sample.jar").takeIf { it.isFile }
            ?: File("app/src/test/resources/sample.jar"))
        isDeobfuscationOn = false
    }).also { it.load(); if (clearResources) it.resources.clear() }

    private fun add(decompiler: JadxDecompiler, name: String, type: ResourceType, container: ResContainer): JadxResourceFile {
        val res = object : JadxResourceFile(decompiler, name, type) {
            override fun loadContent(): ResContainer = container
        }
        decompiler.resources.add(res)
        return res
    }

    @Test fun stringsXmlHandlesEntitiesCdataAndMarkupWithoutRegexFallback() {
        val strings = ResourceCatalog.parseStrings(stringsXml)
        assertEquals(listOf("a", "item", "z"), strings.map { it.name })
        assertEquals("<literal>", strings[0].value)
        assertEquals("Hello & world", strings[2].value)
        assertThrows(ProtocolException::class.java) { ResourceCatalog.parseStrings("<resources><string>") }
        assertThrows(ProtocolException::class.java) {
            ResourceCatalog.parseStrings("""<!DOCTYPE resources [<!ENTITY x SYSTEM "https://invalid.example/secret">]><resources><string name="x">&x;</string></resources>""")
        }
    }

    @Test fun actualJadxContainersDistinguishTextDecodedDataAndLinks() {
        val text = ResourceCatalog.describe("a.xml", ResContainer.textResource("a.xml", SimpleCodeInfo("<a>é</a>")))
        assertFalse(text.binary)
        assertEquals("application/xml", text.text!!.mediaType)
        assertEquals(9L, text.sizeBytes)
        val binary = ResourceCatalog.describe("a.png", ResContainer.decodedData("a.png", byteArrayOf(1, 2)))
        assertTrue(binary.binary)
        assertNull(binary.text)
        assertEquals(2L, binary.sizeBytes)
        loaded().use { decompiler ->
            val link = JadxResourceFile.createResourceFile(decompiler, "asset.dat", ResourceType.UNKNOWN)
            assertTrue(ResourceCatalog.describe("asset.dat", ResContainer.resourceFileLink(link)).binary)
        }
    }

    @Test fun resourceListGeneratedXmlAliasesPaginationAndIdLookup() {
        loaded().use { decompiler ->
            add(decompiler, "resources.arsc", ResourceType.ARSC, ResContainer.resourceTable("resources.arsc",
                listOf(ResContainer.textResource("res/values/strings.xml", SimpleCodeInfo(stringsXml))), SimpleCodeInfo("table")))
            val binary = add(decompiler, "res/raw/old.bin", ResourceType.UNKNOWN,
                ResContainer.decodedData("res/raw/old.bin", byteArrayOf(0, 1, 2)))
            binary.deobfName = "res/raw/new.bin"
            val service = ResourceService(FakeWorkspace(decompiler))
            val list = service.resource(ResourceRequest("s", page = PageRequest(0, 1)), context)
            assertEquals(3, list.page.total)
            assertEquals(1, list.page.nextOffset)
            assertEquals(list.files.sorted(), list.files)
            for (name in listOf("res/raw/old.bin", "res/raw/new.bin")) {
                assertTrue(service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = name), context).file!!.binary)
            }
            val strings = service.resource(ResourceRequest("s", ResourceAction.STRINGS, page = PageRequest(1, 1)), context)
            assertEquals("item", strings.strings.single().name)
            assertEquals(3, strings.page.total)
            decompiler.root.constValues.resourcesNames[0x7f010001] = "string/z"
            val id = service.resource(ResourceRequest("s", ResourceAction.ID, resourceId = "0x7f010001"), context).resource!!
            assertEquals("string/z", id.name)
            assertEquals("Hello & world", id.value)
            assertEquals("0x7f010001", id.idHex)
            assertEquals(id, service.resource(ResourceRequest("s", ResourceAction.ID, resourceId = "2130771969"), context).resource)
            assertNull(service.resource(ResourceRequest("s", ResourceAction.ID, resourceId = "0xffffffff"), context).resource!!.name)
            assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProtocolException::class.java) {
                service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = "absent.xml"), context)
            }.error.code)
        }
    }

    @Test fun basenameAmbiguityIsNotSilentlyResolved() {
        loaded().use { decompiler ->
            for (path in listOf("a/data.xml", "b/data.xml")) add(decompiler, path, ResourceType.XML,
                ResContainer.textResource(path, SimpleCodeInfo("<data/>")))
            val service = ResourceService(FakeWorkspace(decompiler))
            assertEquals(ErrorCode.CONFLICT, assertThrows(ProtocolException::class.java) {
                service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = "data.xml"), context)
            }.error.code)
            assertEquals("<data/>", service.resource(ResourceRequest("s", ResourceAction.FILE, fileName = "a/data.xml"), context).file!!.text!!.content)
        }
    }

    @Test fun actualSampleJarResourceHandlesReadUtf8WithoutUsingClientPaths() {
        loaded(clearResources = false).use { decompiler ->
            val service = ResourceService(FakeWorkspace(decompiler))
            val names = service.resource(ResourceRequest("s"), context).files
            assertTrue(names.contains("META-INF/MANIFEST.MF"), "sample.jar resource names: $names")
            val manifest = service.resource(ResourceRequest("s", ResourceAction.FILE,
                fileName = "META-INF/MANIFEST.MF"), context).file!!
            assertFalse(manifest.binary)
            assertTrue(manifest.text!!.content.contains("Manifest-Version:"))
            assertTrue(manifest.sizeBytes!! > 0)
        }
    }
}
