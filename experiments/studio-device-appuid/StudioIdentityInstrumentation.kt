package de.kontrakurs.kontraprompter.cloud

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Separate fixture package and UID; never touches the KontraPrompter package. */
class StudioIdentityInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments ?: Bundle(); start() }

    override fun onStart() {
        val result = Bundle()
        var identityId: String? = null
        var outcome = Activity.RESULT_CANCELED
        try {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (arguments.getString("mode") == "check") {
                val id = checkNotNull(arguments.getString("identityId"))
                require(UUID.fromString(id).toString() == id)
                val alias = "videomaker-studio-device-v1-$id"
                result.putBoolean("alias_present",keys.containsAlias(alias))
                result.putBoolean("marker_present",File(targetContext.filesDir,"studio-device-auth/identity.json").exists())
                finish(Activity.RESULT_OK,result)
                return
            }
            check(arguments.getString("mode") == "run")
            val underlying = AndroidStudioIdentityStore(targetContext)
            val observed = object : StudioIdentityStore by underlying {
                override fun write(marker: StudioIdentityMarker) {
                    if (marker.state == "creating") {
                        identityId = marker.id
                        sendStatus(0,Bundle().apply { putString("test_identity_id",marker.id) })
                    }
                    underlying.write(marker)
                }
            }
            val first = StudioDeviceIdentity(observed)
            check(first.load() == StudioIdentityState.EnrollmentRequired)
            val ready = first.createAfterExplicitConsent() as StudioIdentityState.Ready
            check(ready.id == identityId)
            val second = StudioDeviceIdentity(AndroidStudioIdentityStore(targetContext)).load() as StudioIdentityState.Ready
            check(ready.id == second.id && ready.publicKeyDer.contentEquals(second.publicKeyDer))
            val challenge = UUID.randomUUID().toString()
            check(first.signEnrollmentForFixture(ready.id,challenge,"A".repeat(43),listOf("projects:read"),"a".repeat(64)).isNotEmpty())
            val alias = "videomaker-studio-device-v1-${ready.id}"
            check(keys.containsAlias(alias))
            check(keys.getKey(alias,null).encoded == null)
            // Test only our own new fixture key loss; no regular-app key is visible.
            keys.deleteEntry(alias)
            check(first.load() == StudioIdentityState.RecoveryRequired)
            check(first.createAfterExplicitConsent() == StudioIdentityState.RecoveryRequired)
            check(!keys.containsAlias(alias))
            result.putString("test_identity_id",ready.id)
            result.putString("result","APP_UID_CREATE_SIGN_RELOAD_KEYLOSS_OK")
            outcome = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("error",error.javaClass.simpleName)
        } finally {
            val id = identityId
            if (id != null) {
                try {
                    require(UUID.fromString(id).toString() == id)
                    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    val alias = "videomaker-studio-device-v1-$id"
                    if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
                    check(!keyStore.containsAlias(alias))
                    val dir = File(targetContext.filesDir,"studio-device-auth")
                    for (name in listOf("identity.json","identity.json.bak","identity.json.new")) {
                        val item = File(dir,name)
                        if (item.exists()) check(item.isFile && item.delete())
                    }
                    if (dir.exists()) check(dir.isDirectory && dir.delete())
                    result.putBoolean("fixture_cleanup_ok",true)
                } catch (error: Throwable) {
                    result.putString("cleanup_error",error.javaClass.simpleName)
                    outcome = Activity.RESULT_CANCELED
                }
            }
            // The CHECK mode already finished above and intentionally changes nothing.
            if (arguments.getString("mode") != "check") finish(outcome,result)
        }
    }
}
