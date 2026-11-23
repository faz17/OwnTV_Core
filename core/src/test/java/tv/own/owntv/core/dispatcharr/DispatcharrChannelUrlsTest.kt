package tv.own.owntv.core.dispatcharr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DispatcharrChannelUrlsTest {
    private val uuid = "123e4567-e89b-42d3-a456-426614174000"

    @Test fun recognizesDispatcharrProxyStream() {
        val ref = DispatcharrChannelUrls.parse(
            "http://dispatcharr.local:9191/proxy/ts/stream/$uuid?token=not-used"
        )
        assertEquals("http://dispatcharr.local:9191", ref?.origin)
        assertEquals(uuid, ref?.channelUuid)
    }

    @Test fun ignoresNonDispatcharrUrls() {
        assertNull(DispatcharrChannelUrls.parse("http://dispatcharr.local:9191/live/user/password/3.ts"))
        assertNull(DispatcharrChannelUrls.parse("http://dispatcharr.local:9191/proxy/ts/status/$uuid"))
        assertNull(DispatcharrChannelUrls.parse("https://dispatcharr.local:9191/proxy/ts/stream/123"))
        assertNull(DispatcharrChannelUrls.parse("file:///proxy/ts/stream/$uuid"))
    }

    @Test fun refusesUnconfiguredHostForPrivilegedOperations() {
        val playingUrl = "https://trusted.example/proxy/ts/stream/$uuid"
        assertNull(DispatcharrChannelUrls.parseForServer(playingUrl, "https://other.example"))
        assertEquals(uuid, DispatcharrChannelUrls.parseForServer(playingUrl, "https://trusted.example/")?.channelUuid)
    }

    @Test fun refusesCredentialsAndUnexpectedConfigurationPaths() {
        assertNull(DispatcharrChannelUrls.parse("https://user:pass@trusted.example/proxy/ts/stream/$uuid"))
        assertNull(DispatcharrChannelUrls.parseForServer("https://trusted.example/proxy/ts/stream/$uuid", "https://trusted.example/admin"))
    }
}
