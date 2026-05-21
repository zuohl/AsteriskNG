@file:OptIn(ExperimentalScrollBarApi::class)

package features.proxy.server.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import app.AppState
import app.ProxyServerState
import app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import features.proxy.server.model.getUrlOrNull
import app.navigation.Navigator
import app.navigation.Route
import ui.feedback.AndroidToastTipNotifier
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import ui.text.formatTemplate
import ui.layout.pageScrollModifiers
import ui.clipboard.setPlainText

private const val ProxyServerSwapThreshold = 0.3f

@Composable
internal fun ProxyServerListPager(
    groupPagerState: PagerState,
    groupState: ProxyServerListGroups,
    searchValue: String,
    servers: List<ProxyServerState>,
    selectedServerId: Int,
    unknownGroupName: String,
    itemTextFormatter: ProxyServerListItemTextFormatter,
    topAppBarScrollBehavior: ScrollBehavior,
    listPadding: PaddingValues,
    contentPadding: PaddingValues,
    updateAppState: ((AppState) -> AppState) -> Unit,
    navigator: Navigator,
    clipboard: Clipboard,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
    resultKey: String,
    onSelectedServerIdChange: (Int) -> Unit,
) {
    HorizontalPager(
        state = groupPagerState,
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Top,
    ) {
        val pageGroupId = groupState.groupTabs.getOrNull(it)?.id ?: groupState.selectedTabId
        val pageIsAllGroupsSelected = pageGroupId == AllProxyGroupId
        val keyword = searchValue.trim()
        val pageServers = servers.filter { server ->
            val info = server.server.getInfo()
            ((pageIsAllGroupsSelected && server.groupId in groupState.visibleGroupIds) || server.groupId == pageGroupId) &&
                (
                    keyword.isEmpty() ||
                        info.remarks.contains(keyword, ignoreCase = true)
                    )
        }
        val lazyListState = rememberLazyListState()
        var draggingServerId by remember { mutableStateOf<Int?>(null) }
        var draggedServerOffset by remember { mutableFloatStateOf(0f) }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.pageScrollModifiers(topAppBarScrollBehavior),
                contentPadding = listPadding,
            ) {
                if (pageServers.isEmpty()) {
                    item(key = "proxy_empty", contentType = "empty") {
                        ProxyServerListEmptyState(text = stringResource(R.string.common_empty))
                    }
                } else {
                    items(
                        items = pageServers,
                        key = { server -> server.id },
                        contentType = { "proxy_server" },
                    ) { server ->
                        ProxyServerListItem(
                            server = server,
                            selectedServerId = selectedServerId,
                            pageIsAllGroupsSelected = pageIsAllGroupsSelected,
                            pageGroupId = pageGroupId,
                            unknownGroupName = unknownGroupName,
                            itemTextFormatter = itemTextFormatter,
                            groupState = groupState,
                            updateAppState = updateAppState,
                            navigator = navigator,
                            clipboard = clipboard,
                            tipNotifier = tipNotifier,
                            scope = scope,
                            messages = messages,
                            resultKey = resultKey,
                            onSelectedServerIdChange = onSelectedServerIdChange,
                            isDragging = draggingServerId == server.id,
                            dragActive = draggingServerId != null,
                            visualOffset = lazyListState.proxyServerVisualOffset(
                                serverId = server.id,
                                draggedServerId = draggingServerId,
                                draggedOffset = draggedServerOffset,
                            ),
                            draggable = pageServers.size > 1,
                            onDragStart = {
                                draggingServerId = server.id
                                draggedServerOffset = 0f
                            },
                            onDrag = { dragAmountY ->
                                if (draggingServerId != server.id) {
                                    draggingServerId = server.id
                                    draggedServerOffset = 0f
                                }
                                draggedServerOffset += dragAmountY
                                lazyListState.autoScrollForDraggedProxyServer(
                                    draggedServerId = server.id,
                                    draggedOffset = draggedServerOffset,
                                    scrollBy = { amount ->
                                        scope.launch { lazyListState.scrollBy(amount) }
                                    },
                                )
                            },
                            onDragEnd = {
                                lazyListState.findProxyServerDropTarget(
                                    draggedServerId = server.id,
                                    draggedOffset = draggedServerOffset,
                                )?.let { targetServerId ->
                                    updateAppState { state ->
                                        state.copy(
                                            proxyServers = state.proxyServers.moveServerTo(
                                                serverId = server.id,
                                                targetServerId = targetServerId,
                                            ),
                                        )
                                    }
                                }
                                draggingServerId = null
                                draggedServerOffset = 0f
                            },
                            modifier = Modifier
                                .zIndex(if (draggingServerId == server.id) 2f else 0f)
                                .animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                    placementSpec = folmeSpring(damping = 0.9f, response = 0.38f),
                                ),
                        )
                    }
                }
            }
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(lazyListState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                trackPadding = contentPadding,
            )
        }
    }
}

@Composable
private fun ProxyServerListItem(
    server: ProxyServerState,
    selectedServerId: Int,
    pageIsAllGroupsSelected: Boolean,
    pageGroupId: Int,
    unknownGroupName: String,
    itemTextFormatter: ProxyServerListItemTextFormatter,
    groupState: ProxyServerListGroups,
    updateAppState: ((AppState) -> AppState) -> Unit,
    navigator: Navigator,
    clipboard: Clipboard,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
    resultKey: String,
    onSelectedServerIdChange: (Int) -> Unit,
    isDragging: Boolean,
    dragActive: Boolean,
    visualOffset: Float,
    draggable: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProxyServerListItemCard(
        server = server,
        displayText = itemTextFormatter.displayOf(server),
        selected = selectedServerId == server.id,
        modifier = modifier,
        groupName = if (pageIsAllGroupsSelected) {
            groupState.groupNames[server.groupId] ?: unknownGroupName
        } else {
            null
        },
        isDragging = isDragging,
        dragActive = dragActive,
        visualOffset = visualOffset,
        draggable = draggable,
        onDragStart = onDragStart,
        onDrag = onDrag,
        onDragEnd = onDragEnd,
        onSelect = {
            onSelectedServerIdChange(server.id)
            updateAppState { state ->
                if (state.selectedProxyServerId == server.id) {
                    state
                } else {
                    state.copy(selectedProxyServerId = server.id)
                }
            }
        },
        onShare = {
            scope.launch {
                val url = runCatching { server.server.getUrlOrNull() }.getOrNull()
                if (url == null) {
                    tipNotifier.show(messages.unsupported)
                } else {
                    clipboard.setPlainText(url)
                    tipNotifier.show(messages.copied)
                }
            }
        },
        onEdit = {
            navigator.navigateForResult(
                route = Route.ProxyServerEditor(
                    ps = server.server,
                    serverId = server.id,
                    groupId = server.groupId,
                    returnGroupId = pageGroupId,
                    resultKey = resultKey,
                ),
                requestKey = resultKey,
            )
        },
        onDelete = {
            var deleted = false
            updateAppState { state ->
                if (state.selectedProxyServerId == server.id) {
                    state
                } else {
                    deleted = true
                    state.copy(
                        proxyServers = state.proxyServers.filterNot { it.id == server.id },
                    )
                }
            }
            scope.launch {
                tipNotifier.show(
                    if (deleted) {
                        messages.deletedTemplate.formatTemplate("name" to server.server.getInfo().remarks)
                    } else {
                        messages.deleteSelectedBlocked
                    },
                )
            }
        },
    )
}

private fun androidx.compose.foundation.lazy.LazyListState.proxyServerVisualOffset(
    serverId: Int,
    draggedServerId: Int?,
    draggedOffset: Float,
): Float {
    if (draggedServerId == null) return 0f
    if (serverId == draggedServerId) return draggedOffset

    val visibleServers = layoutInfo.visibleItemsInfo.filter { item -> item.key is Int }
    val dragged = visibleServers.firstOrNull { item -> item.key == draggedServerId } ?: return 0f
    val item = visibleServers.firstOrNull { item -> item.key == serverId } ?: return 0f

    return when {
        draggedOffset > 0f && item.offset > dragged.offset -> {
            val draggedBottom = dragged.offset + dragged.size + draggedOffset
            val progress = ((draggedBottom - item.offset) / item.size).coerceIn(0f, 1f)
            -dragged.size * progress
        }
        draggedOffset < 0f && item.offset < dragged.offset -> {
            val draggedTop = dragged.offset + draggedOffset
            val progress = ((item.offset + item.size - draggedTop) / item.size).coerceIn(0f, 1f)
            dragged.size * progress
        }
        else -> 0f
    }
}

private fun androidx.compose.foundation.lazy.LazyListState.findProxyServerDropTarget(
    draggedServerId: Int,
    draggedOffset: Float,
): Int? {
    val visibleServers = layoutInfo.visibleItemsInfo.filter { item -> item.key is Int }
    val dragged = visibleServers.firstOrNull { item -> item.key == draggedServerId } ?: return null
    val draggedCenter = dragged.offset + dragged.size / 2f + draggedOffset
    val target = when {
        draggedOffset > 0f -> visibleServers.lastOrNull { item ->
            item.offset > dragged.offset && draggedCenter >= item.forwardSwapThreshold
        }
        draggedOffset < 0f -> visibleServers.firstOrNull { item ->
            item.offset < dragged.offset && draggedCenter <= item.backwardSwapThreshold
        }
        else -> null
    } ?: return null

    return target.key as? Int
}

private val LazyListItemInfo.forwardSwapThreshold: Float
    get() = offset + size * ProxyServerSwapThreshold

private val LazyListItemInfo.backwardSwapThreshold: Float
    get() = offset + size * (1f - ProxyServerSwapThreshold)

private fun androidx.compose.foundation.lazy.LazyListState.autoScrollForDraggedProxyServer(
    draggedServerId: Int,
    draggedOffset: Float,
    scrollBy: (Float) -> Unit,
) {
    val dragged = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.key == draggedServerId } ?: return
    val draggedCenter = dragged.offset + dragged.size / 2f + draggedOffset
    val edgeSize = dragged.size.coerceAtLeast(1) / 2f
    val topEdge = layoutInfo.viewportStartOffset + edgeSize
    val bottomEdge = layoutInfo.viewportEndOffset - edgeSize

    when {
        draggedCenter < topEdge -> scrollBy(-edgeSize / 3f)
        draggedCenter > bottomEdge -> scrollBy(edgeSize / 3f)
    }
}

private fun List<ProxyServerState>.moveServerTo(
    serverId: Int,
    targetServerId: Int,
): List<ProxyServerState> {
    val currentIndex = indexOfFirst { server -> server.id == serverId }
    val targetIndex = indexOfFirst { server -> server.id == targetServerId }
    if (currentIndex < 0 || targetIndex < 0 || currentIndex == targetIndex) {
        return this
    }

    return toMutableList().apply {
        add(targetIndex, removeAt(currentIndex))
    }
}
