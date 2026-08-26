package dev.xxemail.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientsTest {

    @Test
    fun `reply-all to mail To A,B Cc C includes From plus B and C`() {
        val (to, cc) = Recipients.replyAll(
            fromHeader = "From <from@example.com>",
            toHeader = "a@example.com, b@example.com",
            ccHeader = "c@example.com",
            self = "me@example.com",
        )
        assertEquals(listOf("from@example.com", "a@example.com", "b@example.com"), to)
        assertEquals(listOf("c@example.com"), cc)
    }

    @Test
    fun `reply-all excludes self everywhere and never duplicates`() {
        val (to, cc) = Recipients.replyAll(
            fromHeader = "Me <me@example.com>",
            toHeader = "Me <me@example.com>, B@Example.com, b@example.com",
            ccHeader = "me@example.com, c@example.com",
            self = "me@example.com",
        )
        assertEquals(listOf("B@Example.com"), to)
        assertEquals(listOf("c@example.com"), cc)
    }

    @Test
    fun `reply-all keeps original To when From is self`() {
        val (to, _) = Recipients.replyAll(
            fromHeader = "me@example.com",
            toHeader = "a@example.com",
            ccHeader = "",
            self = "me@example.com",
        )
        assertEquals(listOf("a@example.com"), to)
    }

    @Test
    fun `reply uses original From unless it is self`() {
        assertEquals(
            listOf("jane@example.com"),
            Recipients.replyTo("Jane Doe <jane@example.com>", "a@example.com", self = "me@example.com"),
        )
        // From is us → fall back to the original To list.
        assertEquals(
            listOf("a@example.com", "b@example.com"),
            Recipients.replyTo("me@example.com", "a@example.com, b@example.com", self = "me@example.com"),
        )
    }

    @Test
    fun `quoted display name with comma stays one address`() {
        assertEquals(
            listOf("jane@x.com", "bob@y.com"),
            Recipients.parse("\"Doe, Jane\" <jane@x.com>, bob@y.com"),
        )
    }

    @Test
    fun `fully quoted address string is one entry not a split pair`() {
        val result = Recipients.parse("\"Doe, Jane <jane@x.com>\"")
        assertEquals(1, result.size)
        assertTrue(result.single().contains("jane@x.com"))
    }

    @Test
    fun `garbage input does not crash and valid addresses survive`() {
        val result = Recipients.parseValidated("<<<<>>, , total garbage, jane@x.com")
        assertTrue(result.contains("jane@x.com"))
        assertTrue(result.all { it.contains('@') })
        assertEquals(emptyList<String>(), Recipients.parse(""))
        assertEquals(emptyList<String>(), Recipients.parseValidated("   "))
    }
}
