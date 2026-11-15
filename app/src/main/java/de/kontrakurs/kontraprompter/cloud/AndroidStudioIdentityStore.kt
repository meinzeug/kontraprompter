package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.AlgorithmParameters
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.UUID

/** Dedicated alias namespace; callers must keep this unconnected until enrollment is implemented. */
internal class AndroidStudioIdentityStore(context: Context) : StudioIdentityStore {
    private val dir = File(context.applicationContext.filesDir, "studio-device-auth")
    private val file = File(dir, "identity.json")
    private val atomic = AtomicFile(file)
    private val prefix = "videomaker-studio-device-v1-"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(id: String): String { require(UUID.fromString(id).toString() == id); return prefix + id }

    override fun marker(): StudioIdentityMarker? {
        val backup = File(file.path + ".bak")
        val pending = File(file.path + ".new")
        check(!backup.exists() && !pending.exists()) { "Incomplete identity marker" }
        if (!file.exists()) return null
        val raw = file.inputStream().bufferedReader(Charsets.UTF_8).use { input ->
            val value = StringBuilder(); val buffer = CharArray(512)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                check(value.length + count <= 4096); value.append(buffer, 0, count)
            }
            value.toString()
        }
        val data = JSONObject(raw)
        check(data.getInt("version") == 1)
        val state = data.getString("state")
        return StudioIdentityMarker(data.getString("id"), state, if (data.has("fingerprint")) data.getString("fingerprint") else null)
    }

    override fun hasAnyDeviceKey() = store().aliases().toList().any { it.startsWith(prefix) }

    override fun key(id: String): StudioIdentityKey? {
        val keyStore = store(); val name = alias(id)
        val privateKey = keyStore.getKey(name, null) as? PrivateKey ?: return null
        val publicKey = keyStore.getCertificate(name)?.publicKey as? ECPublicKey ?: return null
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        check(publicKey.params.curve == params.curve && publicKey.params.generator == params.generator && publicKey.params.order == params.order && publicKey.params.cofactor == params.cofactor)
        val der = publicKey.encoded.copyOf()
        return object : StudioIdentityKey {
            override val publicKeyDer get() = der.copyOf()
            override val nonExportable get() = privateKey.encoded == null
            override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
                initSign(privateKey); update(message); sign()
            }
        }
    }

    override fun createKey(id: String): StudioIdentityKey {
        val name = alias(id)
        check(!store().containsAlias(name))
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256).build())
        generator.generateKeyPair()
        return checkNotNull(key(id))
    }

    override fun write(marker: StudioIdentityMarker) {
        check((dir.isDirectory || dir.mkdirs()) && dir.isDirectory)
        syncDirectory(dir.parentFile!!)
        val json = JSONObject().put("version", 1).put("id", marker.id).put("state", marker.state)
        if (marker.fingerprint != null) json.put("fingerprint", marker.fingerprint)
        val out = atomic.startWrite()
        try { out.write(json.toString().toByteArray(Charsets.UTF_8)); out.fd.sync(); atomic.finishWrite(out) }
        catch (error: Exception) { atomic.failWrite(out); throw error }
        syncDirectory(dir)
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
    }
}
