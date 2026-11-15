package de.kontrakurs.kontraprompter.network

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyntheticPairBindingFixtureTest {
    private fun keys(curve: String = "secp256r1"): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec(curve)); generateKeyPair()
    }
    private fun proof(f: SyntheticPairBindingFixture, a: SyntheticPairBindingFixture.Attempt, role: SyntheticRole, key: KeyPair): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(checkNotNull(f.message(a, role))); sign() }
    private fun approve(f: SyntheticPairBindingFixture, a: SyntheticPairBindingFixture.Attempt, role: SyntheticRole, key: KeyPair): Boolean =
        f.confirm(a, role, a.pins, true, proof(f, a, role, key))

    @Test fun `both explicitly approved role signatures are needed for trust`() {
        val anchor = SyntheticAnchor(); val f = SyntheticPairBindingFixture(anchor); val camera = keys(); val prompter = keys(); val id = UUID.randomUUID().toString()
        val a = f.begin(id, camera.public.encoded, prompter.public.encoded)
        assertNull(f.commit(a))
        assertFalse(f.confirm(a, SyntheticRole.CAMERA, a.pins, false, proof(f, a, SyntheticRole.CAMERA, camera)))
        assertTrue(approve(f, a, SyntheticRole.CAMERA, camera)); assertNull(f.commit(a))
        assertTrue(approve(f, a, SyntheticRole.PROMPTER, prompter))
        val record = checkNotNull(f.commit(a)); assertTrue(f.permits(record)); assertNull(f.commit(a))
    }

    @Test fun `swapped identity role key and display are rejected`() {
        val f = SyntheticPairBindingFixture(SyntheticAnchor()); val camera = keys(); val prompter = keys(); val other = keys()
        val a = f.begin(UUID.randomUUID().toString(), camera.public.encoded, prompter.public.encoded)
        assertFalse(f.confirm(a, SyntheticRole.CAMERA, a.pins, true, proof(f, a, SyntheticRole.CAMERA, other)))
        assertFalse(f.confirm(a, SyntheticRole.PROMPTER, a.pins, true, proof(f, a, SyntheticRole.CAMERA, camera)))
        assertFalse(f.confirm(a, SyntheticRole.CAMERA, SyntheticPins(a.pins.prompter, a.pins.camera), true, proof(f, a, SyntheticRole.CAMERA, camera)))
        assertThrows(Exception::class.java) { f.begin(UUID.randomUUID().toString(), camera.public.encoded, camera.public.encoded) }
        assertThrows(Exception::class.java) { f.begin(UUID.randomUUID().toString(), keys("secp384r1").public.encoded, prompter.public.encoded) }
    }

    @Test fun `replayed approvals from prior attempt fail on fresh challenge`() {
        val f = SyntheticPairBindingFixture(SyntheticAnchor()); val camera = keys(); val prompter = keys(); val id = UUID.randomUUID().toString()
        val old = f.begin(id, camera.public.encoded, prompter.public.encoded)
        val oldCameraProof = proof(f, old, SyntheticRole.CAMERA, camera)
        assertTrue(f.confirm(old, SyntheticRole.CAMERA, old.pins, true, oldCameraProof)); f.disconnect()
        val next = f.begin(id, camera.public.encoded, prompter.public.encoded)
        assertFalse(f.confirm(next, SyntheticRole.CAMERA, next.pins, true, oldCameraProof))
        assertFalse(f.confirm(old, SyntheticRole.CAMERA, old.pins, true, oldCameraProof))
        assertTrue(approve(f, next, SyntheticRole.CAMERA, camera)); assertTrue(approve(f, next, SyntheticRole.PROMPTER, prompter)); assertNotNull(f.commit(next))
    }

    @Test fun `disconnect before commit invalidates both completed proofs`() {
        val f = SyntheticPairBindingFixture(SyntheticAnchor()); val camera = keys(); val prompter = keys()
        val a = f.begin(UUID.randomUUID().toString(), camera.public.encoded, prompter.public.encoded)
        assertTrue(approve(f, a, SyntheticRole.CAMERA, camera)); assertTrue(approve(f, a, SyntheticRole.PROMPTER, prompter))
        f.disconnect(); assertNull(f.commit(a)); assertNull(f.message(a, SyntheticRole.CAMERA))
    }

    @Test fun `revocation fences old snapshot across simulated restart and rejects reused pair ID`() {
        val anchor = SyntheticAnchor(); val f = SyntheticPairBindingFixture(anchor); val camera = keys(); val prompter = keys(); val id = UUID.randomUUID().toString()
        val a = f.begin(id, camera.public.encoded, prompter.public.encoded)
        assertTrue(approve(f, a, SyntheticRole.CAMERA, camera)); assertTrue(approve(f, a, SyntheticRole.PROMPTER, prompter))
        val before = checkNotNull(f.commit(a)); assertTrue(f.permits(before))
        val revoked = checkNotNull(f.revoke(id)); assertEquals(2L, revoked.epoch); assertTrue(revoked.revoked)
        val restarted = SyntheticPairBindingFixture(anchor)
        assertFalse(restarted.permits(before)); assertFalse(restarted.permits(revoked))
        val reloadedAuthority = SyntheticPairBindingFixture(SyntheticAnchor(listOf(revoked)))
        assertFalse(reloadedAuthority.permits(before)) // synthetic durable reload of the newer authority state
        assertThrows(IllegalArgumentException::class.java) { restarted.begin(id, camera.public.encoded, prompter.public.encoded) }
    }

    @Test fun `restoring the independent anchor too can revive old trust and is not solved`() {
        val anchor = SyntheticAnchor(); val f = SyntheticPairBindingFixture(anchor); val camera = keys(); val prompter = keys(); val id = UUID.randomUUID().toString()
        val a = f.begin(id, camera.public.encoded, prompter.public.encoded)
        assertTrue(approve(f, a, SyntheticRole.CAMERA, camera)); assertTrue(approve(f, a, SyntheticRole.PROMPTER, prompter))
        val before = checkNotNull(f.commit(a)); assertNotNull(f.revoke(id)); assertFalse(f.permits(before))
        val restoredAnchor = SyntheticAnchor(listOf(before)) // synthetic simultaneous rollback
        assertTrue(SyntheticPairBindingFixture(restoredAnchor).permits(before))
    }

    @Test fun `input key arrays are copied before later mutation`() {
        val anchor = SyntheticAnchor(); val f = SyntheticPairBindingFixture(anchor); val camera = keys(); val prompter = keys()
        val aBytes = camera.public.encoded; val bBytes = prompter.public.encoded
        val a = f.begin(UUID.randomUUID().toString(), aBytes, bBytes); aBytes.fill(0); bBytes.fill(0)
        assertTrue(approve(f, a, SyntheticRole.CAMERA, camera)); assertTrue(approve(f, a, SyntheticRole.PROMPTER, prompter))
        assertTrue(f.permits(checkNotNull(f.commit(a))))
    }

    @Test fun `revocation before delayed connection admission rejects the old binding`() {
        val anchor = SyntheticAnchor(); val f = SyntheticPairBindingFixture(anchor); val camera = keys(); val prompter = keys(); val id = UUID.randomUUID().toString()
        val a = f.begin(id, camera.public.encoded, prompter.public.encoded)
        assertTrue(approve(f, a, SyntheticRole.CAMERA, camera)); assertTrue(approve(f, a, SyntheticRole.PROMPTER, prompter))
        val old = checkNotNull(f.commit(a)); val waiting = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> { waiting.countDown(); check(release.await(5, TimeUnit.SECONDS)); f.permits(old) }
            assertTrue(waiting.await(5, TimeUnit.SECONDS)); assertNotNull(f.revoke(id)); release.countDown()
            assertFalse(result.get(5, TimeUnit.SECONDS))
        } finally { release.countDown(); executor.shutdownNow() }
    }

}
