// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import androidx.compose.runtime.Stable
import features.resources.ResourceFileChocolate4UGeoIpUrl
import features.resources.ResourceFileChocolate4UGeoSiteUrl
import features.resources.ResourceFileGeoIpName
import features.resources.ResourceFileGeoIpOnlyCnPrivateName
import features.resources.ResourceFileGeoSiteName
import features.resources.ResourceFileLoyalsoldierGeoIpUrl
import features.resources.ResourceFileLoyalsoldierGeoSiteUrl
import features.resources.ResourceFileSourceChocolate4UGithub
import features.resources.ResourceFileSourceCustom
import features.resources.ResourceFileSourceLoyalsoldierGithub
import features.resources.ResourceFileSourceV2FlyGithub
import features.resources.ResourceFileV2FlyGeoIpUrl
import features.resources.ResourceFileV2FlyGeoIpOnlyCnPrivateUrl
import features.resources.ResourceFileV2FlyGeoSiteUrl
import features.resources.ResourceFileXrayCoreName
import features.resources.XrayCoreVersion
import features.proxy.server.model.ProxyServer

@Stable
data class SubscriptionGroupState(
    val id: Int,
    val name: String,
    val url: String,
    val userAgent: String,
    val updateInterval: String,
    val updateViaProxy: Boolean = false,
    val enabled: Boolean,
    val builtIn: Boolean = false,
    val lastUpdatedAtMillis: Long = 0L,
)

data class ProxyServerState(
    val id: Int,
    val server: ProxyServer<*>,
    val groupId: Int,
    val latency: String = "",
)

fun ProxyServerState.proxyServerOutboundTag(): String {
    return id.toString()
}

enum class ResourceFileKind(
    val fileName: String,
) {
    GeoIp(ResourceFileGeoIpName),
    GeoSite(ResourceFileGeoSiteName),
    GeoIpOnlyCnPrivate(ResourceFileGeoIpOnlyCnPrivateName),
    XrayCore(ResourceFileXrayCoreName),
    ;

    val displayName: String
        get() = when (this) {
            GeoIp,
            GeoSite,
            GeoIpOnlyCnPrivate -> fileName
            XrayCore -> "Xray-core $XrayCoreVersion"
        }
}

@Stable
data class ResourceFileStatus(
    val exists: Boolean = false,
    val sizeBytes: Long = 0,
    val updatedAtMillis: Long = 0,
)

@Stable
data class CustomResourceFileState(
    val id: Int,
    val name: String,
    val url: String,
)

@Stable
data class CustomResourceFileStatus(
    val file: CustomResourceFileState,
    val status: ResourceFileStatus = ResourceFileStatus(),
)

@Stable
data class ResourceFilesStatus(
    val geoIp: ResourceFileStatus = ResourceFileStatus(),
    val geoSite: ResourceFileStatus = ResourceFileStatus(),
    val geoIpOnlyCnPrivate: ResourceFileStatus = ResourceFileStatus(),
    val xrayCore: ResourceFileStatus = ResourceFileStatus(),
    val customResourceFiles: List<CustomResourceFileStatus> = emptyList(),
)

data class ResourceFileUpdateSource(
    val id: Int,
    val geoIpUrl: String,
    val geoSiteUrl: String,
    val geoIpOnlyCnPrivateUrl: String,
)

val ResourceFileUpdateSources = listOf(
    ResourceFileUpdateSource(
        id = ResourceFileSourceLoyalsoldierGithub,
        geoIpUrl = ResourceFileLoyalsoldierGeoIpUrl,
        geoSiteUrl = ResourceFileLoyalsoldierGeoSiteUrl,
        geoIpOnlyCnPrivateUrl = ResourceFileV2FlyGeoIpOnlyCnPrivateUrl,
    ),
    ResourceFileUpdateSource(
        id = ResourceFileSourceV2FlyGithub,
        geoIpUrl = ResourceFileV2FlyGeoIpUrl,
        geoSiteUrl = ResourceFileV2FlyGeoSiteUrl,
        geoIpOnlyCnPrivateUrl = ResourceFileV2FlyGeoIpOnlyCnPrivateUrl,
    ),
    ResourceFileUpdateSource(
        id = ResourceFileSourceChocolate4UGithub,
        geoIpUrl = ResourceFileChocolate4UGeoIpUrl,
        geoSiteUrl = ResourceFileChocolate4UGeoSiteUrl,
        geoIpOnlyCnPrivateUrl = ResourceFileV2FlyGeoIpOnlyCnPrivateUrl,
    ),
)

fun resourceFileUpdateSourceAt(index: Int): ResourceFileUpdateSource {
    return ResourceFileUpdateSources.getOrElse(index) { ResourceFileUpdateSources.first() }
}

fun AppState.resourceFileUpdateSource(): ResourceFileUpdateSource {
    if (resourceFileSource != ResourceFileSourceCustom) {
        return resourceFileUpdateSourceAt(resourceFileSource)
    }
    val fallback = ResourceFileUpdateSources.first()
    return ResourceFileUpdateSource(
        id = ResourceFileSourceCustom,
        geoIpUrl = customResourceFileGeoIpUrl.trim().ifBlank { fallback.geoIpUrl },
        geoSiteUrl = customResourceFileGeoSiteUrl.trim().ifBlank { fallback.geoSiteUrl },
        geoIpOnlyCnPrivateUrl = customResourceFileGeoIpOnlyCnPrivateUrl.trim().ifBlank {
            fallback.geoIpOnlyCnPrivateUrl
        },
    )
}

fun ResourceFilesStatus.statusOf(kind: ResourceFileKind): ResourceFileStatus {
    return when (kind) {
        ResourceFileKind.GeoIp -> geoIp
        ResourceFileKind.GeoSite -> geoSite
        ResourceFileKind.GeoIpOnlyCnPrivate -> geoIpOnlyCnPrivate
        ResourceFileKind.XrayCore -> xrayCore
    }
}

fun sanitizeCustomResourceFileName(value: String, fallback: String): String {
    val candidate = value
        .trim()
        .replace('\\', '/')
        .substringAfterLast('/')
        .map { char -> if (char.isResourceFileNameChar()) char else '_' }
        .joinToString("")
        .trim()
    return candidate
        .takeUnless { it.isBlank() || it == "." || it == ".." }
        ?: fallback
}

fun uniqueCustomResourceFileName(
    value: String,
    reservedNames: Set<String>,
    fallback: String,
): String {
    val sanitized = sanitizeCustomResourceFileName(value, fallback)
    if (sanitized !in reservedNames) return sanitized

    val extensionStart = sanitized.lastIndexOf('.').takeIf { index -> index > 0 } ?: sanitized.length
    val baseName = sanitized.substring(0, extensionStart).ifBlank { fallback.substringBeforeLast('.') }
    val extension = sanitized.substring(extensionStart)
    var counter = 1
    while (true) {
        val candidate = "$baseName-$counter$extension"
        if (candidate !in reservedNames) return candidate
        counter += 1
    }
}

private fun Char.isResourceFileNameChar(): Boolean {
    return code >= 32 && this != '/' && this != '\\'
}
