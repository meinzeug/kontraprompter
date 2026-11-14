package de.kontrakurs.kontraprompter.pairing

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log

/** Only packaged into a separate test application; no KontraPrompter Activity is started. */
class KeystoreProbeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            main()
            finish(Activity.RESULT_OK, Bundle().apply { putString("result", "ANDROID_KEYSTORE_TLS13_OK NEGATIVE_PINS_OK NON_EXPORTABLE_OK CLEANUP_OK") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("error", Log.getStackTraceString(error)) })
        }
    }
}
