package de.kontrakurs.kontraprompter.video

import android.content.Context
import de.kontrakurs.kontraprompter.network.LanSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LiveSession(context: Context, val host: Boolean) : AutoCloseable {
    val lan = LanSession(context)
    private val _video = MutableStateFlow<RtcVideo?>(null)
    val video: StateFlow<RtcVideo?> = _video
    init {
        lan.onReady = {
            try { RtcVideo(context.applicationContext, host, lan).also { _video.value = it; it.start() } }
            catch (e: Exception) { lan.report("Videofehler", e.localizedMessage ?: "WebRTC nicht verfügbar") }
        }
        lan.onMessage = { type, payload -> _video.value?.message(type, payload) }
        lan.onDisconnect = { _video.value?.close(); _video.value = null }
        if (host) lan.startHost() else lan.startDiscovery()
    }
    override fun close() { lan.close(); _video.value?.close(); _video.value = null }
}
