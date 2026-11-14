package de.kontrakurs.kontraprompter

/** Historical information returned by Videomaker, not an approval of the script. */
enum class GenerationNoticeStatus { PROVIDED, UNKNOWN, INCOMPLETE }

data class GenerationNotice(val status: GenerationNoticeStatus, val messages: List<String> = emptyList())

/** Never coerce model- or server-supplied non-string values into displayed warnings. */
internal fun parseGenerationNotice(present: Boolean, value: Any?): GenerationNotice {
    if (!present) return GenerationNotice(GenerationNoticeStatus.UNKNOWN)
    if (value !is List<*>) return GenerationNotice(GenerationNoticeStatus.INCOMPLETE)
    var incomplete = value.size > 10
    val messages = mutableListOf<String>()
    value.take(10).forEach { item ->
        if (item !is String) { incomplete = true; return@forEach }
        if (item.length > 1000) incomplete = true
        val bounded = item.take(1000)
        if (bounded.any { it.isISOControl() && it != '\n' && it != '\t' }) incomplete = true
        val text = bounded.map { if (it.isISOControl() && it != '\n' && it != '\t') ' ' else it }.joinToString("").trim()
        if (text.isEmpty()) incomplete = true else messages.add(text)
    }
    return GenerationNotice(if (incomplete) GenerationNoticeStatus.INCOMPLETE else GenerationNoticeStatus.PROVIDED, messages)
}

internal fun GenerationNotice.summary(): String = when {
    status == GenerationNoticeStatus.INCOMPLETE && messages.isNotEmpty() -> "Hinweise unvollständig: ${messages.first().take(140)}${if (messages.first().length > 140) "…" else ""}"
    messages.isNotEmpty() -> "Hinweis: ${messages.first().take(140)}${if (messages.first().length > 140) "…" else ""}"
    status == GenerationNoticeStatus.UNKNOWN -> "Keine Angaben zu Erstellhinweisen vorhanden"
    status == GenerationNoticeStatus.INCOMPLETE -> "Erstellhinweise unvollständig"
    else -> "Keine Erstellhinweise gespeichert"
}

internal fun GenerationNotice.needsAttention(): Boolean = messages.isNotEmpty() || status != GenerationNoticeStatus.PROVIDED

internal fun requireProjectString(value: Any?): String = value as? String ?: throw IllegalArgumentException("Die Skriptdaten vom Studio sind ungültig.")

/** A local edit can invalidate even an empty set of historical Studio notices. */
internal fun preserveNoticeOnEdit(before: Script, after: Script): Script {
    if (before.id != after.id || (before.title == after.title && before.text == after.text)) return after
    // A locally edited copy is no longer the same text/title as the remote project.
    // Do not silently attach its recording to the unchanged cloud script.
    return after.copy(generationNotice = before.generationNotice?.copy(status = GenerationNoticeStatus.INCOMPLETE), studioProjectId = null)
}
