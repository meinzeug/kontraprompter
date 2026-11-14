package de.kontrakurs.kontraprompter.pairing

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

/** Separate identity for phone pairing; it cannot replace the cloud camera key. */
internal class RelayDeviceIdentity private constructor(val id: String, publicKeyDer: ByteArray, private val key: PrivateKey) {
    private val publicKey = publicKeyDer.copyOf()
    val publicKeyDer: ByteArray get() = publicKey.copyOf()
    /** Alias is only a selector; the Android Keystore retains the non-exportable private key. */
    fun tlsKeyAlias(): String = PREFIX + id
    fun signPairing(owner: String, pairId: String, challengeId: String, nonce: String, transcriptHash: String, cameraId: String, cameraKey: ByteArray, prompterId: String, prompterKey: ByteArray, role: RelayRole): ByteArray {
        require(if (role == RelayRole.CAMERA) id == cameraId && publicKeyDer.contentEquals(cameraKey) else id == prompterId && publicKeyDer.contentEquals(prompterKey))
        return sign(relayPairingProofMessage(owner, pairId, challengeId, nonce, transcriptHash, cameraId, cameraKey, prompterId, prompterKey, role))
    }
    fun signSession(owner: String, sessionHash: String, pairId: String, role: RelayRole, roomId: String, tokenHash: String, challengeId: String, nonce: String): ByteArray =
        sign(relaySessionProofMessage(owner, sessionHash, pairId, role, id, roomId, tokenHash, challengeId, nonce))
    private fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(bytes); sign() }

    companion object {
        private const val PREFIX = "videomaker-relay-phone-v1-"
        private val lock = Any()
        fun loadOrCreate(context: Context): RelayDeviceIdentity = synchronized(lock) {
            val dir = File(context.applicationContext.filesDir, "relay-pairing")
            check((dir.isDirectory || dir.mkdirs()) && dir.isDirectory)
            sync(dir.parentFile!!)
            val markerFile = File(dir, "identity.json"); val atomic = AtomicFile(markerFile)
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!markerFile.exists() && !File(markerFile.path + ".bak").exists()) {
                if (keys.aliases().toList().any { it.startsWith(PREFIX) } || (dir.listFiles() ?: throw RelayIdentityRecoveryRequiredException()).isNotEmpty()) throw RelayIdentityRecoveryRequiredException()
                write(atomic, dir, JSONObject().put("version", 1).put("id", UUID.randomUUID().toString()).put("state", "creating"))
            }
            val marker = try {
                val text = atomic.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                    val result = StringBuilder(); val block = CharArray(512)
                    while (true) { val count = reader.read(block); if (count < 0) break; check(result.length + count <= 4096); result.append(block, 0, count) }; result.toString()
                }
                JSONObject(text)
            } catch (_: Exception) { throw RelayIdentityRecoveryRequiredException() }
            val id = try { marker.getString("id").also { check(marker.getInt("version") == 1 && UUID.fromString(it).toString() == it) } } catch (_: Exception) { throw RelayIdentityRecoveryRequiredException() }
            val state = marker.optString("state", ""); if (state !in setOf("creating", "ready")) throw RelayIdentityRecoveryRequiredException()
            val alias = PREFIX + id
            if (state == "creating" && !keys.containsAlias(alias)) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
                generator.generateKeyPair()
            }
            val publicKey = keys.getCertificate(alias)?.publicKey?.encoded ?: throw RelayIdentityRecoveryRequiredException()
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(publicKey).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (state == "creating") write(atomic, dir, marker.put("state", "ready").put("fingerprint", fingerprint))
            else if (marker.optString("fingerprint", "") != fingerprint) throw RelayIdentityRecoveryRequiredException()
            val privateKey = keys.getKey(alias, null) as? PrivateKey ?: throw RelayIdentityRecoveryRequiredException()
            RelayDeviceIdentity(id, publicKey, privateKey)
        }
        private fun write(atomic: AtomicFile, dir: File, marker: JSONObject) {
            val out = atomic.startWrite(); try { out.write(marker.toString().toByteArray(Charsets.UTF_8)); out.fd.sync(); atomic.finishWrite(out) } catch (error: Exception) { atomic.failWrite(out); throw error }; sync(dir)
        }
        private fun sync(path: File) { val fd = Os.open(path.absolutePath, OsConstants.O_RDONLY, 0); try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) } }
    }
}
internal class RelayIdentityRecoveryRequiredException : IllegalStateException("Telefonidentität nicht verfügbar. Bestehende Kopplungen bleiben unverändert; erneute Paarung ist erforderlich.")
