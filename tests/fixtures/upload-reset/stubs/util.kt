package android.util
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
object FixtureHooks { var beforeConfirmed:CountDownLatch?=null; var continueConfirmed:CountDownLatch?=null;var onConfirmedCommit:(()->Unit)?=null }
class AtomicFile(private val base:File) {
 fun openRead()=FileInputStream(base)
 fun startWrite()=FileOutputStream(File(base.path+".new"))
 fun finishWrite(out:FileOutputStream){
  out.close(); val tmp=File(base.path+".new")
  if(tmp.readText().contains("\"phase\":\"confirmed\"")){FixtureHooks.beforeConfirmed?.countDown();FixtureHooks.continueConfirmed?.await();FixtureHooks.onConfirmedCommit?.invoke()}
  check(tmp.renameTo(base))
 }
 fun failWrite(out:FileOutputStream){out.close();File(base.path+".new").delete()}
}
object Base64 {
 const val URL_SAFE=8;const val NO_PADDING=1;const val NO_WRAP=2
 fun encodeToString(data:ByteArray,flags:Int)=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data)
}
