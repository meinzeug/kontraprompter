package de.kontrakurs.kontraprompter
enum class GenerationNoticeStatus { UNKNOWN }
data class GenerationNotice(val status:GenerationNoticeStatus)
fun parseGenerationNotice(present:Boolean,values:Any?)=GenerationNotice(GenerationNoticeStatus.UNKNOWN)
fun requireProjectString(value:Any?)=value as String
