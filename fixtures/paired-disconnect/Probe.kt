package de.kontrakurs.kontraprompter.video

import de.kontrakurs.kontraprompter.network.LanSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject

private fun waitUntil(timeoutMs: Long = 2000, predicate: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
    check(predicate()) { "timed out waiting for fixture event" }
}

fun main() {
    repeat(4) { scenario ->
        val lan = LanSession()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val capture = PairedCapture(false, lan, scope, LiveSession())
            fun receive(type: String, payload: JSONObject) { capture.message(lan.marker, type, payload.toString()) }
            fun status(revision: Int, query: String? = null) = JSONObject().put("revision", revision).put("phase", "idle").apply { if (query != null) put("query", query) }
            receive("capture_status", status(1))
            check(capture.request("start"))
            val request = JSONObject(lan.sent.last { it.first == "capture_command" }.second).getString("request")
            // A malformed ACK is neither acceptance nor rejection.
            for (bad in listOf(JSONObject(), JSONObject().put("accepted", "false"), JSONObject().put("accepted", JSONObject.NULL))) {
                receive("capture_ack", bad.put("request", request))
                check(capture.state.value.pending)
                check(lan.sent.none { it.first == "capture_query" && it.second.isNotBlank() })
            }
            if (scenario == 1) {
                waitUntil(10_000) { capture.state.value.message == START_STATUS_TIMEOUT_MESSAGE }
                check(capture.state.value.pending)
                receive("capture_status", status(2)) // Uncorrelated timeout query cannot prove send ordering.
                check(capture.state.value.pending)
            }
            receive("capture_ack", JSONObject().put("request", request).put("accepted", scenario != 2))
            check(capture.state.value.pending) // The earlier idle status cannot authorize disconnect.
            check(lan.sent.any { it.first == "capture_query" && it.second.isNotBlank() && JSONObject(it.second).optString("request") == request })
            if (scenario == 2) {
                val take = java.util.UUID.randomUUID().toString()
                receive("capture_status", JSONObject().put("revision", 2).put("phase", "recording").put("recording", take).put("query", request))
                check(!capture.state.value.pending && capture.state.value.phase == "recording")
                check(capture.state.value.message == "Befehl wurde von der Kamera nicht angenommen.")
                return@repeat
            }
            receive("capture_status", status(6, java.util.UUID.randomUUID().toString()))
            check(capture.state.value.pending) // A different query is insufficient.
            receive("capture_status", status(5, request))
            check(capture.state.value.pending) // A stale correlated response triggers a new query.
            fun countQueries() = lan.sent.count { it.first == "capture_query" && it.second.isNotBlank() && JSONObject(it.second).optString("request") == request }
            check(countQueries() == 2)
            if (scenario == 3) {
                receive("capture_status", status(4, request))
                check(countQueries() == 3 && capture.state.value.pending)
                receive("capture_status", status(4, request))
                check(countQueries() == 3 && capture.state.value.pending)
                check(capture.state.value.message == START_STATUS_STALE_MESSAGE)
                return@repeat
            }
            receive("capture_status", status(7, request))
            check(!capture.state.value.pending && capture.state.value.phase == "idle")
            check(capture.state.value.message == null)
        } finally { scope.cancel() }
    }
    println("PASS: positive/negative ACK; malformed ACK; timeout; overtaken response; bounded retry; precise message cleanup")
}
