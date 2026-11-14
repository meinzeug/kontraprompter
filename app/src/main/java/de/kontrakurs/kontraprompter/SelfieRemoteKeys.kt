package de.kontrakurs.kontraprompter

/** Never treats the built-in or virtual keyboard as a recording remote. */
internal fun isSelfieTrigger(
    external: Boolean, virtual: Boolean, vendor: Int, product: Int, name: String,
    descriptor: String, selectedDescriptor: String?, keyCode: Int,
): Boolean {
    if (!external || virtual || descriptor.isBlank()) return false
    // Android KeyEvent constants; dedicated camera/focus/media trigger keys from external devices.
    if (keyCode in setOf(27, 80, 85, 126, 127, 130)) return true
    if (keyCode !in setOf(24, 25)) return false
    val knownP007 = vendor == 0x0a5c && product == 0x0001 && name.equals("ITFIT Selfie P007", ignoreCase = true)
    return knownP007 || (!selectedDescriptor.isNullOrBlank() && descriptor == selectedDescriptor)
}

internal enum class ShutterKeyDecision { PASS, TRIGGER, CONSUME }

/** A held button and its release are consumed but cannot trigger a second action. */
internal fun shutterKeyDecision(isTrigger: Boolean, action: Int, repeatCount: Int): ShutterKeyDecision = when {
    !isTrigger -> ShutterKeyDecision.PASS
    action == 0 && repeatCount == 0 -> ShutterKeyDecision.TRIGGER
    action == 0 || action == 1 -> ShutterKeyDecision.CONSUME
    else -> ShutterKeyDecision.PASS
}

/** Drops immediate duplicate key downs from the same remote, but permits later presses. */
internal class ShutterDebounce(private val intervalMs: Long = 250L) {
    private var previousDevice: String? = null
    private var previousTime = 0L
    @Synchronized fun accept(descriptor: String, eventTimeMs: Long): Boolean {
        if (eventTimeMs < 0L || intervalMs < 1L) return false
        if (descriptor == previousDevice && eventTimeMs >= previousTime && eventTimeMs - previousTime < intervalMs) return false
        if (descriptor == previousDevice && eventTimeMs < previousTime) return false
        previousDevice = descriptor; previousTime = eventTimeMs
        return true
    }
}
