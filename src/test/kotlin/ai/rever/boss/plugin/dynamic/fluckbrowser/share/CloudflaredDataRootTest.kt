package ai.rever.boss.plugin.dynamic.fluckbrowser.share

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudflaredDataRootTest {
    @Test
    fun `managed binary lookup prefers boss data root`() {
        val home = Files.createTempDirectory("fluck-browser-home")

        val candidates = CloudflaredExposer.managedCandidates(home.toString(), windows = false)

        assertEquals(home.resolve(".boss/bossterm/bin/cloudflared"), Path.of(candidates.first()))
        assertTrue(Path.of(candidates.first()).startsWith(home.resolve(".boss")))
    }
}
