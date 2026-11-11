package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeAutofillTest {
    private fun field(isNewPassword: Boolean = true) = FocusedLoginField(
        key = "1|password|pw|password",
        isPassword = true,
        isNewPassword = isNewPassword,
        maxLength = 64,
        pageUrl = "https://example.com/signup",
        hasValue = false,
        left = 0.0,
        top = 0.0,
        width = 200.0,
        height = 30.0,
    )

    @Test
    fun `inactive until the engine has wired a browser, whatever the old host property says`() {
        System.setProperty("boss.browser.nativeAutofill", "true")
        try {
            assertFalse(NativeAutofill.isActive())
        } finally {
            System.clearProperty("boss.browser.nativeAutofill")
        }
    }

    @Test
    fun `saved logins list is offered normally and suppressed when native is active`() {
        assertTrue(shouldOfferSuggestions(field(), null, 1))
        assertTrue(shouldOfferSuggestions(field(), null, 1, nativeAutofill = false))
        assertFalse(shouldOfferSuggestions(field(), null, 1, nativeAutofill = true))
    }

    @Test
    fun `automatic generator offer is suppressed when native is active, forced stays`() {
        assertTrue(shouldOfferGeneratedPassword(field(), null, enabled = true))
        assertFalse(shouldOfferGeneratedPassword(field(), null, enabled = true, nativeAutofill = true))
        assertTrue(
            shouldOfferGeneratedPassword(field(), null, enabled = true, forced = true, nativeAutofill = true),
        )
        assertEquals(
            shouldOfferGeneratedPassword(field(false), null, enabled = true, forced = true),
            shouldOfferGeneratedPassword(field(false), null, enabled = true, forced = true, nativeAutofill = true),
        )
    }
}
