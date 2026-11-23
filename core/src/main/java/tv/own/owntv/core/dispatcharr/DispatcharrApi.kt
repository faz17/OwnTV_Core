package tv.own.owntv.core.dispatcharr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Explicit, opt-in client for Dispatcharr's documented channel/stream endpoints.
 *
 * The caller supplies an authenticated admin API key at runtime. This class never
 * saves credentials, changes OwnTV's playback URL, or changes a playlist's stream
 * assignments. Storage and user-facing configuration are separate tasks.
 */
class DispatcharrApi(
    private val http: OkHttpClient,
    private val configuredOrigin: String,
    private val apiKey: String,
) {
    data class AssignedStream(
        val id: Int,
        val name: String,
        val providerAccountId: Int?,
        val streamStats: String?,
    )

    data class ChannelSources(
        val channelUuid: String,
        val channelId: Int,
        val activeStreamId: Int?,
        val streams: List<AssignedStream>,
    )

    /** Fail closed if the playing URL is not from the configured Dispatcharr. */
    suspend fun sourcesFor(playingUrl: String): ChannelSources = withContext(Dispatchers.IO) {
        requireConfiguredChannel(playingUrl)
        // Xtream /live/<user>/<password>/<numeric-channel-id>.ts maps to the
        // Dispatcharr channel PK. Resolve its UUID before calling proxy APIs.
        val xc = DispatcharrXtreamChannelUrls.parseForServer(playingUrl, configuredOrigin)
        val channelId: Int
        val channelUuid: String
        if (xc != null) {
            val row = requestJson("/api/channels/channels/${xc.channelId}/") as? JSONObject
                ?: throw IOException("Invalid channel lookup response")
            channelId = row.optInt("id").takeIf { it == xc.channelId }
                ?: throw IOException("Dispatcharr channel ID did not match the playing channel")
            channelUuid = row.optString("uuid").takeIf {
                DispatcharrChannelUrls.isValidUuid(it)
            }?.lowercase(java.util.Locale.ROOT)
                ?: throw IOException("Invalid channel UUID in Dispatcharr response")
        } else {
            val ref = DispatcharrChannelUrls.parseForServer(playingUrl, configuredOrigin)
                ?: throw IllegalArgumentException("Not a Dispatcharr channel")
            val rows = requestJson(
                "/api/channels/channels/by-uuids/",
                JSONObject().put("uuids", JSONArray().put(ref.channelUuid)),
            ) as? JSONArray ?: throw IOException("Invalid channel lookup response")
            val exact = (0 until rows.length())
                .mapNotNull(rows::optJSONObject)
                .firstOrNull { it.optString("uuid").equals(ref.channelUuid, ignoreCase = true) }
                ?: throw IOException("Channel not found in Dispatcharr")
            channelId = exact.optInt("id").takeIf { it > 0 }
                ?: throw IOException("Invalid Dispatcharr channel id")
            channelUuid = ref.channelUuid
        }

        val streamsJson = requestJson("/api/channels/channels/$channelId/streams/") as? JSONArray
            ?: throw IOException("Invalid stream list response")
        val streams = (0 until streamsJson.length()).mapNotNull { index ->
            val stream = streamsJson.optJSONObject(index) ?: return@mapNotNull null
            val id = stream.optInt("id")
            if (id <= 0) return@mapNotNull null
            AssignedStream(
                id = id,
                name = stream.optString("name").ifBlank { "Stream $id" },
                providerAccountId = stream.optInt("m3u_account").takeIf { it > 0 },
                streamStats = stream.optJSONObject("stream_stats")?.toString(),
            )
        }

        // Dispatcharr reports the active upstream only when the channel is playing.
        val activeId = runCatching {
            (requestJson("/proxy/ts/status/${channelUuid}") as? JSONObject)
                ?.optInt("stream_id")?.takeIf { it > 0 }
        }.getOrNull()

        ChannelSources(channelUuid, channelId, activeId, streams)
    }

    /** Switch the upstream inside Dispatcharr without retuning the OwnTV player. */
    suspend fun switch(playingUrl: String, selectedStreamId: Int): Unit = withContext(Dispatchers.IO) {
        require(selectedStreamId > 0) { "Invalid stream id" }
        val sources = sourcesFor(playingUrl)
        require(sources.streams.any { it.id == selectedStreamId }) {
            "Selected stream is not assigned to this channel"
        }
        if (sources.activeStreamId == selectedStreamId) return@withContext
        if (sources.activeStreamId == null) {
            throw IOException("Channel is not running in Dispatcharr")
        }
        requestJson(
            "/proxy/ts/change_stream/${sources.channelUuid}",
            JSONObject().put("stream_id", selectedStreamId),
        )
        Unit
    }

    private fun requireConfiguredChannel(playingUrl: String) {
        check(apiKey.isNotBlank()) { "Dispatcharr API key not configured" }
        require(
            DispatcharrChannelUrls.parseForServer(playingUrl, configuredOrigin) != null ||
                DispatcharrXtreamChannelUrls.parseForServer(playingUrl, configuredOrigin) != null
        ) { "Not a Dispatcharr channel on the configured server" }
    }

    private fun requestJson(path: String, payload: JSONObject? = null): Any {
        // Only use the normalized origin that the strict URL matcher approved.
        val origin = DispatcharrChannelUrls.parseForServer(
            "$configuredOrigin/proxy/ts/stream/123e4567-e89b-42d3-a456-426614174000",
            configuredOrigin,
        )?.origin ?: throw IllegalArgumentException("Invalid Dispatcharr server origin")
        val builder = Request.Builder().url(origin + path).header("X-API-Key", apiKey)
        val request = if (payload == null) builder.get().build() else builder
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        // OkHttp ordinarily follows cross-origin redirects. A privileged X-API-Key
        // must never be forwarded to another host.
        http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Do not expose the raw body: it may contain upstream URLs/credentials.
                throw IOException("Dispatcharr request failed (HTTP ${response.code})")
            }
            val body = response.body?.string().orEmpty()
            return when (body.trimStart().firstOrNull()) {
                '{' -> JSONObject(body)
                '[' -> JSONArray(body)
                else -> throw IOException("Unexpected Dispatcharr response")
            }
        }
    }
}
