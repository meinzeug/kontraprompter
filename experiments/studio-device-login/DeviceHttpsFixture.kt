package de.kontrakurs.kontraprompter.cloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** JVM-only loopback experiment; neither Android system trust nor real app is modified. */
internal class DeviceHttpsFixture(private val base: String, certificate: X509Certificate, enforceExactPin: Boolean = true) {
    private val sockets = run {
        val uri=URI(base)
        require(uri.scheme=="https" && uri.host=="127.0.0.1" && uri.port in 1..65535 && uri.rawPath.isNullOrEmpty() && uri.rawQuery==null && uri.rawFragment==null && uri.rawUserInfo==null)
        val anchors=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null);setCertificateEntry("fixture",certificate) }
        val factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }
        val delegate=factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val exact=certificate.encoded.copyOf()
        val manager=object:X509TrustManager {
            override fun getAcceptedIssuers()=delegate.acceptedIssuers
            override fun checkClientTrusted(chain: Array<X509Certificate>,authType: String)=delegate.checkClientTrusted(chain,authType)
            override fun checkServerTrusted(chain: Array<X509Certificate>,authType: String) {
                delegate.checkServerTrusted(chain,authType)
                if(enforceExactPin && (chain.isEmpty() || !chain[0].encoded.contentEquals(exact)))throw CertificateException("Fixture certificate mismatch")
            }
        }
        SSLContext.getInstance("TLS").apply { init(null,arrayOf(manager),null) }.socketFactory
    }
    fun request(path: String,body: JsonObject?,headers: Map<String,String> = emptyMap()): Pair<Int,JsonObject> {
        require(path in setOf("/fixture/enroll/begin","/fixture/enroll/complete","/fixture/login/begin","/fixture/login/complete","/fixture/revoke","/fixture/projects"))
        val connection=URI.create(base+path).toURL().openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory=sockets // Keep the default hostname verifier.
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

/** No real credentials: browser token, CSRF and key belong to the fixture only. */
fun main(args: Array<String>) {
    require(args.size==8)
    val factory=java.security.cert.CertificateFactory.getInstance("X.509")
    fun cert(path: String)=File(path).inputStream().use { factory.generateCertificate(it) as X509Certificate }
    val bad=DeviceHttpsFixture(args[0],cert(args[2]))
    val denied=runCatching { bad.request("/fixture/projects",null) }.exceptionOrNull()
    check(denied is SSLException)
    // Test control: another leaf signed by the same fixture CA is trusted
    // without the leaf pin, then rejected with the pin before HTTP is sent.
    val trustOnly=DeviceHttpsFixture(args[5],cert(args[1]),enforceExactPin=false)
    check(trustOnly.request("/fixture/projects",null).first==403)
    val wrongLeaf=DeviceHttpsFixture(args[5],cert(args[1]))
    check(runCatching { wrongLeaf.request("/fixture/projects",null) }.exceptionOrNull() is SSLException)
    val wrongHost=DeviceHttpsFixture(args[6],cert(args[7]))
    check(runCatching { wrongHost.request("/fixture/projects",null) }.exceptionOrNull() is SSLException)
    val http=DeviceHttpsFixture(args[0],cert(args[1]));val browser=mapOf("Origin" to "https://fixture.invalid","Cookie" to "fixture_session=${args[3]}","X-Fixture-CSRF" to args[4])
    val pair=KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val id=UUID.randomUUID().toString();val encoder=Base64.getUrlEncoder().withoutPadding();val scope=listOf("projects:read","recordings:upload")
    fun json(vararg pairs:Pair<String,String>)=JsonObject().apply { for((key,value) in pairs)addProperty(key,value) }
    fun sign(bytes: ByteArray)=Signature.getInstance("SHA256withECDSA").run { initSign(pair.private);update(bytes);sign() }
    val (beginCode,begin)=http.request("/fixture/enroll/begin",json("deviceId" to id,"publicKey" to encoder.encodeToString(pair.public.encoded)),browser);check(beginCode==200)
    val challengeId=begin.get("challengeId").asString;val nonce=begin.get("nonce").asString;val owner=begin.get("ownerHash").asString
    check(begin.getAsJsonArray("scope").map { it.asString }.toSet()==scope.toSet())
    val signed=sign(StudioDeviceTranscript.enrollment(id,challengeId,nonce,pair.public.encoded,scope,owner))
    check(http.request("/fixture/enroll/complete",json("deviceId" to id,"challengeId" to challengeId,"signature" to encoder.encodeToString(signed)),browser).first==200)
    val (loginCode,login)=http.request("/fixture/login/begin",json("deviceId" to id));check(loginCode==200)
    val token=login.get("challengeToken").asString;val loginId=login.get("challengeId").asString
    val proof=sign(StudioDeviceTranscript.login(id,loginId,token,pair.public.encoded,scope))
    val (completeCode,complete)=http.request("/fixture/login/complete",json("deviceId" to id,"challengeToken" to token,"signature" to encoder.encodeToString(proof)));check(completeCode==200)
    val ticket=complete.get("ticket").asString
    check(http.request("/fixture/projects",null,mapOf("Authorization" to "Bearer $ticket")).first==200)
    check(http.request("/fixture/revoke",json("deviceId" to id),browser).first==200)
    check(http.request("/fixture/projects",null,mapOf("Authorization" to "Bearer $ticket")).first==403)
    println("DEVICE_HTTPS_FIXTURE_OK")
}
