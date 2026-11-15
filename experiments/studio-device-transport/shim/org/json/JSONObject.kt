package org.json

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Only the subset used by this test; Android uses the real framework class. */
class JSONObject {
    private val value:JsonObject
    constructor(){value=JsonObject()}
    constructor(raw:String){value=JsonParser.parseString(raw).asJsonObject}
    fun put(key:String,item:String):JSONObject {value.addProperty(key,item);return this}
    fun keys():MutableIterator<String> = value.keySet().iterator()
    fun opt(key:String):Any? {
        val item=value.get(key) ?: return null
        if(!item.isJsonPrimitive)return null
        val primitive=item.asJsonPrimitive
        return when {primitive.isString -> primitive.asString;primitive.isNumber -> primitive.asNumber;primitive.isBoolean -> primitive.asBoolean;else -> null}
    }
    override fun toString():String=value.toString()
}
