package android.os
object SystemClock {fun elapsedRealtime()=System.nanoTime()/1_000_000}
object Build {const val MODEL="fixture"}
