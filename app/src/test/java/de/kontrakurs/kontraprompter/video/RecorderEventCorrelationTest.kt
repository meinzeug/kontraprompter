package de.kontrakurs.kontraprompter.video

import de.kontrakurs.kontraprompter.test.jvmAllocate
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Exercises the real LiveSession event handler with synthetic Recorder events, no camera or codec. */
class RecorderEventCorrelationTest {
    private fun id() = UUID.randomUUID().toString()
    private fun session(initial: LocalRecording): Pair<LiveSession, MutableStateFlow<LocalRecording>> {
        val target = jvmAllocate(LiveSession::class.java)
        val state = MutableStateFlow(initial)
        fun set(name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        set("guard", Any()); set("_recording", state); set("_video", MutableStateFlow<RtcVideo?>(null))
        set("closed", false); set("disconnected", false)
        return target to state
    }
    private fun emit(target: LiveSession, event: RtcMp4Recorder.Event) {
        LiveSession::class.java.getDeclaredMethod("recordingEvent", RtcMp4Recorder.Event::class.java).apply { isAccessible = true }.invoke(target, event)
    }
    @Test fun `only recording event for same take advances preparing to recording`() {
        val take = id(); val (target, state) = session(LocalRecording.Preparing(take))
        emit(target, RtcMp4Recorder.Event.Recording(id(), 1280, 720))
        assertEquals(LocalRecording.Preparing(take), state.value)
        emit(target, RtcMp4Recorder.Event.Recording(take, 1280, 720))
        assertEquals(LocalRecording.Recording(take), state.value)
    }
    @Test fun `same take failure after enqueue remains visible and unrelated failure is ignored`() {
        val take = id(); val (target, state) = session(LocalRecording.Preparing(take))
        emit(target, RtcMp4Recorder.Event.Failed(id(), "old", null))
        assertEquals(LocalRecording.Preparing(take), state.value)
        emit(target, RtcMp4Recorder.Event.Failed(take, "synthetic recorder failure", null))
        assertEquals(LocalRecording.Failed("synthetic recorder failure", false, take), state.value)
        emit(target, RtcMp4Recorder.Event.Recording(take, 1280, 720))
        assertEquals(LocalRecording.Failed("synthetic recorder failure", false, take), state.value)
    }
}
