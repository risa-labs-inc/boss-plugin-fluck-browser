package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.PopupNavigation
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
        assertContains(script, "element.closest('a[href], area[href]')")
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
    fun `native popup racing link resolution is buffered then suppressed`() {
        val coordinator = MiddleClickPopupCoordinator()
        val gesture = coordinator.begin()
        val trackingPopup = PopupNavigation("https://www.google.com/gen_204")

        assertEquals(
            MiddleClickPopupDisposition.BUFFERED,
            coordinator.onPopup(trackingPopup)
        )
        assertNull(coordinator.release())

        val resolution = coordinator.complete(
            token = gesture.token,
            directLinkResolved = true
        )
        assertTrue(resolution.accepted)
        assertTrue(resolution.popupsToForward.isEmpty())
        assertEquals(gesture.token, resolution.finishAfterReleaseToken)
        assertEquals(
            MiddleClickPopupDisposition.SUPPRESS,
            coordinator.onPopup(trackingPopup)
        )

        coordinator.finish(gesture.token)
        assertEquals(
            MiddleClickPopupDisposition.FORWARD,
            coordinator.onPopup(PopupNavigation("https://example.com/next"))
        )
    }

    @Test
    fun `native popup is forwarded when direct resolution fails`() {
        val coordinator = MiddleClickPopupCoordinator()
        val gesture = coordinator.begin()
        val popup = PopupNavigation("https://example.com/from-chromium")

        assertEquals(MiddleClickPopupDisposition.BUFFERED, coordinator.onPopup(popup))

        val resolution = coordinator.complete(
            token = gesture.token,
            directLinkResolved = false
        )
        assertTrue(resolution.accepted)
        assertEquals(listOf(popup), resolution.popupsToForward)
        assertEquals(
            MiddleClickPopupDisposition.FORWARD,
            coordinator.onPopup(PopupNavigation("https://example.com/later"))
        )
    }

    @Test
    fun `subsequent pointer gesture cancels suppression immediately`() {
        val coordinator = MiddleClickPopupCoordinator()
        val gesture = coordinator.begin()
        val resolution = coordinator.complete(
            token = gesture.token,
            directLinkResolved = true
        )
        assertTrue(resolution.accepted)

        assertTrue(coordinator.cancel().isEmpty())
        assertEquals(
            MiddleClickPopupDisposition.FORWARD,
            coordinator.onPopup(PopupNavigation("https://example.com/intentional"))
        )
    }

    @Test
    fun `superseded renderer result cannot open an old gesture`() {
        val coordinator = MiddleClickPopupCoordinator()
        val firstGesture = coordinator.begin()
        val secondGesture = coordinator.begin()

        val staleResolution = coordinator.complete(
            token = firstGesture.token,
            directLinkResolved = true
        )
        assertFalse(staleResolution.accepted)

        val currentResolution = coordinator.complete(
            token = secondGesture.token,
            directLinkResolved = true
        )
        assertTrue(currentResolution.accepted)
    }
}
