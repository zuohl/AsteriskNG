package features.proxy.server.usecase

import features.logs.AndroidAppLogger
import features.proxy.server.model.HTTP
import features.proxy.server.model.Hysteria2
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.ProxyServerConstants
import features.proxy.server.model.Shadowsocks
import features.proxy.server.model.Socks
import features.proxy.server.model.Trojan
import features.proxy.server.model.V2RayParameters
import features.proxy.server.model.VLESS
import features.proxy.server.model.VMess
import features.proxy.server.model.Wireguard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private const val JsonByteOrderMark = '\uFEFF'
private const val LogTag = "ProxyServerJsonImport"
private const val XrayProtocolHysteria = "hysteria"
private const val XrayProtocolShadowsocks = "shadowsocks"
private val IgnoredXrayOutboundProtocols = setOf("blackhole", "dns", "freedom")

internal fun parseProxyServersFromJsonConfig(
    text: String,
    source: ProxyServerImportSource,
): ProxyServerImportResult {
    val root = runCatching {
        ProxyServer.json.parseToJsonElement(text.trimStart(JsonByteOrderMark))
    }.getOrNull() ?: return ProxyServerImportResult(urlCount = 0, servers = emptyList())
    val configs = when (root) {
        is JsonArray -> root.mapNotNull { element -> element.asJsonObjectOrNull() }
        is JsonObject -> listOf(root)
        else -> emptyList()
    }
    if (configs.isEmpty()) {
        return ProxyServerImportResult(urlCount = 0, servers = emptyList())
    }

    var candidateCount = 0
    var failedCount = 0
    val servers = configs.flatMap { config ->
        val configRemarks = config.string("remarks").orEmpty()
        val proxyOutbounds = config.jsonArray("outbounds")
            .mapNotNull { outbound -> outbound.asJsonObjectOrNull() }
            .filter { outbound -> outbound.isProxyOutbound() }
        proxyOutbounds.mapIndexedNotNull { index, outbound ->
            candidateCount += 1
            runCatching {
                outbound.toProxyServer(
                    remarks = outbound.proxyOutboundRemarks(
                        configRemarks = configRemarks,
                        appendTag = proxyOutbounds.size > 1,
                    ),
                )
            }.onFailure { error ->
                failedCount += 1
                AndroidAppLogger.warn(
                    LogTag,
                    outbound.importFailureMessage(
                        index = index,
                        source = source,
                        configRemarks = configRemarks,
                    ),
                    error,
                )
            }.getOrNull()
        }
    }
    if (failedCount > 0) {
        AndroidAppLogger.warn(
            LogTag,
            "Imported ${servers.size} ${source.logName} JSON outbounds, skipped $failedCount/$candidateCount failed outbounds",
        )
    }
    return ProxyServerImportResult(
        urlCount = candidateCount,
        servers = servers,
    )
}

private fun JsonObject.isProxyOutbound(): Boolean {
    return protocol() !in IgnoredXrayOutboundProtocols
}

private fun JsonObject.toProxyServer(remarks: String): ProxyServer<*> {
    val server = when (protocol()) {
        ProxyServerConstants.PROTOCOL_VLESS -> toVless(remarks)
        ProxyServerConstants.PROTOCOL_VMESS -> toVMess(remarks)
        ProxyServerConstants.PROTOCOL_TROJAN -> toTrojan(remarks)
        ProxyServerConstants.PROTOCOL_SS,
        XrayProtocolShadowsocks,
        -> toShadowsocks(remarks)

        ProxyServerConstants.PROTOCOL_SOCKS -> toSocks(remarks)
        ProxyServerConstants.PROTOCOL_HTTP -> toHttp(remarks)
        ProxyServerConstants.PROTOCOL_HY2,
        ProxyServerConstants.PROTOCOL_HYSTERIA2,
        XrayProtocolHysteria,
        -> toHysteria2(remarks)

        ProxyServerConstants.PROTOCOL_WIREGUARD -> toWireguard(remarks)
        else -> unsupportedProxyServerUrl(protocol())
    }
    server.check()
    return server
}

private fun JsonObject.toVless(remarks: String): VLESS {
    val settings = settingsObject()
    val endpoint = settings.vnextEndpointObject()
    val user = settings.vnextUserObject()
    return VLESS(
        remarks = remarks,
        id = user.string("id").orEmpty(),
        server = endpoint.string("address") ?: endpoint.string("server").orEmpty(),
        port = endpoint.portString(),
        encryption = user.string("encryption") ?: "none",
        flow = user.string("flow").orEmpty(),
        parms = streamSettingsObject().toV2RayParameters(),
    )
}

private fun JsonObject.toVMess(remarks: String): VMess {
    val settings = settingsObject()
    val endpoint = settings.vnextEndpointObject()
    val user = settings.vnextUserObject()
    return VMess(
        remarks = remarks,
        id = user.string("id").orEmpty(),
        server = endpoint.string("address") ?: endpoint.string("server").orEmpty(),
        port = endpoint.portString(),
        encryption = user.string("security") ?: user.string("encryption") ?: "auto",
        parms = streamSettingsObject().toV2RayParameters(),
    )
}

private fun JsonObject.toTrojan(remarks: String): Trojan {
    val settings = settingsObject().serverLikeObject()
    return Trojan(
        remarks = remarks,
        password = settings.string("password").orEmpty(),
        server = settings.string("address") ?: settings.string("server").orEmpty(),
        port = settings.portString(),
        parms = streamSettingsObject().toV2RayParameters(),
    )
}

private fun JsonObject.toShadowsocks(remarks: String): Shadowsocks {
    val settings = settingsObject().serverLikeObject()
    return Shadowsocks(
        remarks = remarks,
        server = settings.string("address") ?: settings.string("server").orEmpty(),
        port = settings.portString(),
        method = settings.string("method").orEmpty(),
        password = settings.string("password").orEmpty(),
        parms = streamSettingsObject().toV2RayParameters(),
    )
}

private fun JsonObject.toSocks(remarks: String): Socks {
    val settings = settingsObject().serverLikeObject()
    val user = settings.jsonArray("users").firstObjectOrNull()
    return Socks(
        remarks = remarks,
        server = settings.string("address") ?: settings.string("server").orEmpty(),
        port = settings.portString(),
        user = user.string("user"),
        password = user.string("pass") ?: user.string("password"),
    )
}

private fun JsonObject.toHttp(remarks: String): HTTP {
    val settings = settingsObject().serverLikeObject()
    val user = settings.jsonArray("users").firstObjectOrNull()
    return HTTP(
        remarks = remarks,
        server = settings.string("address") ?: settings.string("server").orEmpty(),
        port = settings.portString(),
        user = user.string("user"),
        password = user.string("pass") ?: user.string("password"),
    )
}

private fun JsonObject.toHysteria2(remarks: String): Hysteria2 {
    val settings = settingsObject()
    val streamSettings = streamSettingsObject()
    val hysteriaSettings = streamSettings.jsonObject("hysteriaSettings")
    val tlsSettings = streamSettings.jsonObject("tlsSettings")
    return Hysteria2(
        remarks = remarks,
        server = settings.string("address") ?: settings.string("server").orEmpty(),
        port = settings.portString(),
        auth = hysteriaSettings.string("auth")
            ?: settings.string("auth")
            ?: settings.string("password")
            ?: settings.string("auth_str")
            ?: settings.string("authString")
            ?: "",
        sni = tlsSettings.string("serverName").orEmpty(),
        insecure = if (tlsSettings.boolean("allowInsecure") == true) 1 else 0,
        pinSHA256 = tlsSettings.string("pinnedPeerCertSha256").orEmpty(),
        security = streamSettings.string("security") ?: "tls",
    )
}

private fun JsonObject.toWireguard(remarks: String): Wireguard {
    val settings = settingsObject()
    val peer = settings.jsonArray("peers").firstObjectOrNull()
    val endpoint = peer.string("endpoint").orEmpty().toEndpointParts()
    return Wireguard(
        remarks = remarks,
        server = endpoint.host,
        port = endpoint.port,
        secretKey = settings.string("secretKey").orEmpty(),
        publicKey = peer.string("publicKey").orEmpty(),
        preSharedKey = peer.string("preSharedKey").orEmpty(),
        reserved = settings.stringList("reserved").joinToString(","),
        address = settings.stringList("address").joinToString(","),
        mtu = settings.intString("mtu"),
    )
}

private fun JsonObject.toV2RayParameters(): V2RayParameters {
    val network = (string("network") ?: "raw").toModelTransportType()
    val rawSettings = jsonObject("rawSettings").takeIf(JsonObject::isNotEmpty)
        ?: jsonObject("tcpSettings")
    val rawHeader = rawSettings.jsonObject("header")
    val rawHeaderRequest = rawHeader.jsonObject("request")
    val rawHeaderRequestHeaders = rawHeaderRequest.jsonObject("headers")
    val wsSettings = jsonObject("wsSettings")
    val grpcSettings = jsonObject("grpcSettings")
    val httpUpgradeSettings = jsonObject("httpupgradeSettings")
    val xhttpSettings = jsonObject("xhttpSettings").takeIf(JsonObject::isNotEmpty)
        ?: jsonObject("splithttpSettings")
    val kcpSettings = jsonObject("kcpSettings")
    val security = string("security") ?: "none"
    val tlsSettings = jsonObject("tlsSettings")
    val realitySettings = jsonObject("realitySettings")
    return V2RayParameters(
        type = network,
        security = security,
        path = when (network) {
            "raw" -> rawHeaderRequest.stringList("path").joinToString(",").ifBlank { null }
            "websocket" -> wsSettings.string("path")
            "httpupgrade" -> httpUpgradeSettings.string("path")
            "xhttp" -> xhttpSettings.string("path")
            else -> null
        },
        host = when (network) {
            "raw" -> rawHeaderRequestHeaders.stringList("Host").joinToString(",").ifBlank { null }
            "websocket" -> wsSettings.string("host")
                ?: wsSettings.jsonObject("headers").string("Host")
            "httpupgrade" -> httpUpgradeSettings.string("host")
            "xhttp" -> xhttpSettings.string("host")
            else -> null
        },
        mtu = kcpSettings.intString("mtu").ifBlank { null },
        tti = kcpSettings.intString("tti").ifBlank { null },
        serviceName = grpcSettings.string("serviceName"),
        mode = when (network) {
            "grpc" -> grpcSettings.grpcMode()
            "xhttp" -> xhttpSettings.string("mode")
            else -> null
        },
        authority = grpcSettings.string("authority"),
        extra = xhttpSettings["extra"]?.toString(),
        fm = this["finalmask"]?.toString(),
        fp = when (security) {
            "tls" -> tlsSettings.string("fingerprint")
            "reality" -> realitySettings.string("fingerprint")
            else -> null
        },
        sni = when (security) {
            "tls" -> tlsSettings.string("serverName")
            "reality" -> realitySettings.string("serverName")
            else -> null
        },
        alpn = tlsSettings.stringList("alpn").joinToString(",").ifBlank { null },
        ech = tlsSettings.string("echConfigList"),
        pcs = tlsSettings.string("pinnedPeerCertSha256"),
        vcn = tlsSettings.string("verifyPeerCertByName"),
        pbk = realitySettings.string("publicKey") ?: realitySettings.string("password"),
        sid = realitySettings.string("shortId"),
        pqv = realitySettings.string("mldsa65Verify"),
        spx = realitySettings.string("spiderX"),
        headerType = if (network == "raw") rawHeader.string("type") ?: "none" else "none",
    )
}

private fun JsonObject.grpcMode(): String {
    if (boolean("multiMode") == true) {
        return "multi"
    }
    return when (val mode = string("mode")) {
        "true" -> "multi"
        "false", null -> "gun"
        else -> mode
    }
}

private fun JsonObject.importFailureMessage(
    index: Int,
    source: ProxyServerImportSource,
    configRemarks: String,
): String {
    val protocol = protocol().ifBlank { "<blank>" }
    val tag = string("tag").orEmpty().ifBlank { "<blank>" }
    val config = configRemarks.ifBlank { "<blank>" }
    return "Failed to import proxy server JSON outbound source=${source.logName} index=$index tag=$tag protocol=$protocol config=$config"
}

private fun JsonObject.proxyOutboundRemarks(
    configRemarks: String,
    appendTag: Boolean,
): String {
    val tag = string("tag").orEmpty()
    val base = configRemarks.ifBlank { tag }.ifBlank { protocol() }
    return if (appendTag && tag.isNotBlank()) {
        "$base ($tag)"
    } else {
        base
    }
}

private fun JsonObject.protocol(): String {
    return string("protocol").orEmpty().lowercase()
}

private fun JsonObject.settingsObject(): JsonObject {
    return jsonObject("settings")
}

private fun JsonObject.streamSettingsObject(): JsonObject {
    return jsonObject("streamSettings")
}

private fun JsonObject.serverLikeObject(): JsonObject {
    return jsonArray("servers").firstObjectOrNull().takeIf(JsonObject::isNotEmpty) ?: this
}

private fun JsonObject.vnextEndpointObject(): JsonObject {
    return jsonArray("vnext").firstObjectOrNull().takeIf(JsonObject::isNotEmpty) ?: this
}

private fun JsonObject.vnextUserObject(): JsonObject {
    val vnext = jsonArray("vnext").firstObjectOrNull()
    return vnext.jsonArray("users").firstObjectOrNull()
        .takeIf(JsonObject::isNotEmpty)
        ?: jsonArray("users").firstObjectOrNull().takeIf(JsonObject::isNotEmpty)
        ?: this
}

private fun JsonArray.firstObjectOrNull(): JsonObject {
    return firstOrNull()?.asJsonObjectOrNull() ?: JsonObject(emptyMap())
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
    return this as? JsonObject
}

private fun JsonObject.jsonObject(name: String): JsonObject {
    return this[name] as? JsonObject ?: JsonObject(emptyMap())
}

private fun JsonObject.jsonArray(name: String): JsonArray {
    return this[name] as? JsonArray ?: JsonArray(emptyList())
}

private fun JsonObject.string(name: String): String? {
    return this[name].primitiveContent()
}

private fun JsonObject.intString(name: String): String {
    val primitive = this[name] as? JsonPrimitive
    return primitive?.contentOrNull ?: primitive?.intOrNull?.toString().orEmpty()
}

private fun JsonObject.portString(): String {
    return intString("port")
}

private fun JsonObject.boolean(name: String): Boolean? {
    return (this[name] as? JsonPrimitive)?.booleanOrNull
}

private fun JsonObject.stringList(name: String): List<String> {
    return when (val value = this[name]) {
        is JsonArray -> value.mapNotNull { element -> element.primitiveContent() }
        else -> listOfNotNull(value.primitiveContent())
    }
}

private fun JsonElement?.primitiveContent(): String? {
    return (this as? JsonPrimitive)?.contentOrNull
}

private fun String.toModelTransportType(): String {
    val normalized = lowercase()
    return when (normalized) {
        "tcp", "raw" -> "raw"
        "kcp", "mkcp" -> "mkcp"
        "ws", "websocket" -> "websocket"
        "grpc" -> "grpc"
        "httpupgrade" -> "httpupgrade"
        "xhttp", "splithttp" -> "xhttp"
        else -> normalized
    }
}

private fun String.toEndpointParts(): XrayEndpointParts {
    val endpoint = trim()
    val ipv6HostEnd = endpoint.lastIndexOf("]:")
    if (endpoint.startsWith('[') && ipv6HostEnd > 0) {
        return XrayEndpointParts(
            host = endpoint.substring(1, ipv6HostEnd),
            port = endpoint.substring(ipv6HostEnd + 2),
        )
    }
    val separator = endpoint.lastIndexOf(':')
    if (separator <= 0 || separator == endpoint.lastIndex) {
        return XrayEndpointParts(host = endpoint, port = "")
    }
    return XrayEndpointParts(
        host = endpoint.substring(0, separator),
        port = endpoint.substring(separator + 1),
    )
}

private fun unsupportedProxyServerUrl(protocol: String): Nothing {
    throw IllegalArgumentException("Unsupported ProxyServer protocol: $protocol")
}

private data class XrayEndpointParts(
    val host: String,
    val port: String,
)
