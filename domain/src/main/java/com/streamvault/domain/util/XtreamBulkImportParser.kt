package com.streamvault.domain.util

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** One Xtream Codes account extracted from a pasted playlist/API URL. Never print [password]. */
class XtreamAccountRef(
    /** `scheme://host[:port]` exactly as entered (explicit default ports are kept). */
    val serverUrl: String,
    /** Lower-case host plus port when it is not the scheme default, e.g. `stream8k.store`, `iptv.test:8080`. */
    val hostLabel: String,
    val username: String,
    val password: String
) {
    /** Host + username identity used to recognise the same account across imports. */
    val identityKey: String get() = XtreamBulkImportParser.identityKey(hostLabel, username)

    override fun equals(other: Any?): Boolean =
        other is XtreamAccountRef && other.serverUrl == serverUrl && other.username == username &&
            other.password == password

    override fun hashCode(): Int = 31 * (31 * serverUrl.hashCode() + username.hashCode()) + password.hashCode()

    override fun toString(): String = "XtreamAccountRef(host=$hostLabel, username=$username)"
}

sealed interface XtreamBulkImportLine {
    /** 1-based line number in the pasted text. */
    val lineNumber: Int

    data class Account(override val lineNumber: Int, val account: XtreamAccountRef) : XtreamBulkImportLine
    data class Invalid(override val lineNumber: Int) : XtreamBulkImportLine
}

/**
 * Parses a block of Xtream Codes URLs, one per line, such as
 * `http://host:80/get.php?username=u&password=p&type=m3u_plus&output=ts` or
 * `https://host/player_api.php?username=u&password=p`. Blank lines and `#` comment lines
 * (including `#EXTM3U`) are ignored; missing schemes default to `http`.
 */
object XtreamBulkImportParser {
    private val hostRegex = Regex("""[a-z0-9]([a-z0-9\-.]*[a-z0-9])?""")
    private val ipv6HostRegex = Regex("""\[[0-9a-f:.]+]""")

    fun parse(text: String): List<XtreamBulkImportLine> =
        text.lineSequence().mapIndexedNotNull { index, rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> null
                else -> parseLine(line)
                    ?.let { XtreamBulkImportLine.Account(index + 1, it) }
                    ?: XtreamBulkImportLine.Invalid(index + 1)
            }
        }.toList()

    fun parseLine(rawLine: String): XtreamAccountRef? {
        val line = rawLine.trim().trim('"', '\'', '<', '>').trim()
        if (line.isEmpty() || line.any { it.isWhitespace() }) return null
        val schemeSeparator = line.indexOf("://")
        val scheme = if (schemeSeparator > 0) line.substring(0, schemeSeparator).lowercase(Locale.ROOT) else "http"
        if (scheme != "http" && scheme != "https") return null
        val remainder = (if (schemeSeparator > 0) line.substring(schemeSeparator + 3) else line)
            .substringBefore('#')
        val authorityEnd = remainder.indexOfFirst { it == '/' || it == '?' }.takeIf { it >= 0 } ?: remainder.length
        val authority = remainder.substring(0, authorityEnd)
        if (authority.isEmpty() || '@' in authority) return null
        val query = remainder.substring(authorityEnd).substringAfter('?', missingDelimiterValue = "")

        val (host, port) = splitAuthority(authority) ?: return null
        val params = parseQuery(query)
        val username = params["username"]?.takeIf(String::isNotBlank) ?: return null
        val password = params["password"]?.takeIf(String::isNotBlank) ?: return null

        val serverUrl = buildString {
            append(scheme).append("://").append(host)
            port?.let { append(':').append(it) }
        }
        return XtreamAccountRef(
            serverUrl = serverUrl,
            hostLabel = hostLabel(scheme, host, port),
            username = username,
            password = password
        )
    }

    /** Identity key for an already configured provider (`serverUrl` + `username`). */
    fun identityKeyForServer(serverUrl: String, username: String): String? {
        val schemeSeparator = serverUrl.indexOf("://")
        if (schemeSeparator <= 0) return null
        val scheme = serverUrl.substring(0, schemeSeparator).lowercase(Locale.ROOT)
        val authority = serverUrl.substring(schemeSeparator + 3).substringBefore('/').substringBefore('?')
        val (host, port) = splitAuthority(authority) ?: return null
        return identityKey(hostLabel(scheme, host, port), username)
    }

    internal fun identityKey(hostLabel: String, username: String): String =
        "${hostLabel.lowercase(Locale.ROOT)}|${username.trim()}"

    private fun splitAuthority(authority: String): Pair<String, Int?>? {
        val lower = authority.lowercase(Locale.ROOT)
        val host: String
        val portText: String?
        if (lower.startsWith("[")) {
            val end = lower.indexOf(']')
            if (end < 0) return null
            host = lower.substring(0, end + 1)
            portText = lower.substring(end + 1).removePrefix(":").takeIf(String::isNotEmpty)
            if (!ipv6HostRegex.matches(host)) return null
        } else {
            host = lower.substringBefore(':')
            portText = lower.substringAfter(':', missingDelimiterValue = "").takeIf(String::isNotEmpty)
            if (!hostRegex.matches(host)) return null
        }
        val port = portText?.let { text -> text.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null }
        return host to port
    }

    private fun hostLabel(scheme: String, host: String, port: Int?): String {
        val isDefaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        return if (port == null || isDefaultPort) host else "$host:$port"
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').mapNotNull { part ->
            val key = part.substringBefore('=').takeIf(String::isNotBlank) ?: return@mapNotNull null
            decode(key).lowercase(Locale.ROOT) to decode(part.substringAfter('=', missingDelimiterValue = ""))
        }.toMap()

    /** Percent-decodes without turning `+` into a space (passwords may contain `+`). */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}

    /** Percent-decodes without turning `+` into a space (passwords may contain `+`). */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name()) }.getOrDefault(value)