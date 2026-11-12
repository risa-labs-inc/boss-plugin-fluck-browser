package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifecycleTest {
    @Test
    fun `attach finishing after detach is refused so the caller undoes its wiring`() {
        val r = TabRegistry<String, String>()
        assertTrue(r.reserve("b"))
        assertNull(r.detach("b"), "detach during wiring cancels it")
        assertFalse(r.publish("b", "tab"))
        assertNull(r["b"])
        // The same browser can be wired again later.
        assertTrue(r.reserve("b"))
        assertTrue(r.publish("b", "tab"))
        assertEquals("tab", r["b"])
    }

    @Test
    fun `concurrent attaches for one browser wire it once`() {
        val r = TabRegistry<String, String>()
        assertTrue(r.reserve("b"))
        assertFalse(r.reserve("b"))
        assertTrue(r.publish("b", "tab"))
        assertFalse(r.reserve("b"))
        assertEquals("tab", r.detach("b"))
        assertEquals(0, r.live().size)
    }

    @Test
    fun `last-tab detection ignores the closing browser`() {
        val r = TabRegistry<String, String>()
        listOf("a", "b").forEach { r.reserve(it); r.publish(it, it) }
        assertEquals(1, r.liveCountExcept("a"))
        r.detach("b")
        assertEquals(0, r.liveCountExcept("a"))
    }

    @Test
    fun `a failed listing or removal keeps every target in the index`() {
        val targets = setOf("pw|https://a.com|me", "card|4242|2030-01")
        assertEquals(targets, MirrorDiff.keepAfterClear(targets, failed = 0, after = null))
        assertEquals(targets, MirrorDiff.keepAfterClear(targets, failed = 1, after = emptySet()))
        assertEquals(setOf("card|4242|2030-01"), MirrorDiff.keepAfterClear(targets, failed = 0, after = setOf("card|4242|2030-01", "pw|https://users-own.com|x")))
        assertTrue(MirrorDiff.keepAfterClear(targets, failed = 0, after = emptySet()).isEmpty())
    }

    @Test
    fun `history of written keys survives a clear, owned keys do not`() {
        val dir = kotlin.io.path.createTempDirectory("idx").toFile()
        val f = java.io.File(dir, "i.json")
        val idx = MirrorIndex(f)
        idx.add("/p", "pw|https://a.com|me")
        idx.clear("/p")
        idx.save()
        val again = MirrorIndex(f)
        assertTrue(again.keys("/p").isEmpty())
        assertEquals(setOf("pw|https://a.com|me"), again.written("/p"))
        dir.deleteRecursively()
    }

    @Test
    fun `one parser for both kill switches`() {
        listOf("false", "FALSE", " 0 ", "no", "Off").forEach { assertTrue(NativeAutofillEngine.isOff(it), it) }
        listOf(null, "", "true", "1").forEach { assertFalse(NativeAutofillEngine.isOff(it), it.toString()) }
    }

    @Test
    fun `the index remembers whose rows it mirrored`() {
        val dir = kotlin.io.path.createTempDirectory("idx").toFile()
        val f = java.io.File(dir, "i.json")
        MirrorIndex(f).apply {
            setOwner("/p", "user-a")
            add("/p", "pw|https://a.com|me")
            save()
        }
        val again = MirrorIndex(f)
        assertEquals("user-a", again.owner("/p"))
        assertEquals(setOf("pw|https://a.com|me"), again.keys("/p"))
        dir.deleteRecursively()
    }
}
