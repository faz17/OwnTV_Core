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
    enum class ApiStage { CHANNEL, STREAMS, STATUS, SWITCH, RECORDING, AUTH }

    /** Never put the response body or streaming credentials in diagnostics. */
    class ApiFailure(val stage: ApiStage, val statusCode: Int?, cause: Throwable? = null) : IOException(cause)

    /** Verify the configured API key and reachability without needing a playing channel. */
    suspend fun verifyConnection(): Unit = withContext(Dispatchers.IO) {
        val response = requestJson("/api/accounts/users/me/", stage = ApiStage.AUTH)
        if (response !is JSONObject) throw ApiFailure(ApiStage.AUTH, null)
    }

    data class AssignedStream(
        val id: Int,
        val name: String,
        val providerAccountId: Int?,
        val streamStats: String?,
        /** User-facing Dispatcharr M3U account name, if the account can be resolved. */
        val providerAccountName: String? = null,
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
            val row = requestJson("/api/channels/channels/${xc.channelId}/", stage = ApiStage.CHANNEL) as? JSONObject
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
                stage = ApiStage.CHANNEL,
            ) as? JSONArray ?: throw IOException("Invalid channel lookup response")
            val exact = (0 until rows.length())
                .mapNotNull(rows::optJSONObject)
                .firstOrNull { it.optString("uuid").equals(ref.channelUuid, ignoreCase = true) }
                ?: throw IOException("Channel not found in Dispatcharr")
            channelId = exact.optInt("id").takeIf { it > 0 }
                ?: throw IOException("Invalid Dispatcharr channel id")
            channelUuid = ref.channelUuid
        }

        val streamsJson = requestJson("/api/channels/channels/$channelId/streams/", stage = ApiStage.STREAMS) as? JSONArray
            ?: throw IOException("Invalid stream list response")
        val streams = (0 until streamsJson.length()).mapNotNull { index ->
            val stream = streamsJson.optJSONObject(index) ?: return@mapNotNull null
            val id = stream.optInt("id")
            if (id <= 0) return@mapNotNull null
            AssignedStream(
                id = id,
                name = stream.optString("name").ifBlank { id.toString() },
                providerAccountId = stream.optInt("m3u_account").takeIf { it > 0 },
                streamStats = stream.optJSONObject("stream_stats")?.toString(),
            )
        }

        // Account IDs from the stream serializer refer to M3U accounts. Resolve their display
        // names once per sheet load. A failed metadata lookup must never hide playable sources.
        val names = if (streams.any { it.providerAccountId != null }) {
            runCatching { loadAccountNames() }.getOrDefault(emptyMap())
        } else emptyMap()
        val namedStreams = streams.map { stream ->
            stream.copy(providerAccountName = stream.providerAccountId?.let(names::get))
        }

        // Dispatcharr reports the active upstream only when the channel is playing.
        val activeId = runCatching {
            (requestJson("/proxy/ts/status/${channelUuid}", stage = ApiStage.STATUS) as? JSONObject)
                ?.optInt("stream_id")?.takeIf { it > 0 }
        }.getOrNull()

        ChannelSources(channelUuid, channelId, activeId, namedStreams)
    }

    /**
     * Read the Dispatcharr playlist names, not their server URLs or XC credentials.
     * Supports both unpaginated and DRF paginated account lists, using same-origin
     * generated page paths rather than following API-supplied redirect/next URLs.
     */
    private fun loadAccountNames(): Map<Int, String> {
        val names = mutableMapOf<Int, String>()
        for (page in 1..10) {
            val payload = requestJson(
                if (page == 1) "/api/m3u/accounts/" else "/api/m3u/accounts/?page=$page",
                stage = ApiStage.STREAMS,
            )
            val rows = when (payload) {
                is JSONArray -> payload
                is JSONObject -> payload.optJSONArray("results") ?: break
                else -> break
            }
            for (index in 0 until rows.length()) {
                val account = rows.optJSONObject(index) ?: continue
                val id = account.optInt("id")
                val name = account.optString("name").trim()
                if (id > 0 && name.isNotBlank()) names[id] = name
            }
            if (payload !is JSONObject ||
                payload.isNull("next") ||
                payload.optString("next").isBlank()) break
        }
        return names
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
            stage = ApiStage.SWITCH,
        )
        Unit
    }

    /**
     * Schedule a DVR job on the Dispatcharr host. The channel is resolved from the
     * exact same-origin playing URL; local OwnTV recording is never involved.
     */
    suspend fun scheduleServerRecording(playingUrl: String, startMs: Long, endMs: Long): Int =
        withContext(Dispatchers.IO) {
            require(endMs > startMs && endMs > System.currentTimeMillis()) {
                "Invalid recording window"
            }
            val channelId = sourcesFor(playingUrl).channelId
            val body = JSONObject()
                .put("channel", channelId)
                .put("start_time", java.time.Instant.ofEpochMilli(startMs).toString())
                .put("end_time", java.time.Instant.ofEpochMilli(endMs).toString())
            val result = requestJson("/api/channels/recordings/", body, ApiStage.RECORDING) as? JSONObject
                ?: throw ApiFailure(ApiStage.RECORDING, null)
            result.optInt("id").takeIf { it > 0 }
                ?: throw ApiFailure(ApiStage.RECORDING, null)
        }

    /**
     * Find a current DVR job on this channel before offering a second recording.
     * Dispatcharr stores its status in custom_properties rather than a top-level column.
     */
    suspend fun activeServerRecording(playingUrl: String): Int? = withContext(Dispatchers.IO) {
        val channelId = sourcesFor(playingUrl).channelId
        val response = requestJson("/api/channels/recordings/", stage = ApiStage.RECORDING)
        val rows = when (response) {
            is JSONArray -> response
            is JSONObject -> response.optJSONArray("results")
            else -> null
        } ?: throw ApiFailure(ApiStage.RECORDING, null)
        val now = System.currentTimeMillis()
        (0 until rows.length()).asSequence()
            .mapNotNull(rows::optJSONObject)
            .filter { it.optInt("channel") == channelId }
            .firstOrNull { row ->
                val state = row.optJSONObject("custom_properties")?.optString("status").orEmpty()
                val start = runCatching {
                    java.time.OffsetDateTime.parse(row.optString("start_time"))
                        .toInstant().toEpochMilli()
                }.getOrNull()
                val end = runCatching {
                    java.time.OffsetDateTime.parse(row.optString("end_time"))
                        .toInstant().toEpochMilli()
                }.getOrNull()
                start != null && end != null && start <= now && end > now &&
                    state !in setOf("stopped", "completed", "interrupted", "failed", "cancelled")
            }?.optInt("id")?.takeIf { it > 0 }
    }


    /** Remote DVR catalogue entry (no local recording files are created). */
    data class ServerRecording(
        val id: Int, val channelId: Int, val startMs: Long, val endMs: Long,
        val title: String, val status: String, val hasFile: Boolean,
    ) {
        fun isScheduled(now: Long): Boolean =
            startMs > now && status !in setOf("cancelled", "failed", "completed")
        fun isActive(now: Long): Boolean = startMs <= now && endMs > now &&
            status !in setOf("cancelled", "failed", "completed", "stopped", "interrupted")
        fun isCompleted(): Boolean = hasFile &&
            status in setOf("completed", "stopped", "interrupted")
    }

    suspend fun serverRecordings(): List<ServerRecording> = withContext(Dispatchers.IO) {
        val all = mutableListOf<ServerRecording>()
        for (page in 1..30) {
            val route = if (page == 1) "/api/channels/recordings/"
                else "/api/channels/recordings/?page=$page"
            val response = requestJson(route, stage = ApiStage.RECORDING)
            val rows = when (response) {
                is JSONArray -> response
                is JSONObject -> response.optJSONArray("results")
                else -> null
            } ?: throw ApiFailure(ApiStage.RECORDING, null)
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val id = row.optInt("id")
                val channelId = row.optInt("channel")
                if (id <= 0 || channelId <= 0) continue
                val start = runCatching {
                    java.time.OffsetDateTime.parse(row.getString("start_time")).toInstant().toEpochMilli()
                }.getOrNull() ?: continue
                val end = runCatching {
                    java.time.OffsetDateTime.parse(row.getString("end_time")).toInstant().toEpochMilli()
                }.getOrNull() ?: continue
                val cp = row.optJSONObject("custom_properties")
                val title = cp?.optJSONObject("program")?.optString("title").orEmpty()
                    .ifBlank { row.optString("name") }
                    .ifBlank { row.optString("file_name") }
                    .ifBlank { channelId.toString() }
                val status = cp?.optString("status").orEmpty()
                    .ifBlank { row.optString("status") }.lowercase(java.util.Locale.ROOT)
                val hasFile = listOf("file_url", "output_file_url", "file_path")
                    .any { !cp?.optString(it).isNullOrBlank() }
                all += ServerRecording(id, channelId, start, end, title, status, hasFile)
            }
            if (response !is JSONObject || response.isNull("next") ||
                response.optString("next").isBlank()) break
        }
        all.sortedWith(compareByDescending<ServerRecording> { it.startMs }.thenByDescending { it.id })
    }

    /** Use the same host as the configured API, never a third-party URL from JSON. */
    fun serverRecordingPlaybackUrl(recordingId: Int): String {
        require(recordingId > 0)
        val origin = DispatcharrChannelUrls.parseForServer(
            "$configuredOrigin/proxy/ts/stream/123e4567-e89b-42d3-a456-426614174000",
            configuredOrigin,
        )?.origin ?: throw IllegalArgumentException("Invalid Dispatcharr server origin")
        return "$origin/api/channels/recordings/$recordingId/file/"
    }

    fun serverRecordingHeaders(): String {
        check(apiKey.isNotBlank())
        return "X-API-Key: $apiKey"
    }

    /** Cancel only a pending server DVR job. */
    suspend fun cancelServerRecording(recordingId: Int): Unit = withContext(Dispatchers.IO) {
        require(recordingId > 0)
        requestJson("/api/channels/recordings/$recordingId/", stage = ApiStage.RECORDING, method = "DELETE")
        Unit
    }

    /** Stop a Dispatcharr DVR job while keeping the bytes already recorded on the server. */
    suspend fun stopServerRecording(recordingId: Int): Unit = withContext(Dispatchers.IO) {
        require(recordingId > 0)
        requestJson(
            "/api/channels/recordings/$recordingId/stop/",
            JSONObject(),
            stage = ApiStage.RECORDING,
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

    private fun requestJson(path: String, payload: JSONObject? = null, stage: ApiStage, method: String? = null): Any {
        // Only use the normalized origin that the strict URL matcher approved.
        val origin = DispatcharrChannelUrls.parseForServer(
            "$configuredOrigin/proxy/ts/stream/123e4567-e89b-42d3-a456-426614174000",
            configuredOrigin,
        )?.origin ?: throw IllegalArgumentException("Invalid Dispatcharr server origin")
        val builder = Request.Builder().url(origin + path).header("X-API-Key", apiKey)
        val request = when {
            method == "DELETE" -> builder.delete().build()
            payload == null -> builder.get().build()
            else -> builder.post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        }
        // OkHttp ordinarily follows cross-origin redirects. A privileged X-API-Key
        // must never be forwarded to another host.
        try {
            http.newBuilder().followRedirects(false).followSslRedirects(false).build()
                .newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Do not expose the raw body: it may contain upstream URLs/credentials.
                throw ApiFailure(stage, response.code)
            }
            val body = response.body?.string().orEmpty()
            if (method == "DELETE" && body.isBlank()) return JSONObject()
            return when (body.trimStart().firstOrNull()) {
                '{' -> JSONObject(body)
                '[' -> JSONArray(body)
                else -> throw ApiFailure(stage, null)
            }
        }
        } catch (e: ApiFailure) {
            throw e
        } catch (e: IOException) {
            throw ApiFailure(stage, null, e)
        }
    }
}
