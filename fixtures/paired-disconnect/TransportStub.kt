package de.kontrakurs.kontraprompter.network

import kotlinx.coroutines.flow.MutableStateFlow

data class LanState(val ready: Boolean = true)
class LanSession {
    val state = MutableStateFlow(LanState())
    val sent = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    val marker = Any()
    fun isApprovedConnection(value: Any) = value === marker && state.value.ready
    fun send(type: String, payload: String): Boolean { sent.add(type to payload); return true }
    fun sendOnApprovedConnection(value: Any, type: String, payload: String): Boolean =
        isApprovedConnection(value) && send(type, payload)
}
