@file:OptIn(ExperimentalScrollBarApi::class)

package features.routing

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalUpdateAppState
import app.R
import app.collectAppState
import app.proxyServerOutboundTag
import features.proxy.server.display.displayNameById
import features.proxy.server.display.displayNameWithGroup
import features.routing.model.RouteRule
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import ui.components.NavigationIcon
import ui.layout.AdaptiveTopAppBar
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.layout.pageScrollModifiers
import ui.text.formatTemplate

private val DomainStrategyOptions = listOf(
    "AsIs",
    "IPIfNonMatch",
    "IPOnDemand",
)

private const val RouteRuleSwapThreshold = 0.3f

@Composable
fun RoutingPage(
    padding: PaddingValues,
) {
    val isWideScreen = LocalIsWideScreen.current
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    val unknownGroup = stringResource(R.string.common_unknown_group)
    val defaultGroupName = stringResource(R.string.subscription_default_group)
    val defaultNodeTemplate = stringResource(R.string.routing_default_node)
    val deletedTemplate = stringResource(R.string.routing_deleted)
    val savedTemplate = stringResource(R.string.routing_saved)
    val fixedOutboundOptions = fixedRouteRuleOutboundOptions()
    val outboundOptions = remember(
        fixedOutboundOptions,
        appState.proxyServers,
        appState.subscriptionGroups,
        unknownGroup,
        defaultGroupName,
        defaultNodeTemplate,
    ) {
        val groupNames = appState.subscriptionGroups.displayNameById(defaultGroupName)
        (fixedOutboundOptions + appState.proxyServers.map { node ->
            RouteRuleOutboundOption(
                tag = node.proxyServerOutboundTag(),
                label = node.displayNameWithGroup(
                    defaultNodeTemplate = defaultNodeTemplate,
                    groupNames = groupNames,
                    unknownGroupName = unknownGroup,
                ),
            )
        }).distinctBy { option -> option.tag }
    }
    val outboundLabels = remember(outboundOptions) {
        outboundOptions.associate { option -> option.tag to option.label }
    }

    var editingRule by remember { mutableStateOf<RouteRule?>(null) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var draggingRuleId by remember { mutableStateOf<Int?>(null) }
    var draggedRuleOffset by remember { mutableFloatStateOf(0f) }
    val rules = appState.routeRules

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = stringResource(R.string.routing_title),
                isWideScreen = isWideScreen,
                scrollBehavior = topAppBarScrollBehavior,
                actions = {
                    NavigationIcon(
                        onClick = {
                            editingRule = null
                            showRuleDialog = true
                        },
                        imageVector = MiuixIcons.Add,
                    )
                },
            )
        },
    ) { innerPadding ->
        val lazyListState = rememberLazyListState()
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        val listPadding = pageListPadding(contentPadding)

        Box {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.pageScrollModifiers(
                    topAppBarScrollBehavior,
                ),
                contentPadding = listPadding,
            ) {
                item(key = "routing_policy") {
                    SmallTitle(text = stringResource(R.string.routing_domain_policy))
                    RoutingPolicyCard(
                        domainStrategyOptions = DomainStrategyOptions,
                        selectedDomainStrategy = appState.routeDomainStrategy,
                        onDomainStrategyChange = { index ->
                            updateAppState { state -> state.copy(routeDomainStrategy = index) }
                        },
                    )
                }
                item(key = "routing_rules_title") {
                    SmallTitle(text = stringResource(R.string.routing_title))
                }
                itemsIndexed(
                    items = rules,
                    key = { _, rule -> rule.id },
                ) { _, rule ->
                    RouteRuleCard(
                        rule = rule,
                        outboundLabel = outboundLabels[rule.outboundTag] ?: rule.outboundTag,
                        isDragging = draggingRuleId == rule.id,
                        dragActive = draggingRuleId != null,
                        visualOffset = lazyListState.routeRuleVisualOffset(
                            ruleId = rule.id,
                            draggedRuleId = draggingRuleId,
                            draggedOffset = draggedRuleOffset,
                        ),
                        draggable = rules.size > 1,
                        onDragStart = {
                            draggingRuleId = rule.id
                            draggedRuleOffset = 0f
                        },
                        onDrag = { dragAmountY ->
                            if (draggingRuleId != rule.id) {
                                draggingRuleId = rule.id
                                draggedRuleOffset = 0f
                            }
                            draggedRuleOffset += dragAmountY
                            lazyListState.autoScrollForDraggedRouteRule(
                                draggedRuleId = rule.id,
                                draggedOffset = draggedRuleOffset,
                                scrollBy = { amount ->
                                    scope.launch { lazyListState.scrollBy(amount) }
                                },
                            )
                        },
                        onDragEnd = {
                            lazyListState.findRouteRuleDropTarget(
                                draggedRuleId = rule.id,
                                draggedOffset = draggedRuleOffset,
                            )?.let { targetRuleId ->
                                updateAppState { state ->
                                    state.copy(
                                        routeRules = state.routeRules.moveRuleTo(
                                            ruleId = rule.id,
                                            targetRuleId = targetRuleId,
                                        ),
                                    )
                                }
                            }
                            draggingRuleId = null
                            draggedRuleOffset = 0f
                        },
                        onToggle = { enabled ->
                            updateAppState { state ->
                                state.copy(
                                    routeRules = state.routeRules.map {
                                        if (it.id == rule.id) it.copy(enabled = enabled) else it
                                    },
                                )
                            }
                        },
                        onEdit = {
                            editingRule = rule
                            showRuleDialog = true
                        },
                        onDelete = {
                            if (draggingRuleId == rule.id) {
                                draggingRuleId = null
                                draggedRuleOffset = 0f
                            }
                            updateAppState { state ->
                                state.copy(
                                    routeRules = state.routeRules.filterNot { it.id == rule.id },
                                )
                            }
                            scope.launch {
                                tipNotifier.show(deletedTemplate.formatTemplate("name" to rule.remarks))
                            }
                        },
                        modifier = Modifier
                            .zIndex(if (draggingRuleId == rule.id) 2f else 0f)
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = folmeSpring(damping = 0.9f, response = 0.38f),
                            ),
                    )
                }
                item(key = "routing_empty") {
                    if (rules.isEmpty()) {
                        RoutingEmptyCard()
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

    RouteRuleEditorBottomSheet(
        show = showRuleDialog,
        initialRule = editingRule,
        nextRuleId = appState.nextRouteRuleId,
        outboundOptions = outboundOptions,
        onDismissRequest = { showRuleDialog = false },
        onSave = { savedRule ->
            updateAppState { state ->
                val exists = state.routeRules.any { it.id == savedRule.id }
                state.copy(
                    routeRules = if (exists) {
                        state.routeRules.map { if (it.id == savedRule.id) savedRule else it }
                    } else {
                        state.routeRules + savedRule
                    },
                    nextRouteRuleId = if (exists) {
                        state.nextRouteRuleId
                    } else {
                        maxOf(state.nextRouteRuleId, savedRule.id + 1)
                    },
                )
            }
            showRuleDialog = false
            scope.launch {
                tipNotifier.show(savedTemplate.formatTemplate("name" to savedRule.remarks))
            }
        },
    )
}

@Composable
private fun fixedRouteRuleOutboundOptions(): List<RouteRuleOutboundOption> {
    return listOf(
        RouteRuleOutboundOption(tag = "proxy", label = stringResource(R.string.routing_outbound_proxy)),
        RouteRuleOutboundOption(tag = "direct", label = stringResource(R.string.routing_outbound_direct)),
        RouteRuleOutboundOption(tag = "block", label = stringResource(R.string.routing_outbound_block)),
    )
}

private fun androidx.compose.foundation.lazy.LazyListState.routeRuleVisualOffset(
    ruleId: Int,
    draggedRuleId: Int?,
    draggedOffset: Float,
): Float {
    if (draggedRuleId == null) return 0f
    if (ruleId == draggedRuleId) return draggedOffset

    val visibleRouteRules = layoutInfo.visibleItemsInfo.filter { item -> item.key is Int }
    val dragged = visibleRouteRules.firstOrNull { item -> item.key == draggedRuleId } ?: return 0f
    val item = visibleRouteRules.firstOrNull { item -> item.key == ruleId } ?: return 0f

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

private fun androidx.compose.foundation.lazy.LazyListState.findRouteRuleDropTarget(
    draggedRuleId: Int,
    draggedOffset: Float,
): Int? {
    val visibleRouteRules = layoutInfo.visibleItemsInfo.filter { item -> item.key is Int }
    val dragged = visibleRouteRules.firstOrNull { item -> item.key == draggedRuleId } ?: return null
    val draggedCenter = dragged.offset + dragged.size / 2f + draggedOffset
    val target = when {
        draggedOffset > 0f -> visibleRouteRules.lastOrNull { item ->
            item.offset > dragged.offset && draggedCenter >= item.forwardSwapThreshold
        }
        draggedOffset < 0f -> visibleRouteRules.firstOrNull { item ->
            item.offset < dragged.offset && draggedCenter <= item.backwardSwapThreshold
        }
        else -> null
    } ?: return null

    return target.key as? Int
}

private val LazyListItemInfo.forwardSwapThreshold: Float
    get() = offset + size * RouteRuleSwapThreshold

private val LazyListItemInfo.backwardSwapThreshold: Float
    get() = offset + size * (1f - RouteRuleSwapThreshold)

private fun androidx.compose.foundation.lazy.LazyListState.autoScrollForDraggedRouteRule(
    draggedRuleId: Int,
    draggedOffset: Float,
    scrollBy: (Float) -> Unit,
) {
    val dragged = layoutInfo.visibleItemsInfo.firstOrNull { item -> item.key == draggedRuleId } ?: return
    val draggedCenter = dragged.offset + dragged.size / 2f + draggedOffset
    val edgeSize = dragged.size.coerceAtLeast(1) / 2f
    val topEdge = layoutInfo.viewportStartOffset + edgeSize
    val bottomEdge = layoutInfo.viewportEndOffset - edgeSize

    when {
        draggedCenter < topEdge -> scrollBy(-edgeSize / 3f)
        draggedCenter > bottomEdge -> scrollBy(edgeSize / 3f)
    }
}

private fun List<RouteRule>.moveRuleTo(ruleId: Int, targetRuleId: Int): List<RouteRule> {
    val currentIndex = indexOfFirst { rule -> rule.id == ruleId }
    val targetIndex = indexOfFirst { rule -> rule.id == targetRuleId }
    if (currentIndex < 0 || targetIndex < 0 || currentIndex == targetIndex) {
        return this
    }

    return toMutableList().apply {
        add(targetIndex, removeAt(currentIndex))
    }
}
