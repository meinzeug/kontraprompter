package de.kontrakurs.kontraprompter.video

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class UnverifiedRecordingsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `only nonempty canonical leftover files are offered with no mutation`() {
        val dir = temp.newFolder("recordings")
        val orphan = UUID.randomUUID().toString()
        val verified = UUID.randomUUID().toString()
        val empty = UUID.randomUUID().toString()
        val source = File(dir, "$orphan.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(dir, "$verified.mp4").writeBytes(byteArrayOf(4))
        File(dir, "$empty.mp4").createNewFile()
        File(dir, "untrusted.mp4").writeBytes(byteArrayOf(7))
        val link = UUID.randomUUID().toString()
        Files.createSymbolicLink(File(dir, "$link.mp4").toPath(), source.toPath())
        val entries = unverifiedRecordingsInDirectory(dir, setOf(verified))
        assertEquals(listOf(orphan), entries.map { it.id })
        assertEquals(3L, entries.single().bytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
        assertTrue(File(dir, "$verified.mp4").isFile)
    }
}
