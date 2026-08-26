package dev.xxemail.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteImagePolicyTest {

    @Test
    fun `https urls with a host are allowed`() {
        assertTrue(RemoteImagePolicy.isHttpsUrl("https://images.example.com/px.gif"))
        assertTrue(RemoteImagePolicy.isHttpsUrl("HTTPS://Example.COM/a.png"))
    }

    @Test
    fun `non-https and empty sources are rejected`() {
        assertFalse(RemoteImagePolicy.isHttpsUrl("http://images.example.com/px.gif"))
        assertFalse(RemoteImagePolicy.isHttpsUrl("file:///data/local/tmp/x"))
        assertFalse(RemoteImagePolicy.isHttpsUrl("javascript:alert(1)"))
        assertFalse(RemoteImagePolicy.isHttpsUrl("https://"))
        assertFalse(RemoteImagePolicy.isHttpsUrl(""))
        assertFalse(RemoteImagePolicy.isHttpsUrl("not a url"))
    }
}
