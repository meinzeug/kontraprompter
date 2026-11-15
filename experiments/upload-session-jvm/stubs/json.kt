package org.json
import com.google.gson.*
class JSONException(message:String):RuntimeException(message)
private fun wrap(value:JsonElement?):Any?=when {
 value==null||value.isJsonNull->JSONObject.NULL
 value.isJsonObject->JSONObject(value.asJsonObject)
 value.isJsonArray->JSONArray(value.asJsonArray)
 value.asJsonPrimitive.isBoolean->value.asBoolean
 value.asJsonPrimitive.isNumber->value.asLong
 else->value.asString
}
private fun unwrap(value:Any?):JsonElement=when(value){null,JSONObject.NULL->JsonNull.INSTANCE;is JSONObject->value.value;is JSONArray->value.value;is Number->JsonPrimitive(value);is Boolean->JsonPrimitive(value);else->JsonPrimitive(value.toString())}
class JSONObject internal constructor(internal val value:JsonObject) {
 constructor():this(JsonObject())
 constructor(text:String):this(JsonParser.parseString(text).asJsonObject)
 fun put(key:String,entry:Any?):JSONObject{value.add(key,unwrap(entry));return this}
 fun opt(key:String):Any?=if(value.has(key))wrap(value.get(key)) else null
 fun get(key:String):Any=wrap(value.get(key))?:throw JSONException(key)
 fun getString(key:String)=get(key).toString()
 fun getInt(key:String)=(get(key) as Number).toInt()
 fun getLong(key:String)=(get(key) as Number).toLong()
 fun getBoolean(key:String)=get(key) as Boolean
 fun optString(key:String,fallback:String="")=opt(key)?.takeUnless{it===NULL}?.toString()?:fallback
 fun optBoolean(key:String,fallback:Boolean=false)=opt(key) as? Boolean?:fallback
 fun has(key:String)=value.has(key)
 fun isNull(key:String)=!value.has(key)||value.get(key).isJsonNull
 fun getJSONArray(key:String)=get(key) as JSONArray
 fun getJSONObject(key:String)=get(key) as JSONObject
 override fun toString()=value.toString()
 companion object {val NULL=Any()}
}
class JSONArray internal constructor(internal val value:JsonArray) {
 constructor():this(JsonArray())
 constructor(text:String):this(JsonParser.parseString(text).asJsonArray)
 constructor(values:Collection<*>):this(JsonArray()){values.forEach{put(it)}}
 fun put(entry:Any?):JSONArray{value.add(unwrap(entry));return this}
 fun length()=value.size()
 fun opt(index:Int):Any?=if(index in 0 until value.size())wrap(value[index]) else null
 fun getJSONObject(index:Int)=opt(index) as JSONObject
 override fun toString()=value.toString()
}
