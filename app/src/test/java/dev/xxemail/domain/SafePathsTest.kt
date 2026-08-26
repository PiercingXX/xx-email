package dev.xxemail.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

class SafePathsTest {

    // ------------------------------------------------------------- childName

    @Test
    fun `clean filename passes through`() {
        assertEquals("report.pdf", SafePaths.childName("report.pdf"))
        assertEquals("attachment-ANGtJd-_abc123", SafePaths.childName("attachment-ANGtJd-_abc123"))
    }

    @Test
    fun `path traversal collapses to last segment`() {
        assertEquals("authstates.bin", SafePaths.childName("../../files/authstates.bin"))
        assertEquals("secret", SafePaths.childName("..%2F..%2Fsecret".replace("%2F", "/")))
    }

    @Test
    fun `dot and dotdot are rejected`() {
        assertNull(SafePaths.childName("."))
        assertNull(SafePaths.childName(".."))
    }

    @Test
    fun `dots-only and trailing-dot names are rejected`() {
        assertNull(SafePaths.childName("..."))
        assertNull(SafePaths.childName("../.."))
    }

    @Test
    fun `blank and null are rejected`() {
        assertNull(SafePaths.childName(null))
        assertNull(SafePaths.childName(""))
        assertNull(SafePaths.childName("   "))
    }

    @Test
    fun `nested separators collapse to final segment`() {
        assertEquals("c", SafePaths.childName("a/b/c"))
        assertEquals("deep.txt", SafePaths.childName("/a/b/c/deep.txt"))
    }

    @Test
    fun `absolute paths keep only the file part`() {
        assertEquals("passwd", SafePaths.childName("/etc/passwd"))
    }

    @Test
    fun `windows-style separators are treated as separators`() {
        assertEquals("x.bin", SafePaths.childName("..\\..\\x.bin"))
        assertEquals("notes", SafePaths.childName("C:\\Users\\victim\\notes"))
    }

    @Test
    fun `null bytes are rejected`() {
        assertNull(SafePaths.childName("bad\u0000name"))
        assertNull(SafePaths.childName("\u0000"))
    }

    @Test
    fun `overly long names are truncated`() {
        val long = "x".repeat(10_000) + ".pdf"
        val safe = SafePaths.childName(long)
        assertEquals(SafePaths.MAX_NAME_LENGTH, safe!!.length)
        assertTrue(safe.endsWith(".pdf") || !safe.contains("/"))
    }

    @Test
    fun `childNameOr falls back through seed to last resort`() {
        assertEquals("ok.txt", SafePaths.childNameOr("a/ok.txt", "seed", "last"))
        assertEquals("seed", SafePaths.childNameOr(null, "seed", "last"))
        assertEquals("seed", SafePaths.childNameOr("../..", "../seed", "last"))
        assertEquals("last", SafePaths.childNameOr("", "", "last"))
    }

    // -------------------------------------------------------------- isInside

    @Test
    fun `file directly in dir is inside`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "safepaths-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            assertTrue(SafePaths.isInside(dir, File(dir, "child.txt")))
        } finally {
            dir.delete()
        }
    }

    @Test
    fun `parent escape is not inside`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "safepaths-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            assertFalse(SafePaths.isInside(dir, File(dir, "../../escape.txt")))
            val parent = requireNotNull(dir.parentFile) { "temp dir must have a parent" }
            assertFalse(SafePaths.isInside(dir, parent))
            assertFalse(SafePaths.isInside(dir, File("/etc/passwd")))
        } finally {
            dir.delete()
        }
    }
}
