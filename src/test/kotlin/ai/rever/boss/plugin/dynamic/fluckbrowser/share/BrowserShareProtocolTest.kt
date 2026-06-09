package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserShareProtocolTest {
    @Test fun serverMessagesRoundTripWithDiscriminator() {
        val msgs: List<ServerMessage> = listOf(
            ServerMessage.Layout(listOf(BrowserTabNode("t1", "Title", "https://x", null, false, true, false)), "t1", "sess"),
            ServerMessage.DomSnapshot("t1", "{}", 800, 600),
            ServerMessage.DomMutation("t1", "{\"type\":3}"),
            ServerMessage.DomFocusAck("t1"),
            ServerMessage.NavStatus("t1", "https://x", "Title", null, false, true, false),
            ServerMessage.Presence(2),
            ServerMessage.Control(true),
            ServerMessage.Pending,
            ServerMessage.Grant("k", 123L, true),
            ServerMessage.Denied("nope"),
        )
        for (m in msgs) {
            val json = encodeServer(m)
            assertTrue(json.contains("\"t\":"), "missing discriminator in $json")
            assertEquals(m, ShareJson.decodeFromString(ServerMessage.serializer(), json))
        }
    }

    @Test fun clientMessagesRoundTrip() {
        val msgs: List<ClientMessage> = listOf(
            ClientMessage.Hello("name", "cid", "key"),
            ClientMessage.FocusTab("t1"),
            ClientMessage.RequestControl("t1"),
            ClientMessage.Navigate("t1", "https://y"),
            ClientMessage.Back("t1"),
            ClientMessage.Click("t1", 42),
            ClientMessage.Input("t1", 7, "hi"),
            ClientMessage.Key("t1", 3, "Enter", "Enter"),
            ClientMessage.Scroll("t1", 1, 0, 600),
        )
        for (m in msgs) {
            val json = ShareJson.encodeToString(ClientMessage.serializer(), m)
            assertEquals(m, decodeClient(json))
        }
    }

    @Test fun ignoresUnknownKeys() {
        val json = "{\"t\":\"focusTab\",\"tabId\":\"t1\",\"futureField\":123}"
        assertEquals(ClientMessage.FocusTab("t1"), decodeClient(json))
    }

    @Test fun decodeKexDistinguishesFromHello() {
        assertNull(decodeKex(encodeServer(ServerMessage.Pending)))
        val kex = decodeKex(encodeKex(Kex(1, "abc", "conf")))
        assertEquals("abc", kex?.salt)
    }

    @Test fun controlPayloadOmitsNullFields() {
        val json = ControlJson.encodeToString(ControlPayload.serializer(), ControlPayload(kind = "click", id = 42))
        assertTrue(json.contains("\"kind\":\"click\""))
        assertTrue(json.contains("\"id\":42"))
        assertFalse(json.contains("value"))
        assertFalse(json.contains("\"x\""))
    }
}
