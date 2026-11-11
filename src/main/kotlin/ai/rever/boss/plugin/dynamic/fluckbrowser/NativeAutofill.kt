package ai.rever.boss.plugin.dynamic.fluckbrowser

/**
 * True once this plugin has wired native Chromium autofill (backed by Secret Manager) into a
 * browser. Its own JS autofill (saved-logins popup, login probe, save prompt) then stands down;
 * explicit actions (right-click fill, password generator) stay. Snapshot state, so composition
 * that reads it recomposes when the engine comes up.
 */
internal object NativeAutofill {
    fun isActive(): Boolean = ai.rever.boss.plugin.dynamic.fluckbrowser.autofill.NativeAutofillEngine.active
}
