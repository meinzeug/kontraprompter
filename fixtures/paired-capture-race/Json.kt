package org.json

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class JSONObject {
    private val data = mutableMapOf<String, Any>()
    constructor()
    constructor(serialized: String) { data.putAll(objects[serialized] ?: error("unknown fixture JSON")) }
    fun put(key: String, value: Any?): JSONObject { if (value != null) data[key] = value; return this }
    fun optString(key: String, fallback: String = "") = data[key] as? String ?: fallback
    fun optLong(key: String, fallback: Long) = (data[key] as? Number)?.toLong() ?: fallback
    fun optBoolean(key: String, fallback: Boolean) = data[key] as? Boolean ?: fallback
    fun opt(key: String): Any? = data[key]
    override fun toString(): String = UUID.randomUUID().toString().also { objects[it] = data.toMap() }
    companion object { private val objects = ConcurrentHashMap<String, Map<String, Any>>() }
}
