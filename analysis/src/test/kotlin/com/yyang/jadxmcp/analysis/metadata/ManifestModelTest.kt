package com.yyang.jadxmcp.analysis.metadata

import com.yyang.jadxmcp.protocol.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManifestModelTest {
    private fun manifest(body: String, target: Int = 30, enabled: Boolean = true) = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.app">
        <uses-sdk android:targetSdkVersion="$target"/><application android:enabled="$enabled">$body</application></manifest>
    """.trimIndent()

    @Test fun aliasesRelativeNamesAndExportDefaults() {
        val model = ManifestModel.parse(manifest("""
            <activity android:name="Main"/>
            <activity-alias android:name=".Launch" android:targetActivity="Main"><intent-filter>
            <action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter></activity-alias>
            <service android:name=".Local"/><receiver android:name="foreign.Receiver" android:exported="false"><intent-filter/></receiver>
            <provider android:name=".Provider"/>
        """))
        assertEquals("example.app.Main", model.mainActivity)
        val alias = model.components.single { it.aliasName != null }
        assertEquals("example.app.Launch", alias.aliasName)
        assertTrue(alias.exported)
        assertFalse(model.components.single { it.type == ComponentType.PROVIDER }.exported)
        assertFalse(model.components.single { it.type == ComponentType.RECEIVER }.exported)
        assertTrue(ManifestModel.parse(manifest("<provider android:name=\".P\"/>", 16)).components.single().exported)
    }

    @Test fun launcherRequiresOneFilterAndEnabledApplicationButAliasHasIndependentFlags() {
        val divided = """<activity android:name=".Main"><intent-filter><action android:name="android.intent.action.MAIN"/></intent-filter>
            <intent-filter><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>"""
        assertNull(ManifestModel.parse(manifest(divided)).mainActivity)
        val launcher = """<activity android:name=".Main" android:enabled="false"/>
            <activity-alias android:name=".Alias" android:targetActivity=".Main"><intent-filter>
            <action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter></activity-alias>"""
        assertEquals("example.app.Main", ManifestModel.parse(manifest(launcher)).mainActivity)
        assertTrue(ManifestModel.parse(manifest(launcher, enabled = false)).components.none { it.enabled })
    }

    @Test fun malformedAndExternalEntityXmlFailClosed() {
        for (xml in listOf("<manifest>", """<!DOCTYPE manifest [<!ENTITY x SYSTEM "file:///nonexistent-private-file">]><manifest package="&x;"/>""")) {
            assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(ProtocolException::class.java) { ManifestModel.parse(xml) }.error.code)
        }
    }
}
