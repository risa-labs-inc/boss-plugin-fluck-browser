package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserShareManagerApprovalTest {
    @Test fun approveAndDenyResolveAwaitApproval() = runTest {
        // Approve path
        val granted = async { BrowserShareManager.awaitApproval("viewer-A", wantsControl = false) }
        testScheduler.runCurrent()
        val req = BrowserShareManager.pendingRequests.value.single()
        assertEquals("viewer-A", req.deviceName)
        BrowserShareManager.approveRequest(req.id)
        testScheduler.runCurrent()
        assertTrue(granted.await())
        assertTrue(BrowserShareManager.pendingRequests.value.isEmpty())

        // Deny path
        val denied = async { BrowserShareManager.awaitApproval("viewer-B", wantsControl = true) }
        testScheduler.runCurrent()
        val req2 = BrowserShareManager.pendingRequests.value.single()
        BrowserShareManager.denyRequest(req2.id)
        testScheduler.runCurrent()
        assertFalse(denied.await())
        assertTrue(BrowserShareManager.pendingRequests.value.isEmpty())
    }
}
