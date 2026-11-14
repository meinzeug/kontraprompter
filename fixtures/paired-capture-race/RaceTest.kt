package de.kontrakurs.kontraprompter.video

import de.kontrakurs.kontraprompter.network.LanSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RaceTest {
    private val one = "11111111-1111-4111-8111-111111111111"
    private val two = "22222222-2222-4222-8222-222222222222"
    private fun status(p: PairedCapture, revision: Int, phase: String, id: String? = null) {
        val data = JSONObject().put("revision", revision).put("phase", phase)
        if (id != null) data.put("recording", id)
        assertTrue(p.message("capture_status", data.toString()))
    }
    private fun instance(lan: LanSession) = PairedCapture(false, lan, CoroutineScope(SupervisorJob() + Dispatchers.Default), LiveSession())
    @Test fun phaseAndTakeMustStillMatchWhenCommandIsAdmitted() {
        val lan = LanSession(); val p = instance(lan); val marker = lan.current
        status(p, 1, "recording", two)
        assertFalse(p.requestShutter("pause", null, "recording", one, marker))
        assertTrue(lan.sent.isEmpty())
        assertFalse(p.requestShutter("start", null, "idle", null, marker))
        assertTrue(lan.sent.isEmpty())
        assertTrue(p.requestShutter("pause", null, "recording", two, marker))
        val command = JSONObject(lan.sent.single())
        assertEquals("pause", command.optString("action")); assertEquals(two, command.optString("recording"))
    }
    @Test fun connectionSwapBetweenCheckAndSendIsRejected() {
        val lan = LanSession(); val p = instance(lan); val marker = lan.current
        status(p, 1, "idle")
        lan.beforeBoundSend = { lan.current = Any() }
        assertFalse(p.requestShutter("start", null, "idle", null, marker))
        assertTrue(lan.sent.isEmpty()); assertFalse(p.state.value.pending)
    }
    @Test fun phaseUpdateCannotInterleaveBetweenAdmissionAndBoundSend() {
        val lan = LanSession(); val p = instance(lan); val marker = lan.current
        status(p, 1, "recording", one)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val statusReturned = AtomicBoolean(false)
        val statusStarted = CountDownLatch(1); val statusDone = CountDownLatch(1)
        lan.atMarkerCheck = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val result = AtomicBoolean(false)
        val first = thread { result.set(p.requestShutter("pause", null, "recording", one, marker)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = thread { statusStarted.countDown(); status(p, 2, "recording", two); statusReturned.set(true); statusDone.countDown() }
        assertTrue(statusStarted.await(5, TimeUnit.SECONDS)); assertFalse(statusDone.await(100, TimeUnit.MILLISECONDS))
        release.countDown(); first.join(5000); second.join(5000)
        assertFalse(first.isAlive); assertFalse(second.isAlive); assertTrue(result.get()); assertTrue(statusReturned.get())
        val command = JSONObject(lan.sent.single())
        assertEquals("pause", command.optString("action")); assertEquals(one, command.optString("recording"))
    }
}
