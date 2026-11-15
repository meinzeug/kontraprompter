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

/** Test-only loopback transport. Not an HTTPS client and never used by the app. */
internal class OwnedDeviceHttpFixture(private val base: String) {
    init {
        val uri=URI(base)
        require(uri.scheme=="http" && uri.host=="127.0.0.1" && uri.port in 1..65535 && uri.rawPath.isNullOrEmpty() && uri.rawQuery==null && uri.rawFragment==null && uri.rawUserInfo==null)
    }
    fun request(path: String,body: JsonObject?,headers: Map<String,String> = emptyMap()): Pair<Int,JsonObject> {
        require(path in setOf("/fixture/enroll/begin","/fixture/enroll/complete","/fixture/login/begin","/fixture/login/complete","/fixture/revoke","/fixture/grant","/fixture/grant/revoke","/fixture/project/check"))
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

/** Only synthetic values; no Android identity, production credentials or TLS. */
fun main(args: Array<String>) {
    require(args.size==4)
    val http=OwnedDeviceHttpFixture(args[0]);val browser=mapOf("Origin" to "https://fixture.invalid","Cookie" to "fixture_session=${args[1]}","X-Fixture-CSRF" to args[2]);val project=args[3]
    val pair=KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val device=UUID.randomUUID().toString();val encoder=Base64.getUrlEncoder().withoutPadding();val scope=listOf("projects:read","recordings:upload")
    fun sign(bytes: ByteArray)=Signature.getInstance("SHA256withECDSA").run { initSign(pair.private);update(bytes);sign() }
    val (beginCode,begin)=http.request("/fixture/enroll/begin",json("deviceId" to device,"publicKey" to encoder.encodeToString(pair.public.encoded)),browser)
    check(beginCode==200 && begin.keySet()==setOf("challengeId","nonce","ownerHash","workspaceHash","confirmingSessionHash","scope","expiresInMs"))
    val owner=str(begin,"ownerHash");val workspace=str(begin,"workspaceHash");val session=str(begin,"confirmingSessionHash");val id=str(begin,"challengeId")
    check(begin.getAsJsonArray("scope").map { it.asString }.toSet()==scope.toSet())
    val enrollment=sign(StudioOwnedDeviceTranscript.enrollment(device,id,str(begin,"nonce"),pair.public.encoded,scope,owner,workspace,session))
    val enrollmentBody=json("deviceId" to device,"challengeId" to id,"signature" to encoder.encodeToString(enrollment))
    check(http.request("/fixture/enroll/complete",enrollmentBody,browser).first==200)
    check(http.request("/fixture/enroll/complete",enrollmentBody,browser).first==403)
    val (loginCode,login)=http.request("/fixture/login/begin",json("deviceId" to device));check(loginCode==200)
    val token=str(login,"challengeToken");val loginId=str(login,"challengeId")
    val proof=sign(StudioOwnedDeviceTranscript.login(device,loginId,token,pair.public.encoded,scope,owner,workspace))
    val complete=json("deviceId" to device,"challengeToken" to token,"signature" to encoder.encodeToString(proof))
    val (code,result)=http.request("/fixture/login/complete",complete);check(code==200)
    check(http.request("/fixture/login/complete",complete).first==403)
    val ticket=str(result,"ticket");val headers=mapOf("Authorization" to "Bearer $ticket");val projectBody=json("projectId" to project)
    check(http.request("/fixture/project/check",projectBody,headers).first==403)
    check(http.request("/fixture/grant",json("deviceId" to device,"projectId" to project),browser).first==200)
    check(http.request("/fixture/project/check",projectBody,headers).first==200)
    check(http.request("/fixture/revoke",json("deviceId" to device),browser).first==200)
    check(http.request("/fixture/project/check",projectBody,headers).first==403)
    println("OWNED_DEVICE_HTTP_FIXTURE_OK")
}
