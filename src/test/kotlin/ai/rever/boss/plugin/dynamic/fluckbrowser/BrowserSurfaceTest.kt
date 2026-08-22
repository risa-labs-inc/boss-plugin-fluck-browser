package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `fullscreen needs a handle, because its only control acts through one`() {
        // Unreachable today only because releaseBrowserHandle clears the fullscreen state - a fact
        // about a different function, and depending on it here is how an "unreachable" state
        // becomes a bug later. The placeholder's exit button asks the host to leave a session a
        // handle must own, so with no handle a spinner that resolves is the better answer.
        assertEquals(
            BrowserSurface.STARTING,
            browserSurfaceFor(
                error = null,
                isInFullscreen = true,
                hasHandle = false,
                showDashboard = false,
                hasDashboardProvider = true,
            ),
        )
    }

    @Test
    fun `a crash recovery in progress is a spinner, not a warning`() {
        // Recovery rebuilds the browser by itself, so it belongs on the starting surface - the tab
        // is busy, not broken, and `error` staying null is what lets the rebuilt page appear the
        // moment it arrives.
        val recovering = browserStatusFor(error = null, initMessage = RECOVERING_MESSAGE)

        assertEquals(RECOVERING_MESSAGE, recovering.message)
        assertEquals(true, recovering.isLoading)
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
    fun `a real failure with no handle is the error surface, not the starting one`() {
        // The state immediately after createBrowser returns null. Both branches could claim it,
        // and the failure has to win or the tab spins forever over a boot that already gave up.
        assertEquals(
            BrowserSurface.ERROR,
            browserSurfaceFor(
                error = "Failed to create browser instance. The browser engine may not be available.",
                isInFullscreen = false,
                hasHandle = false,
                showDashboard = false,
                hasDashboardProvider = true,
            ),
        )
    }

    @Test
    fun `the status surface says what the branch was chosen for`() {
        // One composable serves ERROR and STARTING, and its content is derived from the same
        // `error` that chose the branch - so a failure can never be drawn with the starting
        // message, or a boot with a warning icon.
        val failed = browserStatusFor(error = "Failed to create browser instance.", initMessage = INITIALIZING_MESSAGE)
        assertEquals("Failed to create browser instance.", failed.message)
        assertEquals(false, failed.isLoading)

        val starting = browserStatusFor(error = null, initMessage = SLOW_BOOT_MESSAGE)
        assertEquals(SLOW_BOOT_MESSAGE, starting.message)
        assertEquals(true, starting.isLoading)
    }

    @Test
    fun `releasing the handle also drops the load it was describing`() {
        // isLoading describes THAT handle's navigation, and the listener that would flip it goes
        // with the handle. Left set, a tab hibernated or retried mid-load spends the whole next
        // boot showing a Stop button and a running progress bar over a browser that no longer
        // exists. Same argument as the fullscreen state this function already clears.
        val state = FluckBrowserTabState().apply { isLoading = true }

        state.releaseBrowserHandle()

        assertFalse(state.isLoading, "a load cannot still be running on a handle the tab let go of")
    }

    @Test
    fun `a fresh tab starts with nothing wrong`() {
        // The literal regression: this field used to hold a message from the moment the tab
        // existed, which is what put the warning triangle on screen.
        assertNull(FluckBrowserTabState().error)
        assertEquals(INITIALIZING_MESSAGE, FluckBrowserTabState().initMessage)
    }
}
