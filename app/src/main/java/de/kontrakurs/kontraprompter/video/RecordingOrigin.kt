package de.kontrakurs.kontraprompter.video

/** Persisted provenance determines whether the server must require a web-device claim. */
enum class RecordingOrigin(val wire: String) {
    MANUAL("manual"), LAN("lan"), LAN_UNASSIGNED("lan_unassigned"), WEB("web");

    companion object {
        /** Old project-bound manifests were created only by web-controlled capture. */
        fun read(wire: String?, projectId: String?): RecordingOrigin {
            val origin = if (wire == null) {
                if (projectId == null) MANUAL else WEB
            } else entries.singleOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("Unbekannte Aufnahmeherkunft")
            val expectsProject = origin == LAN || origin == WEB
            require(expectsProject == (projectId != null)) { "Aufnahmeherkunft passt nicht zur Projektzuordnung" }
            return origin
        }
    }
}

/** Only web-controlled captures present the server's separately authenticated device claim. */
internal fun cameraClaimRecordingId(origin: RecordingOrigin, recordingId: String, boundProject: String?, targetProject: String): String? {
    require(RecordingOrigin.read(origin.wire, boundProject) == origin)
    return if (origin == RecordingOrigin.WEB && boundProject == targetProject) recordingId else null
}
