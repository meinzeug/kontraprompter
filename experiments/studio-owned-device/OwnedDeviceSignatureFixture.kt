package de.kontrakurs.kontraprompter.cloud

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Synthetic JVM process with an ephemeral software key, never an app identity. */
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
            "ENROLL" -> { require(fields.size == 6); StudioOwnedDeviceTranscript.enrollment(device,fields[1],fields[2],pub,scope,fields[3],fields[4],fields[5]) }
            "LOGIN" -> { require(fields.size == 5); StudioOwnedDeviceTranscript.login(device,fields[1],fields[2],pub,scope,fields[3],fields[4]) }
            "STOP" -> break
            else -> error("Unknown test command")
        }
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(message) }
        println("SIG\t${base64.encodeToString(signature.sign())}")
    }
}
