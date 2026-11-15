package de.kontrakurs.kontraprompter.cloud

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** Test-package-only CAS. Cooperating processes lock writes, not live session actions. */
internal class AtomicFixtureConsentStore(context: Context) : StudioOwnedConsentStore {
    companion object { private val processLock=Any();private const val PACKAGE="de.kontrakurs.studioconsentfixture" }
    private val parent:File=context.applicationContext.filesDir
    private val dir=File(parent,"studio-owned-consent-fixture")
    private val data=File(dir,"consent.v1")
    private val lockFile=File(dir,"consent.lock")
    private val atomic=AtomicFile(data)
    init {
        check(context.applicationContext.packageName==PACKAGE)
        check((dir.isDirectory || dir.mkdir()) && dir.isDirectory && dir.canonicalFile.parentFile==parent.canonicalFile)
        check(OsConstants.S_ISDIR(Os.lstat(dir.absolutePath).st_mode))
        syncDir(parent);syncDir(dir)
    }
    private fun syncDir(path:File){val fd=Os.open(path.absolutePath,OsConstants.O_RDONLY,0);try{check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode));Os.fsync(fd)}finally{Os.close(fd)}}
    private fun entryExists(path:File):Boolean=try{Os.lstat(path.absolutePath);true}catch(e:ErrnoException){if(e.errno==OsConstants.ENOENT)false else throw e}
    private fun requireRegular(path:File){val info=Os.lstat(path.absolutePath);check(OsConstants.S_ISREG(info.st_mode) && info.st_nlink==1L)}
    private fun <T> locked(block:()->T):T=synchronized(processLock){
        if(entryExists(lockFile))requireRegular(lockFile)
        RandomAccessFile(lockFile,"rw").use { file ->
            requireRegular(lockFile)
            val held=try{file.channel.tryLock()}catch (_:OverlappingFileLockException){null}
            check(held!=null){"Consent fixture is busy"}
            try{block()}finally{held.release()}
        }
    }
    private fun readLocked():StudioOwnedConsentRecord? {
        // Never silently roll back to a previous active record after interruption.
        check(!entryExists(File(data.path+".bak")) && !entryExists(File(data.path+".new"))){"Interrupted consent write"}
        if(!entryExists(data))return null
        requireRegular(data)
        val bytes=data.inputStream().use { input ->
            val out=ByteArrayOutputStream();val buffer=ByteArray(128)
            while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=512);out.write(buffer,0,n)}
            out.toByteArray()
        }
        return ConsentRecordCodec.decode(bytes)
    }
    /** Explicit synthetic cleanup, never a production recovery path. */
    fun removeOwnedTestRecord(expected:StudioOwnedConsentRecord):Boolean=locked {
        if(!expected.revoked || readLocked()!=expected)return@locked false
        requireRegular(data);check(data.delete());syncDir(dir);true
    }
    override fun read():StudioOwnedConsentRecord?=locked { readLocked() }
    override fun compareAndSet(expected:StudioOwnedConsentRecord?,update:StudioOwnedConsentRecord):Boolean=locked {
        val current=readLocked();if(current!=expected)return@locked false
        require(ConsentRecordCodec.permittedTransition(current,update))
        val bytes=ConsentRecordCodec.encode(update)
        val stream=atomic.startWrite()
        try{stream.write(bytes);stream.fd.sync();atomic.finishWrite(stream)}
        catch(error:Exception){atomic.failWrite(stream);throw error}
        syncDir(dir)
        check(readLocked()==update)
        true
    }
}
