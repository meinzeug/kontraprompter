package de.kontrakurs.kontraprompter.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class StudioDeviceLoginTransportTest {
    private class Connection(private val code:Int,private val response:String):HttpURLConnection(URI.create("http://127.0.0.1").toURL()) {
        val sent=ByteArrayOutputStream();var disconnected=false
        override fun disconnect(){disconnected=true}
        override fun usingProxy()=false
        override fun connect(){}
        override fun getResponseCode()=code
        override fun getInputStream()=ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        override fun getOutputStream()=sent
    }
    private val nonce="A".repeat(43)
    private val device="2b8b26bc-d809-4f67-8759-028bb5891001"
    private val challenge="2b8b26bc-d809-4f67-8759-028bb5891002"
    @Test fun `sends only intended endpoint and bounded request with redirects disabled`() {
        val c=Connection(200,"""{"challengeId":"$challenge","challengeToken":"$challenge.1000.$nonce.$nonce","expiresInMs":60000}""")
        var path="";val t=StudioDeviceLoginTransport {path=it;c}
        assertEquals(challenge,t.begin(device).challengeId);assertEquals("/api/studio-devices/login/begin",path)
        assertEquals("POST",c.requestMethod);assertFalse(c.instanceFollowRedirects);assertTrue(c.disconnected)
        assertTrue(c.sent.toString("UTF-8").contains(device));assertFalse(c.sent.toString("UTF-8").contains("password"))
    }
    @Test fun `redirect failed and malformed oversized responses fail closed`() {
        val bad=listOf(302 to "{}",200 to "{}",200 to "{",200 to "x".repeat(4097),200 to """{"challengeId":"$challenge","challengeToken":"$nonce","expiresInMs":60001}""",200 to """{"challengeId":"$challenge","challengeToken":"$nonce","expiresInMs":10,"extra":true}""")
        for((code,body) in bad){val c=Connection(code,body);val t=StudioDeviceLoginTransport {c};assertThrows(IOException::class.java){t.begin(device)};assertTrue(c.disconnected)}
    }
    @Test fun `ticket must be canonical and expiry in bounds`() {
        val payloads=listOf("""{"ticket":"${"B".repeat(43)}","expiresInMs":300000}""", """{"ticket":"$nonce","expiresInMs":300001}""", """{"ticket":"$nonce","expiresInMs":0}""")
        for(body in payloads){val t=StudioDeviceLoginTransport {Connection(200,body)};assertThrows(IOException::class.java){t.complete(device,"token",byteArrayOf(1))}}
        val t=StudioDeviceLoginTransport {Connection(200,"""{"ticket":"$nonce","expiresInMs":1}""")};assertEquals(nonce,t.complete(device,"token",byteArrayOf(1)).token)
        assertThrows(IOException::class.java){t.complete(device,"token",byteArrayOf())}
    }
}
