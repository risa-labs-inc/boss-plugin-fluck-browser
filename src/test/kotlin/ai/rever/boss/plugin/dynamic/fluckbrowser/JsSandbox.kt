package ai.rever.boss.plugin.dynamic.fluckbrowser

import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

/**
 * A ~40-line `window`/`document` stub and a Rhino interpreter, so the scripts this plugin injects
 * into real pages can be EXECUTED by the test suite instead of pinned by substring.
 *
 * Substring assertions catch deletion and nothing else. Everything this plugin injects is wrapped
 * in `try{...}catch(e){}` so it fails **silently** in production - a syntax error, an inverted
 * condition (`if (e.isTrusted) return;` satisfies every string pin the suite had), or a filter
 * that never matches all look identical from outside: no flag, no log, guard dead. Actually
 * running the script is the only way those become test failures.
 *
 * Deliberately not a DOM implementation. It answers exactly what the injected scripts ask for -
 * `addEventListener`, an event object, an element with `tagName`/`readOnly`/`isContentEditable`/
 * `form`, `querySelectorAll`, `scrollTo` - and nothing else. A real headless DOM would be a much
 * larger dependency to test three self-contained scripts against.
 */
internal class JsSandbox : AutoCloseable {
    private val context: Context =
        run {
            // Context.enter() NESTS on a thread that already has one, and the nested handle is the
            // same Context - whose optimization level can no longer be set once it has run a
            // script, which surfaces as an IllegalStateException from the constructor rather than
            // anything that names the real problem. One sandbox at a time per test; a test needing
            // several runs them sequentially.
            check(Context.getCurrentContext() == null) {
                "a JsSandbox is already open on this thread - use them sequentially, not nested"
            }
            Context.enter().apply {
                // Interpreted mode: no bytecode generation, so nothing here depends on the JVM's
                // class-file version. These scripts run once per test; the speed is noise.
                optimizationLevel = -1
                languageVersion = Context.VERSION_ES6
            }
        }
    private val scope: ScriptableObject = context.initStandardObjects()

    init {
        eval(STUB, "dom-stub")
    }

    /** Runs [source] in the sandbox and returns its value, unwrapped to a plain Kotlin type. */
    fun eval(
        source: String,
        name: String = "test",
    ): Any? = Context.jsToJava(context.evaluateString(scope, source, name, 1, null), Any::class.java)

    /** [eval], as a number - every flag this reads back is numeric. */
    fun evalInt(source: String): Int = (eval(source) as Number).toInt()

    override fun close() {
        Context.exit()
    }

    private companion object {
        /**
         * `window` IS the global object here, exactly as in a browser, so a script assigning or
         * defining a property on `window` is observable as a global and `typeof window.x` behaves
         * the way the install guard expects.
         *
         * `dispatch` always sets `isTrusted: true`; the untrusted cases pass it explicitly. That
         * matches the asymmetry being tested - a trusted event is the normal case and an
         * untrusted one is the attack.
         */
        const val STUB: String = """
            var window = this;
            var listeners = {};
            window.addEventListener = function (type, fn, capture) {
                if (!listeners[type]) listeners[type] = [];
                listeners[type].push({ fn: fn, capture: !!capture });
            };
            // Records the capture flag so a test can assert the phase, not just the registration.
            window.listenerPhase = function (type) {
                var l = listeners[type];
                return (l && l.length) ? l[0].capture : null;
            };
            window.listenerCount = function (type) {
                return (listeners[type] || []).length;
            };
            window.dispatch = function (type, target, props) {
                var e = { type: type, target: target, isTrusted: true };
                if (props) { for (var k in props) e[k] = props[k]; }
                var l = listeners[type] || [];
                for (var i = 0; i < l.length; i++) l[i].fn(e);
                return e;
            };
            // A minimal element. `form` is null unless a test says otherwise, which is what an
            // input outside any form (and every contenteditable) looks like to the real DOM.
            window.el = function (tag, props) {
                var o = { tagName: tag, readOnly: false, isContentEditable: false, form: null };
                if (props) { for (var k in props) o[k] = props[k]; }
                return o;
            };
            var document = { visibilityState: 'visible', pictureInPictureElement: null, media: [] };
            document.querySelectorAll = function (selector) {
                return document.media.filter(function (m) {
                    return selector.split(',').indexOf(m.tagName.toLowerCase()) >= 0;
                });
            };
            window.document = document;
            window.scrollX = 0;
            window.scrollY = 0;
            window.scrollTo = function (x, y) { window.scrollX = x; window.scrollY = y; };
        """
    }
}
