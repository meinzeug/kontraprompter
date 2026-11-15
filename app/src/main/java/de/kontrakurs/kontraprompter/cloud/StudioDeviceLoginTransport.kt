package de.kontrakurs.kontraprompter.cloud

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/** WIP only: reachable through StudioClient exclusively when its hard gate is enabled. */
internal class StudioDeviceLoginTransport(private val open: (String) -> HttpURLConnection) : DeviceLoginTransport {
    private val canonical=Regex("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")
    private fun value(json:JSONObject,key:String):String {
        val item=json.opt(key);if(item !is String)throw IOException("Ungültige Geräteantwort")
        return item
    }
    private fun expiry(json:JSONObject,limit:Long):Long {
        val item=json.opt("expiresInMs");if(item !is Number)throw IOException("Ungültige Geräteantwort")
        val text=item.toString();if(!text.matches(Regex("^[1-9][0-9]{0,8}$")))throw IOException("Ungültige Geräteantwort")
        val result=text.toLongOrNull() ?: throw IOException("Ungültige Geräteantwort")
        if(result !in 1..limit)throw IOException("Ungültige Geräteantwort")
        return result
    }
    private fun post(path:String,body:JSONObject):JSONObject {
        val payload=body.toString().toByteArray(Charsets.UTF_8)
        if(payload.size>2048)throw IOException("Geräteanfrage zu groß")
        val connection=open(path)
        try {
            connection.requestMethod="POST";connection.instanceFollowRedirects=false;connection.useCaches=false
            connection.connectTimeout=10_000;connection.readTimeout=30_000;connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json")
            connection.setFixedLengthStreamingMode(payload.size);connection.outputStream.use { it.write(payload) }
            if(connection.responseCode!=200)throw IOException("Geräteanmeldung abgelehnt oder nicht verfügbar")
            val bytes=connection.inputStream.use { stream ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(512)
                while(true){val n=stream.read(buffer);if(n<0)break;if(out.size()+n>4096)throw IOException("Geräteantwort zu groß");out.write(buffer,0,n)}
                out.toByteArray()
            }
            return try{JSONObject(bytes.toString(Charsets.UTF_8))}catch (_:Exception){throw IOException("Ungültige Geräteantwort")}
        } finally {connection.disconnect()}
    }
    override fun begin(deviceId:String):DeviceChallenge {
        val result=post("/api/studio-devices/login/begin",JSONObject().put("deviceId",deviceId))
        if(result.keys().asSequence().toSet()!=setOf("challengeId","challengeToken","expiresInMs"))throw IOException("Ungültige Geräteantwort")
        return DeviceChallenge(value(result,"challengeId"),value(result,"challengeToken"),expiry(result,60_000))
    }
    override fun complete(deviceId:String,challengeToken:String,signature:ByteArray):DeviceTicket {
        if(signature.isEmpty() || signature.size>80)throw IOException("Ungültiger Gerätenachweis")
        val signed=Base64.encodeToString(signature,Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val body=JSONObject().put("deviceId",deviceId).put("challengeToken",challengeToken).put("signature",signed)
        val result=post("/api/studio-devices/login/complete",body)
        if(result.keys().asSequence().toSet()!=setOf("ticket","expiresInMs"))throw IOException("Ungültige Geräteantwort")
        val ticket=value(result,"ticket");if(!ticket.matches(canonical))throw IOException("Ungültige Geräteantwort")
        return DeviceTicket(ticket,expiry(result,300_000))
    }
}
