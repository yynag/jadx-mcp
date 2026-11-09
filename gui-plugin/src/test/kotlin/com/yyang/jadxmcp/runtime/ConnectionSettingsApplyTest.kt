package com.yyang.jadxmcp.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.prefs.BackingStoreException

class ConnectionSettingsApplyTest {
    private val selected = MasterConnectionSettings.of("127.0.0.1", "9010")

    @Test fun failedSavedConfigurationNeverSuppliesDefaultConnectionSettings() {
        val failures = listOf<Throwable>(IllegalArgumentException("private origin"),
            BackingStoreException("private path"), NoClassDefFoundError("private/Class"))
        var activations = 0
        failures.forEach { failure ->
            val result = loadSavedConnectionSettings { throw failure }
            result.settings?.let { activations++ }
            assertNull(result.settings)
            assertNotNull(result.notice)
            assertFalse(requireNotNull(result.notice).contains("private"))
        }
        assertEquals(0, activations)
        assertEquals(ConnectionSettingsLoadResult(selected), loadSavedConnectionSettings { selected })
    }

    @Test fun validPortIsSavedAndActivatedWithoutAnHttpProbe() {
        val stages = mutableListOf<String>()
        val result = applySavedConnectionSettings(selected,
            save = { stages += "save:${it.port}" }, activate = { stages += "activate:${it.port}" })
        assertEquals(listOf("save:9010", "activate:9010"), stages)
        assertEquals(ConnectionSettingsApplyResult.APPLIED, result)
    }

    @Test fun persistenceFailureDoesNotSwitchBridgeOrLeakExceptionContents() {
        var activated = false
        val result = applySavedConnectionSettings(selected,
            save = { throw BackingStoreException("sensitive token and path") }, activate = { activated = true })
        assertFalse(activated)
        assertFalse(result.applied)
        assertEquals("Could not save settings (BackingStoreException); connection unchanged", result.message)
    }

    @Test fun savedButActivationFailedIsNotMisreportedAsAPersistenceFailure() {
        var saved = false
        val result = applySavedConnectionSettings(selected, save = { saved = true },
            activate = { throw java.util.concurrent.RejectedExecutionException("secret") })
        assertTrue(saved)
        assertFalse(result.applied)
        assertEquals("Settings saved; bridge activation failed (RejectedExecutionException)", result.message)
    }

    @Test fun incompatibleRuntimeClassFailureIsDiagnosedAndDoesNotEscapeCallback() {
        val result = applySavedConnectionSettings(selected, save = {},
            activate = { throw NoClassDefFoundError("some/host/Class") })
        assertEquals("Java runtime incompatible (NoClassDefFoundError); use a full JDK 21", result.message)
        assertFalse(result.applied)
    }

}
