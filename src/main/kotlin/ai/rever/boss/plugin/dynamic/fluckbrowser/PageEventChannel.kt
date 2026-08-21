package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserHandle

/**
 * Reaches `BrowserHandle`'s page-event channel **reflectively**, so this plugin loads on hosts that
 * do not have it.
 *
 * **Why reflection, when the api documents a `supportsPageEventScript` flag for exactly this.**
 * Because the flag cannot be read on a host that lacks it. `BrowserHandle` is `@HostImplemented`:
 * the host compiles its own copy and serves it parent-first, so a plugin that *names* a member the
 * host's copy does not have is rejected outright - `BinaryCompatibilityValidator` refuses the whole
 * jar at load and the host disables the plugin. Not a `NoSuchMethodError` at the call site, which a
 * guard could survive: the plugin never runs at all.
 *
 * So a direct call and `minBossVersion` are the same decision. Declaring the floor is honest, and it
 * is what 1.2.22 did - but the consequence is that a plugin carrying an optional feature becomes
 * unloadable on every host below the floor. fluck-browser is a `systemPlugin` and it IS the browser
 * tab, so on 9.4.22 that read as "my browser disappeared", and the only recovery was to replace the
 * jar by hand.
 *
 * Reflection makes the dependency genuinely optional: no member reference in the bytecode, so the
 * validator has nothing to reject, and the credential-save half simply does not arm where the host
 * cannot serve it. The suggestor half needs none of this and works everywhere.
 *
 * **What is NOT reflective, and why that is safe.** `PAGE_EVENT_BRIDGE` and `PAGE_EVENT_EMIT` are
 * `const val`, so Kotlin inlines them into this jar's constant pool as string literals - verified
 * with `javap`, which shows no `getstatic` against `BrowserHandleKt`. A `const` reference leaves
 * nothing to resolve at runtime, which is why the names can stay shared while the methods cannot.
 *
 * **Scope of the reflection**, deliberately narrow: three members on one interface, resolved once
 * per handle class, called nowhere else. `PageEventChannelSourceTest` fails the build if a direct
 * call reappears anywhere in this plugin.
 */
internal object PageEventChannel {
    /**
     * Whether this handle can actually deliver page events.
     *
     * Two questions in one, and both matter. The method's *presence* answers "was this host built
     * with the channel" - absent on anything before it existed. `supportsPageEventScript` then
     * answers "is this a real implementation rather than the api's no-op default", which is the
     * distinction the api added that flag for.
     */
    fun isSupported(handle: BrowserHandle): Boolean {
        if (method(handle, "setPageEventScript", 2) == null) return false
        val supports =
            method(handle, "getSupportsPageEventScript", 0)
                // No flag but the method is there: a host built between the two api levels. Treated
                // as supported, because trying and being told nothing arrives is strictly better
                // than refusing a channel that works.
                ?: return true
        return runCatching { supports.invoke(handle) as? Boolean }.getOrNull() ?: false
    }

    /**
     * Install [script] and route its events to [onEvent]. False when the host has no channel.
     *
     * [onEvent] is passed straight through as the `Function2` a Kotlin lambda already is, so the
     * host receives exactly what a direct call would have handed it.
     */
    fun install(
        handle: BrowserHandle,
        script: String,
        onEvent: (String, String) -> Unit,
    ): Boolean {
        val install = method(handle, "setPageEventScript", 2) ?: return false
        return runCatching { install.invoke(handle, script, onEvent) }.isSuccess
    }

    /** Uninstall. Silent when the host has no channel, since there is nothing to retract. */
    fun clear(handle: BrowserHandle) {
        method(handle, "clearPageEventScript", 0)?.let { clear ->
            runCatching { clear.invoke(handle) }
        }
    }

    /**
     * Resolve a member on the handle's own class.
     *
     * Matched by name and arity rather than by signature: the callback parameter is a
     * `kotlin.jvm.functions.Function2` this plugin must not name for the same reason it must not
     * name the method, and no overload of any of these exists to be confused with.
     */
    private fun method(
        handle: BrowserHandle,
        name: String,
        parameterCount: Int,
    ) = runCatching {
        handle.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == parameterCount }
    }.getOrNull()
}
