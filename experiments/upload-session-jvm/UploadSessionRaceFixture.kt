package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import android.util.FixtureHooks
import de.kontrakurs.kontraprompter.video.SavedRecording
import java.io.*
import java.net.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

private object Transport {
 var code=409
 var response=ByteArray(0)
 var entered=CountDownLatch(1)
 var release=CountDownLatch(1)
 var blockBody=true
 val logins=AtomicInteger()
 var sentUploadId:String?=null
}
private class FakeConnection(url:URL):HttpURLConnection(url) {
 private val output=ByteArrayOutputStream()
 override fun disconnect(){}
 override fun usingProxy()=false
 override fun connect(){}
 override fun getOutputStream():OutputStream=output
 override fun getResponseCode()=if(url.path=="/api/login")200 else Transport.code
 override fun getHeaderFields():Map<String,List<String>> = if(url.path=="/api/login")mapOf("Set-Cookie" to listOf("kontrakurs_session=fixture-${Transport.logins.incrementAndGet()}; Path=/")) else emptyMap()
 private fun body():InputStream {
  val delegate=ByteArrayInputStream(Transport.response)
  return object:InputStream(){
   var waited=false
   fun waitOnce(){if(!waited){waited=true;if(Transport.blockBody){Transport.entered.countDown();check(Transport.release.await(10,TimeUnit.SECONDS))}}}
   override fun read():Int {waitOnce();return delegate.read()}
   override fun read(b:ByteArray,off:Int,len:Int):Int{waitOnce();return delegate.read(b,off,len)}
  }
 }
 override fun getErrorStream():InputStream {Transport.sentUploadId=getRequestProperty("X-Recording-Id");return body()}
 override fun getInputStream():InputStream {Transport.sentUploadId=getRequestProperty("X-Recording-Id");return body()}
}

private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
private suspend fun await(latch:CountDownLatch)=withContext(Dispatchers.IO){check(latch.await(10,TimeUnit.SECONDS))}

private suspend fun runCase(name:String,code:Int,swapAt:String) = coroutineScope {
 val dir=Files.createTempDirectory("kp-upload-jvm-").toFile();val ctx=Context(dir)
 try {
  val bytes="synthetic-upload".toByteArray();val file=File(dir,"local.mp4");file.writeBytes(bytes)
  val recording=SavedRecording(UUID.randomUUID().toString(),bytes.size.toLong(),digest(bytes),file)
  val project=UUID.randomUUID().toString();val uploadId=uploadIdFor(recording.id,project)
  Transport.code=code;Transport.logins.set(0);Transport.sentUploadId=null;Transport.entered=CountDownLatch(1);Transport.release=CountDownLatch(1);Transport.blockBody=swapAt=="body"
  Transport.response=if(code==409)"{\"code\":\"recording_deleted\",\"error\":\"Gelöscht\"}".toByteArray() else "{\"id\":\"$uploadId\",\"projectId\":\"$project\",\"sizeBytes\":${bytes.size},\"sha256\":\"${recording.sha256}\"}".toByteArray()
  FixtureHooks.beforeConfirmed=if(swapAt=="journal")CountDownLatch(1) else null;FixtureHooks.continueConfirmed=if(swapAt=="journal")CountDownLatch(1) else null
  val client=StudioClient();client.login("fixture");val originalSession=checkNotNull(client.manualUploadSession()).cookie;val coordinator=CloudUploadCoordinator(ctx,client)
  var sessionAtCommit:String?=null;FixtureHooks.onConfirmedCommit={sessionAtCommit=client.manualUploadSession()?.cookie}
  val operation=async { coordinator.upload(recording,project) }
  var changedLogin:Deferred<Unit>?=null
  when(swapAt){
   "body"->{await(Transport.entered);client.login("fixture");Transport.release.countDown()}
   "journal"->{await(checkNotNull(FixtureHooks.beforeConfirmed));changedLogin=async(Dispatchers.Default){client.login("fixture")};delay(200);checkNotNull(FixtureHooks.continueConfirmed).countDown()}
  }
  val outcome=withTimeout(10_000){operation.await()};changedLogin?.let{withTimeout(10_000){it.await()}};val persisted=UploadJournal.get(ctx,recording,project)
  check(file.readBytes().contentEquals(bytes));check(Transport.sentUploadId==uploadId)
  println("$name outcome=${when(outcome){is CloudUploadState.Failed->outcome.kind;is CloudUploadState.Confirmed->"confirmed";else->"unexpected"}} journal=${persisted?.phase} reason=${persisted?.conflictReason?:"none"} session_at_confirmed_commit=${if(sessionAtCommit==null)"none" else if(sessionAtCommit==originalSession)"original" else "changed"} original=preserved")
 } finally {FixtureHooks.beforeConfirmed=null;FixtureHooks.continueConfirmed=null;FixtureHooks.onConfirmedCommit=null;dir.deleteRecursively()}
}

fun main()=runBlocking {
 URL.setURLStreamHandlerFactory { protocol -> if(protocol=="https")object:URLStreamHandler(){override fun openConnection(url:URL):URLConnection {check(url.host=="videomaker.dorfspy.de");return FakeConnection(url)}} else null }
 runCase("control_409",409,"none")
 runCase("session_during_409_body",409,"body")
 runCase("control_200",200,"none")
 runCase("session_during_200_body",200,"body")
 runCase("session_during_confirmed_journal",200,"journal")
}
