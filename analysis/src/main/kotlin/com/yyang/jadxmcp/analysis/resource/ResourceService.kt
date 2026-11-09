package com.yyang.jadxmcp.analysis.resource

import com.yyang.jadxmcp.analysis.metadata.SafeXml
import com.yyang.jadxmcp.analysis.metadata.missing
import com.yyang.jadxmcp.analysis.metadata.page
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxDecompiler
import jadx.api.ResourceType
import jadx.api.ResourcesLoader
import jadx.core.utils.android.AndroidResourcesMap
import jadx.core.xmlgen.ResContainer
import java.io.InputStreamReader
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import jadx.core.xmlgen.entry.ResourceEntry

/** Resource reads are lease-scoped and request-local; this service never exposes host paths or retains decoded payloads. */
class ResourceService(private val workspace: WorkspaceAccess<JadxDecompiler>) : com.yyang.jadxmcp.protocol.ResourceService {
    override fun resource(request: ResourceRequest, context: CallContext): ResourceResult {
        request.validate()
        return workspace.withDecompiler(context) { decompiler ->
            context.checkActive()
            val catalog = ResourceCatalog(decompiler, context)
            when (request.action) {
                ResourceAction.LIST -> {
                    val (items, info) = page(catalog.names(), request.page)
                    ResourceResult(request.action, files = items, page = info)
                }
                ResourceAction.FILE -> ResourceResult(request.action, file = catalog.file(request.fileName!!))
                ResourceAction.STRINGS -> {
                    val (items, info) = page(catalog.strings(), request.page)
                    ResourceResult(request.action, strings = items, page = info)
                }
                ResourceAction.ID -> {
                    val raw = request.resourceId!!
                    val id = (if (raw.startsWith("0x", true)) raw.drop(2).toLong(16) else raw.toLong()).toInt()
                    val name = decompiler.root.constValues.resourcesNames[id] ?: AndroidResourcesMap.getResName(id)
                    val value = if (name?.substringBefore('/')?.substringAfter(':') == "string") {
                        catalog.stringValue(name.substringAfter('/'))
                    } else null
                    ResourceResult(request.action, resource = ResourceIdResult("0x%08x".format(id), name, value))
                }
            }
        }
    }
}

/** Request-local containers never survive the workspace lease or a resource rename. */
internal class ResourceCatalog(private val decompiler: JadxDecompiler, private val context: CallContext) {
    private val loaded = mutableMapOf<jadx.api.ResourceFile, ResContainer>()
    private val nativeEntries: List<ResourceEntry> by lazy {
        decompiler.resources.filter { it.type == ResourceType.ARSC }.flatMap { resource ->
            context.checkActive()
            try {
                ResourcesLoader.decodeStream(resource) { _, input ->
                    decompiler.resourcesLoader.decodeTable(resource, input).resStorage.resources.toList()
                }
            } catch (e: Exception) {
                context.checkActive()
                throw ProtocolException(ProtocolError(ErrorCode.UNSUPPORTED, "Cannot decode native resource values for manifest booleans"))
            }
        }
    }

    fun boolean(value: String): Boolean = NativeBooleanResolver { nativeEntries }.resolve(value)

    private data class Entry(
        val path: String, val aliases: Set<String>, val source: String,
        val root: jadx.api.ResourceFile, val child: ResContainer? = null,
        val occurrence: Int = 0
    ) {
        // Archive URI comes from IZipEntry.getZipFile(), not a guessed original-name prefix.
        val qualified: String get() = "archive:$source!/$path#resource-$occurrence"
    }

    private val entries: List<Entry> by lazy {
        val result = mutableListOf<Entry>()
        for (resource in decompiler.resources) {
            context.checkActive()
            val zip = resource.zipEntry
            val source = zip?.zipFile?.toURI()?.toASCIIString() ?: "unlocated"
            val path = if (resource.deobfName == resource.originalName) zip?.name ?: resource.originalName else resource.deobfName
            result += Entry(path, setOfNotNull(resource.originalName, resource.deobfName, zip?.name), source, resource)
            if (resource.type == ResourceType.ARSC) flatten(load(resource)).drop(1).forEach { child ->
                result += Entry(child.name, setOf(child.name, child.fileName), source, resource, child)
            }
        }
        val occurrences = mutableMapOf<Pair<String, String>, Int>()
        // Archive/path identities survive input-list reordering. Occurrences only disambiguate
        // duplicate entries inside one archive (or custom loaders which supply no ZIP provenance).
        result.sortedWith(compareBy({ it.source }, { it.path }, { it.root.originalName })).map { entry ->
            val key = entry.source to entry.path
            val ordinal = occurrences.getOrDefault(key, 0)
            occurrences[key] = ordinal + 1
            entry.copy(occurrence = ordinal)
        }.sortedBy { it.qualified }
    }

    private fun content(entry: Entry) = entry.child ?: load(entry.root)

    private fun describeEntry(entry: Entry): ResourceFile {
        val name = identity(entry)
        // Native image decoders can materialize the whole payload. Metadata needs only ZIP size.
        if (entry.child == null && entry.root.originalName.substringAfterLast('.').lowercase() in BINARY_EXTENSIONS) {
            return ResourceFile(name, binary = true, sizeBytes = entry.root.zipEntry?.uncompressedSize?.takeIf { it >= 0 })
        }
        return describeFile(name, content(entry))
    }

    private fun identity(entry: Entry): String =
        if (!multipleSources && pathCounts.getValue(entry.path) == 1) entry.path else entry.qualified

    private val pathCounts by lazy { entries.groupingBy { it.path }.eachCount() }
    private val multipleSources by lazy { entries.map { it.source }.distinct().size > 1 }

    private fun load(resource: jadx.api.ResourceFile): ResContainer {
        context.checkActive()
        return loaded.getOrPut(resource) {
            try { resource.loadContent() ?: missing("No content for resource: ${resource.originalName}") }
            catch (e: ProtocolException) { throw e }
            catch (e: Exception) {
                throw ProtocolException(ProtocolError(ErrorCode.INTERNAL, "Failed to decode resource: ${resource.originalName}"))
            }
        }
    }

    private fun flatten(container: ResContainer): List<ResContainer> {
        context.checkActive()
        return listOf(container) + container.subFiles.flatMap(::flatten)
    }

    fun names(): List<String> {
        return entries.map(::identity).sorted()
    }

    fun file(name: String): ResourceFile {
        entries.firstOrNull { it.qualified == name }?.let { return describeEntry(it) }
        val exact = entries.filter { it.path == name || name in it.aliases }
        if (exact.size > 1) conflict(name)
        exact.singleOrNull()?.let { return describeEntry(it) }
        val suffix = entries.filter { it.path.endsWith("/$name") || it.aliases.any { alias -> alias.endsWith("/$name") } }
        if (suffix.size > 1) conflict(name)
        suffix.singleOrNull()?.let { return describeEntry(it) }
        missing("Resource not found: $name")
    }

    private fun conflict(name: String): Nothing =
        throw ProtocolException(ProtocolError(ErrorCode.CONFLICT, "Ambiguous resource name: $name; use an identity returned by LIST"))

    private fun describeFile(name: String, container: ResContainer): ResourceFile {
        if (container.dataType != ResContainer.DataType.RES_LINK) return describe(name, container)
        // Read only JADX's resource handle, never a client-supplied host path. Stream to bound retained text.
        return try {
            ResourcesLoader.decodeStream(container.resLink) { size, input ->
                val byteSize = size.takeIf { it >= 0 }
                if (container.resLink.originalName.substringAfterLast('.').lowercase() in BINARY_EXTENSIONS)
                    return@decodeStream ResourceFile(name, binary = true, sizeBytes = byteSize)
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                // A binary stream containing a huge ASCII prefix must not cause unbounded reads.
                // Oversize ambiguous/text links fail explicitly rather than inventing a totalChars value.
                val reader = InputStreamReader(ScanLimitedStream(input), decoder)
                val buffer = CharArray(8192)
                val text = StringBuilder()
                var total = 0L
                try {
                    while (true) {
                        context.checkActive()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if ((0 until count).any { buffer[it] < ' ' && buffer[it] !in "\t\n\r\u000c" }) {
                            return@decodeStream ResourceFile(name, binary = true, sizeBytes = byteSize)
                        }
                        total += count
                        val keep = count.coerceAtMost((MAX_TEXT_CHARS - text.length).coerceAtLeast(0))
                        text.append(buffer, 0, keep)
                    }
                    ResourceFile(name, TextContent(text.toString(), total > text.length,
                        total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), mediaType(name)), sizeBytes = byteSize)
                } catch (_: CharacterCodingException) {
                    ResourceFile(name, binary = true, sizeBytes = byteSize)
                }
            }
        } catch (e: ProtocolException) { throw e }
        catch (e: Exception) {
            // decodeStream may wrap callback exceptions; preserve cooperative cancellation/deadline failures.
            context.checkActive()
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is ProtocolException) throw cause
                cause = cause.cause
            }
            throw ProtocolException(ProtocolError(ErrorCode.INTERNAL, "Failed to read resource: $name"))
        }
    }

    fun manifestXml(): String? {
        val manifests = decompiler.resources.filter {
            it.originalName == "AndroidManifest.xml" || it.deobfName == "AndroidManifest.xml" ||
                it.zipEntry?.name == "AndroidManifest.xml"
        }
        val decoded = manifests.map { load(it).text?.codeStr ?: missing("Manifest has no XML text") }
        // Parse the split attribute, not an incidental substring in a comment or value.
        return decoded.firstOrNull { !SafeXml.parse(it).documentElement.hasAttribute("split") } ?: decoded.firstOrNull()
    }

    fun strings(): List<StringResource> {
        val records = stringRecords()
        val counts = records.groupingBy { it.second.name }.eachCount()
        return records.map { (entry, string) ->
            if (counts.getValue(string.name) == 1) string
            else string.copy(name = "${entry.qualified}#string/${string.name}")
        }.sortedWith(compareBy({ it.name }, { it.value }))
    }

    private fun stringRecords(): List<Pair<Entry, StringResource>> = entries
        .filter { it.path == "res/values/strings.xml" || "res/values/strings.xml" in it.aliases }
        .flatMap { entry ->
            context.checkActive()
            val container = content(entry)
            if (container.dataType != ResContainer.DataType.TEXT && container.dataType != ResContainer.DataType.RES_TABLE)
                throw ProtocolException(ProtocolError(ErrorCode.UNSUPPORTED, "strings.xml is not decoded XML: ${entry.qualified}"))
            // Parse complete XML before presentation truncation; never parse a truncated FILE response.
            parseStrings(container.text.codeStr).map { entry to it }
        }

    fun stringValue(name: String): String? {
        val values = stringRecords().filter { it.second.name == name }.map { it.second.value }.distinct()
        if (values.size > 1) conflict("string/$name")
        return values.singleOrNull()
    }

    companion object {
        private const val MAX_TEXT_CHARS = 200_000
        private const val MAX_SCAN_BYTES = 16 * 1024 * 1024
        private val BINARY_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bin", "dat", "mp3", "mp4", "ogg", "wav", "ttf", "woff", "zip", "dex", "so")

        private class ScanLimitedStream(input: InputStream) : FilterInputStream(input) {
            private var remaining = MAX_SCAN_BYTES
            override fun read(): Int {
                if (remaining == 0) return probeEnd()
                return super.read().also { if (it >= 0) remaining-- }
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (remaining == 0) return probeEnd()
                return `in`.read(bytes, offset, length.coerceAtMost(remaining)).also { if (it > 0) remaining -= it }
            }
            private fun probeEnd(): Int {
                if (`in`.read() < 0) return -1
                throw ProtocolException(ProtocolError(ErrorCode.UNSUPPORTED,
                    "Resource stream exceeds bounded text scan (16 MiB); exact text length unavailable"))
            }
        }

        private fun mediaType(name: String): String = when {
            name.substringBeforeLast("#resource-", name).endsWith(".xml", true) -> "application/xml"
            name.substringBeforeLast("#resource-", name).endsWith(".json", true) -> "application/json"
            else -> "text/plain"
        }

        internal fun parseStrings(xml: String): List<StringResource> {
            val root = SafeXml.parse(xml).documentElement
            if (root.tagName != "resources") throw ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT, "Expected resources root"))
            return SafeXml.children(root).filter { it.tagName == "string" ||
                (it.tagName == "item" && it.getAttribute("type") == "string") }
                .mapNotNull { element -> element.getAttribute("name").takeIf { it.isNotBlank() }
                    ?.let { StringResource(it, element.textContent) } }
                .sortedWith(compareBy({ it.name }, { it.value }))
        }

        internal fun describe(name: String, container: ResContainer): ResourceFile = when (container.dataType.name) {
            "TEXT", "RES_TABLE" -> {
                val text = container.text.codeStr
                ResourceFile(name, TextContent(text.take(MAX_TEXT_CHARS), truncated = text.length > MAX_TEXT_CHARS, totalChars = text.length,
                    mediaType = mediaType(name)),
                    sizeBytes = utf8Size(text))
            }
            "DECODED_DATA" -> ResourceFile(name, binary = true, sizeBytes = container.decodedData.size.toLong())
            // A standalone descriptor does not open links; leased FILE reads use describeFile.
            else -> ResourceFile(name, binary = true)
        }

        /** Match UTF-8 replacement of unpaired surrogates without allocating a second full byte array. */
        private fun utf8Size(text: String): Long {
            var bytes = 0L
            var i = 0
            while (i < text.length) {
                val c = text[i]
                bytes += when {
                    c < '\u0080' -> 1
                    c < '\u0800' -> 2
                    c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> { i++; 4 }
                    c.isSurrogate() -> 1
                    else -> 3
                }
                i++
            }
            return bytes
        }
    }
}
