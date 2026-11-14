package de.kontrakurs.kontraprompter.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import de.kontrakurs.kontraprompter.network.PinnedPairingTls
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shell-context fixture: creates and removes only its two random test aliases. */
fun main() {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val prefix = "kp-shell-tls-fixture-${UUID.randomUUID()}-"
    val aliases = listOf(prefix + "camera", prefix + "prompter")
    check(aliases.none(store::containsAlias))
    try {
        for (alias in aliases) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            generator.generateKeyPair()
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
        } finally { worker.shutdownNow() }
        println("ANDROID_KEYSTORE_TLS13_OK")
    } finally {
        // The names were generated inside this process and confirmed absent before creation.
        aliases.forEach { alias -> if (store.containsAlias(alias)) store.deleteEntry(alias) }
    }
}
