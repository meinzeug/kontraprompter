package de.kontrakurs.kontraprompter.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/** Hardware fixture only: temporary shell-UID key, no app data or production alias. */
fun main(args: Array<String>) {
    require(args.size == 1)
    val device = args[0]
    val alias = "videomaker-studio-test-${UUID.randomUUID()}"
    // Shell app_process does not install the framework provider automatically.
    Class.forName("android.security.keystore2.AndroidKeyStoreProvider").getMethod("install").invoke(null)
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    check(!store.containsAlias(alias))
    // Recovery after an interrupted fixture can target this exact test alias.
    System.err.println("FIXTURE_ALIAS\t$alias")
    try {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256).build())
        val pair = generator.generateKeyPair()
        check(pair.private.encoded == null)
        val scope = listOf("projects:read", "recordings:upload")
        val base64 = Base64.getEncoder()
        println("PUB\t${base64.encodeToString(pair.public.encoded)}")
        while (true) {
            val fields = readlnOrNull()?.split('\t') ?: break
            val message = when(fields.firstOrNull()) {
                "ENROLL" -> { require(fields.size == 4); StudioDeviceTranscript.enrollment(device,fields[1],fields[2],pair.public.encoded,scope,fields[3]) }
                "LOGIN" -> { require(fields.size == 3); StudioDeviceTranscript.login(device,fields[1],fields[2],pair.public.encoded,scope) }
                "STOP" -> break
                else -> error("Unknown fixture command")
            }
            val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(message) }
            println("SIG\t${base64.encodeToString(signer.sign())}")
        }
    } finally {
        // Only this freshly generated, collision-checked test alias is touched.
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        check(!store.containsAlias(alias))
        println("FIXTURE_CLEANUP_OK")
    }
}
