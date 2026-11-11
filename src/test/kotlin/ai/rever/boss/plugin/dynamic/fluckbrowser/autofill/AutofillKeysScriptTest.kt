package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill

import ai.rever.boss.plugin.dynamic.fluckbrowser.CredentialCapture
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A page must not be able to drive the native dropdown: nothing it can post reaches accept. */
class AutofillKeysScriptTest {
    @Test
    fun `a page-emitted nav message is not a credential and is dropped`() {
        // The capture loop only turns parsed credentials into anything; dropdown keys come from the
        // host's PressKeyCallback, which a page cannot synthesise.
        assertNull(CredentialCapture.parse("""{"nav":"enter"}"""))
        assertNull(CredentialCapture.parse("""{"nav":"down"}"""))
    }

    @Test
    fun `the page script carries no dropdown key handling`() {
        assertFalse("nav" in CredentialCapture.INSTALL_JS.replace("navigat", ""))
    }

    @Test
    fun `a synthetic Enter does not trigger a capture`() {
        assertTrue("e.isTrusted && e.key === 'Enter'" in CredentialCapture.INSTALL_JS)
    }
}
