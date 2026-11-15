package de.kontrakurs.kontraprompter.camera

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CameraWireTest {
    private fun samples(): JSONArray = JSONObject(checkNotNull(javaClass.getResourceAsStream("/camera-v1-v2-contract.json")).bufferedReader().use { it.readText() }).getJSONArray("commands")
    private fun body(item: JSONObject) = JSONObject().put("commands", JSONArray().put(item)).toString()
    private fun copy(item: JSONObject) = JSONObject(item.toString())

    @Test fun `shared server contract decodes each actual delivery`() {
        val commands = samples()
        val expected = listOf("record_start", "record_pause", "record_resume", "record_stop", "camera_switch")
        expected.forEachIndexed { index, type ->
            val item = commands.getJSONObject(index)
            val parsed = parseCameraPollResponse(body(item))
            assertEquals(type, parsed.command?.type)
            assertEquals(item.getString("id"), parsed.command?.id)
            assertEquals(9_990L, parsed.remainingMs)
            if (type == "camera_switch") assertEquals(false, parsed.command?.cameraFrom)
        }
        assertNull(parseCameraPollResponse("{\"commands\":[]}").command)
    }

    @Test fun `malformed wire types ids scopes and deadlines fail before decision`() {
        val samples = samples(); val start = samples.getJSONObject(0); val pause = samples.getJSONObject(1); val switch = samples.getJSONObject(4)
        val bad = listOf(
            copy(start).put("remainingMs", "50"), copy(start).put("remainingMs", 1.5),
            copy(start).put("remainingMs", 10_001), copy(start).put("remainingMs", -1),
            copy(start).put("id", "INVALID"), copy(start).put("projectId", JSONObject.NULL),
            copy(pause).put("projectId", start.getString("projectId")),
            copy(pause).put("cameraFrom", false), copy(pause).put("type", "unexpected"),
            copy(switch).put("cameraFrom", "false"), copy(switch).put("recordingId", start.getString("recordingId")),
            copy(start).also { it.remove("projectId") },
        )
        bad.forEach { assertThrows(Exception::class.java) { parseCameraPollResponse(body(it)) } }
        assertThrows(Exception::class.java) { parseCameraPollResponse(JSONObject().put("commands", JSONArray().put(start).put(pause)).toString()) }
    }
}
