package org.pockettts.android.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class PocketThemeTest {
    @Test fun readableTextAndControlsInEveryMode() {
        for (mode in listOf("light", "dark", "amoled")) {
            val colors = pocketColors(mode, false)
            val glass = pocketGlass(mode != "light")
            val surfaces = listOf(glass.card.compositeOver(colors.background), glass.sheet.compositeOver(colors.background))
            for (surface in surfaces) {
                for (text in listOf(colors.onSurface, colors.onSurfaceVariant, colors.primary, colors.error, glass.success)) {
                    assertTrue("$mode text contrast: ${contrast(text, surface)}", contrast(text, surface) >= 4.5)
                }
            }
            assertTrue(contrast(colors.onPrimary, colors.primary) >= 4.5)
            assertTrue(contrast(colors.onPrimaryContainer, colors.primaryContainer) >= 4.5)
            assertTrue(contrast(colors.outline, surfaces[0]) >= 3.0)
        }
        assertEquals(Color.Black, pocketColors("amoled", false).background)
        assertEquals(pocketColors("dark", false), pocketColors("system", true))
        assertEquals(pocketColors("light", true), pocketColors("system", false))
    }
    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance(); val second = b.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }
}
