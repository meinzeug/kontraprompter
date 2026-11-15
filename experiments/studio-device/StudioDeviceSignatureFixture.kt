package de.kontrakurs.kontraprompter.cloud

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Separate JVM test process. Ephemeral software key only, never an Android identity. */
fun main(args: Array<String>) {
    require(args.size == 1)
    val device = args[0]
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val pub = pair.public.encoded
    val scope = listOf("projects:read", "recordings:upload")
    val base64 = Base64.getEncoder()
    println("PUB\t${base64.encodeToString(pub)}")
    while (true) {
        val fields = readlnOrNull()?.split('\t') ?: break
        val message = when (fields.firstOrNull()) {
            "ENROLL" -> { require(fields.size == 4); StudioDeviceTranscript.enrollment(device,fields[1],fields[2],pub,scope,fields[3]) }
            "LOGIN" -> { require(fields.size == 3); StudioDeviceTranscript.login(device,fields[1],fields[2],pub,scope) }
            "STOP" -> break
            else -> error("Unknown test command")
        }
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(message) }
        println("SIG\t${base64.encodeToString(signer.sign())}")
    }
}
