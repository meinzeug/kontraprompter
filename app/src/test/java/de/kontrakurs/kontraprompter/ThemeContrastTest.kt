package de.kontrakurs.kontraprompter

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun luminance(argb: Long): Double {
        fun component(shift: Int): Double {
            val value = ((argb shr shift) and 255).toDouble() / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * component(16) + .7152 * component(8) + .0722 * component(0)
    }
    private fun contrast(first: Long, second: Long): Double {
        val a = luminance(first); val b = luminance(second)
        return (max(a, b) + .05) / (min(a, b) + .05)
    }

    @Test fun `fallback text and controls meet normal text contrast`() {
        val pairs = listOf(
            ThemePalette.LIGHT_ON_SURFACE to ThemePalette.LIGHT_BACKGROUND,
            ThemePalette.LIGHT_ON_SURFACE to ThemePalette.LIGHT_SURFACE,
            ThemePalette.LIGHT_ON_SURFACE_VARIANT to ThemePalette.LIGHT_SURFACE_VARIANT,
            ThemePalette.LIGHT_ON_PRIMARY to ThemePalette.LIGHT_PRIMARY,
            ThemePalette.LIGHT_ON_PRIMARY_CONTAINER to ThemePalette.LIGHT_PRIMARY_CONTAINER,
            ThemePalette.LIGHT_ON_SECONDARY to ThemePalette.LIGHT_SECONDARY,
            ThemePalette.LIGHT_ON_ERROR to ThemePalette.LIGHT_ERROR,
            ThemePalette.DARK_ON_SURFACE to ThemePalette.DARK_BACKGROUND,
            ThemePalette.DARK_ON_SURFACE to ThemePalette.DARK_SURFACE,
            ThemePalette.DARK_ON_SURFACE_VARIANT to ThemePalette.DARK_SURFACE_VARIANT,
            ThemePalette.DARK_ON_PRIMARY to ThemePalette.DARK_PRIMARY,
            ThemePalette.DARK_ON_PRIMARY_CONTAINER to ThemePalette.DARK_PRIMARY_CONTAINER,
            ThemePalette.DARK_ON_SECONDARY to ThemePalette.DARK_SECONDARY,
            ThemePalette.DARK_ON_ERROR to ThemePalette.DARK_ERROR,
        )
        pairs.forEach { (text, background) ->
            assertTrue("Contrast ${contrast(text, background)} for ${text.toString(16)}/${background.toString(16)}", contrast(text, background) >= 4.5)
        }
    }

    @Test fun `video overlay remains readable over worst-case white pixels`() {
        // White is the brightest possible camera pixel under a black overlay.
        val remaining = ((1.0 - ThemePalette.VIDEO_OVERLAY_ALPHA.toDouble()) * 255).toInt().coerceIn(0, 255)
        val composed = 0xff000000L or (remaining.toLong() shl 16) or (remaining.toLong() shl 8) or remaining.toLong()
        assertTrue(contrast(0xffffffffL, composed) >= 4.5)
    }
}
