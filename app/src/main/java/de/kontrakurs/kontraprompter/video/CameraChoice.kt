package de.kontrakurs.kontraprompter.video

/** Camera selection is fixed for a session, including while recording. */
internal fun chooseCamera(names: List<String>, preferFront: Boolean, isFront: (String) -> Boolean, isBack: (String) -> Boolean): String? =
    names.firstOrNull(if (preferFront) isFront else isBack)
        ?: names.firstOrNull(if (preferFront) isBack else isFront)
        ?: names.firstOrNull()
