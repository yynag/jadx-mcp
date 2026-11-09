package com.yyang.jadxmcp.protocol

data class SummaryRequest(override val sessionId: String = "") : BackendRequest {
    override val operation get() = Operation.META_SUMMARY
    override fun validate() = session(sessionId)
}

enum class ComponentType { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

data class ManifestRequest(
    override val sessionId: String = "",
    val componentType: ComponentType? = null,
    val onlyExported: Boolean = false
) : BackendRequest {
    override val operation get() = Operation.META_MANIFEST
    override fun validate() {
        session(sessionId)
        if (onlyExported && componentType == null) invalid("onlyExported requires componentType")
    }
}

data class ClassRequest(
    override val sessionId: String = "",
    val className: String? = null,
    val packageName: String? = null,
    val mainApp: Boolean = false,
    val page: PageRequest = PageRequest()
) : BackendRequest {
    override val operation get() = Operation.META_CLASS
    override fun validate() {
        session(sessionId); page.validate()
        if (className != null) nonBlank(className, "className")
    }
}

data class PackageCount(val name: String = "", val classes: Int = 0)
data class SummaryResult(
    val classesCount: Int = 0, val mainActivity: String? = null, val mainPackage: String? = null,
    val deobf: Boolean = false, val topPackages: List<PackageCount> = emptyList()
) : OperationResult

data class ManifestComponent(
    val className: String = "", val type: ComponentType = ComponentType.ACTIVITY,
    val aliasName: String? = null, val exported: Boolean = false, val enabled: Boolean = true
)

data class ManifestResult(
    val xml: String? = null, val components: List<ManifestComponent> = emptyList(),
    val componentType: ComponentType? = null
) : OperationResult

/** Raw identities and exact descriptors survive aliases and disambiguate overloaded methods. */
data class SymbolRef(
    val className: String = "", val memberName: String? = null, val descriptor: String? = null
)

data class MethodInfo(
    val name: String = "", val fullName: String = "", val descriptor: String = "",
    val returnType: String = "", val parameterTypes: List<String> = emptyList(), val access: String = ""
)

data class FieldInfo(
    val name: String = "", val fullName: String = "", val type: String = "", val access: String = ""
)

data class ClassResult(
    val className: String? = null, val classes: List<String> = emptyList(),
    val methods: List<MethodInfo> = emptyList(), val fields: List<FieldInfo> = emptyList(),
    val page: PageInfo = PageInfo()
) : OperationResult
