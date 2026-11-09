package com.yyang.jadxmcp.analysis.project

import com.yyang.jadxmcp.protocol.CallContext
import com.yyang.jadxmcp.protocol.ProjectDataSnapshot

/**
 * Optional single-owner storage boundary, invoked ONLY by explicit persist(). No implementation,
 * path, load, sharing policy or .jadx convention is selected here. Save must be atomic and must not
 * report success before durable commit; cancellation/failure must leave the previous value intact.
 * Native GUI projects remain owned by their host: this store is not accepted for BORROWED workspaces.
 */
fun interface ProjectOwnerStore {
    fun save(snapshot: ProjectDataSnapshot, context: CallContext): OwnerSaveReceipt
}

/** A successful return attests this exact revision was durably saved by its single owner. */
data class OwnerSaveReceipt(val revision: String, val projectPath: String? = null)
