package de.kontrakurs.kontraprompter.cloud

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/** Separate network-enabled test UID, never the user's app or system trust store. */
class StudioHttpsInstrumentation : Instrumentation() {
    private var arguments=Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);this.arguments=arguments?:Bundle();start() }

    private fun certificate(encoded: String): X509Certificate {
        check(encoded.length<=8192)
        val der=Base64.decode(encoded,Base64.DEFAULT);check(der.size in 1..4096)
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }
    private class LocalHttps(private val base: String,cert: X509Certificate) {
        private val sockets = run {
            val uri=URI(base)
            require(uri.scheme=="https" && uri.host=="127.0.0.1" && uri.port in 1..65535 && uri.rawPath.isNullOrEmpty() && uri.rawQuery==null && uri.rawFragment==null && uri.rawUserInfo==null)
            val anchors=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null);setCertificateEntry("fixture",cert) }
            val manager=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(anchors) }.trustManagers.filterIsInstance<X509TrustManager>().single()
            val exact=cert.encoded.copyOf()
            val pinned=object:X509TrustManager {
                override fun getAcceptedIssuers()=manager.acceptedIssuers
                override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String)=manager.checkClientTrusted(chain,authType)
                override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String) {
                    manager.checkServerTrusted(chain,authType)
                    if(chain.isEmpty() || !chain[0].encoded.contentEquals(exact))throw CertificateException("Fixture certificate mismatch")
                }
            }
            SSLContext.getInstance("TLS").apply { init(null,arrayOf(pinned),null) }.socketFactory
        }
        fun request(path:String,body:JsonObject?,headers:Map<String,String> = emptyMap()):Pair<Int,JsonObject> {
            require(path in setOf("/fixture/enroll/begin","/fixture/enroll/complete","/fixture/login/begin","/fixture/login/complete","/fixture/revoke","/fixture/projects"))
            val connection=URI.create(base+path).toURL().openConnection() as HttpsURLConnection
            try {
                connection.sslSocketFactory=sockets // Keep default platform hostname verification.
                connection.instanceFollowRedirects=false;connection.connectTimeout=3000;connection.readTimeout=5000
                connection.requestMethod=if(body==null)"GET" else "POST"
                for((name,value) in headers)connection.setRequestProperty(name,value)
                if(body!=null){connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");val bytes=body.toString().toByteArray(Charsets.UTF_8);check(bytes.size<=2048);connection.setFixedLengthStreamingMode(bytes.size);connection.outputStream.use{it.write(bytes)}}
                val status=connection.responseCode;val stream=if(status in 200..299)connection.inputStream else connection.errorStream
                val bytes=stream.use { input ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(512)
                    while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=2048);out.write(buffer,0,n)};out.toByteArray()
                }
                return status to JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            }finally{connection.disconnect()}
        }
    }
    override fun onStart() {
        val result=Bundle();var id:String?=null;var ownKey:ByteArray?=null;var outcome=Activity.RESULT_CANCELED;var lock:FixtureExclusiveLock?=null
        try {
            lock=checkNotNull(FixtureExclusiveLock.tryAcquire(File(targetContext.filesDir,"studio-identity-test.lock")))
            val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if(arguments.getString("mode")=="check") {
                val value=checkNotNull(arguments.getString("identityId"));require(UUID.fromString(value).toString()==value)
                result.putBoolean("alias_present",keys.containsAlias("videomaker-studio-device-v1-$value"))
                result.putBoolean("marker_present",File(targetContext.filesDir,"studio-device-auth/identity.json").exists())
                outcome=Activity.RESULT_OK;return
            }
            check(arguments.getString("mode")=="run")
            val base=checkNotNull(arguments.getString("base"));val good=certificate(checkNotNull(arguments.getString("certificate")))
            val wrong=certificate(checkNotNull(arguments.getString("wrongCertificate")))
            val cookie=checkNotNull(arguments.getString("fixtureSession"));val csrf=checkNotNull(arguments.getString("fixtureCsrf"))
            val tokenPattern=Regex("^[A-Za-z0-9_-]{43}$");check(cookie.matches(tokenPattern)&&csrf.matches(tokenPattern))
            check(runCatching { LocalHttps(base,wrong).request("/fixture/projects",null) }.exceptionOrNull() is SSLException)
            val http=LocalHttps(base,good);val browser=mapOf("Origin" to "https://fixture.invalid","Cookie" to "fixture_session=$cookie","X-Fixture-CSRF" to csrf)
            val underlying=AndroidStudioIdentityStore(targetContext)
            val observed=object:StudioIdentityStore by underlying {
                override fun write(marker:StudioIdentityMarker) { if(marker.state=="creating"){id=marker.id;sendStatus(0,Bundle().apply{putString("test_identity_id",marker.id)})};underlying.write(marker) }
                override fun createKey(value:String):StudioIdentityKey { check(value==id&&!underlying.hasAnyDeviceKey());val key=underlying.createKey(value);ownKey=key.publicKeyDer.copyOf();return key }
            }
            val identity=StudioDeviceIdentity(observed);check(identity.load()==StudioIdentityState.EnrollmentRequired)
            val ready=identity.createAfterExplicitConsent() as StudioIdentityState.Ready;check(ready.id==id)
            val encoded=Base64.encodeToString(ready.publicKeyDer,Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            fun json(vararg items:Pair<String,String>)=JsonObject().apply { for((key,value) in items)addProperty(key,value) }
            val (enrollCode,enroll)=http.request("/fixture/enroll/begin",json("deviceId" to ready.id,"publicKey" to encoded),browser);check(enrollCode==200)
            val scope=listOf("projects:read","recordings:upload")
            check(enroll.getAsJsonArray("scope").map{it.asString}.toSet()==scope.toSet())
            val challenge=enroll.get("challengeId").asString;val nonce=enroll.get("nonce").asString;val owner=enroll.get("ownerHash").asString
            val signed=identity.signEnrollmentForFixture(ready.id,challenge,nonce,scope,owner)
            val signature=Base64.encodeToString(signed,Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            check(http.request("/fixture/enroll/complete",json("deviceId" to ready.id,"challengeId" to challenge,"signature" to signature),browser).first==200)
            val (loginCode,login)=http.request("/fixture/login/begin",json("deviceId" to ready.id));check(loginCode==200)
            val loginId=login.get("challengeId").asString;val token=login.get("challengeToken").asString
            val loginProof=identity.signLoginForFixture(ready.id,loginId,token,scope)
            val (completeCode,complete)=http.request("/fixture/login/complete",json("deviceId" to ready.id,"challengeToken" to token,"signature" to Base64.encodeToString(loginProof,Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)));check(completeCode==200)
            val ticket=complete.get("ticket").asString
            check(http.request("/fixture/projects",null,mapOf("Authorization" to "Bearer $ticket")).first==200)
            check(http.request("/fixture/revoke",json("deviceId" to ready.id),browser).first==200)
            check(http.request("/fixture/projects",null,mapOf("Authorization" to "Bearer $ticket")).first==403)
            result.putString("test_identity_id",ready.id);result.putString("result","APP_UID_HTTPS_AUTH_REVOKE_OK");outcome=Activity.RESULT_OK
        } catch(error:Throwable) { result.putString("error",error.javaClass.simpleName) }
        finally {
            val value=id
            if(value!=null)try {
                require(UUID.fromString(value).toString()==value)
                val dir=File(targetContext.filesDir,"studio-device-auth");val allowed=setOf("identity.json","identity.json.bak","identity.json.new")
                if(dir.exists())check(dir.isDirectory && checkNotNull(dir.list()).all{it in allowed})
                val markers=allowed.map{File(dir,it)}.filter{it.exists()}
                val owners=markers.map{item->check(item.isFile && item.length()<=4096);val marker=JSONObject(item.readText(Charsets.UTF_8));FixtureMarkerOwner(item.name,marker.getString("id"),marker.getInt("version"))}
                check(fixtureMarkersOwned(value,owners))
                val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};val alias="videomaker-studio-device-v1-$value"
                if(store.containsAlias(alias)){val actual=checkNotNull(store.getCertificate(alias)?.publicKey?.encoded);check(fixtureKeyOwned(ownKey,actual));store.deleteEntry(alias)}
                check(!store.containsAlias(alias));for(item in markers)check(item.delete());if(dir.exists())check(dir.delete())
                result.putBoolean("fixture_cleanup_ok",true)
            }catch(error:Throwable){result.putString("cleanup_error",error.javaClass.simpleName);outcome=Activity.RESULT_CANCELED}
            try{lock?.close()}catch(error:Throwable){result.putString("lock_cleanup_error",error.javaClass.simpleName);outcome=Activity.RESULT_CANCELED}
            finish(outcome,result)
        }
    }
}
