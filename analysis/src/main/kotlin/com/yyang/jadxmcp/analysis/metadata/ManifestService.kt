package com.yyang.jadxmcp.analysis.metadata

import com.yyang.jadxmcp.analysis.resource.ResourceCatalog
import com.yyang.jadxmcp.protocol.*
import jadx.api.JadxDecompiler
import org.w3c.dom.Element

class ManifestService(private val workspace: WorkspaceAccess<JadxDecompiler>) : com.yyang.jadxmcp.protocol.ManifestService {
    override fun manifest(request: ManifestRequest, context: CallContext): ManifestResult {
        request.validate()
        return workspace.withDecompiler(context) { decompiler ->
            context.checkActive()
            val catalog = ResourceCatalog(decompiler, context)
            val xml = catalog.manifestXml() ?: missing("AndroidManifest.xml not found")
            if (request.componentType == null) ManifestResult(xml = xml)
            else ManifestResult(components = ManifestModel.parse(xml, catalog::boolean).components
                .filter { it.type == request.componentType && (!request.onlyExported || it.exported) },
                componentType = request.componentType)
        }
    }
}

/** Android defaults are target-SDK-sensitive for providers; aliases identify their target and alias separately. */
internal data class ManifestModel(
    val packageName: String?, val components: List<ManifestComponent>, val mainActivity: String?
) {
    companion object {
        fun parse(xml: String, resolveBoolean: (String) -> Boolean = { value ->
            throw ProtocolException(ProtocolError(ErrorCode.UNSUPPORTED, "Unresolved manifest boolean: $value"))
        }): ManifestModel {
            val root = SafeXml.parse(xml).documentElement
            if (root.tagName != "manifest") throw ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT, "Expected manifest root"))
            val pkg = root.getAttribute("package").ifBlank { null }
            fun qualify(name: String): String = when {
                name.startsWith('.') -> pkg.orEmpty() + name
                '.' !in name && pkg != null -> "$pkg.$name"
                else -> name
            }
            val sdk = SafeXml.children(root, "uses-sdk").firstOrNull()
            val sdkValue = sdk?.let { SafeXml.android(it, "targetSdkVersion").ifBlank {
                SafeXml.android(it, "minSdkVersion") } }.orEmpty()
            val targetSdk = if (sdkValue.isBlank()) 1 else sdkValue.toIntOrNull()
                ?: throw ProtocolException(ProtocolError(ErrorCode.UNSUPPORTED, "Unresolved manifest SDK: $sdkValue"))
            val app = SafeXml.children(root, "application").firstOrNull()
                ?: return ManifestModel(pkg, emptyList(), null)
            fun boolean(node: Element, attr: String, default: Boolean): Boolean = when (val value = SafeXml.android(node, attr).trim()) {
                "" -> default
                "true", "1" -> true
                "false", "0" -> false
                else -> resolveBoolean(value)
            }
            val appEnabled = boolean(app, "enabled", true)
            val nodes = SafeXml.children(app).filter { it.tagName in setOf("activity", "activity-alias", "service", "receiver", "provider") }
            fun enabled(node: Element) = boolean(node, "enabled", true) && appEnabled
            var main: String? = null
            val result = nodes.mapNotNull { node ->
                val alias = node.tagName == "activity-alias"
                val rawName = SafeXml.android(node, if (alias) "targetActivity" else "name")
                if (rawName.isBlank()) return@mapNotNull null
                val type = when (node.tagName) {
                    "service" -> ComponentType.SERVICE
                    "receiver" -> ComponentType.RECEIVER
                    "provider" -> ComponentType.PROVIDER
                    else -> ComponentType.ACTIVITY
                }
                val filters = SafeXml.children(node, "intent-filter")
                val exported = boolean(node, "exported",
                    if (type == ComponentType.PROVIDER) targetSdk < 17 else filters.isNotEmpty())
                val name = qualify(rawName)
                // Alias enabled/exported flags are independent of the target activity's flags.
                val isEnabled = enabled(node)
                // MAIN and LAUNCHER must occur in the same filter, not across unrelated filters.
                if (main == null && type == ComponentType.ACTIVITY && isEnabled && exported && filters.any { filter ->
                    SafeXml.children(filter, "action").any { SafeXml.android(it, "name") == "android.intent.action.MAIN" } &&
                        SafeXml.children(filter, "category").any { SafeXml.android(it, "name") == "android.intent.category.LAUNCHER" }
                }) main = name
                ManifestComponent(name, type, if (alias) qualify(SafeXml.android(node, "name")) else null, exported, isEnabled)
            }
            return ManifestModel(pkg, result.sortedWith(compareBy({ it.type.name }, { it.className }, { it.aliasName.orEmpty() })), main)
        }
    }
}
