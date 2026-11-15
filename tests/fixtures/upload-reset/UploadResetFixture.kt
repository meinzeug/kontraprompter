package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import de.kontrakurs.kontraprompter.video.SavedRecording
import java.io.*
import java.net.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

private object Transport { var probeCode=409; var body=""; var probes=0; var expectedPath=""; var expectedQuery="" }
private class FakeConnection(url:URL):HttpURLConnection(url) {
 override fun disconnect() {}
 override fun usingProxy()=false
 override fun connect() {}
 override fun getOutputStream():OutputStream = if (url.path=="/api/login") ByteArrayOutputStream() else object:OutputStream() {
  override fun write(b:Int):Unit=throw IOException("Connection reset")
  override fun write(b:ByteArray,off:Int,len:Int):Unit=throw IOException("Connection reset")
 }
 override fun getResponseCode():Int=when {
  url.path=="/api/login" -> 200
  url.path==Transport.expectedPath -> { check(url.query==Transport.expectedQuery); Transport.probes++;Transport.probeCode }
  else -> throw IOException("Connection reset")
 }
 override fun getHeaderFields():Map<String,List<String>> = if(url.path=="/api/login") mapOf("Set-Cookie" to listOf("kontrakurs_session=fixture; Path=/")) else emptyMap()
 override fun getErrorStream():InputStream = ByteArrayInputStream(Transport.body.toByteArray())
}
private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
private suspend fun runCase(name:String, status:Int, body:String, expected:String) {
 val dir=Files.createTempDirectory("kp-reset-upload-").toFile()
 try {
  val bytes=ByteArray(100_000){3}; val file=File(dir,"local.mp4");file.writeBytes(bytes)
  val recording=SavedRecording(UUID.randomUUID().toString(),bytes.size.toLong(),digest(bytes),file)
  val project=UUID.randomUUID().toString(); val upload=uploadIdFor(recording.id,project)
  Transport.expectedPath="/api/projects/$project/recordings/upload-status";Transport.expectedQuery="recordingId=$upload"
  Transport.probeCode=status;Transport.body=body;Transport.probes=0
  val client=StudioClient();client.login("fixture");val ctx=Context(dir);val coordinator=CloudUploadCoordinator(ctx,client)
  val result=withTimeout(20_000){coordinator.upload(recording,project)}
  check(result is CloudUploadState.Failed && result.kind==expected){"$name result=$result"}
  val journal=checkNotNull(UploadJournal.get(ctx,recording,project));check(journal.uploadId==upload)
  check(journal.phase==if(expected==RECORDING_DELETED)"conflict" else "uncertain")
  check(journal.conflictReason==if(expected==RECORDING_DELETED)RECORDING_DELETED else null)
  check(Transport.probes==1);check(file.readBytes().contentEquals(bytes))
  println("$name kind=${result.kind} phase=${journal.phase} original=preserved")
 } finally {dir.deleteRecursively()}
}
fun main()=runBlocking {
 URL.setURLStreamHandlerFactory { protocol -> if(protocol=="https")object:URLStreamHandler(){override fun openConnection(url:URL):URLConnection {check(url.host=="videomaker.dorfspy.de");return FakeConnection(url)}} else null }
 runCase("confirmed_tombstone",409,"{\"code\":\"recording_deleted\",\"error\":\"gelöscht\"}",RECORDING_DELETED)
 runCase("old_server",404,"{}","uncertain")
 runCase("unrelated_conflict",409,"{\"code\":\"another_error\"}","uncertain")
 runCase("malformed_tombstone",409,"{\"code\":\"recording_deleted\",\"error\":\"x\"}garbage","uncertain")
 runCase("not_deleted",204,"","uncertain")
}
