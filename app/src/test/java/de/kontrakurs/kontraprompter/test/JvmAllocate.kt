package de.kontrakurs.kontraprompter.test

/** JVM-only fixture allocation; avoid a sun.* compile-time dependency on Android bootclasspath. */
internal fun <T> jvmAllocate(type: Class<T>): T {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return type.cast(unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))!!
}
