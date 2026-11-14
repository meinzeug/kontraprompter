package de.kontrakurs.kontraprompter

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

/** The session code is a bearer secret even when the user chooses to reveal it. */
internal fun sessionCodeKeyboardOptions() =
    KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
