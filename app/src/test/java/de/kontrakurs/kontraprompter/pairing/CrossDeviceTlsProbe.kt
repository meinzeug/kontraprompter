package de.kontrakurs.kontraprompter.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import de.kontrakurs.kontraprompter.network.PinnedPairingTls
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Synthetic shell-only cross-phone test. Pin transfer and tunnel are supplied by the test operator. */
object CrossDeviceTlsProbe {
    @JvmStatic fun main(args: Array<String>) {
        try { crossDeviceProbe(args) } catch (error: Throwable) { error.printStackTrace(); throw error }
    }
}

private fun crossDeviceProbe(args: Array<String>) {
    require(args.size in setOf(3, 5))
    val mode=args[0]; val id=args[1]; val role=args[2]
    require(UUID.fromString(id).toString()==id && role in setOf("camera", "prompter"))
    val alias="kp-cross-tls-fixture-$id-$role"
    ensureTestProvider()
    val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    when (mode) {
        "create" -> {
            require(args.size==3);check(!store.containsAlias(alias))
            try {
                val generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore")
                generator.initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_NONE).build())
                generator.generateKeyPair()
                check(store.getKey(alias,null).encoded==null)
                val pin=MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).publicKey.encoded).joinToString(""){"%02x".format(it.toInt() and 255)}
                println("CROSS_ALIAS=$alias CROSS_PIN=$pin")
            } catch (error:Throwable) { if(store.containsAlias(alias))store.deleteEntry(alias);throw error }
        }
        "cleanup" -> {
            require(args.size==3);val present=store.containsAlias(alias);if(present)store.deleteEntry(alias)
            check(!store.containsAlias(alias));println("CROSS_CLEANUP_OK=$alias WAS_PRESENT=$present")
        }
        "host", "client" -> {
            require(args.size==5 && ((mode=="host"&&role=="camera")||(mode=="client"&&role=="prompter")))
            check(store.containsAlias(alias));val port=args[3].toInt();require(port in 1..65535)
            val expected=args[4];require(expected.matches(Regex("[a-f0-9]{64}")))
            val pin=ByteArray(32){i->expected.substring(i*2,i*2+2).toInt(16).toByte()}
            val tls=PinnedPairingTls(store,null,pin,alias);val loopback=InetAddress.getByName("127.0.0.1")
            if(mode=="host")tls.listen(loopback,port,15000).use { listener ->
                println("CROSS_LISTENING=$port")
                listener.accept().use { socket ->
                    check(socket.session.protocol=="TLSv1.3")
                    val input=DataInputStream(socket.inputStream);val output=DataOutputStream(socket.outputStream)
                    check(input.readUTF()=="cross-phone-tls-fixture-v1");output.writeUTF("camera-confirmed");output.flush()
                    check(input.readUTF()=="prompter-confirmed")
                }
            } else tls.connect(loopback,port,15000).use { socket ->
                check(socket.session.protocol=="TLSv1.3")
                val input=DataInputStream(socket.inputStream);val output=DataOutputStream(socket.outputStream)
                output.writeUTF("cross-phone-tls-fixture-v1");output.flush();check(input.readUTF()=="camera-confirmed")
                output.writeUTF("prompter-confirmed");output.flush()
            }
            println("CROSS_DEVICE_TLS13_OK=$role")
        }
        else -> error("Unknown fixture mode")
    }
}
