package tv.own.owntv.core.dispatcharr

import java.net.URI
import java.util.Locale

/**
 * A Dispatcharr live channel uses a stable channel UUID. This is not the ID of an
 * upstream stream: upstream stream IDs can change as provider lists are refreshed.
 *
 * Recognition is deliberately strict. An arbitrary M3U/Xtream/other IPTV URL must
 * never enable a privileged source-switch operation just because names resemble one
 * another.
 */
data class DispatcharrChannelRef(
    val origin: String,
    val channelUuid: String,
)

/** Recognize the Dispatcharr MPEG-TS proxy URL and ignore query parameters. */
object DispatcharrChannelUrls {
    private val UUID = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
    )
    fun isValidUuid(value: String): Boolean = UUID.matches(value)
    private val PATH = Regex("^/proxy/ts/stream/([0-9a-fA-F-]{36})/?$", RegexOption.IGNORE_CASE)

    fun parse(rawUrl: String): DispatcharrChannelRef? {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (uri.userInfo != null || uri.fragment != null) return null
        val port = uri.port
        if (port !in -1..65535 || port == 0) return null
        val match = PATH.matchEntire(uri.path ?: return null) ?: return null
        val uuid = match.groupValues[1]
        if (!UUID.matches(uuid)) return null
        val hostName = if (host.contains(':')) "[$host]" else host
        val origin = "$scheme://$hostName" + if (port == -1) "" else ":$port"
        return DispatcharrChannelRef(origin, uuid.lowercase(Locale.ROOT))
    }

    /**
     * Only recognize URLs belonging to the configured Dispatcharr instance.
     * Do not send an administrator API key to a host inferred from an M3U URL.
     */
    fun parseForServer(rawUrl: String, configuredOrigin: String): DispatcharrChannelRef? {
        val candidate = parse(rawUrl) ?: return null
        val configured = runCatching { URI(configuredOrigin.trim().trimEnd('/')) }.getOrNull() ?: return null
        val scheme = configured.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = configured.host ?: return null
        val port = configured.port
        if (configured.userInfo != null || configured.query != null || configured.fragment != null ||
            (configured.path?.isNotBlank() == true && configured.path != "/") ||
            scheme !in setOf("http", "https") || port !in -1..65535 || port == 0
        ) return null
        val hostName = if (host.contains(':')) "[$host]" else host
        val origin = "$scheme://$hostName" + if (port == -1) "" else ":$port"
        return candidate.takeIf { it.origin.equals(origin, ignoreCase = true) }
    }
}

/**
 * Dispatcharr's Xtream endpoint uses the *numeric channel ID*, not its UUID.
 * The username/password in the playback URL are never retained in this model.
 */
data class DispatcharrXtreamChannelRef(val origin: String, val channelId: Int)

object DispatcharrXtreamChannelUrls {
    private val PATH = Regex("^/live/[^/]+/[^/]+/([0-9]+)\\.(ts|m3u8)$", RegexOption.IGNORE_CASE)

    fun parseForServer(rawUrl: String, configuredOrigin: String): DispatcharrXtreamChannelRef? {
        val actual = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        val configured = runCatching { URI(configuredOrigin.trim().trimEnd('/')) }.getOrNull() ?: return null
        val scheme = actual.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = actual.host ?: return null
        if (scheme !in setOf("http", "https") || actual.userInfo != null ||
            actual.fragment != null || actual.port !in -1..65535 || actual.port == 0
        ) return null
        if (!scheme.equals(configured.scheme, ignoreCase = true) ||
            !host.equals(configured.host, ignoreCase = true) ||
            actual.port != configured.port ||
            configured.userInfo != null || configured.query != null || configured.fragment != null ||
            configured.port !in -1..65535 || configured.port == 0 ||
            (configured.path?.isNotBlank() == true && configured.path != "/")
        ) return null
        val match = PATH.matchEntire(actual.rawPath ?: return null) ?: return null
        val channelId = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val hostName = if (host.contains(':')) "[$host]" else host
        val origin = "$scheme://$hostName" + if (actual.port == -1) "" else ":${actual.port}"
        return DispatcharrXtreamChannelRef(origin, channelId)
    }
}
