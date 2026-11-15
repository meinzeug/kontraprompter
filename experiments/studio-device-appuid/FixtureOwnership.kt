package de.kontrakurs.kontraprompter.cloud

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

internal data class FixtureMarkerOwner(val name: String, val id: String, val version: Int)

internal fun fixtureMarkersOwned(id: String, markers: List<FixtureMarkerOwner>): Boolean {
    if (runCatching { UUID.fromString(id).toString() != id }.getOrDefault(true)) return false
    val names = setOf("identity.json", "identity.json.bak", "identity.json.new")
    return markers.map { it.name }.toSet().size == markers.size && markers.all { it.name in names && it.id == id && it.version == 1 }
}

internal class FixtureExclusiveLock private constructor(private val file: RandomAccessFile, private val lock: FileLock) : Closeable {
    override fun close() { try { lock.release() } finally { file.close() } }
    companion object {
        fun tryAcquire(path: File): FixtureExclusiveLock? {
            val file = RandomAccessFile(path,"rw")
            try {
                val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) { file.close(); return null }
                return FixtureExclusiveLock(file,lock)
            } catch (error: Throwable) { file.close(); throw error }
        }
    }
}
