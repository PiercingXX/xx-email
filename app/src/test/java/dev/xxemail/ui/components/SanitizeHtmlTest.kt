package dev.xxemail.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizeHtmlTest {

    // ------------------------------------------------------- script/style strip

    @Test
    fun `script blocks are removed`() {
        assertEquals(
            "<p>hi</p>",
            sanitizeHtml("<p>hi</p><script>alert(1)</script>", allowRemoteImages = false),
        )
    }

    @Test
    fun `script removal is case-insensitive`() {
        assertEquals("", sanitizeHtml("<ScRiPt>alert(1)</sCrIpT>", allowRemoteImages = false))
    }

    @Test
    fun `script blocks spanning newlines are removed`() {
        assertEquals(
            "<p>a</p>\n",
            sanitizeHtml("<p>a</p>\n<script type=\"text/javascript\">\nvar x = 1;\n</script>", allowRemoteImages = false),
        )
    }

    @Test
    fun `style blocks are removed`() {
        assertEquals(
            "<p>hi</p>",
            sanitizeHtml("<style>body { color: red }</style><p>hi</p>", allowRemoteImages = false),
        )
        assertEquals(
            "<p>hi</p>",
            sanitizeHtml("<STYLE>\n.p { margin:0 }\n</STYLE><p>hi</p>", allowRemoteImages = false),
        )
    }

    // -------------------------------------------------------- event handlers

    @Test
    fun `double-quoted event handler attribute is stripped`() {
        assertEquals(
            "<a href=\"https://example.com\">link</a>",
            sanitizeHtml("<a href=\"https://example.com\" onclick=\"evil()\">link</a>", allowRemoteImages = false),
        )
    }

    @Test
    fun `single-quoted and unquoted event handlers are stripped`() {
        assertEquals("<a>x</a>", sanitizeHtml("<a onmouseover='evil()'>x</a>", allowRemoteImages = false))
        assertEquals("<b>y</b>", sanitizeHtml("<b onerror=evil()>y</b>", allowRemoteImages = false))
    }

    @Test
    fun `javascript uris are removed case-insensitively`() {
        assertEquals(
            "<a href=\"alert(1)\">c</a>",
            sanitizeHtml("<a href=\"javascript:alert(1)\">c</a>", allowRemoteImages = false),
        )
        assertEquals(
            "<a href=\"alert(1)\">c</a>",
            sanitizeHtml("<a href=\"JaVaScRiPt:alert(1)\">c</a>", allowRemoteImages = false),
        )
    }

    // ------------------------------------------------------------ image gate

    @Test
    fun `remote images are stripped when not allowed`() {
        assertEquals(
            "<p>a</p>",
            sanitizeHtml("<p>a<img src=\"https://track.example/pixel.gif\"></p>", allowRemoteImages = false),
        )
    }

    @Test
    fun `images survive when remote images are allowed`() {
        val html = "<p>a<img src=\"https://cdn.example/cat.png\"></p>"
        assertEquals(html, sanitizeHtml(html, allowRemoteImages = true))
    }

    // --------------------------------------------------------------- entities

    @Test
    fun `html entities pass through untouched`() {
        val html = "<p>Fish &amp; Chips &lt;3 &gt;9 &#39;quote&#39;</p>"
        assertEquals(html, sanitizeHtml(html, allowRemoteImages = false))
    }

    @Test
    fun `plain text without markup is unchanged`() {
        assertEquals("hello world", sanitizeHtml("hello world", allowRemoteImages = false))
    }

    // ------------------------------------------------------ degenerate inputs

    @Test
    fun `empty and blank input return as-is`() {
        assertEquals("", sanitizeHtml("", allowRemoteImages = false))
        assertEquals("", sanitizeHtml("", allowRemoteImages = true))
        assertEquals("   ", sanitizeHtml("   ", allowRemoteImages = false))
    }

    @Test
    fun `unterminated tags do not crash`() {
        assertEquals("<script>alert(1)", sanitizeHtml("<script>alert(1)", allowRemoteImages = false))
        assertEquals("<img src=x", sanitizeHtml("<img src=x", allowRemoteImages = false))
    }

    @Test
    fun `combined hostile document is neutralized`() {
        val out = sanitizeHtml(
            "<script>steal()</script><a href=\"javascript:pwn()\" onmouseover='x'>click</a>" +
                "<img src=\"https://e.example/t\"><style>s</style>&amp;",
            allowRemoteImages = false,
        )
        assertFalse(out.contains("script"))
        assertFalse(out.contains("javascript"))
        assertFalse(out.contains("onmouseover"))
        assertFalse(out.contains("<img"))
        assertTrue(out.contains("&amp;"))
        assertTrue(out.contains("click"))
    }
}
