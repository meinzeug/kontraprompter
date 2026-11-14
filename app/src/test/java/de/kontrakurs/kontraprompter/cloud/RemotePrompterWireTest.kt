package de.kontrakurs.kontraprompter.cloud

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class RemotePrompterWireTest {
    private class Connection(private val response: String, private val afterWrite: () -> Unit = {}) : HttpURLConnection(URL("http://fixture.invalid")) {
        private val sink = object : ByteArrayOutputStream() { override fun close() { super.close(); afterWrite() } }
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun getOutputStream() = sink
        fun body() = sink.toString("UTF-8")
    }
    private val command = """{"commands":[{"id":"12c3267c-c343-41d1-8245-2a88107e7213","type":"set_speed","projectId":null,"value":76,"remainingMs":8000}]}"""
    @Test fun `phone sends actual settings and decodes numeric commands`() {
        val conn = Connection(command)
        val result = pollPrompterDevice({ _, _ -> conn }, "http://fixture.invalid", "fixture", { true }, {}, "device", "Xiaomi", "Bereit", emptyList(), 35f, 44f)
        assertEquals("set_speed", result.single().type); assertEquals(76.0, result.single().value!!, 0.0)
        assertTrue(conn.body().contains("\"speed\":35")); assertTrue(conn.body().contains("\"fontSize\":44"))
    }
    @Test fun `session change and string command values are rejected`() {
        var same = true; val conn = Connection(command) { same = false }
        assertTrue(runCatching { pollPrompterDevice({ _, _ -> conn }, "http://fixture.invalid", "fixture", { same }, {}, "device", "Xiaomi", "Bereit", emptyList(), 35f, 44f) }.isFailure)
        val invalid = Connection(command.replace("\"value\":76", "\"value\":\"76\""))
        assertTrue(runCatching { pollPrompterDevice({ _, _ -> invalid }, "http://fixture.invalid", "fixture", { true }, {}, "device", "Xiaomi", "Bereit", emptyList(), 35f, 44f) }.isFailure)
    }
}
