package de.kontrakurs.kontraprompter.camera

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Creation is explicit; the private ECDSA key remains in Android Keystore. */
internal class CameraDeviceIdentity private constructor(val id: String, val publicKeyDer: ByteArray, private val key: PrivateKey) {
    fun signChallenge(challengeId: String, challenge: String, action: String, bodySha256: String): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(key)
        signer.update(cameraAuthMessage(id, challengeId, challenge, action, bodySha256))
        return signer.sign() // Android and Node use ASN.1 DER ECDSA signatures.
    }

    companion object {
        private const val ALIAS = "videomaker-camera-auth-v2"
        private val lock = Any()

        fun loadOrCreate(context: Context): CameraDeviceIdentity = synchronized(lock) {
            val dir = File(context.applicationContext.filesDir, "remote-camera")
            check((dir.isDirectory || dir.mkdirs()) && dir.isDirectory)
            syncDirectory(dir.parentFile!!)
            val file = File(dir, "identity.json")
            val atomic = AtomicFile(file)
            val markerExists = file.exists() || File(file.path + ".bak").exists()
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val anyDeviceKey = keyStore.aliases().toList().any { it == ALIAS || it.startsWith("$ALIAS-") }
            if (!markerExists) {
                val existing = dir.listFiles() ?: throw CameraIdentityRecoveryRequiredException()
                val priorState = existing.any { it.name == "decisions.json" || it.name.startsWith("decisions-") || it.name.startsWith("identity.json.") || it.name == "identity-archive" }
                if (anyDeviceKey || priorState) throw CameraIdentityRecoveryRequiredException()
                writeMarker(atomic, dir, JSONObject().put("version", 2).put("id", UUID.randomUUID().toString()).put("state", "creating").put("journalState", "initializing"))
            }
            val text = try { atomic.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder(); val buffer = CharArray(1024)
                while (true) { val count = reader.read(buffer); if (count < 0) break; check(result.length + count <= 4096); result.append(buffer, 0, count) }
                result.toString()
            } } catch (_: IllegalStateException) { throw CameraIdentityRecoveryRequiredException() }
            val marker = try { JSONObject(text) } catch (_: Exception) { throw CameraIdentityRecoveryRequiredException() }
            val id = try { marker.getString("id").also { check(marker.getInt("version") == 2 && UUID.fromString(it).toString() == it) } }
                catch (_: Exception) { throw CameraIdentityRecoveryRequiredException() }
            val state = marker.optString("state", "")
            if (state != "creating" && state != "ready") throw CameraIdentityRecoveryRequiredException()
            val alias = marker.optString("alias", ALIAS)
            if (alias != ALIAS && alias != "$ALIAS-$id") throw CameraIdentityRecoveryRequiredException()
            if (state == "creating" && !keyStore.containsAlias(alias)) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
                generator.generateKeyPair()
            }
            val publicKey = keyStore.getCertificate(alias)?.publicKey?.encoded ?: throw CameraIdentityRecoveryRequiredException()
            if (state == "creating") { marker.put("state", "ready").put("fingerprint", digest(publicKey)); writeMarker(atomic, dir, marker) }
            else if (marker.optString("fingerprint", "") != digest(publicKey)) throw CameraIdentityRecoveryRequiredException()
            val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: throw CameraIdentityRecoveryRequiredException()
            // A caller cannot register, poll or acknowledge until the empty journal and the
            // identity marker have both been made durable. A ready marker never permits reset.
            val journalState = marker.optString("journalState", "")
            if (journalState !in setOf("", "initializing", "ready")) throw CameraIdentityRecoveryRequiredException()
            RemoteCameraJournal.ensureInitialized(dir, id, mayInitializeCameraJournal(journalState, state), journalState == "")
            if (journalState != "ready") writeMarker(atomic, dir, marker.put("journalState", "ready"))
            CameraDeviceIdentity(id, publicKey, privateKey)
        }

        /** Call only after explicit user confirmation; old keys, journals and videos are retained. */
        fun createNewAfterConfirmation(context: Context): CameraDeviceIdentity = synchronized(lock) {
          RemoteCameraController.withIdentityReset {
            val app = context.applicationContext
            val dir = File(app.filesDir, "remote-camera")
            check((dir.isDirectory || dir.mkdirs()) && dir.isDirectory)
            syncDirectory(dir.parentFile!!)
            val markerFile = File(dir, "identity.json"); val atomic = AtomicFile(markerFile)
            val markerExists = markerFile.exists() || File(markerFile.path + ".bak").exists()
            val previous = if (markerExists) atomic.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream(); val block = ByteArray(1024)
                while (true) { val count = input.read(block); if (count < 0) break; check(output.size() + count <= 4096); output.write(block, 0, count) }
                output.toByteArray()
            } else null
            val previousId = previous?.let { bytes -> runCatching { JSONObject(bytes.toString(Charsets.UTF_8)).getString("id").also { check(UUID.fromString(it).toString() == it) } }.getOrNull() }
            RemoteCameraJournal.bindLegacyOwner(app, previousId)
            if (previous != null) {
                val archiveDir = File(dir, "identity-archive")
                check((archiveDir.isDirectory || archiveDir.mkdirs()) && archiveDir.isDirectory)
                syncDirectory(dir)
                val archive = AtomicFile(File(archiveDir, "${UUID.randomUUID()}.json")); val out = archive.startWrite()
                try { out.write(previous); out.fd.sync(); archive.finishWrite(out) }
                catch (error: Exception) { archive.failWrite(out); throw error }
                syncDirectory(archiveDir)
            }
            val id = UUID.randomUUID().toString()
            writeMarker(atomic, dir, JSONObject().put("version", 2).put("id", id).put("state", "creating").put("alias", "$ALIAS-$id").put("journalState", "initializing"))
            loadOrCreate(app)
          }
        }

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun writeMarker(atomic: AtomicFile, dir: File, marker: JSONObject) {
            val out = atomic.startWrite()
            try { out.write(marker.toString().toByteArray(Charsets.UTF_8)); out.fd.sync(); atomic.finishWrite(out) }
            catch (error: Exception) { atomic.failWrite(out); throw error }
            syncDirectory(dir)
        }
        private fun syncDirectory(path: File) {
            val fd = Os.open(path.absolutePath, OsConstants.O_RDONLY, 0)
            try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
        }
    }
}

internal class CameraIdentityRecoveryRequiredException : IllegalStateException("Kameraidentität nicht verfügbar. Lokale Aufnahmen bleiben erhalten; eine neue Identität muss ausdrücklich angelegt werden.")
