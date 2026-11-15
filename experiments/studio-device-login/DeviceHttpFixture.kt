package de.kontrakurs.kontraprompter.cloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Test-only loopback transport. Not an HTTPS client and never used by the app. */
internal class DeviceHttpFixture(private val base: String) {
    init {
        val uri=URI(base)
        require(uri.scheme=="http" && uri.host=="127.0.0.1" && uri.port in 1..65535 && uri.rawPath.isNullOrEmpty() && uri.rawQuery==null && uri.rawFragment==null && uri.rawUserInfo==null)
    }
    fun request(path: String,body: JsonObject?,headers: Map<String,String> = emptyMap()): Pair<Int,JsonObject> {
        require(path in setOf("/fixture/enroll/begin","/fixture/enroll/complete","/fixture/login/begin","/fixture/login/complete","/fixture/revoke","/fixture/projects"))
        val connection=URI.create(base+path).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects=false;connection.connectTimeout=2000;connection.readTimeout=4000
            connection.requestMethod=if(body==null) "GET" else "POST"
            for((name,value) in headers)connection.setRequestProperty(name,value)
            if(body!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");val bytes=body.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=2048);connection.setFixedLengthStreamingMode(bytes.size);connection.outputStream.use { it.write(bytes) }}
            val status=connection.responseCode
            val stream=if(status in 200..299)connection.inputStream else connection.errorStream
            val bytes=stream.use { input ->
                val output=java.io.ByteArrayOutputStream();val buf=ByteArray(512)
                while(true){val n=input.read(buf);if(n<0)break;check(output.size()+n<=2048);output.write(buf,0,n)}
                output.toByteArray()
            }
            return status to JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        } finally { connection.disconnect() }
    }
}

private fun json(vararg values: Pair<String,String>)=JsonObject().apply { for((k,v) in values)addProperty(k,v) }
private fun str(value: JsonObject,name: String): String { val element=checkNotNull(value.get(name));check(element.isJsonPrimitive && element.asJsonPrimitive.isString);return element.asString }
private fun number(value: JsonObject,name: String): Long { val element=checkNotNull(value.get(name));check(element.isJsonPrimitive && element.asJsonPrimitive.isNumber && element.asString.matches(Regex("^[0-9]{1,9}$")));return element.asLong }

/** Reproducible JVM integration runner using only a synthetic software key. */
fun main(args: Array<String>) {
    require(args.size==3)
    val http=DeviceHttpFixture(args[0]);val cookie=args[1];val csrf=args[2]
    val browser=mapOf("Origin" to "https://fixture.invalid","Cookie" to "fixture_session=$cookie","X-Fixture-CSRF" to csrf)
    val pair=KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val device=UUID.randomUUID().toString();val encoder=Base64.getUrlEncoder().withoutPadding();val scope=listOf("projects:read","recordings:upload")
    fun sign(bytes: ByteArray)=Signature.getInstance("SHA256withECDSA").run { initSign(pair.private);update(bytes);sign() }
    val (beginStatus,enrollment)=http.request("/fixture/enroll/begin",json("deviceId" to device,"publicKey" to encoder.encodeToString(pair.public.encoded)),browser)
    check(beginStatus==200 && enrollment.keySet()==setOf("challengeId","nonce","ownerHash","scope","expiresInMs"))
    val receivedScope=enrollment.getAsJsonArray("scope")
    check(receivedScope.size()==scope.size && receivedScope.all { it.isJsonPrimitive && it.asJsonPrimitive.isString } && receivedScope.map { it.asString }.toSet()==scope.toSet())
    val id=str(enrollment,"challengeId");val nonce=str(enrollment,"nonce");val owner=str(enrollment,"ownerHash")
    check(number(enrollment,"expiresInMs") in 1..60_000)
    val signature=sign(StudioDeviceTranscript.enrollment(device,id,nonce,pair.public.encoded,scope,owner))
    check(http.request("/fixture/enroll/complete",json("deviceId" to device,"challengeId" to id,"signature" to encoder.encodeToString(signature)),browser).first==200)
    val transport=object:DeviceLoginTransport {
        override fun begin(deviceId: String): DeviceChallenge {
            val (status,value)=http.request("/fixture/login/begin",json("deviceId" to deviceId));check(status==200 && value.keySet()==setOf("challengeId","challengeToken","expiresInMs"))
            return DeviceChallenge(str(value,"challengeId"),str(value,"challengeToken"),number(value,"expiresInMs"))
        }
        override fun complete(deviceId: String,challengeToken: String,signature: ByteArray): DeviceTicket {
            val (status,value)=http.request("/fixture/login/complete",json("deviceId" to deviceId,"challengeToken" to challengeToken,"signature" to encoder.encodeToString(signature)))
            check(status==200 && value.keySet()==setOf("ticket","expiresInMs"));return DeviceTicket(str(value,"ticket"),number(value,"expiresInMs"))
        }
    }
    val signer=DeviceLoginSigner { challenge -> sign(StudioDeviceTranscript.login(device,challenge.challengeId,challenge.token,pair.public.encoded,scope)) }
    val coordinator=DeviceLoginCoordinator { System.nanoTime()/1_000_000 }
    val entered=CountDownLatch(1);val release=CountDownLatch(1)
    val delayed=object:DeviceLoginTransport by transport {
        override fun complete(deviceId: String,challengeToken: String,signature: ByteArray): DeviceTicket {
            val out=transport.complete(deviceId,challengeToken,signature);entered.countDown();check(release.await(5,TimeUnit.SECONDS));return out
        }
    }
    val pool=Executors.newSingleThreadExecutor()
    try {
        val pending=pool.submit<DeviceLoginCoordinator.Session?> { coordinator.login(device,delayed,signer) }
        check(entered.await(5,TimeUnit.SECONDS));coordinator.logout();release.countDown();check(pending.get(5,TimeUnit.SECONDS)==null && coordinator.snapshot()==null)
        // Fixture-only hook: not a real user approval or server revocation.
        coordinator.explicitEnrollmentConfirmed()
        val active=checkNotNull(coordinator.login(device,transport,signer))
        check(http.request("/fixture/projects",null,mapOf("Authorization" to "Bearer ${active.token}")).first==200)
        check(http.request("/fixture/revoke",json("deviceId" to device),browser).first==200)
        check(http.request("/fixture/projects",null,mapOf("Authorization" to "Bearer ${active.token}")).first==403)
        println("DEVICE_HTTP_FIXTURE_OK")
    } finally { release.countDown();pool.shutdownNow() }
}
