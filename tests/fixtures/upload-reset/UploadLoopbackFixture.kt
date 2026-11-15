package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import de.kontrakurs.kontraprompter.video.SavedRecording
import java.io.*
import java.net.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.*

private class Server(private val probeCode:Int):AutoCloseable {
 val server=ServerSocket(0,32,InetAddress.getByName("127.0.0.1"))
 val probes=AtomicInteger();val uploads=AtomicInteger()
 private val job=thread(isDaemon=true) {
  while(!server.isClosed) {
   val socket=try{server.accept()}catch(_:SocketException){break}
   thread(isDaemon=true){socket.use {
    try {
     val source=it.getInputStream();val header=ByteArrayOutputStream();var tail=0
     while(tail!=0x0d0a0d0a){val c=source.read();if(c<0)return@use;header.write(c);tail=(tail shl 8) or c;check(header.size()<32_768)}
     val lines=header.toString("ISO-8859-1").split("\r\n");val request=lines.first().split(' ');val path=request[1]
     val len=lines.firstOrNull{line->line.startsWith("Content-Length:",true)}?.substringAfter(':')?.trim()?.toInt()?:0
     val out=it.getOutputStream()
     when {
      path=="/api/login" -> {
       repeat(len){check(source.read()>=0)}
       out.write("HTTP/1.1 200 OK\r\nSet-Cookie: kontrakurs_session=loopback; Path=/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());out.flush()
      }
      path.contains("/upload-status?") -> {
       probes.incrementAndGet();val body=if(probeCode==409)"{\"code\":\"recording_deleted\",\"error\":\"gelöscht\"}".toByteArray() else byteArrayOf()
       out.write("HTTP/1.1 $probeCode Result\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(body);out.flush()
      }
      else -> {
       uploads.incrementAndGet();check(source.read()>=0)
       // Reply before the body is consumed, then force a TCP reset.
       val early="{\"code\":\"recording_deleted\",\"error\":\"gelöscht\"}".toByteArray()
       out.write("HTTP/1.1 409 Conflict\r\nContent-Length: ${early.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(early);out.flush()
       it.setSoLinger(true,0)
      }
     }
    } catch(_:IOException) { }
   }}
  }
 }
 override fun close(){server.close();job.join(2000)}
}
private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
private var active:Server?=null
private suspend fun runCase(code:Int,expected:String){
 val dir=Files.createTempDirectory("kp-reset-wire-").toFile()
 Server(code).use { server ->
  active=server
  try {
   val bytes=ByteArray(8*1024*1024){3};val file=File(dir,"local.mp4");file.writeBytes(bytes)
   val recording=SavedRecording(UUID.randomUUID().toString(),bytes.size.toLong(),digest(bytes),file);val project=UUID.randomUUID().toString()
   val client=StudioClient();client.login("fixture");val ctx=Context(dir);val coordinator=CloudUploadCoordinator(ctx,client)
   val result=withTimeout(30_000){coordinator.upload(recording,project)}
   check(result is CloudUploadState.Failed && result.kind==expected){"status=$code result=$result"}
   val journal=checkNotNull(UploadJournal.get(ctx,recording,project))
   check(journal.phase==if(expected==RECORDING_DELETED)"conflict" else "uncertain")
   check(server.uploads.get()==1 && server.probes.get()==1);check(file.readBytes().contentEquals(bytes))
   println("socket_reset status=$code kind=${result.kind} journal=${journal.phase} original=preserved")
  } finally {active=null;dir.deleteRecursively()}
 }
}
fun main()=runBlocking {
 URL.setURLStreamHandlerFactory { protocol -> if(protocol=="https")object:URLStreamHandler(){override fun openConnection(url:URL):URLConnection {
  check(url.host=="videomaker.dorfspy.de");val port=checkNotNull(active).server.localPort
  return URL("http://127.0.0.1:$port${url.file}").openConnection()
 }} else null }
 runCase(409,RECORDING_DELETED);runCase(404,"uncertain")
}
