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
            val keyExists = keyStore.containsAlias(ALIAS)
            if (!markerExists) {
                check(!keyExists) { "Camera identity is missing; explicit re-registration is required" }
                writeMarker(atomic, dir, JSONObject().put("version", 2).put("id", UUID.randomUUID().toString()).put("state", "creating"))
            }
            val text = atomic.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder(); val buffer = CharArray(1024)
                while (true) { val count = reader.read(buffer); if (count < 0) break; check(result.length + count <= 4096); result.append(buffer, 0, count) }
                result.toString()
            }
            val marker = JSONObject(text)
            check(marker.getInt("version") == 2)
            val id = marker.getString("id").also { check(UUID.fromString(it).toString() == it) }
            val state = marker.getString("state"); check(state == "creating" || state == "ready")
            if (state == "creating" && !keyStore.containsAlias(ALIAS)) {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                generator.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
                generator.generateKeyPair()
            }
            val publicKey = keyStore.getCertificate(ALIAS)?.publicKey?.encoded ?: error("Camera device public key is unavailable")
            if (state == "creating") writeMarker(atomic, dir, marker.put("state", "ready").put("fingerprint", digest(publicKey)))
            else check(marker.getString("fingerprint") == digest(publicKey)) { "Camera device key has changed; explicit re-registration is required" }
            val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey ?: error("Camera device private key is unavailable")
            CameraDeviceIdentity(id, publicKey, privateKey)
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
