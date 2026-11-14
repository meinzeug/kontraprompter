package de.kontrakurs.kontraprompter.cloud

import de.kontrakurs.kontraprompter.remotePrompterValue
import java.net.HttpURLConnection
import java.net.URI

/** Isolated shell-process probe, not part of the app or any production APK. */
object RemotePrompterDeviceProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && args[0] == "http://127.0.0.1:39077")
        val origin = args[0]; val id = "00000000-0000-4000-8000-000000000002"
        var speed = 35f; var font = 44f; var status = "Bereit"; var ack = emptyList<String>()
        val seen = linkedSetOf<String>(); val deadline = System.nanoTime() + 120_000_000_000L
        while (System.nanoTime() < deadline && seen.size < 6) {
            val commands = pollPrompterDevice({ path, method ->
                (URI(origin + path).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = method; connectTimeout = 5_000; readTimeout = 5_000; instanceFollowRedirects = false; useCaches = false
                }
            }, origin, "fixture-only", { true }, {}, id, "Android-Fixture", status, ack, speed, font)
            ack = emptyList()
            commands.forEach { command ->
                check(command.remainingMs > 0)
                when (command.type) {
                    "set_speed" -> speed = checkNotNull(remotePrompterValue(command.type, command.value))
                    "set_font_size" -> font = checkNotNull(remotePrompterValue(command.type, command.value))
                    "load" -> check(command.projectId == "00000000-0000-4000-8000-000000000001")
                    "play" -> status = "Läuft"
                    "pause" -> status = "Pausiert"
                    "reset" -> status = "Anfang"
                    else -> error("unexpected fixture action")
                }
                seen.add(command.type); ack = listOf(command.id)
            }
            Thread.sleep(250)
        }
        // Send final ACK and the actually applied settings/status to the fixture server.
        pollPrompterDevice({ path, method ->
            (URI(origin + path).toURL().openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 5_000; readTimeout = 5_000; instanceFollowRedirects = false; useCaches = false }
        }, origin, "fixture-only", { true }, {}, id, "Android-Fixture", status, ack, speed, font)
        check(seen == linkedSetOf("set_speed", "set_font_size", "load", "play", "pause", "reset"))
        check(speed == 76f && font == 64f && status == "Anfang")
        println("ANDROID_PROMPTER_WIRE_OK speed=$speed font=$font status=$status actions=${seen.size}")
    }
}
