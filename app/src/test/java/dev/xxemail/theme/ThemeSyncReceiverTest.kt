package dev.xxemail.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ThemeSyncReceiverTest {

    private val manifestFile: File =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }

    private val manifest: Element = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(manifestFile)
        .documentElement

    private fun children(parent: Element, name: String): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == name) out += node
        }
        return out
    }

    private fun themeSyncReceiverElement(): Element? =
        children(manifest, "application")
            .flatMap { children(it, "receiver") }
            .firstOrNull { it.getAttribute("android:name") == ".theme.ThemeSyncReceiver" }

    @Test
    fun `the receiver speaks the family's action and extra names`() {
        assertEquals("xx.launcher.THEME_CHANGED", ThemeSyncReceiver.ACTION_THEME_CHANGED)
        assertEquals("xx.launcher.extra.THEME_NAME", ThemeSyncReceiver.EXTRA_THEME_NAME)
        assertEquals("xx.launcher.extra.BACKGROUND", ThemeSyncReceiver.EXTRA_BACKGROUND)
        assertEquals(
            "com.piercingxx.xxlauncher.permission.THEME_SYNC",
            ThemeSyncReceiver.PERMISSION_THEME_SYNC,
        )
    }

    @Test
    fun `manifest exports the receiver and gates it with the family permission`() {
        val receiver = themeSyncReceiverElement()
        assertNotNull(receiver)
        assertEquals("true", receiver!!.getAttribute("android:exported"))
        assertEquals(
            ThemeSyncReceiver.PERMISSION_THEME_SYNC,
            receiver.getAttribute("android:permission"),
        )
        assertTrue(
            "XX-Launcher owns THEME_SYNC; this app must not declare it",
            children(manifest, "permission").isEmpty(),
        )
        val uses = children(manifest, "uses-permission")
            .any { it.getAttribute("android:name") == ThemeSyncReceiver.PERMISSION_THEME_SYNC }
        assertTrue(uses)
        assertFalse(
            children(manifest, "uses-permission")
                .any { it.getAttribute("android:name") == "dev.xxemail.permission.THEME_SYNC" },
        )
    }

    @Test
    fun `manifest registers the launcher theme-changed action`() {
        val receiver = themeSyncReceiverElement()
        assertNotNull(receiver)
        val actions = children(receiver!!, "intent-filter")
            .flatMap { children(it, "action") }
            .map { it.getAttribute("android:name") }
        assertTrue(ThemeSyncReceiver.ACTION_THEME_CHANGED in actions)
        assertFalse("dev.xxemail.action.THEME_SYNC" in actions)
    }

    @Test
    fun `declared receiver name resolves to a class`() {
        Class.forName("dev.xxemail.theme.ThemeSyncReceiver")
    }

    @Test
    fun `a named preset broadcast persists that preset's ground`() {
        val persisted = mutableListOf<SyncedTheme>()
        ThemeSyncReceiver(
            extractAction = { ThemeSyncReceiver.ACTION_THEME_CHANGED },
            extractThemeName = { "Graphite" },
            extractBackground = { 0xFF00FF00L },
            persistTheme = { _, theme -> persisted += theme },
            applyLive = { _, _ -> },
        ).onReceive(null, null)
        assertEquals(
            listOf(SyncedTheme(0xFF131316L, isDark = true, presetKey = "graphite")),
            persisted,
        )
    }

    @Test
    fun `a custom broadcast persists the BACKGROUND extra`() {
        val persisted = mutableListOf<SyncedTheme>()
        val applied = mutableListOf<SyncedTheme>()
        ThemeSyncReceiver(
            extractAction = { ThemeSyncReceiver.ACTION_THEME_CHANGED },
            extractThemeName = { "Custom" },
            extractBackground = { 0xFF224466L },
            persistTheme = { _, theme -> persisted += theme },
            applyLive = { _, theme -> applied += theme },
        ).onReceive(null, null)
        val expected = SyncedTheme(0xFF224466L, isDark = true)
        assertEquals(listOf(expected), persisted)
        assertEquals(listOf(expected), applied)
    }

    @Test
    fun `wrong action is ignored`() {
        val persisted = mutableListOf<SyncedTheme>()
        ThemeSyncReceiver(
            extractAction = { "dev.xxemail.action.THEME_SYNC" },
            extractThemeName = { "Graphite" },
            extractBackground = { 0xFF131316L },
            persistTheme = { _, theme -> persisted += theme },
            applyLive = { _, _ -> },
        ).onReceive(null, null)
        assertTrue(persisted.isEmpty())
    }
}
