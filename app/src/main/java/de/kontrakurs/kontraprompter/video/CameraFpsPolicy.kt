package de.kontrakurs.kontraprompter.video

/** Experimental, pure selection logic. This is not connected to a camera capturer. */
internal object CameraFpsPolicy {
    data class FpsRange(val min: Int, val max: Int)
    sealed interface Decision {
        data class MinimumRequested(val range: FpsRange) : Decision
        data object UseExistingCapturePolicy : Decision
    }

    /**
     * Choose only an advertised range with exactly the requested minimum. A higher
     * minimum could increase encoder load beyond what has been checked at that rate.
     * If it cannot be met, do not change WebRTC's existing policy.
     * A reported range is a request to AE, not a guarantee of achieved frame rate.
     */
    fun chooseMinimum(ranges: Collection<FpsRange>, minimum: Int): Decision {
        require(minimum > 0)
        val best = ranges.asSequence()
            .filter { it.min == minimum && it.max >= it.min }
            .minWithOrNull(compareBy<FpsRange> { it.min }.thenBy { it.max })
        return best?.let { Decision.MinimumRequested(it) } ?: Decision.UseExistingCapturePolicy
    }
}
