package de.kontrakurs.kontraprompter.pairing

import java.security.KeyStore
import java.util.UUID

/** Removes exactly the two explicit aliases printed by one prior shell fixture. */
fun main(args: Array<String>) {
    require(args.size == 2)
    val prefix = "kp-shell-tls-fixture-"
    val id = args[0].removePrefix(prefix).removeSuffix("-camera")
    require(UUID.fromString(id).toString() == id)
    require(args[0] == "$prefix$id-camera" && args[1] == "$prefix$id-prompter")
    try {
        ensureTestProvider()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (alias in args) {
            val present = store.containsAlias(alias)
            if (present) store.deleteEntry(alias)
            check(!store.containsAlias(alias))
            println("FIXTURE_ALIAS_REMOVED=$alias WAS_PRESENT=$present")
        }
        println("FIXTURE_CLEANUP_OK")
    } catch (error: Throwable) { error.printStackTrace(); throw error }
}
