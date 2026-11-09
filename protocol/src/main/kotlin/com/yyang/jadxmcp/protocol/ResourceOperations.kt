package com.yyang.jadxmcp.protocol

enum class ResourceAction { LIST, FILE, STRINGS, ID }

data class ResourceRequest(
    override val sessionId: String = "", val action: ResourceAction = ResourceAction.LIST,
    val fileName: String? = null, val resourceId: String? = null, val page: PageRequest = PageRequest()
) : BackendRequest {
    override val operation get() = Operation.RESOURCE
    override fun validate() {
        session(sessionId); page.validate()
        if (action == ResourceAction.FILE) nonBlank(fileName ?: "", "fileName")
        if (action == ResourceAction.ID) {
            val id = resourceId ?: invalid("resourceId is required")
            val value = if (id.startsWith("0x", true)) id.drop(2).toLongOrNull(16) else id.toLongOrNull()
            if (value == null || value !in 0..0xffff_ffffL) invalid("Invalid unsigned 32-bit resourceId")
        }
    }
}

data class StringResource(val name: String = "", val value: String = "")
data class ResourceFile(
    val name: String = "", val text: TextContent? = null, val binary: Boolean = false, val sizeBytes: Long? = null
)
data class ResourceIdResult(val idHex: String = "", val name: String? = null, val value: String? = null)
data class ResourceResult(
    val action: ResourceAction = ResourceAction.LIST, val files: List<String> = emptyList(),
    val file: ResourceFile? = null, val strings: List<StringResource> = emptyList(),
    val resource: ResourceIdResult? = null, val page: PageInfo = PageInfo()
) : OperationResult
