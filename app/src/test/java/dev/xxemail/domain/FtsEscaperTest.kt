package dev.xxemail.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FtsEscaperTest {

    @Test
    fun `plain terms become quoted prefix phrases`() {
        assertEquals("\"hello\"* \"world\"*", FtsEscaper.escapePrefixQuery("hello world"))
    }

    @Test
    fun `boolean operators are neutralized as literals`() {
        assertEquals("\"AND\"*", FtsEscaper.escapePrefixQuery("AND"))
        assertEquals("\"OR\"* \"NOT\"*", FtsEscaper.escapePrefixQuery("OR NOT"))
    }

    @Test
    fun `fts syntax characters are defused`() {
        // Quotes are dropped; every other special ends up INSIDE a quoted phrase, where
        // SQLite treats it as a literal — no operator interpretation, no syntax error.
        assertEquals("\"from:alice@example.com\"*", FtsEscaper.escapePrefixQuery("from:alice@example.com"))
        assertEquals("\"ab\"*", FtsEscaper.escapePrefixQuery("a\"b"))
        assertEquals("\"(paren)\"*", FtsEscaper.escapePrefixQuery("(paren)"))
        assertEquals("\"a-b\"* \"c*d\"*", FtsEscaper.escapePrefixQuery("a-b c*d"))
    }

    @Test
    fun `whitespace runs collapse and empties vanish`() {
        assertEquals("\"a\"* \"b\"*", FtsEscaper.escapePrefixQuery("  a   b  "))
        assertEquals("", FtsEscaper.escapePrefixQuery("   "))
        assertEquals("", FtsEscaper.escapePrefixQuery(""))
        assertEquals("\"ab\"*", FtsEscaper.escapePrefixQuery("\" \" ab"))
    }
}
