package ai.rever.boss.plugin.dynamic.fluckbrowser

import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        val script = middleClickTargetAtPointScript(24, 42)

        assertContains(script, "document.elementFromPoint(24, 42)")
        assertContains(script, "return 'link:' + link.href")
        assertContains(script, "submitter.setAttribute('formtarget', '_blank')")
        assertContains(script, "submitter.form.requestSubmit(submitter)")
    }
}
