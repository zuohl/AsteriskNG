package features.subscription

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import app.AppState
import app.SubscriptionGroupState
import data.AndroidAppStateStore
import features.proxy.server.usecase.ProxyServerListSubscriptionUpdateResult
import features.proxy.server.usecase.withUpdatedSubscriptionServers
import features.subscription.usecase.toSubscriptionFetchOptions
import features.subscription.usecase.updateSubscriptions

internal data class V2rayNgInstallConfig(
    val name: String,
    val url: String,
)

internal class SubscriptionInstallConfigUseCase(
    private val stateStore: AndroidAppStateStore,
    private val subscriptionFetchUseCase: SubscriptionFetchUseCase,
) {
    suspend fun install(config: V2rayNgInstallConfig): ProxyServerListSubscriptionUpdateResult {
        val group = stateStore.addSubscriptionGroup(config)
        val result = updateSubscriptions(
            groups = listOf(group),
            subscriptionFetchUseCase = subscriptionFetchUseCase,
            fetchOptions = { stateStore.state.value.toSubscriptionFetchOptions(it) },
        )
        if (result.updates.isNotEmpty()) {
            stateStore.update { state ->
                state.withUpdatedSubscriptionServers(
                    updates = result.updates,
                    updatedAtMillis = result.updatedAtMillis,
                )
            }
        }
        return result
    }
}

internal fun Intent.toV2rayNgInstallConfigOrNull(): V2rayNgInstallConfig? {
    if (action != Intent.ACTION_VIEW) return null
    return data?.toV2rayNgInstallConfigOrNull()
}

internal fun Uri.isV2rayNgInstallConfigUri(): Boolean {
    return scheme.equals(V2rayNgScheme, ignoreCase = true) &&
        isHierarchical &&
        host.equals(V2rayNgInstallConfigHost, ignoreCase = true)
}

private fun Uri.toV2rayNgInstallConfigOrNull(): V2rayNgInstallConfig? {
    if (!isV2rayNgInstallConfigUri()) return null
    val name = getQueryParameter("name")?.trim().orEmpty()
    val url = getQueryParameter("url")?.trim().orEmpty()
    if (name.isBlank() || !url.isValidSubscriptionUrl()) return null
    return V2rayNgInstallConfig(
        name = name,
        url = url,
    )
}

private fun AndroidAppStateStore.addSubscriptionGroup(config: V2rayNgInstallConfig): SubscriptionGroupState {
    var savedGroup: SubscriptionGroupState? = null
    update { state ->
        val group = state.newSubscriptionGroup(config)
        savedGroup = group
        state.copy(
            subscriptionGroups = state.subscriptionGroups + group,
            nextSubscriptionGroupId = state.nextSubscriptionGroupId + 1,
        )
    }
    return checkNotNull(savedGroup)
}

private fun AppState.newSubscriptionGroup(config: V2rayNgInstallConfig): SubscriptionGroupState {
    return SubscriptionGroupState(
        id = nextSubscriptionGroupId,
        name = config.name,
        url = config.url,
        userAgent = DefaultSubscriptionUserAgent,
        updateInterval = "",
        updateViaProxy = false,
        enabled = true,
    )
}

private fun String.isValidSubscriptionUrl(): Boolean {
    val uri = runCatching { toUri() }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    return uri.isHierarchical &&
        scheme in SubscriptionUrlSchemes &&
        uri.host?.isNotBlank() == true
}

private const val V2rayNgScheme = "v2rayng"
private const val V2rayNgInstallConfigHost = "install-config"
private val SubscriptionUrlSchemes = setOf("http", "https")
