package app.hermes.mobile.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesThemeTest {
    @Test fun storedModesAndFallbackResolve() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStoredValue("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStoredValue("dark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue("unknown"))
    }

    @Test fun darkResolutionFollowsMode() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, false))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, true))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, false))
    }

    @Test fun paletteIsNeutralWhiteAndGray() {
        assertEquals(Color(0xFFF5F5F5), HermesColors.LightBackground)
        assertEquals(Color.White, HermesColors.LightSurface)
        assertEquals(Color(0xFFE1E1E1), HermesColors.LightUserBubble)
        assertEquals(Color(0xFF303030), HermesColors.DarkBackground)
        assertEquals(Color(0xFF393939), HermesColors.DarkSurface)
        assertEquals(Color(0xFF454545), HermesColors.DarkHermesBubble)
        assertEquals(Color(0xFF5A5A5A), HermesColors.DarkUserBubble)
    }
}
