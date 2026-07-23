package ai.rever.boss.plugin.dynamic.fluckbrowser

import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserMouseNavigationTest {

    @Test
    fun `middle button is not treated as browser history navigation`() {
        assertNull(browserMouseNavigationForButton(MouseEvent.BUTTON2))
    }

    @Test
    fun `auxiliary back and forward buttons remain intercepted`() {
        listOf(4, 6, 8).forEach { button ->
            assertEquals(BrowserMouseNavigation.BACK, browserMouseNavigationForButton(button))
        }
        listOf(5, 7, 9).forEach { button ->
            assertEquals(BrowserMouseNavigation.FORWARD, browserMouseNavigationForButton(button))
        }
    }

    @Test
    fun `middle click target is resolved at the pressed viewport point`() {
        val script = middleClickTargetAtPointScript(24f, 42f)

        assertContains(script, "24.0 / deviceScale")
        assertContains(script, "42.0 / deviceScale")
        assertContains(script, "window.devicePixelRatio || 1")
        assertContains(script, "link.href.baseVal")
        assertContains(script, "resolvedUrl.protocol !== 'http:'")
        assertContains(script, "return 'link:' + resolvedUrl.href")
        assertContains(script, "submitter.setAttribute('formtarget', '_blank')")
        assertContains(script, "submitter.form.requestSubmit(submitter)")
    }

    @Test
    fun `only web URLs can be opened from a middle click`() {
        assertEquals(
            "https://example.com/path",
            middleClickUrlFromScriptResult("link:https://example.com/path")
        )
        assertEquals(
            "http://localhost:8080/",
            middleClickUrlFromScriptResult("link:http://localhost:8080/")
        )
        assertNull(middleClickUrlFromScriptResult("link:javascript:alert(1)"))
        assertNull(middleClickUrlFromScriptResult("link:data:text/html,hello"))
        assertNull(middleClickUrlFromScriptResult("link:file:///tmp/example.html"))
        assertNull(middleClickUrlFromScriptResult("submitted"))
    }

    @Test
    fun `middle click popup suppression is one shot and expires`() {
        var now = 100L
        val guard = MiddleClickPopupGuard(
            nowNanos = { now },
            suppressionWindowNanos = 10L
        )

        assertFalse(guard.consumeIfArmed())

        guard.arm()
        assertTrue(guard.consumeIfArmed())
        assertFalse(guard.consumeIfArmed())

        guard.arm()
        now = 111L
        assertFalse(guard.consumeIfArmed())
    }
}
