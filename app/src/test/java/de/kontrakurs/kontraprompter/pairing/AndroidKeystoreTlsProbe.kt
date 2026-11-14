package de.kontrakurs.kontraprompter.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import de.kontrakurs.kontraprompter.network.PinnedPairingTls
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shell-context fixture: creates and removes only its two random test aliases. */
fun main() {
    // A plain shell app_process does not run Android's normal provider setup.
    // Try only a publicly callable framework entry point; do not change hidden-API policy.
    if (Security.getProvider("AndroidKeyStore") == null) {
        val installed = sequenceOf("android.security.keystore2.AndroidKeyStoreProvider", "android.security.keystore.AndroidKeyStoreProvider")
            .any { name -> runCatching { Class.forName(name).getMethod("install").invoke(null); Security.getProvider("AndroidKeyStore") != null }.getOrDefault(false) }
        check(installed) { "AndroidKeyStore provider unavailable in shell context" }
    }
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val prefix = "kp-shell-tls-fixture-${UUID.randomUUID()}-"
    val aliases = listOf(prefix + "camera", prefix + "prompter")
    check(aliases.none(store::containsAlias))
    // If the process is externally killed, the operator can identify only its own aliases.
    println("TEST_ALIASES=${aliases.joinToString(",")}")
    try {
        for (alias in aliases) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            generator.generateKeyPair()
        }
        for (alias in aliases) {
            val privateKey = store.getKey(alias, null)
            check(privateKey.encoded == null && privateKey.format == null)
        }
        fun pin(alias: String) = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).publicKey.encoded)
        val camera = PinnedPairingTls(store, null, pin(aliases[1]), aliases[0])
        val prompter = PinnedPairingTls(store, null, pin(aliases[0]), aliases[1])
        val loopback = InetAddress.getByName("127.0.0.1")
        val worker = Executors.newSingleThreadExecutor()
        try {
            camera.listen(loopback, 0, 10000).use { listener ->
                val accepted = worker.submit<Boolean> {
                    listener.accept().use { socket ->
                        check(socket.session.protocol == "TLSv1.3")
                        check(socket.inputStream.read() == 0x41)
                        socket.outputStream.write(0x42); socket.outputStream.flush()
                        check(socket.inputStream.read() == 0x43)
                    }
                    true
                }
                prompter.connect(loopback, listener.localPort, 10000).use { socket ->
                    check(socket.session.protocol == "TLSv1.3")
                    socket.outputStream.write(0x41); socket.outputStream.flush()
                    check(socket.inputStream.read() == 0x42)
                    socket.outputStream.write(0x43); socket.outputStream.flush()
                }
                check(accepted.get(12, TimeUnit.SECONDS))
            }
            fun pinError(error: Throwable?): Boolean = generateSequence(error) { it.cause }
                .any { it.message?.contains("Gegenstelle nicht gepinnt") == true }
            fun rejected(client: PinnedPairingTls, rejectedByServer: Boolean) {
                camera.listen(loopback, 0, 10000).use { listener ->
                    val accepted = worker.submit<Throwable?> { runCatching { listener.accept().use { } }.exceptionOrNull() }
                    val clientError = runCatching { client.connect(loopback, listener.localPort, 10000).use { } }.exceptionOrNull()
                    check(clientError != null)
                    val serverError = accepted.get(12, TimeUnit.SECONDS)
                    check(serverError != null)
                    check(pinError(if (rejectedByServer) serverError else clientError))
                }
            }
            rejected(PinnedPairingTls(store, null, ByteArray(32), aliases[1]), false)
            rejected(PinnedPairingTls(store, null, pin(aliases[0]), aliases[0]), true)
        } finally { worker.shutdownNow() }
    } finally {
        // The names were generated inside this process and confirmed absent before creation.
        aliases.forEach { alias -> if (store.containsAlias(alias)) store.deleteEntry(alias) }
        check(aliases.none(store::containsAlias))
    }
    println("ANDROID_KEYSTORE_TLS13_OK NEGATIVE_PINS_OK NON_EXPORTABLE_OK CLEANUP_OK")
}
