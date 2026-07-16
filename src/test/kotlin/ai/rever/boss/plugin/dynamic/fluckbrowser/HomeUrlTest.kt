package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the definition of the home (dashboard) state so a refactor can't
 * silently change which URLs render the dashboard and receive the home
 * tab identity (title [HOME_TITLE] + cleared favicon).
 */
class HomeUrlTest {

    @Test
    fun `empty and blank urls are home`() {
        assertTrue(isHomeUrl(""))
        assertTrue(isHomeUrl("   "))
    }

    @Test
    fun `about-blank is home`() {
        assertTrue(isHomeUrl("about:blank"))
    }

    @Test
    fun `real urls are not home`() {
        assertFalse(isHomeUrl("https://example.com"))
        assertFalse(isHomeUrl("http://localhost:3000"))
        assertFalse(isHomeUrl("about:blank#anchor"))
        assertFalse(isHomeUrl("about:config"))
    }

    @Test
    fun `home title is stable`() {
        assertEquals("Home", HOME_TITLE)
    }
}
