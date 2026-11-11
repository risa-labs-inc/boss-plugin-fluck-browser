package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill

import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JxProbeTest {
    /** A stand-in for the host's BrowserHandleImpl: the engine reads its private `browser` field. */
    private class FakeHandleBase(
        @Suppress("unused") private val browser: Any,
    )

    @Test
    fun `a classloader without JxBrowser fails the probe instead of throwing`() {
        val empty = URLClassLoader(emptyArray(), null)
        val probe = Jx.resolveFor(empty)
        assertTrue(probe.isFailure)
        assertTrue(probe.exceptionOrNull() is ClassNotFoundException)
    }

    @Test
    fun `a non-JxBrowser object fails the probe through its own loader`() {
        // The plugin's own loader has no com.teamdev classes either: same clean failure.
        assertTrue(Jx.resolve(Any()).isFailure || Jx.resolve("not a browser").isFailure)
    }

    @Test
    fun `dropdown key payloads are recognised and nothing else is`() {
        assertEquals("down", AutofillKeys.parse("""{"nav":"down"}"""))
        assertEquals("escape", AutofillKeys.parse("""{"nav":"escape"}"""))
        assertNull(AutofillKeys.parse("""{"username":"a","password":"b"}"""))
        assertNull(AutofillKeys.parse("""{"nav":"left"}"""))
    }

    @Test
    fun `the key script only acts while the flag is set`() {
        val js = AutofillKeys.script("bridge.emit")
        assertTrue("if (!e.isTrusted || !navState) return;" in js)
        assertTrue(AutofillKeys.EVENT in AutofillKeys.setState(1))
        assertTrue("window." !in js, "no globals on window")
    }

    @Test
    fun `suggestion rows`() {
        val pw = SuggestionRows.rowOf("ada@x.com", "••••", null, null, "PASSWORDS", "example.com")
        assertEquals(SuggestionKind.PASSWORD, pw.kind)
        assertEquals("••••  ·  example.com", pw.secondary)
        val card = SuggestionRows.rowOf("Visa  ••4242, •, 12/30", null, null, null, "CREDIT_CARDS", "x")
        assertEquals("Visa •••• 4242", card.primary)
        assertEquals("Expires 12/30", card.secondary)
    }
}
