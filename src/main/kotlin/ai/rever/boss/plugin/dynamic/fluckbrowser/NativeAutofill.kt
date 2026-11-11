package ai.rever.boss.plugin.dynamic.fluckbrowser

/**
 * The host sets `boss.browser.nativeAutofill=true` when Chromium autofill backed by Secret Manager
 * is active. This plugin's own JS autofill (saved-logins popup, login probe, save prompt) then
 * stands down; explicit actions (right-click fill, password generator) stay.
 *
 * Read on every call, never cached: the host may set it after the plugin starts.
 */
internal object NativeAutofill {
    const val PROPERTY = "boss.browser.nativeAutofill"

    fun isActive(): Boolean = System.getProperty(PROPERTY) == "true"
}
