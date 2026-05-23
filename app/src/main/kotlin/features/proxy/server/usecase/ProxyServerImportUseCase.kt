package features.proxy.server.usecase

import features.logs.AndroidAppLogger
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.ProxyServerConstants
import kotlin.io.encoding.Base64

internal data class ProxyServerImportResult(
    val urlCount: Int,
    val servers: List<ProxyServer<*>>,
)

internal enum class ProxyServerImportSource(
    val logName: String,
    val decodeBase64: Boolean,
) {
    Clipboard(logName = "clipboard", decodeBase64 = false),
    File(logName = "file", decodeBase64 = true),
    QrCode(logName = "qr_code", decodeBase64 = false),
    SubscriptionUrl(logName = "subscription_url", decodeBase64 = true),
}

internal fun importProxyServersFromText(
    text: String,
    source: ProxyServerImportSource,
): ProxyServerImportResult {
    return parseProxyServersFromPayloads(
        payloads = text.importPayloads(source),
        source = source,
    )
}

private fun parseProxyServersFromPayloads(
    payloads: List<String>,
    source: ProxyServerImportSource,
): ProxyServerImportResult {
    var lastAttempt = ProxyServerImportResult(urlCount = 0, servers = emptyList())
    payloads.forEach { payload ->
        val jsonResult = parseProxyServersFromJsonConfig(
            text = payload,
            source = source,
        )
        if (jsonResult.servers.isNotEmpty()) {
            return jsonResult
        }
        if (jsonResult.urlCount > 0) {
            lastAttempt = jsonResult
        }

        val lineResult = parseProxyServersFromLines(
            lines = payload.lineSequence(),
            source = source,
        )
        if (lineResult.servers.isNotEmpty()) {
            return lineResult
        }
        if (lineResult.urlCount > 0 || lastAttempt.urlCount == 0) {
            lastAttempt = lineResult
        }
    }
    return lastAttempt
}

private fun parseProxyServersFromLines(
    lines: Sequence<String>,
    source: ProxyServerImportSource,
): ProxyServerImportResult {
    val urls = lines.proxyServerUrlCandidates(distinct = true)
    val servers = urls.mapIndexedNotNull { index, url ->
        parseProxyServerUrlOrNull(
            url = url,
            index = index,
            source = source,
        )
    }
    return ProxyServerImportResult(
        urlCount = urls.size,
        servers = servers,
    )
}

private fun parseProxyServerUrlOrNull(
    url: String,
    index: Int,
    source: ProxyServerImportSource,
): ProxyServer<*>? {
    return runCatching { ProxyServer.parse(url) }
        .onFailure { error ->
            AndroidAppLogger.warn(
                ProxyServerImportLogTag,
                url.importFailureMessage(index = index, source = source),
                error,
            )
        }
        .getOrNull()
}

private fun String.importFailureMessage(
    index: Int,
    source: ProxyServerImportSource,
): String {
    val protocol = substringBefore("://", missingDelimiterValue = "").ifBlank { "<blank>" }
    return "Failed to import proxy server URL source=${source.logName} index=$index protocol=$protocol length=$length"
}

private fun String.importPayloads(source: ProxyServerImportSource): List<String> {
    return if (source.decodeBase64) {
        listOfNotNull(decodeImportBase64(), this).distinct()
    } else {
        listOf(this)
    }
}

private fun String.decodeImportBase64(): String? {
    val normalized = trimStart(ImportByteOrderMark).filterNot(Char::isWhitespace)
    if (normalized.isBlank()) return null
    return ImportBase64Decoders.firstNotNullOfOrNull { decoder ->
        runCatching { decoder.decode(normalized).decodeToString() }.getOrNull()
    } ?: normalized.trimEnd('=').takeIf { it.length != normalized.length }?.let { trimmed ->
        ImportBase64Decoders.firstNotNullOfOrNull { decoder ->
            runCatching { decoder.decode(trimmed).decodeToString() }.getOrNull()
        }
    }
}

private fun Sequence<String>.proxyServerUrlCandidates(distinct: Boolean): List<String> {
    val urls = flatMap { line -> line.proxyServerUrlCandidates() }
        .let { sequence -> if (distinct) sequence.distinct() else sequence }
    return urls.toList()
}

private fun String.proxyServerUrlCandidates(): Sequence<String> {
    val line = trim().trimStart(ImportByteOrderMark)
    if (line.isBlank()) return emptySequence()
    val embeddedUrls = ProxyServerUrlRegex.findAll(line)
        .map { match -> match.value.trimEnd(',', ';') }
        .filterNot { url -> url == line }
        .toList()
    return if (line.startsWithProxyServerScheme()) {
        (listOf(line) + embeddedUrls).asSequence()
    } else {
        embeddedUrls.asSequence()
    }
}

private fun String.startsWithProxyServerScheme(): Boolean {
    val lower = lowercase()
    return ProxyServerUrlPrefixes.any { prefix -> lower.startsWith(prefix) }
}

private val ImportBase64Decoders = listOf(
    Base64.Default,
    Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
    Base64.UrlSafe,
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
)

private val ProxyServerUrlPrefixes = listOf(
    "${ProxyServerConstants.PROTOCOL_HTTP}://",
    "${ProxyServerConstants.PROTOCOL_SOCKS}://",
    "${ProxyServerConstants.PROTOCOL_SS}://",
    "${ProxyServerConstants.PROTOCOL_VMESS}://",
    "${ProxyServerConstants.PROTOCOL_VLESS}://",
    "${ProxyServerConstants.PROTOCOL_TROJAN}://",
    "${ProxyServerConstants.PROTOCOL_HY2}://",
    "${ProxyServerConstants.PROTOCOL_HYSTERIA2}://",
    "${ProxyServerConstants.PROTOCOL_WIREGUARD}://",
)

private val ProxyServerUrlRegex = Regex(
    "(?i)\\b(?:http|socks|ss|vmess|vless|trojan|hy2|hysteria2|wireguard)://[^\\s<>\"']+",
)

private const val ProxyServerImportLogTag = "ProxyServerImport"
private const val ImportByteOrderMark = '\uFEFF'
