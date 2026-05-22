@file:OptIn(ExperimentalFoundationApi::class, ExperimentalScrollBarApi::class)

package features.proxy.server.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
import ui.components.DragReorderLazyListCacheWindow
import ui.components.adjacentDragDisplacementForItem
import ui.components.autoScrollWhileDragging
import ui.components.findDragDropTarget
import ui.components.rememberLazyListOverlayDragState
import kotlin.math.roundToInt

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
        val lazyListState = rememberLazyListState(cacheWindow = DragReorderLazyListCacheWindow)
        val density = LocalDensity.current
        val maxAutoScrollPerFrame = with(density) { 28.dp.toPx() }
        val dragState = rememberLazyListOverlayDragState(lazyListState)
        val draggedServerId = dragState.draggedKey as? Int
        val activeGhostServerId = dragState.activeGhostKey as? Int
        val hiddenServerId = dragState.hiddenKey as? Int

        fun moveDraggedServerIfNeeded() {
            val currentDragged = dragState.draggedItem ?: return
            val serverId = currentDragged.key as? Int ?: return
            val target = lazyListState.findDragDropTarget(
                draggedItem = currentDragged,
                isReorderableItem = { item -> item.key is Int },
                swapThreshold = ProxyServerSwapThreshold,
            )
            val targetServerId = target?.key as? Int ?: return

            updateAppState { state ->
                state.copy(
                    proxyServers = state.proxyServers.moveServerTo(
                        serverId = serverId,
                        targetServerId = targetServerId,
                    ),
                )
            }
            dragState.updateDraggedIndex(target.index)
        }

        LaunchedEffect(lazyListState, draggedServerId, maxAutoScrollPerFrame) {
            if (draggedServerId != null) {
                lazyListState.autoScrollWhileDragging(
                    draggedItemProvider = { dragState.draggedItem },
                    maxScrollPerFrame = maxAutoScrollPerFrame,
                    onFrame = { moveDraggedServerIfNeeded() },
                )
            }
        }

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
                            isDragging = false,
                            dragActive = dragState.dragActive,
                            visualOffset = lazyListState.adjacentDragDisplacementForItem(
                                itemKey = server.id,
                                draggedItem = dragState.draggedItem,
                                isReorderableItem = { item -> item.key is Int },
                            ),
                            draggable = pageServers.size > 1,
                            onDragStart = {
                                dragState.start(server.id)
                            },
                            onDrag = { dragAmountY ->
                                dragState.dragBy(server.id, dragAmountY)
                                moveDraggedServerIfNeeded()
                            },
                            onDragEnd = {
                                dragState.settle()
                            },
                            modifier = if (hiddenServerId == server.id) {
                                Modifier.alpha(0f)
                            } else {
                                Modifier.animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                    placementSpec = folmeSpring(damping = 0.9f, response = 0.38f),
                                )
                            },
                        )
                    }
                }
            }
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(lazyListState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                trackPadding = contentPadding,
            )
            activeGhostServerId?.let { serverId ->
                val server = pageServers.firstOrNull { item -> item.id == serverId }
                if (server != null) {
                    ProxyServerListDragGhost(
                        server = server,
                        selectedServerId = selectedServerId,
                        pageIsAllGroupsSelected = pageIsAllGroupsSelected,
                        unknownGroupName = unknownGroupName,
                        itemTextFormatter = itemTextFormatter,
                        groupState = groupState,
                        isDragging = dragState.isDragging(serverId),
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    0,
                                    dragState.ghostTop.roundToInt(),
                                )
                            }
                            .zIndex(3f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProxyServerListDragGhost(
    server: ProxyServerState,
    selectedServerId: Int,
    pageIsAllGroupsSelected: Boolean,
    unknownGroupName: String,
    itemTextFormatter: ProxyServerListItemTextFormatter,
    groupState: ProxyServerListGroups,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    ProxyServerListItemCard(
        server = server,
        displayText = itemTextFormatter.displayOf(server),
        selected = selectedServerId == server.id,
        groupName = if (pageIsAllGroupsSelected) {
            groupState.groupNames[server.groupId] ?: unknownGroupName
        } else {
            null
        },
        isDragging = isDragging,
        dragActive = true,
        visualOffset = 0f,
        draggable = false,
        onDragStart = {},
        onDrag = {},
        onDragEnd = {},
        onSelect = {},
        onShare = {},
        onEdit = {},
        onDelete = {},
        modifier = modifier,
    )
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
