package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The tab's surface choice, and the two properties it violated in production.
 *
 * A browser tab rendered a warning triangle with Retry Loading / Reset Tab on every new tab,
 * and sometimes stayed there until the tab was reset - while the browser underneath was fine.
 * The cause was a state machine, not a layout: `error` was seeded with "Initializing browser..."
 * and cleared by a single page-load callback, and the render `when` checked `error` first. So
 * "still starting" and "failed" were the same state, and the one that wins the ordering is the
 * one that says the tab is broken.
 *
 * These tests pin the two invariants that follow: a tab with a live browser is never described as
 * failed, and a tab without one is never nothing at all.
 */
class BrowserSurfaceTest {
    @Test
    fun `a live handle mid-load shows the browser, not an error`() {
        assertEquals(
            BrowserSurface.BROWSER,
            browserSurfaceFor(
                error = null,
                isInFullscreen = false,
                hasHandle = true,
                showDashboard = false,
                hasDashboardProvider = true,
            ),
        )
    }

    @Test
    fun `no handle and nothing wrong is the starting surface, never a blank pane`() {
        // The old `when` had no branch for this at all: `error != null` covered it by accident,
        // and a hibernation wake (which clears the handle with no error) rendered an empty Box.
        assertEquals(
            BrowserSurface.STARTING,
            browserSurfaceFor(
                error = null,
                isInFullscreen = false,
                hasHandle = false,
                showDashboard = false,
                hasDashboardProvider = true,
            ),
        )
    }

    @Test
    fun `starting wins over the dashboard, so its links cannot be swallowed`() {
        // The dashboard navigates through the handle; offering it before one exists would eat the
        // first click.
        assertEquals(
            BrowserSurface.STARTING,
            browserSurfaceFor(
                error = null,
                isInFullscreen = false,
                hasHandle = false,
                showDashboard = true,
                hasDashboardProvider = true,
            ),
        )
    }

    @Test
    fun `home with a handle and a provider shows the dashboard`() {
        assertEquals(
            BrowserSurface.DASHBOARD,
            browserSurfaceFor(
                error = null,
                isInFullscreen = false,
                hasHandle = true,
                showDashboard = true,
                hasDashboardProvider = true,
            ),
        )
    }

    @Test
    fun `home with no dashboard provider falls back to the browser`() {
        assertEquals(
            BrowserSurface.BROWSER,
            browserSurfaceFor(
                error = null,
                isInFullscreen = false,
                hasHandle = true,
                showDashboard = true,
                hasDashboardProvider = false,
            ),
        )
    }

    @Test
    fun `a real error still wins over everything, including a handle`() {
        // The fix must not go the other way: a genuine failure has to stay visible even when a
        // stale handle is still hanging around.
        assertEquals(
            BrowserSurface.ERROR,
            browserSurfaceFor(
                error = "Failed to create browser instance. The browser engine may not be available.",
                isInFullscreen = true,
                hasHandle = true,
                showDashboard = true,
                hasDashboardProvider = true,
            ),
        )
    }

    @Test
    fun `fullscreen outranks the browser and the dashboard`() {
        assertEquals(
            BrowserSurface.FULLSCREEN,
            browserSurfaceFor(
                error = null,
                isInFullscreen = true,
                hasHandle = true,
                showDashboard = true,
                hasDashboardProvider = true,
            ),
        )
    }

    @Test
    fun `a fresh tab starts with nothing wrong`() {
        // The literal regression: this field used to hold a message from the moment the tab
        // existed, which is what put the warning triangle on screen.
        assertNull(FluckBrowserTabState().error)
        assertEquals(INITIALIZING_MESSAGE, FluckBrowserTabState().initMessage)
    }
}
