package de.kontrakurs.kontraprompter.cloud

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import java.util.UUID

/** Separate package, no real identity, no network and no other app data. */
class StudioConsentInstrumentation : Instrumentation() {
    private var arguments=Bundle()
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);this.arguments=arguments?:Bundle();start()}
    override fun onStart(){
        val result=Bundle();var code=Activity.RESULT_CANCELED
        try{
            val mode=checkNotNull(arguments.getString("mode"));val id=checkNotNull(arguments.getString("fixtureId"))
            require(UUID.fromString(id).toString()==id)
            val initial=StudioOwnedConsentRecord(id,"a".repeat(64),"b".repeat(64),1,false)
            val revoked=initial.copy(revision=2,revoked=true)
            val store=AtomicFixtureConsentStore(targetContext)
            when(mode){
                "seed" -> {
                    check(store.read()==null)
                    check(store.compareAndSet(null,initial))
                    check(!store.compareAndSet(null,initial))
                    check(store.compareAndSet(initial,revoked))
                    check(store.read()==revoked)
                    result.putString("state","SEEDED_REVOKED")
                }
                "check" -> {
                    check(store.read()==revoked)
                    check(!store.compareAndSet(initial,initial.copy(revision=2)))
                    check(runCatching { store.compareAndSet(revoked,initial.copy(revision=3)) }.isFailure)
                    result.putString("state","REVOKE_PERSISTED")
                }
                "cleanup" -> {
                    check(!store.removeOwnedTestRecord(revoked.copy(deviceId=UUID.randomUUID().toString())))
                    check(store.removeOwnedTestRecord(revoked));check(store.read()==null)
                    result.putString("state","OWN_RECORD_REMOVED")
                }
                else -> error("Unsupported fixture mode")
            }
            result.putInt("pid",Process.myPid());result.putString("fixtureId",id);code=Activity.RESULT_OK
        }catch(error:Throwable){result.putString("error",error.javaClass.simpleName)}
        finally{finish(code,result)}
    }
}
