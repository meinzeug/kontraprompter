package de.kontrakurs.kontraprompter

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PairingInputPolicyTest {
    @Test fun sessionCodeUsesPasswordKeyboardWithoutAutocorrect() {
        val options = sessionCodeKeyboardOptions()
        assertEquals(KeyboardType.Password, options.keyboardType)
        assertFalse(options.autoCorrectEnabled ?: true)
    }
}
