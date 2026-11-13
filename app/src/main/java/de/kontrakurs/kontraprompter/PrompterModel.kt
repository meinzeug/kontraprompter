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

data class Script(val id: String = UUID.randomUUID().toString(), val title: String = "Neues Skript", val text: String = "")

/** Private app storage; scripts are never sent over the network. */
class ScriptRepository(context: Context) {
    private val prefs = context.getSharedPreferences("scripts", Context.MODE_PRIVATE)
    fun load(): List<Script> = try {
        val array = JSONArray(prefs.getString("items", "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let { Script(it.getString("id"), it.getString("title"), it.getString("text")) } }
    } catch (_: Exception) { emptyList() }
    fun save(scripts: List<Script>) {
        val array = JSONArray()
        scripts.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title).put("text", it.text)) }
        prefs.edit().putString("items", array.toString()).apply()
    }
    var currentId: String?
        get() = prefs.getString("current", null)
        set(value) { prefs.edit().putString("current", value).apply() }
}
