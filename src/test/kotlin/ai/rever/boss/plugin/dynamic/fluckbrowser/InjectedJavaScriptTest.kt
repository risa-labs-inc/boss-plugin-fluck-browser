package ai.rever.boss.plugin.dynamic.fluckbrowser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Executes the scripts this plugin injects into pages, against [JsSandbox]'s DOM stub.
 *
 * The rest of the suite pins these scripts by substring, which proves they still contain certain
 * text and nothing about what they do. Every script here is wrapped in `try{}catch(e){}` and runs
 * inside a renderer nothing in this process can observe, so the failure mode a substring pin
 * cannot see - a syntax error, an inverted condition, a filter that never matches - is exactly
 * the one that ships silently: no flag, no log, guard dead. These tests are the floor under that.
 *
 * Rhino is not Chromium, and the difference matters in one direction only: it can prove a script
 * parses and that its own logic behaves, and it cannot prove Chromium delivers the events the
 * script listens for. `isTrusted` semantics, capture-phase ordering against a page's own
 * handlers, and whether autofill fires a keydown are all browser guarantees; those stay
 * documented in [DirtyInputMarker]'s KDoc rather than asserted here.
 */
class InjectedJavaScriptTest {
    private fun sandbox(): JsSandbox = JsSandbox()

    private fun JsSandbox.install() {
        eval(DirtyInputMarker.INSTALL_JS, "DirtyInputMarker.INSTALL_JS")
    }

    private fun JsSandbox.dirty(): Int = evalInt("window.${DirtyInputMarker.DIRTY_FLAG_PROPERTY}")

    // region DirtyInputMarker — the guard actually running

    /**
     * The floor: the script parses and installs. Its own `try{}catch(e){}` swallows a syntax
     * error into a no-op, so without executing it a typo anywhere in [DirtyInputMarker.INSTALL_JS]
     * ships as "hibernation treats every tab as never dirty" with no signal at all.
     */
    @Test
    fun `INSTALL_JS parses, runs, and leaves a clean flag behind`() =
        sandbox().use { js ->
            js.install()
            assertEquals(0, js.dirty(), "a freshly installed marker must read clean, not undefined")
            assertEquals(true, js.eval("window.listenerPhase('keydown')"), "capture phase")
            assertEquals(true, js.eval("window.listenerPhase('submit')"), "capture phase")
        }

    @Test
    fun `a trusted printable keydown on an input marks the tab dirty`() =
        sandbox().use { js ->
            js.install()
            js.eval("window.dispatch('keydown', window.el('INPUT'), { key: 'a' })")
            assertEquals(1, js.dirty())
        }

    /**
     * The load-bearing check of the whole feature. An inverted `isTrusted` test - the single
     * character change from `if (!e.isTrusted) return;` - passes every substring assertion in
     * [UserInputGuardTest] while turning the guard inside out: autofill and framework writes
     * would mark dirty, and real typing would not.
     */
    @Test
    fun `an untrusted keydown never marks the tab dirty`() =
        sandbox().use { js ->
            js.install()
            js.eval("window.dispatch('keydown', window.el('INPUT'), { key: 'a', isTrusted: false })")
            assertEquals(0, js.dirty())
        }

    @Test
    fun `a keydown on a readOnly input is not typing`() =
        sandbox().use { js ->
            js.install()
            js.eval("window.dispatch('keydown', window.el('INPUT', { readOnly: true }), { key: 'a' })")
            assertEquals(0, js.dirty())
        }

    @Test
    fun `a keydown on a non-editable element is not typing`() =
        sandbox().use { js ->
            js.install()
            js.eval("window.dispatch('keydown', window.el('DIV'), { key: 'a' })")
            assertEquals(0, js.dirty(), "a keystroke with nowhere to land inserts nothing")
            js.eval("window.dispatch('keydown', window.el('DIV', { isContentEditable: true }), { key: 'a' })")
            assertEquals(1, js.dirty(), "a rich-text editor is typing - the DOM approach could never see this")
        }

    /**
     * Cmd/Ctrl/Alt + a printable key is a shortcut (copy, cut, select-all), which inserts nothing.
     * Marking dirty on those would exempt a tab from hibernation for a keystroke that changed no
     * document state.
     */
    @Test
    fun `a modified printable key is a shortcut, not a keystroke`() =
        sandbox().use { js ->
            js.install()
            for (modifier in listOf("ctrlKey", "metaKey", "altKey")) {
                js.eval("window.dispatch('keydown', window.el('INPUT'), { key: 'a', $modifier: true })")
                assertEquals(0, js.dirty(), "$modifier + a is a shortcut")
            }
        }

    /**
     * Enter is excluded because on a form it usually IS the submit; the edit keys are included
     * because deleting what you typed is still unsaved work. The length-1 filter is what keeps
     * navigation and modifier keys out.
     */
    @Test
    fun `the key filter admits printable and edit keys, and excludes the rest`() {
        // A sandbox per key rather than one reused across all of them: the flag is one-way until
        // a submit clears it, so a single sandbox would let the first admitted key satisfy every
        // later assertion. Sequentially, never nested - see [JsSandbox]'s note on why.
        for (key in listOf("a", " ", "Backspace", "Delete")) {
            sandbox().use { js ->
                js.install()
                js.eval("window.dispatch('keydown', window.el('TEXTAREA'), { key: '$key' })")
                assertEquals(1, js.dirty(), "'$key' is typing")
            }
        }
        for (key in listOf("Enter", "Tab", "Shift", "ArrowLeft", "Escape", "Process")) {
            sandbox().use { js ->
                js.install()
                js.eval("window.dispatch('keydown', window.el('TEXTAREA'), { key: '$key' })")
                assertEquals(0, js.dirty(), "'$key' inserts nothing")
            }
        }
    }

    /**
     * The reason `compositionstart` is a listener of its own rather than a keydown variant: an IME
     * sequence reports `key === 'Process'` on every keydown (asserted above as NOT marking), so
     * without this a user composing a paragraph in Japanese or Korean would hibernate mid-draft.
     */
    @Test
    fun `IME composition arms the marker even though its keydowns do not`() =
        sandbox().use { js ->
            js.install()
            js.eval("window.dispatch('keydown', window.el('TEXTAREA'), { key: 'Process' })")
            assertEquals(0, js.dirty(), "the keydown filter alone cannot see IME input")
            js.eval("window.dispatch('compositionstart', window.el('TEXTAREA'), {})")
            assertEquals(1, js.dirty(), "compositionstart is what covers it")
        }

    @Test
    fun `paste and cut are typing by other means, with no key filter applied`() {
        for (type in listOf("paste", "cut")) {
            sandbox().use { js ->
                js.install()
                // No `key` on the event at all: the key filter is keydown-only, and applying it
                // here would drop every paste - the one case where the most text arrives at once.
                js.eval("window.dispatch('$type', window.el('INPUT'), {})")
                assertEquals(1, js.dirty(), "$type is typing")
            }
        }
    }

    // endregion

    // region DirtyInputMarker — submit, scoped to the form that was typed in

    @Test
    fun `submitting the form that was typed into clears the flag`() =
        sandbox().use { js ->
            js.eval("var formA = { tagName: 'FORM' };")
            js.install()
            js.eval("window.dispatch('keydown', window.el('INPUT', { form: formA }), { key: 'a' })")
            assertEquals(1, js.dirty())
            js.eval("window.dispatch('submit', formA, {})")
            assertEquals(0, js.dirty(), "the draft was submitted")
        }

    /**
     * The loss this scoping exists to prevent: a long unsubmitted comment in form B, then a query
     * typed into the site's search box (form A) and sent. An unscoped clear would drop the flag on
     * A's submit and make B's draft hibernatable - silently, and more likely in practice than any
     * of the over-triggering cases [DirtyInputMarker]'s KDoc accepts.
     */
    @Test
    fun `submitting a different form does not clear another form's unsubmitted draft`() =
        sandbox().use { js ->
            js.eval("var formA = { tagName: 'FORM' }; var formB = { tagName: 'FORM' };")
            js.install()
            js.eval("window.dispatch('keydown', window.el('TEXTAREA', { form: formB }), { key: 'a' })")
            assertEquals(1, js.dirty())
            js.eval("window.dispatch('submit', formA, {})")
            assertEquals(1, js.dirty(), "form A's submit says nothing about form B's draft")
        }

    /**
     * Typing outside any form (a contenteditable, an unwrapped input) records no form, and that
     * case keeps the original clear-on-any-submit behaviour - which is what the SPA case this
     * listener was added for actually needs: an app that `preventDefault()`s its own submit and
     * never navigates would otherwise stay exempt for its whole document lifetime.
     */
    @Test
    fun `typing outside a form still clears on any trusted submit`() =
        sandbox().use { js ->
            js.eval("var formA = { tagName: 'FORM' };")
            js.install()
            js.eval("window.dispatch('keydown', window.el('DIV', { isContentEditable: true }), { key: 'a' })")
            assertEquals(1, js.dirty())
            js.eval("window.dispatch('submit', formA, {})")
            assertEquals(0, js.dirty())
        }

    @Test
    fun `an untrusted submit cannot clear the flag`() =
        sandbox().use { js ->
            js.eval("var formA = { tagName: 'FORM' };")
            js.install()
            js.eval("window.dispatch('keydown', window.el('INPUT', { form: formA }), { key: 'a' })")
            js.eval("window.dispatch('submit', formA, { isTrusted: false })")
            assertEquals(1, js.dirty(), "a page must not be able to fake its way out of the guard")
        }

    // endregion

    // region DirtyInputMarker — tamper resistance and idempotence

    /**
     * A page script running `window.__fluckDirty = 0` on a timer (or an accidental name collision)
     * would switch the guard off for that tab and hand back exactly the work loss it exists to
     * prevent. The flag lives in a closure behind a getter, so the assignment does nothing.
     */
    @Test
    fun `a page cannot clear the flag by assigning to it`() =
        sandbox().use { js ->
            js.install()
            js.eval("window.dispatch('keydown', window.el('INPUT'), { key: 'a' })")
            js.eval("window.${DirtyInputMarker.DIRTY_FLAG_PROPERTY} = 0;")
            assertEquals(1, js.dirty(), "the property is read-only to the page")
        }

    @Test
    fun `a page cannot delete or redefine the flag`() =
        sandbox().use { js ->
            js.install()
            js.eval("window.dispatch('keydown', window.el('INPUT'), { key: 'a' })")
            js.eval("try { delete window.${DirtyInputMarker.DIRTY_FLAG_PROPERTY}; } catch (e) {}")
            assertEquals(1, js.dirty(), "configurable:false refuses the delete")
            js.eval(
                "try { Object.defineProperty(window, '${DirtyInputMarker.DIRTY_FLAG_PROPERTY}', " +
                    "{ value: 0, configurable: true }); } catch (e) {}",
            )
            assertEquals(1, js.dirty(), "configurable:false refuses the redefinition")
        }

    /**
     * The property doubles as the install guard, and the marker is reinstalled on every
     * navigation event. A second install that re-armed listeners would double every mark, and one
     * that reset the flag would discard a draft typed before a same-document navigation.
     */
    @Test
    fun `reinstalling is a no-op - it neither clears the flag nor re-arms listeners`() =
        sandbox().use { js ->
            js.install()
            js.eval("window.dispatch('keydown', window.el('INPUT'), { key: 'a' })")
            js.install()
            assertEquals(1, js.dirty(), "a second install must not clear a flag already set")
            assertEquals(1, js.evalInt("window.listenerCount('keydown')"), "listeners must not stack")
        }

    // endregion

    // region BUSY_SCRIPT — the reader, against the marker the setter actually wrote

    /**
     * Setter and reader run in separate evaluations against the same `window`, which is the whole
     * reason the flag is a window property. [UserInputGuardTest] pins that both mention the same
     * constant; this runs them end to end, which is the only way a getter-shaped flag that the
     * reader cannot actually see would show up as a failure.
     */
    @Test
    fun `the busy probe reads back the marker the install script set`() =
        sandbox().use { js ->
            js.install()
            assertEquals("", js.eval(TabHibernation.BUSY_SCRIPT, "BUSY_SCRIPT"), "clean tab is idle")
            js.eval("window.dispatch('keydown', window.el('INPUT'), { key: 'a' })")
            assertEquals("input", js.eval(TabHibernation.BUSY_SCRIPT, "BUSY_SCRIPT"))
        }

    /**
     * Audible playback outranks unsubmitted typing, because both exempt the tab and only the
     * reported reason differs - but the 'shown' inference below IS downgraded to idle by
     * `busyStateFor`, so 'input' must be answered before it or a dirty tab that also happens to
     * be playing a muted video would hibernate.
     */
    @Test
    fun `media is answered before input, and input before the shown inference`() =
        sandbox().use { js ->
            js.install()
            js.eval("window.dispatch('keydown', window.el('INPUT'), { key: 'a' })")
            js.eval(
                "document.media = [{ tagName: 'VIDEO', paused: false, ended: false, muted: false, " +
                    "volume: 1, currentTime: 5 }];",
            )
            assertEquals("media", js.eval(TabHibernation.BUSY_SCRIPT, "BUSY_SCRIPT"), "audible playback answers first")
            // Muted: not 'media' any more, and 'shown' would be inferred - but the tab is dirty,
            // and 'input' survives busyStateFor's downgrade of 'shown' where 'shown' would not.
            js.eval("document.media[0].muted = true;")
            assertEquals("input", js.eval(TabHibernation.BUSY_SCRIPT, "BUSY_SCRIPT"))
        }

    /** A page with nothing going on must read idle, or nothing ever hibernates. */
    @Test
    fun `an idle page reports no busy reason`() =
        sandbox().use { js ->
            js.install()
            assertEquals("", js.eval(TabHibernation.BUSY_SCRIPT, "BUSY_SCRIPT"))
        }

    // endregion

    // region ScrollRestore — capture and apply, round-tripped

    @Test
    fun `CAPTURE_JS reads the window offset in the format parseCapture expects`() =
        sandbox().use { js ->
            js.eval("window.scrollX = 12; window.scrollY = 4500;")
            val captured = js.eval(ScrollRestore.CAPTURE_JS, "CAPTURE_JS")
            assertEquals("12,4500", captured)
            assertEquals(ScrollRestore.Position(12, 4500), ScrollRestore.parseCapture(captured))
        }

    /** `|| 0` and `Math.round`: a fractional or missing offset must still parse. */
    @Test
    fun `CAPTURE_JS rounds fractional offsets and reads a missing one as zero`() =
        sandbox().use { js ->
            js.eval("window.scrollX = 10.6; window.scrollY = undefined;")
            assertEquals(ScrollRestore.Position(11, 0), ScrollRestore.parseCapture(js.eval(ScrollRestore.CAPTURE_JS)))
        }

    /**
     * The full loop the restore performs, with no mocking between the two scripts: apply, then
     * capture, and the captured value is what `awaitSettleAndApply` compares against its target.
     */
    @Test
    fun `restoreJs applies a position that CAPTURE_JS then reads back`() =
        sandbox().use { js ->
            val target = ScrollRestore.Position(0, 4500)
            js.eval(ScrollRestore.restoreJs(target), "restoreJs")
            assertEquals(target, ScrollRestore.parseCapture(js.eval(ScrollRestore.CAPTURE_JS)))
        }

    /** A page that throws on scroll access returns null, which parseCapture refuses. */
    @Test
    fun `CAPTURE_JS returns null on a page that throws, and parseCapture refuses it`() =
        sandbox().use { js ->
            js.eval("Object.defineProperty(window, 'scrollX', { get: function () { throw new Error('x'); } });")
            assertNull(js.eval(ScrollRestore.CAPTURE_JS))
            assertNull(ScrollRestore.parseCapture(null))
        }

    // endregion

    /** Sanity: the sandbox is a stub, and a test asserting on it must not mistake it for a DOM. */
    @Test
    fun `the sandbox reports the events it was given, and nothing more`() =
        sandbox().use { js ->
            assertEquals(0, js.evalInt("window.listenerCount('keydown')"), "no listeners before install")
            assertTrue(js.eval("typeof window.${DirtyInputMarker.DIRTY_FLAG_PROPERTY}") == "undefined")
        }
}
