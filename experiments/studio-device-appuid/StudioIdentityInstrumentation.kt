package de.kontrakurs.kontraprompter.cloud

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.json.JSONObject

/** Separate fixture package and UID; never touches the KontraPrompter package. */
class StudioIdentityInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments ?: Bundle(); start() }

    override fun onStart() {
        val result = Bundle()
        var identityId: String? = null
        var ownKeyAttempted = false
        var outcome = Activity.RESULT_CANCELED
        var lock: FixtureExclusiveLock? = null
        try {
            // Exclusive across processes of this fixture UID. A concurrent run
            // fails closed before it inspects or modifies the test identity.
            lock = checkNotNull(FixtureExclusiveLock.tryAcquire(File(targetContext.filesDir,"studio-identity-test.lock"))) { "Fixture already running" }
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (arguments.getString("mode") == "check") {
                val id = checkNotNull(arguments.getString("identityId"))
                require(UUID.fromString(id).toString() == id)
                val alias = "videomaker-studio-device-v1-$id"
                result.putBoolean("alias_present",keys.containsAlias(alias))
                result.putBoolean("marker_present",File(targetContext.filesDir,"studio-device-auth/identity.json").exists())
                outcome = Activity.RESULT_OK
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
                override fun createKey(id: String): StudioIdentityKey {
                    check(id == identityId && !underlying.hasAnyDeviceKey())
                    ownKeyAttempted = true
                    return underlying.createKey(id)
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
                    if (ownKeyAttempted && keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
                    check(!keyStore.containsAlias(alias))
                    val dir = File(targetContext.filesDir,"studio-device-auth")
                    val allowed = setOf("identity.json","identity.json.bak","identity.json.new")
                    if (dir.exists()) check(dir.isDirectory && checkNotNull(dir.list()).all { it in allowed })
                    val markers = listOf("identity.json","identity.json.bak","identity.json.new").map { File(dir,it) }.filter { it.exists() }
                    // Validate every survivor before deleting any, so an
                    // ambiguous file always leaves all markers for review.
                    val owners = markers.map { item ->
                        check(item.isFile && item.length() <= 4096)
                        val marker = JSONObject(item.readText(Charsets.UTF_8))
                        FixtureMarkerOwner(item.name,marker.getString("id"),marker.getInt("version"))
                    }
                    check(fixtureMarkersOwned(id,owners))
                    for (item in markers) check(item.delete())
                    if (dir.exists()) check(dir.isDirectory && dir.delete())
                    result.putBoolean("fixture_cleanup_ok",true)
                } catch (error: Throwable) {
                    result.putString("cleanup_error",error.javaClass.simpleName)
                    outcome = Activity.RESULT_CANCELED
                }
            }
            try { lock?.close() } catch (error: Throwable) {
                result.putString("lock_cleanup_error",error.javaClass.simpleName)
                outcome = Activity.RESULT_CANCELED
            }
            // Includes failed CHECK calls: no instrumentation request hangs.
            finish(outcome,result)
        }
    }
}
