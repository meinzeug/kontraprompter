package de.kontrakurs.kontraprompter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Distances are expressed in density-independent pixels per second. */
object ScrollMath {
    fun distance(speedDpPerSecond: Float, elapsedSeconds: Float, density: Float): Float =
        speedDpPerSecond.coerceAtLeast(0f) * elapsedSeconds.coerceAtLeast(0f) * density
}

data class LayerTransform(val mirrorHorizontal: Boolean = false, val mirrorVertical: Boolean = false, val rotation: Int = 0) {
    val scaleX: Float get() = if (mirrorHorizontal) -1f else 1f
    val scaleY: Float get() = if (mirrorVertical) -1f else 1f
    fun rotate(): LayerTransform = copy(rotation = (rotation + 90) % 360)
}

data class Script(val id: String = UUID.randomUUID().toString(), val title: String = "Neues Skript", val text: String = "", val generationNotice: GenerationNotice? = null)

/** Legacy local scripts have no Studio metadata; malformed stored metadata stays visibly incomplete. */
private fun storedGenerationNotice(script: JSONObject): GenerationNotice? {
    if (!script.has("studio_notice")) return null
    val stored = script.opt("studio_notice") as? JSONObject ?: return GenerationNotice(GenerationNoticeStatus.INCOMPLETE)
    val raw = stored.opt("messages")
    val values = if (raw is JSONArray) (0 until minOf(raw.length(), 11)).map { raw.opt(it) } else raw
    val parsed = parseGenerationNotice(true, values)
    val version = stored.opt("version")
    val status = stored.opt("status") as? String
    if ((version !is Int && version !is Long) || (version as Number).toLong() != 1L) return parsed.copy(status = GenerationNoticeStatus.INCOMPLETE)
    return when (status) {
        GenerationNoticeStatus.PROVIDED.name -> parsed
        GenerationNoticeStatus.UNKNOWN.name -> if (parsed.status == GenerationNoticeStatus.PROVIDED && parsed.messages.isEmpty()) GenerationNotice(GenerationNoticeStatus.UNKNOWN) else parsed.copy(status = GenerationNoticeStatus.INCOMPLETE)
        GenerationNoticeStatus.INCOMPLETE.name -> parsed.copy(status = GenerationNoticeStatus.INCOMPLETE)
        else -> parsed.copy(status = GenerationNoticeStatus.INCOMPLETE)
    }
}

/** Private app storage; scripts are never sent over the network. */
class ScriptRepository(context: Context) {
    private val prefs = context.getSharedPreferences("scripts", Context.MODE_PRIVATE)
    fun load(): List<Script> = try {
        val array = JSONArray(prefs.getString("items", "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let { Script(it.getString("id"), it.getString("title"), it.getString("text"), storedGenerationNotice(it)) } }
    } catch (_: Exception) { emptyList() }
    fun save(scripts: List<Script>) {
        val array = JSONArray()
        scripts.forEach { script ->
            val item = JSONObject().put("id", script.id).put("title", script.title).put("text", script.text)
            script.generationNotice?.let { notice -> item.put("studio_notice", JSONObject().put("version", 1).put("status", notice.status.name).put("messages", JSONArray(notice.messages))) }
            array.put(item)
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
    var currentId: String?
        get() = prefs.getString("current", null)
        set(value) { prefs.edit().putString("current", value).apply() }
}
