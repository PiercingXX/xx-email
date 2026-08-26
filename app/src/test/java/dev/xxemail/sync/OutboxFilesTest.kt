package dev.xxemail.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutboxFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writeNew stores relative path and readable file`() {
        val filesDir = tmp.newFolder("files")
        val rel = OutboxFiles.writeNew(filesDir, 42L, "hello".toByteArray())
        assertEquals("outbox/42.eml", rel)
        val resolved = OutboxFiles.resolve(filesDir, rel)
        assertNotNull(resolved)
        assertEquals("hello", resolved!!.readText())
        assertTrue(resolved.isFile)
    }

    @Test
    fun `resolve rejects traversal and absolute paths`() {
        val filesDir = tmp.newFolder("files2")
        assertNull(OutboxFiles.resolve(filesDir, "../escape.eml"))
        assertNull(OutboxFiles.resolve(filesDir, "/etc/passwd"))
        assertNull(OutboxFiles.resolve(filesDir, ""))
        assertNull(OutboxFiles.resolve(filesDir, null))
        assertNull(OutboxFiles.resolve(filesDir, "outbox/missing.eml"))
    }

    @Test
    fun `deletePayloadFile removes the eml and stale tmp`() {
        val filesDir = tmp.newFolder("files3")
        val rel = OutboxFiles.writeNew(filesDir, 7L, "x".toByteArray())
        File(File(filesDir, "outbox"), "7.eml.tmp").writeBytes(byteArrayOf(1))
        OutboxFiles.deletePayloadFile(filesDir, rel, 7L)
        assertNull(OutboxFiles.resolve(filesDir, rel))
        assertTrue(!File(File(filesDir, "outbox"), "7.eml.tmp").exists())
        // Deleting again is harmless.
        OutboxFiles.deletePayloadFile(filesDir, null, 7L)
    }
}
