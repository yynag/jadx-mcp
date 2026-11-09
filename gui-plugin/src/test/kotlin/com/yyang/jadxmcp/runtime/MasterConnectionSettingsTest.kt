package com.yyang.jadxmcp.runtime

import java.util.prefs.AbstractPreferences
import java.util.prefs.BackingStoreException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MasterConnectionSettingsTest {
    @Test fun diagnosticPreferenceDefaultsOffAndPersistsBothDirections() {
        val prefs = MemoryPreferences()
        assertFalse(MasterConnectionSettings.loadDiagnostics(prefs))
        MasterConnectionSettings.saveDiagnostics(true, prefs)
        assertTrue(MasterConnectionSettings.loadDiagnostics(prefs))
        MasterConnectionSettings.saveDiagnostics(false, prefs)
        assertFalse(MasterConnectionSettings.loadDiagnostics(prefs))
        assertThrows(BackingStoreException::class.java) {
            MasterConnectionSettings.saveDiagnostics(true, MemoryPreferences(rejectFlush = true))
        }
    }
    @Test fun validatesNumericIpAndBuildsHttpOrigin() {
        assertEquals("http://127.0.0.1:8650", MasterConnectionSettings.of("127.0.0.1", "8650").baseUrl)
        assertEquals("http://[::1]:65535", MasterConnectionSettings.of("::1", "65535").baseUrl)
        assertEquals(1, MasterConnectionSettings.of("2001:db8::1", "1").port)
        assertEquals("::1", MasterConnectionSettings.parse("http://[::1]:8650/").ip)
        assertEquals(80, MasterConnectionSettings.parse("http://127.0.0.1").port)
    }

    @Test fun rejectsInvalidSchemesPortsAndUriParts() {
        listOf("https://127.0.0.1:8650", "http://localhost:8650", "http://127.0.0.1:0", "http://user@127.0.0.1", "http://127.0.0.1/path", "http://bad host").forEach {
            assertThrows(IllegalArgumentException::class.java) { MasterConnectionSettings.parse(it) }
        }
    }

    @Test fun rejectsNonNumericOrMalformedIpAndInvalidPorts() {
        listOf("", "localhost", "127.1", "256.0.0.1", "01.2.3.4", ":::", "[::1]", "fe80::1%en0", "http://127.0.0.1", "127.0.0.1:8650").forEach {
            assertThrows(IllegalArgumentException::class.java) { MasterConnectionSettings.of(it, "8650") }
        }
        listOf("", "0", "65536", "abc", "-1", "1.5", "999999999999999").forEach {
            assertThrows(IllegalArgumentException::class.java) { MasterConnectionSettings.of("127.0.0.1", it) }
        }
    }

    @Test fun preferencesOverrideSystemPropertiesAndPersist() {
        val prefs = MemoryPreferences()
        val previous = System.getProperty("jadx.mcp.url")
        try {
            System.setProperty("jadx.mcp.url", "http://127.0.0.1:8650")
            assertEquals("http://127.0.0.1:8650", MasterConnectionSettings.load(prefs).baseUrl)
            val saved = MasterConnectionSettings.of("192.168.1.10", "9000")
            MasterConnectionSettings.save(saved, prefs)
            assertEquals(saved, MasterConnectionSettings.load(prefs))
            prefs.put("masterUrl", "https://127.0.0.1:9000")
            assertThrows(IllegalArgumentException::class.java) { MasterConnectionSettings.load(prefs) }
            prefs.put("masterUrl", "http://master.example:9000")
            assertThrows(IllegalArgumentException::class.java) { MasterConnectionSettings.load(prefs) }
        } finally {
            if (previous == null) System.clearProperty("jadx.mcp.url") else System.setProperty("jadx.mcp.url", previous)
        }
    }

    @Test fun preferenceFlushFailurePropagatesInsteadOfClaimingPersistence() {
        val prefs = MemoryPreferences(rejectFlush = true)
        assertThrows(BackingStoreException::class.java) {
            MasterConnectionSettings.save(MasterConnectionSettings.of("::1", "8650"), prefs)
        }
    }

    private class MemoryPreferences(private val rejectFlush: Boolean = false) : AbstractPreferences(null, "") {
        private val values = mutableMapOf<String, String>()
        override fun putSpi(key: String, value: String) { values[key] = value }
        override fun getSpi(key: String): String? = values[key]
        override fun removeSpi(key: String) { values.remove(key) }
        override fun removeNodeSpi() { values.clear() }
        override fun keysSpi() = values.keys.toTypedArray()
        override fun childrenNamesSpi() = emptyArray<String>()
        override fun childSpi(name: String): AbstractPreferences = error("No child preferences")
        override fun syncSpi() {}
        override fun flushSpi() { if (rejectFlush) throw BackingStoreException("Test failure") }
    }
}
