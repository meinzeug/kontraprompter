package android.util
/** JVM-fixture shim, never part of the APK. */
object Base64 {
    const val URL_SAFE=8;const val NO_PADDING=1;const val NO_WRAP=2
    fun encodeToString(input:ByteArray,flags:Int):String { check(flags==(URL_SAFE or NO_PADDING or NO_WRAP));return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(input) }
}
