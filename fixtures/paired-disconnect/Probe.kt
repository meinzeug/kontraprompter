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
    repeat(2) { scenario ->
        val lan = LanSession()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val capture = PairedCapture(false, lan, scope, LiveSession())
            fun receive(type: String, payload: JSONObject) { capture.message(lan.marker, type, payload.toString()) }
            fun status(revision: Int, query: String? = null) = JSONObject().put("revision", revision).put("phase", "idle").apply { if (query != null) put("query", query) }
            receive("capture_status", status(1))
            check(capture.request("start"))
            val request = JSONObject(lan.sent.last { it.first == "capture_command" }.second).getString("request")
            if (scenario == 1) {
                waitUntil(10_000) { capture.state.value.message?.contains("Keine Startbestätigung") == true }
                check(capture.state.value.pending)
                receive("capture_status", status(2)) // Uncorrelated timeout query cannot prove send ordering.
                check(capture.state.value.pending)
            }
            receive("capture_ack", JSONObject().put("request", request).put("accepted", true))
            check(capture.state.value.pending) // The earlier idle status cannot authorize disconnect.
            check(lan.sent.any { it.first == "capture_query" && it.second.isNotBlank() && JSONObject(it.second).optString("request") == request })
            receive("capture_status", status(3, java.util.UUID.randomUUID().toString()))
            check(capture.state.value.pending) // A different query is insufficient.
            receive("capture_status", status(3, request))
            check(capture.state.value.pending) // A duplicate or stale revision is insufficient.
            receive("capture_status", status(4, request))
            check(!capture.state.value.pending && capture.state.value.phase == "idle")
        } finally { scope.cancel() }
    }
    println("PASS: ACK-before-status; timeout-before-status; wrong query; stale revision")
}
