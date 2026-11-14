package de.kontrakurs.kontraprompter

import org.junit.Assert.*
import org.junit.Test

class SelfieRemoteKeysTest {
    @Test fun `only changes to the originating device cancel its pending keys and clicks`() {
        val keys = ShutterKeyTracker()
        assertEquals(ShutterKeyDecision.CONSUME, keys.event(true, "same", 66, 0, 0, 1000, deviceId = 7))
        keys.clearDevice(8)
        assertEquals(ShutterKeyDecision.SHORT_PRESS, keys.event(true, "same", 66, 1, 0, 1060, deviceId = 7))
        keys.event(true, "same", 66, 0, 0, 2000, deviceId = 7)
        keys.clearDevice(7)
        assertEquals(ShutterKeyDecision.CONSUME, keys.event(true, "same", 66, 1, 0, 2060, deviceId = 7))

        val clicks = ShutterClickMachine()
        val c = context(id = 7)
        val first = clicks.press("7:same", 66, 3000, c) as ShutterPress.Wait
        clicks.clearDevice(8)
        assertNull(clicks.expire("7:same", first.token, 3399))
        assertEquals(c, clicks.expire("7:same", first.token, 3400))
        val second = clicks.press("7:same", 66, 4000, c) as ShutterPress.Wait
        clicks.clearDevice(7)
        assertNull(clicks.expire("7:same", second.token, 4400))
        val newId = context(id = 9)
        val replacement = clicks.press("9:same", 66, 4500, newId) as ShutterPress.Wait
        assertEquals(newId, clicks.expire("9:same", replacement.token, 4900))
    }
    @Test fun `actual P007 supports both volume keys without internal key interception`() {
        fun check(external: Boolean, code: Int) = isSelfieTrigger(external, false, 0x0a5c, 1, "ITFIT Selfie P007", "bluetooth-p007", null, code)
        assertTrue(check(true, 24)); assertTrue(check(true, 25)); assertFalse(check(false, 24)); assertFalse(check(false, 25))
        assertFalse(isSelfieTrigger(true, true, 0x0a5c, 1, "ITFIT Selfie P007", "virtual", null, 25))
    }
    @Test fun `other external volume remotes require their explicit descriptor`() {
        fun check(selected: String?) = isSelfieTrigger(true, false, 10, 20, "Other", "other-remote", selected, 25)
        assertFalse(check(null)); assertFalse(check("different")); assertTrue(check("other-remote"))
        assertFalse(isSelfieTrigger(false, false, 10, 20, "Other", "other-remote", "other-remote", 25))
    }
    @Test fun `only a complete short uncancelled press is admitted`() {
        val keys=ShutterKeyTracker()
        fun event(action:Int,repeat:Int,time:Long,canceled:Boolean=false)=keys.event(true,"p007",25,action,repeat,time,canceled)
        assertEquals(ShutterKeyDecision.PASS,keys.event(false,"internal",25,0,0,100))
        assertEquals(ShutterKeyDecision.CONSUME,event(1,0,100))
        assertEquals(ShutterKeyDecision.CONSUME,event(0,0,200))
        assertEquals(ShutterKeyDecision.CONSUME,event(0,1,250))
        assertEquals(ShutterKeyDecision.SHORT_PRESS,event(1,0,300))
        assertEquals(ShutterKeyDecision.CONSUME,event(0,0,400))
        assertEquals(ShutterKeyDecision.CONSUME,event(1,0,901))
        assertEquals(ShutterKeyDecision.CONSUME,event(0,0,1000))
        assertEquals(ShutterKeyDecision.CONSUME,event(1,0,1050,true))
        assertEquals(ShutterKeyDecision.CONSUME,event(0,0,1100));keys.clear()
        assertEquals(ShutterKeyDecision.CONSUME,event(1,0,1150))
    }
    @Test fun `dedicated camera and media keys require explicit selection`() {
        for (key in listOf(27, 80, 85, 126, 127, 130)) {
            assertFalse(isSelfieTrigger(true, false, 7, 8, "External", "external", null, key))
            assertFalse(isSelfieTrigger(true, false, 7, 8, "External", "external", "other", key))
            assertTrue(isSelfieTrigger(true, false, 7, 8, "External", "external", "external", key))
            assertFalse(isSelfieTrigger(false, false, 7, 8, "Internal", "internal", null, key))
        }
        assertFalse(isSelfieTrigger(true, false, 7, 8, "External", "external", null, 29))
    }

    @Test fun `keyboard-like shutter keys require explicit external selection`() {
        for (key in listOf(23,62,66,160)) {
            assertFalse(isSelfieTrigger(true,false,7,8,"External","external",null,key))
            assertFalse(isSelfieTrigger(true,false,7,8,"External","external","other",key))
            assertTrue(isSelfieTrigger(true,false,7,8,"External","external","external",key))
            assertFalse(isSelfieTrigger(false,false,7,8,"Built in","external","external",key))
            assertFalse(isSelfieTrigger(true,true,7,8,"Virtual","external","external",key))
        }
    }

    private fun context(phase:String="recording",link:Any=Any(),id:Int=7,descriptor:String="p007",recording:String?="r1") =
        ShutterContext(phase,recording,"project",ShutterLink(link),id,descriptor)

    @Test fun `single waits for full window and double never emits a single`() {
        val machine=ShutterClickMachine();val context=context()
        val wait=machine.press("p007",25,1000,context) as ShutterPress.Wait
        assertNull(machine.expire("p007",wait.token,1399))
        assertEquals(ShutterPress.Double(context),machine.press("p007",25,1120,context))
        assertNull(machine.expire("p007",wait.token,1500))
        assertEquals(ShutterPress.Ignored,machine.press("p007",25,1180,context))
        assertNull(machine.expire("p007",wait.token,2000))
    }

    @Test fun `independent devices and events after the window remain independent`() {
        val machine=ShutterClickMachine();val a=context();val b=context(id=8,descriptor="other")
        val first=machine.press("p007",25,1000,a) as ShutterPress.Wait
        val second=machine.press("other",27,1100,b) as ShutterPress.Wait
        assertEquals(a,machine.expire("p007",first.token,1400))
        assertEquals(b,machine.expire("other",second.token,1500))
        val previous=machine.press("p007",25,2000,a) as ShutterPress.Wait
        val late=machine.press("p007",25,2401,a) as ShutterPress.Wait
        assertEquals(a,late.overdue);assertNull(machine.expire("p007",previous.token,2500))
        assertEquals(a,machine.expire("p007",late.token,2801))
    }

    @Test fun `bounce and alternate codes do not falsely create a double`() {
        val machine=ShutterClickMachine();val context=context()
        val wait=machine.press("p007",24,1000,context) as ShutterPress.Wait
        assertEquals(ShutterPress.Ignored,machine.press("p007",24,1050,context))
        assertEquals(ShutterPress.Ignored,machine.press("p007",25,1120,context))
        assertEquals(context,machine.expire("p007",wait.token,1400))
        assertEquals(ShutterPress.Ignored,machine.press("p007",24,-1,context))
    }

    @Test fun `connection, device, and recording changes invalidate pending clicks`() {
        val oldLink=Any();val context=context(link=oldLink)
        for (changed in listOf(context.copy(link=ShutterLink(Any())),context.copy(deviceId=9),context.copy(recordingId="r2"),context.copy(phase="paused"))) {
            val machine=ShutterClickMachine();val pending=machine.press("p007",25,1000,context) as ShutterPress.Wait
            assertEquals(ShutterPress.Changed,machine.press("p007",25,1120,changed))
            assertNull(machine.expire("p007",pending.token,1400))
        }
        val machine=ShutterClickMachine();val pending=machine.press("p007",25,1000,context) as ShutterPress.Wait
        machine.clear();assertNull(machine.expire("p007",pending.token,1400))
        assertTrue(shutterContextCurrent(context,"recording","r1",false,true,true))
        assertFalse(shutterContextCurrent(context,"recording","r1",false,false,true))
        assertFalse(shutterContextCurrent(context,"recording","r1",false,true,false))
        assertFalse(shutterContextCurrent(context,"recording","r1",true,true,true))
        assertFalse(shutterContextCurrent(context,"paused","r1",false,true,true))
        assertFalse(shutterContextCurrent(context,"recording","r2",false,true,true))
    }

    @Test fun `single starts only before recording and otherwise only scrolls`() {
        for (phase in listOf("idle","ready","partial","failed")) {
            assertEquals(ShutterAction.START,shutterAction(context(phase),ShutterGesture.SINGLE))
            assertEquals(ShutterAction.NONE,shutterAction(context(phase),ShutterGesture.DOUBLE))
        }
        for (phase in listOf("recording","paused")) {
            assertEquals(ShutterAction.SCROLL,shutterAction(context(phase),ShutterGesture.SINGLE))
            assertEquals(ShutterAction.CAPTURE_TOGGLE,shutterAction(context(phase),ShutterGesture.DOUBLE))
        }
        for (phase in listOf("unknown","preparing","finalizing")) for (gesture in ShutterGesture.entries)
            assertEquals(ShutterAction.NONE,shutterAction(context(phase),gesture))
    }
}
