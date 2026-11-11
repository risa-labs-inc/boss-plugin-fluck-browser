package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill

import ai.rever.boss.plugin.dynamic.fluckbrowser.CredentialCapture
import ai.rever.boss.plugin.dynamic.fluckbrowser.JsSandbox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A page script must not be able to drive our dropdown (and so read a filled password) with synthetic keys. */
class AutofillKeysScriptTest {
    private val harness =
        """
        var docListeners = {};
        document.addEventListener = function (type, fn) { (docListeners[type] = docListeners[type] || []).push(fn); };
        function fire(type, ev) { (docListeners[type] || []).forEach(function (fn) { fn(ev); }); }
        var emitted = [];
        var emitter = { emit: function (s) { emitted.push(s); } };
        function key(k, trusted) {
            return { key: k, isTrusted: trusted, preventDefault: function () {}, stopImmediatePropagation: function () {} };
        }
        """.trimIndent()

    @Test
    fun `synthetic keys are ignored while the dropdown is open, real ones are forwarded`() {
        JsSandbox().use { js ->
            js.eval(harness)
            js.eval(AutofillKeys.script("emitter.emit"))
            js.eval("fire('${AutofillKeys.EVENT}', { detail: 2 });")
            js.eval("fire('keydown', key('Enter', false)); fire('keydown', key('ArrowDown', false));")
            assertEquals(0, js.evalInt("emitted.length"))
            js.eval("fire('keydown', key('Enter', true));")
            assertEquals(1, js.evalInt("emitted.length"))
            assertTrue((js.eval("emitted[0]") as String).contains("enter"))
        }
    }

    @Test
    fun `nothing is forwarded while the dropdown is closed`() {
        JsSandbox().use { js ->
            js.eval(harness)
            js.eval(AutofillKeys.script("emitter.emit"))
            js.eval("fire('keydown', key('ArrowDown', true));")
            assertEquals(0, js.evalInt("emitted.length"))
        }
    }

    @Test
    fun `both keydown handlers carry the isTrusted guard`() {
        assertTrue("!e.isTrusted" in AutofillKeys.script("x"))
        assertTrue("e.isTrusted && e.key === 'Enter'" in CredentialCapture.INSTALL_JS)
    }
}
