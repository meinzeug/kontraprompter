package de.kontrakurs.kontraprompter

/** Never treats the built-in or virtual keyboard as a recording remote. */
internal fun isSelfieTrigger(
    external: Boolean, virtual: Boolean, vendor: Int, product: Int, name: String,
    descriptor: String, selectedDescriptor: String?, keyCode: Int,
): Boolean {
    if (!external || virtual || descriptor.isBlank()) return false
    // Android KeyEvent constants; dedicated camera/focus/media trigger keys from external devices.
    if (keyCode in setOf(27, 80, 85, 126, 127, 130)) return true
    // Keyboard-like buttons are accepted only from an explicitly selected external device.
    if (keyCode in setOf(23, 62, 66, 160)) return !selectedDescriptor.isNullOrBlank() && descriptor == selectedDescriptor
    if (keyCode !in setOf(24, 25)) return false
    val knownP007 = vendor == 0x0a5c && product == 0x0001 && name.equals("ITFIT Selfie P007", ignoreCase = true)
    return knownP007 || (!selectedDescriptor.isNullOrBlank() && descriptor == selectedDescriptor)
}

internal enum class ShutterKeyDecision { PASS, SHORT_PRESS, CONSUME }

/** Only a completed short press counts; repeats, long holds and orphan releases do not. */
internal class ShutterKeyTracker(private val maximumPressMs: Long = 500L) {
    private data class Key(val device: String, val code: Int, val deviceId: Int)
    private val pressed = mutableMapOf<Key, Long>()
    init { require(maximumPressMs in 1..2000) }

    @Synchronized fun event(isTrigger: Boolean, device: String, code: Int, action: Int, repeat: Int, timeMs: Long, canceled: Boolean = false, deviceId: Int = -1): ShutterKeyDecision {
        if (!isTrigger) return ShutterKeyDecision.PASS
        if (device.isBlank() || timeMs < 0L) return ShutterKeyDecision.CONSUME
        val key = Key(device, code, deviceId)
        if (action == 0 && repeat == 0 && key !in pressed && pressed.size < 16) pressed[key] = timeMs
        if (action == 1) {
            val started = pressed.remove(key) ?: return ShutterKeyDecision.CONSUME
            if (canceled) return ShutterKeyDecision.CONSUME
            return if (timeMs >= started && timeMs - started <= maximumPressMs) ShutterKeyDecision.SHORT_PRESS else ShutterKeyDecision.CONSUME
        }
        if (action == 3) pressed.remove(key)
        return ShutterKeyDecision.CONSUME
    }

    @Synchronized fun clear() { pressed.clear() }
    @Synchronized fun clearDevice(deviceId: Int) { pressed.keys.removeAll { it.deviceId == deviceId } }
}

/** Reference identity makes a disconnect/reconnect invalidate pending clicks. */
internal class ShutterLink(val state: Any) {
    override fun equals(other: Any?): Boolean = other is ShutterLink && other.state === state
    override fun hashCode(): Int = System.identityHashCode(state)
}
internal data class ShutterContext(
    val phase: String, val recordingId: String?, val projectId: String?,
    val link: ShutterLink, val deviceId: Int, val descriptor: String,
)
internal enum class ShutterGesture { SINGLE, DOUBLE }
internal enum class ShutterAction { START, SCROLL, CAPTURE_TOGGLE, NONE }

internal fun shutterContextCurrent(expected: ShutterContext, phase: String, recordingId: String?, pending: Boolean, linkCurrent: Boolean, deviceCurrent: Boolean): Boolean =
    !pending && linkCurrent && deviceCurrent && phase == expected.phase && recordingId == expected.recordingId

/** The camera's reported state, never the button press itself, determines the action. */
internal fun shutterAction(context: ShutterContext, gesture: ShutterGesture): ShutterAction = when {
    gesture == ShutterGesture.SINGLE && context.phase in setOf("idle", "ready", "partial", "failed") -> ShutterAction.START
    gesture == ShutterGesture.SINGLE && context.phase in setOf("recording", "paused") -> ShutterAction.SCROLL
    gesture == ShutterGesture.DOUBLE && context.phase in setOf("recording", "paused") -> ShutterAction.CAPTURE_TOGGLE
    else -> ShutterAction.NONE
}

internal sealed interface ShutterPress {
    data class Wait(val token: Long, val overdue: ShutterContext? = null) : ShutterPress
    data class Double(val context: ShutterContext) : ShutterPress
    data object Ignored : ShutterPress
    data object Changed : ShutterPress
}

/**
 * A click is delayed until the double-click window closes. Distinct remotes
 * have independent pending clicks. Repeats/bounce never cause a double; a
 * third press immediately after a double cannot become an unexpected single.
 */
internal class ShutterClickMachine(private val windowMs: Long = 400L, private val bounceMs: Long = 80L) {
    private data class Pending(val at: Long, val keyCode: Int, val token: Long, val context: ShutterContext)
    private val pending = mutableMapOf<String, Pending>()
    private data class Cooldown(val until: Long, val deviceId: Int)
    private val cooldown = mutableMapOf<String, Cooldown>()
    private var nextToken = 0L

    init { require(windowMs in 200..1000 && bounceMs in 1 until windowMs) }

    @Synchronized fun press(device: String, keyCode: Int, timeMs: Long, context: ShutterContext): ShutterPress {
        if (device.isBlank() || timeMs < 0L || timeMs > Long.MAX_VALUE - windowMs) return ShutterPress.Ignored
        cooldown.entries.removeAll { it.value.until <= timeMs }
        if (cooldown[device]?.let { timeMs < it.until } == true) return ShutterPress.Ignored
        val old = pending[device]
        if (old != null) {
            val elapsed = timeMs - old.at
            if (elapsed < 0L || elapsed < bounceMs || (old.keyCode != keyCode && elapsed < 250L)) return ShutterPress.Ignored
            if (elapsed < windowMs) {
                pending.remove(device)
                cooldown[device] = Cooldown(timeMs + windowMs, context.deviceId)
                return if (old.context == context) ShutterPress.Double(context) else ShutterPress.Changed
            }
        } else if (pending.size + cooldown.size >= 8) return ShutterPress.Ignored
        nextToken++
        pending[device] = Pending(timeMs, keyCode, nextToken, context)
        return ShutterPress.Wait(nextToken, old?.context)
    }

    @Synchronized fun expire(device: String, token: Long, nowMs: Long): ShutterContext? {
        val current = pending[device] ?: return null
        if (current.token != token || nowMs < current.at || nowMs - current.at < windowMs) return null
        pending.remove(device)
        return current.context
    }

    @Synchronized fun clear() { pending.clear(); cooldown.clear() }
    @Synchronized fun clearDevice(deviceId: Int) {
        pending.entries.removeAll { it.value.context.deviceId == deviceId }
        cooldown.entries.removeAll { it.value.deviceId == deviceId }
    }
}
