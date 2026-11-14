package de.kontrakurs.kontraprompter.network

import kotlinx.coroutines.flow.MutableStateFlow

data class LanState(val ready: Boolean = true)
class LanSession {
    val state = MutableStateFlow(LanState())
    @Volatile var current: Any = Any()
    val sent = mutableListOf<String>()
    var beforeBoundSend: (() -> Unit)? = null
    var atMarkerCheck: (() -> Unit)? = null
    fun isApprovedConnection(marker: Any): Boolean { atMarkerCheck?.invoke(); return current === marker && state.value.ready }
    fun send(type: String, payload: String): Boolean { sent.add(payload); return true }
    fun sendOnApprovedConnection(marker: Any, type: String, payload: String): Boolean {
        beforeBoundSend?.invoke()
        if (current !== marker || !state.value.ready) return false
        sent.add(payload); return true
    }
}
