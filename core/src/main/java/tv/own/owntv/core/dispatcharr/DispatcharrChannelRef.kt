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
