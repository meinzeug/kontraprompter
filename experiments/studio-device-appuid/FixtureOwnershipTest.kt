package de.kontrakurs.kontraprompter.cloud

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FixtureOwnershipTest {
    @Test fun `every marker must belong to same fixture identity before any cleanup`() {
        val id=UUID.randomUUID().toString();val other=UUID.randomUUID().toString()
        assertTrue(fixtureMarkersOwned(id,listOf(FixtureMarkerOwner("identity.json",id,1),FixtureMarkerOwner("identity.json.new",id,1))))
        assertFalse(fixtureMarkersOwned(id,listOf(FixtureMarkerOwner("identity.json",id,1),FixtureMarkerOwner("identity.json.new",other,1))))
        assertFalse(fixtureMarkersOwned(id,listOf(FixtureMarkerOwner("other",id,1))))
        assertFalse(fixtureMarkersOwned(id,listOf(FixtureMarkerOwner("identity.json",id,2))))
        assertFalse(fixtureMarkersOwned(id,listOf(FixtureMarkerOwner("identity.json",id,1),FixtureMarkerOwner("identity.json",id,1))))
    }

    @Test fun `two fixture instances cannot hold same file lock`() {
        val directory=Files.createTempDirectory("studio-fixture-lock-").toFile();val file=directory.resolve("fixture.lock")
        try {
            val first=checkNotNull(FixtureExclusiveLock.tryAcquire(file))
            assertNull(FixtureExclusiveLock.tryAcquire(file))
            first.close()
            val next=checkNotNull(FixtureExclusiveLock.tryAcquire(file));next.close()
        } finally { check(file.delete());check(directory.delete()) }
    }
}
