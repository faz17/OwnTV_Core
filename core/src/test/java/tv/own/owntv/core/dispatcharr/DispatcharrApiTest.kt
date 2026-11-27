package tv.own.owntv.core.dispatcharr

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcharrApiTest {
    private val uuid = "123e4567-e89b-42d3-a456-426614174000"
    private val playingUrl = "https://tv.example/proxy/ts/stream/$uuid"

    private fun client(
        calls: MutableList<Pair<String, String>>,
        active: Boolean = true,
    ): DispatcharrApi {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            calls += req.method to req.url.encodedPath
            assertEquals("secret-test-key", req.header("X-API-Key"))
            val result = when (req.url.encodedPath) {
                "/api/channels/channels/7/" ->
                    """{"id":7,"uuid":"$uuid","name":"Example Channel"}"""
                "/api/channels/channels/by-uuids/" ->
                    """[{"id":7,"uuid":"$uuid","name":"Example Channel"}]"""
                "/api/m3u/accounts/" ->
                    """[{"id":5,"name":"Nexon"},{"id":8,"name":"Italy Sports"}]"""
                "/api/channels/channels/7/streams/" ->
                    """[{"id":31,"name":"Provider A HD","m3u_account":5},{"id":32,"name":"Provider B FHD","m3u_account":8}]"""
                "/proxy/ts/status/$uuid" ->
                    if (active) """{"stream_id":31}""" else """{"state":"stopped"}"""
                "/api/channels/recordings/" -> {
                    if (req.method == "POST") {
                        val buffer = okio.Buffer()
                        req.body?.writeTo(buffer)
                        val payload = org.json.JSONObject(buffer.readUtf8())
                        assertEquals(7, payload.getInt("channel"))
                        assertTrue(payload.has("start_time"))
                        assertTrue(payload.has("end_time"))
                        """{"id":77,"channel":7}"""
                    } else {
                        val now = System.currentTimeMillis()
                        """[{"id":77,"channel":7,"start_time":"${java.time.Instant.ofEpochMilli(now - 10_000)}","end_time":"${java.time.Instant.ofEpochMilli(now + 60_000)}","custom_properties":{"status":"recording"}}]"""
                    }
                }
                "/api/channels/recordings/77/stop/" -> {
                    assertEquals("POST", req.method)
                    """{"success":true}"""
                }
                "/proxy/ts/change_stream/$uuid" -> {
                    assertEquals("POST", req.method)
                    assertEquals("""{"stream_id":32}""", req.body?.let { body ->
                        val buffer = okio.Buffer()
                        body.writeTo(buffer)
                        buffer.readUtf8()
                    })
                    """{"message":"Stream changed successfully","stream_id":32}"""
                }
                else -> error("Unexpected request: ${req.url}")
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(result.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return DispatcharrApi(
            OkHttpClient.Builder().addInterceptor(interceptor).build(),
            "https://tv.example",
            "secret-test-key",
        )
    }

    @Test fun listsAssignedStreamsAndActiveStream() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val result = client(calls).sourcesFor(playingUrl)
        assertEquals(7, result.channelId)
        assertEquals(31, result.activeStreamId)
        assertEquals(listOf(31, 32), result.streams.map { it.id })
        assertEquals(listOf(5, 8), result.streams.map { it.providerAccountId })
        assertEquals(listOf("Nexon", "Italy Sports"), result.streams.map { it.providerAccountName })
        assertEquals(4, calls.size)
    }

    @Test fun xtreamLiveUrlResolvesNumericIdToUuid() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val xtreamUrl = "https://tv.example/live/username/password/7.ts"
        val found = client(calls).sourcesFor(xtreamUrl)
        assertEquals(7, found.channelId)
        assertEquals(uuid, found.channelUuid)
        assertEquals(31, found.activeStreamId)
        assertEquals("GET" to "/api/channels/channels/7/", calls.first())
        assertFalse(calls.any { it.second.contains("by-uuids") })
    }

    @Test fun xtreamSwitchUsesUuidNotCredentialsOrNumericId() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        client(calls).switch("https://tv.example/live/user/pass/7.ts", 32)
        assertTrue(calls.contains("POST" to "/proxy/ts/change_stream/$uuid"))
        assertFalse(calls.any { it.second.contains("/live/user/pass/") })
    }

    @Test fun switchesChosenAssignedStreamNotPlayerUrl() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        client(calls).switch(playingUrl, 32)
        assertTrue(calls.contains("POST" to "/proxy/ts/change_stream/$uuid"))
    }

    @Test fun refusesUnknownStreamAndInactiveChannels() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val api = client(calls, active = false)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api.switch(playingUrl, 99) }
        }
        assertThrows(java.io.IOException::class.java) {
            runBlocking { api.switch(playingUrl, 32) }
        }
        assertFalse(calls.any { it.second.contains("change_stream") })
    }

    @Test fun refusesDifferentHostBeforeAnyHttpRequest() {
        val calls = mutableListOf<Pair<String, String>>()
        val api = client(calls)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api.sourcesFor("https://untrusted.example/proxy/ts/stream/$uuid") }
        }
        assertTrue(calls.isEmpty())
    }
    @Test fun schedulesAndStopsServerRecordingWithoutLocalFiles() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val api = client(calls)
        val start = System.currentTimeMillis()
        val recordingId = api.scheduleServerRecording(playingUrl, start, start + 60_000)
        assertEquals(77, recordingId)
        assertEquals(77, api.activeServerRecording(playingUrl))
        api.stopServerRecording(recordingId)
        assertTrue(calls.contains("POST" to "/api/channels/recordings/"))
        assertTrue(calls.contains("GET" to "/api/channels/recordings/"))
        assertTrue(calls.contains("POST" to "/api/channels/recordings/77/stop/"))
    }

    @Test fun refusesDvrOperationOnOtherHost() {
        val calls = mutableListOf<Pair<String, String>>()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                client(calls).scheduleServerRecording(
                    "https://other.example/proxy/ts/stream/$uuid",
                    System.currentTimeMillis(),
                    System.currentTimeMillis() + 60_000,
                )
            }
        }
        assertTrue(calls.isEmpty())
    }

}
