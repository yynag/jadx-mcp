package com.yyang.jadxmcp.runtime

/** Local settings acknowledgement, deliberately independent from asynchronous HTTP connectivity. */
internal data class ConnectionSettingsApplyResult(val applied: Boolean, val message: String) {
    companion object {
        val APPLIED = ConnectionSettingsApplyResult(true, "Applied!")
        val UNLOADED = ConnectionSettingsApplyResult(false, "Plugin unloaded; reopen the plugin")
        val WORKER_CLOSED = ConnectionSettingsApplyResult(false, "Settings worker closed; reload the plugin")
    }
}

/** A broken host classpath must still complete the UI callback, without exposing paths. */
internal fun connectionRuntimeFailure(failure: LinkageError): ConnectionSettingsApplyResult =
    ConnectionSettingsApplyResult(false,
        "Java runtime incompatible (${failure.javaClass.simpleName}); use a full JDK 21")

internal data class ConnectionSettingsLoadResult(val settings: MasterConnectionSettings?, val notice: String? = null)

/** A visible default in the form is not permission to connect after reading the saved origin failed. */
internal fun loadSavedConnectionSettings(load: () -> MasterConnectionSettings): ConnectionSettingsLoadResult = try {
    ConnectionSettingsLoadResult(load())
} catch (failure: LinkageError) {
    ConnectionSettingsLoadResult(null, connectionRuntimeFailure(failure).message)
} catch (_: IllegalArgumentException) {
    ConnectionSettingsLoadResult(null, "Saved address unsupported: enter IP and port again (HTTP only)")
} catch (failure: Exception) {
    ConnectionSettingsLoadResult(null, "Could not load settings (${failure.javaClass.simpleName})")
}

/**
 * Save failure must not switch the live bridge or claim persistence. Conversely an unavailable
 * remote server is not a save failure: [activate] schedules registration without awaiting HTTP.
 * If activation itself fails after saving, report that partial outcome honestly. Fixed stage and
 * exception-type diagnostics are enough to distinguish failures without echoing tokens/paths.
 */
internal fun applySavedConnectionSettings(
    selected: MasterConnectionSettings,
    save: (MasterConnectionSettings) -> Unit,
    activate: (MasterConnectionSettings) -> Unit,
): ConnectionSettingsApplyResult {
    try {
        save(selected)
    } catch (failure: LinkageError) {
        return connectionRuntimeFailure(failure)
    } catch (failure: Exception) {
        return ConnectionSettingsApplyResult(false,
            "Could not save settings (${failure.javaClass.simpleName}); connection unchanged")
    }
    return try {
        activate(selected)
        ConnectionSettingsApplyResult.APPLIED
    } catch (failure: LinkageError) {
        connectionRuntimeFailure(failure)
    } catch (failure: Exception) {
        ConnectionSettingsApplyResult(false,
            "Settings saved; bridge activation failed (${failure.javaClass.simpleName})")
    }
}
