package ai.rever.boss.plugin.dynamic.fluckbrowser

import ai.rever.boss.plugin.browser.BrowserContextMenuInfo
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserMenuContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import java.lang.reflect.Proxy

class ContextMenuActionsTest {

    private class DummyMenuContext : BrowserMenuContext

    class Recording {
        var lastCommand: String? = null
        var lastContext: BrowserMenuContext? = null
    }

    private fun createRecordingHandle(recording: Recording): BrowserHandle {
        return Proxy.newProxyInstance(
            BrowserHandle::class.java.classLoader,
            arrayOf(BrowserHandle::class.java)
        ) { _, method, args ->
            val argsList = args?.toList() ?: emptyList()
            when (method.name) {
                "copySelection" -> {
                    recording.lastCommand = "copySelection"
                    recording.lastContext = argsList.firstOrNull() as? BrowserMenuContext
                    Unit
                }
                "paste" -> {
                    recording.lastCommand = "paste"
                    recording.lastContext = argsList.firstOrNull() as? BrowserMenuContext
                    Unit
                }
                "cut" -> {
                    recording.lastCommand = "cut"
                    recording.lastContext = argsList.firstOrNull() as? BrowserMenuContext
                    Unit
                }
                "selectAll" -> {
                    recording.lastCommand = "selectAll"
                    recording.lastContext = argsList.firstOrNull() as? BrowserMenuContext
                    Unit
                }
                "equals" -> this === argsList.firstOrNull()
                "hashCode" -> System.identityHashCode(this)
                "toString" -> "RecordingBrowserHandleProxy"
                else -> {
                    val returnType = method.returnType
                    when {
                        returnType == Boolean::class.java -> false
                        returnType == String::class.java -> ""
                        returnType == Double::class.java -> 0.0
                        else -> null
                    }
                }
            }
        } as BrowserHandle
    }

    private fun callBuilder(info: BrowserContextMenuInfo, handle: BrowserHandle): List<ContextMenuItem> {
        return buildContextMenuItems(
            info = info,
            browserHandle = handle,
            canGoBack = false,
            canGoForward = false,
            onNavigate = {},
            onOpenInNewTab = {}
        )
    }

    @Test
    fun `context-menu Copy forwards menuContext`() {
        val recording = Recording()
        val handle = createRecordingHandle(recording)
        val context = DummyMenuContext()
        val items = callBuilder(BrowserContextMenuInfo(isEditable = true, menuContext = context), handle)

        items.first { it.text == "Copy" }.onClick()
        assertEquals("copySelection", recording.lastCommand)
        assertEquals(context, recording.lastContext)
    }

    @Test
    fun `context-menu Paste forwards menuContext`() {
        val recording = Recording()
        val handle = createRecordingHandle(recording)
        val context = DummyMenuContext()
        val items = callBuilder(BrowserContextMenuInfo(isEditable = true, menuContext = context), handle)

        items.first { it.text == "Paste" }.onClick()
        assertEquals("paste", recording.lastCommand)
        assertEquals(context, recording.lastContext)
    }

    @Test
    fun `context-menu Cut forwards menuContext`() {
        val recording = Recording()
        val handle = createRecordingHandle(recording)
        val context = DummyMenuContext()
        val items = callBuilder(BrowserContextMenuInfo(isEditable = true, menuContext = context), handle)

        items.first { it.text == "Cut" }.onClick()
        assertEquals("cut", recording.lastCommand)
        assertEquals(context, recording.lastContext)
    }

    @Test
    fun `context-menu Select All forwards menuContext`() {
        val recording = Recording()
        val handle = createRecordingHandle(recording)
        val context = DummyMenuContext()
        val items = callBuilder(BrowserContextMenuInfo(isEditable = true, menuContext = context), handle)

        items.first { it.text == "Select All" }.onClick()
        assertEquals("selectAll", recording.lastCommand)
        assertEquals(context, recording.lastContext)
    }

    @Test
    fun `two menu actions preserve distinct contexts`() {
        val recording = Recording()
        val handle = createRecordingHandle(recording)

        val context1 = DummyMenuContext()
        val items1 = callBuilder(BrowserContextMenuInfo(isEditable = true, menuContext = context1), handle)

        val context2 = DummyMenuContext()
        val items2 = callBuilder(BrowserContextMenuInfo(isEditable = true, menuContext = context2), handle)

        items1.first { it.text == "Copy" }.onClick()
        assertEquals(context1, recording.lastContext)

        items2.first { it.text == "Paste" }.onClick()
        assertEquals(context2, recording.lastContext)
    }
    @Test
    fun `equal menu contents retain the newest frame token in tab state`() {
        val state = FluckBrowserTabState()
        val firstContext = DummyMenuContext()
        val latestContext = DummyMenuContext()
        val first = BrowserContextMenuInfo(isEditable = true, menuContext = firstContext)
        val latest = BrowserContextMenuInfo(isEditable = true, menuContext = latestContext)
        // The API excludes the transient frame token from data-class equality.
        assertEquals(first, latest)
        state.contextMenuInfo = first
        state.contextMenuInfo = latest
        assertSame(latest, state.contextMenuInfo)

        val recording = Recording()
        val items = callBuilder(state.contextMenuInfo!!, createRecordingHandle(recording))
        items.first { it.text == "Paste" }.onClick()
        assertEquals("paste", recording.lastCommand)
        assertSame(latestContext, recording.lastContext)
    }

    @Test
    fun `editor commands forward a missing context for legacy frame resolution`() {
        for ((label, command) in listOf("Cut" to "cut", "Copy" to "copySelection",
            "Paste" to "paste", "Select All" to "selectAll")) {
            val recording = Recording().apply { lastContext = DummyMenuContext() }
            val items = callBuilder(BrowserContextMenuInfo(isEditable = true), createRecordingHandle(recording))
            items.first { it.text == label }.onClick()
            assertEquals(command, recording.lastCommand)
            assertEquals(null, recording.lastContext)
        }
    }

}
