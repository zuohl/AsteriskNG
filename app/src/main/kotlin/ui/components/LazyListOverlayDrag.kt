package ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import kotlin.coroutines.coroutineContext

private const val DefaultSwapThreshold = 0.3f
private const val AutoScrollEdgeFraction = 0.5f

// About 250 proxy cards on each side at the current card height plus spacing.
private val DragReorderCacheWindowExtent = 32_000.dp

@OptIn(ExperimentalFoundationApi::class)
internal val DragReorderLazyListCacheWindow: LazyLayoutCacheWindow =
    LazyLayoutCacheWindow(
        ahead = DragReorderCacheWindowExtent,
        behind = DragReorderCacheWindowExtent,
    )

internal data class LazyListDragItem(
    val key: Any,
    val index: Int,
    val initialTop: Int,
    val size: Int,
    val dragOffset: Float = 0f,
) {
    val top: Float
        get() = initialTop + dragOffset

    val bottom: Float
        get() = top + size

    val center: Float
        get() = top + size / 2f

    fun dragBy(delta: Float): LazyListDragItem = copy(dragOffset = dragOffset + delta)
}

internal data class LazyListDropTarget(
    val key: Any,
    val index: Int,
)

@Composable
internal fun rememberLazyListOverlayDragState(
    lazyListState: LazyListState,
): LazyListOverlayDragState {
    val scope = rememberCoroutineScope()
    return remember(lazyListState, scope) {
        LazyListOverlayDragState(
            lazyListState = lazyListState,
            scope = scope,
        )
    }
}

internal class LazyListOverlayDragState(
    private val lazyListState: LazyListState,
    private val scope: CoroutineScope,
) {
    var draggedItem by mutableStateOf<LazyListDragItem?>(null)
        private set

    private var settlingItem by mutableStateOf<LazyListDragItem?>(null)
    private val settlingTop = Animatable(0f)
    private var settlingAnimationStarted by mutableStateOf(false)
    private var settlingJob: Job? = null
    private var session = 0

    val draggedKey: Any?
        get() = draggedItem?.key

    val settlingKey: Any?
        get() = settlingItem?.key

    val activeGhostKey: Any?
        get() = (draggedItem ?: settlingItem)?.key

    val hiddenKey: Any?
        get() = draggedKey ?: settlingKey

    val dragActive: Boolean
        get() = draggedItem != null || settlingItem != null

    val ghostTop: Float
        get() = draggedItem?.top
            ?: if (settlingAnimationStarted) {
                settlingTop.value
            } else {
                settlingItem?.top ?: 0f
            }

    fun isDragging(key: Any): Boolean {
        return draggedKey == key
    }

    fun start(key: Any) {
        session += 1
        settlingJob?.cancel()
        settlingJob = null
        settlingItem = null
        settlingAnimationStarted = false
        draggedItem = lazyListState.visibleDragItem(key)
    }

    fun dragBy(key: Any, delta: Float) {
        if (draggedKey != key) {
            draggedItem = lazyListState.visibleDragItem(key)
        }
        draggedItem = draggedItem?.dragBy(delta)
    }

    fun updateDraggedIndex(index: Int) {
        draggedItem = draggedItem?.copy(index = index)
    }

    fun settle() {
        val releasedItem = draggedItem
        if (releasedItem != null) {
            settlingItem = releasedItem
            settlingAnimationStarted = false
        }
        draggedItem = null

        if (releasedItem != null) {
            session += 1
            val currentSession = session
            settlingJob?.cancel()
            settlingJob = scope.launch {
                try {
                    settlingTop.snapTo(releasedItem.top)
                    if (session != currentSession) return@launch
                    settlingAnimationStarted = true
                    settlingTop.animateTo(
                        targetValue = lazyListState.awaitVisibleItemOverlayTop(
                            key = releasedItem.key,
                            fallback = releasedItem.initialTop.toFloat(),
                        ),
                        animationSpec = folmeSpring(damping = 0.9f, response = 0.38f),
                    )
                } finally {
                    if (session == currentSession) {
                        settlingItem = null
                        settlingAnimationStarted = false
                        settlingJob = null
                    }
                }
            }
        }
    }

    fun clearIfActive(key: Any) {
        if (draggedKey == key || settlingKey == key) {
            clear()
        }
    }

    private fun clear() {
        session += 1
        settlingJob?.cancel()
        settlingJob = null
        draggedItem = null
        settlingItem = null
        settlingAnimationStarted = false
    }
}

internal fun LazyListState.visibleDragItem(key: Any): LazyListDragItem? {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { info -> info.key == key } ?: return null
    return LazyListDragItem(
        key = item.key,
        index = item.index,
        initialTop = item.overlayTop(layoutInfo.viewportStartOffset),
        size = item.size,
    )
}

internal fun LazyListState.findDragDropTarget(
    draggedItem: LazyListDragItem?,
    isReorderableItem: (LazyListItemInfo) -> Boolean = { true },
    swapThreshold: Float = DefaultSwapThreshold,
): LazyListDropTarget? {
    if (draggedItem == null) return null
    val draggedSlot = dragSlot(draggedItem) ?: return null
    if (draggedSlot.index != draggedItem.index) return null

    val visibleItems = layoutInfo.visibleItemsInfo.filter { info ->
        info.key != draggedItem.key && isReorderableItem(info)
    }
    val viewportStartOffset = layoutInfo.viewportStartOffset
    val target = when (dragSide(draggedItem)) {
        DragSide.After -> visibleItems.firstOrNull { item ->
            item.index == draggedItem.index + 1 &&
                draggedItem.center >= item.forwardSwapThreshold(viewportStartOffset, swapThreshold)
        }

        DragSide.Before -> visibleItems.firstOrNull { item ->
            item.index == draggedItem.index - 1 &&
                draggedItem.center <= item.backwardSwapThreshold(viewportStartOffset, swapThreshold)
        }

        DragSide.Neutral -> null
    }

    return target?.let { item -> LazyListDropTarget(key = item.key, index = item.index) }
}

internal fun LazyListState.adjacentDragDisplacementForItem(
    itemKey: Any,
    draggedItem: LazyListDragItem?,
    isReorderableItem: (LazyListItemInfo) -> Boolean = { true },
): Float {
    if (draggedItem == null || itemKey == draggedItem.key) return 0f
    val draggedSlot = dragSlot(draggedItem) ?: return 0f
    if (draggedSlot.index != draggedItem.index) return 0f

    val item = layoutInfo.visibleItemsInfo.firstOrNull { info ->
        info.key == itemKey && isReorderableItem(info)
    } ?: return 0f
    val itemTop = item.overlayTop(layoutInfo.viewportStartOffset)
    val dragSide = dragSide(draggedItem)

    return when {
        dragSide == DragSide.After && item.index == draggedItem.index + 1 -> {
            val progress = ((draggedItem.bottom - itemTop) / item.size).coerceIn(0f, 1f)
            -draggedItem.size * progress
        }

        dragSide == DragSide.Before && item.index == draggedItem.index - 1 -> {
            val progress = ((itemTop + item.size - draggedItem.top) / item.size).coerceIn(0f, 1f)
            draggedItem.size * progress
        }

        else -> 0f
    }
}

internal suspend fun LazyListState.autoScrollWhileDragging(
    draggedItemProvider: () -> LazyListDragItem?,
    maxScrollPerFrame: Float,
    onFrame: () -> Unit = {},
) {
    while (coroutineContext.isActive) {
        val draggedItem = draggedItemProvider() ?: break
        val scrollDelta = dragAutoScrollDelta(
            draggedItem = draggedItem,
            maxScrollPerFrame = maxScrollPerFrame,
        )
        if (scrollDelta != 0f) {
            scrollBy(scrollDelta)
        }
        withFrameNanos {}
        onFrame()
    }
}

internal suspend fun LazyListState.awaitVisibleItemOverlayTop(
    key: Any,
    fallback: Float,
): Float {
    repeat(2) {
        withFrameNanos {}
        visibleItemOverlayTop(key)?.let { top -> return top }
    }
    return visibleItemOverlayTop(key) ?: fallback
}

private fun LazyListState.visibleItemOverlayTop(key: Any): Float? {
    return layoutInfo.visibleItemsInfo
        .firstOrNull { item -> item.key == key }
        ?.overlayTop(layoutInfo.viewportStartOffset)
        ?.toFloat()
}

private enum class DragSide {
    Before,
    After,
    Neutral,
}

private fun LazyListState.dragSlot(draggedItem: LazyListDragItem): LazyListItemInfo? {
    return layoutInfo.visibleItemsInfo.firstOrNull { item -> item.key == draggedItem.key }
}

private fun LazyListState.dragSlotCenter(draggedItem: LazyListDragItem): Float? {
    val slot = dragSlot(draggedItem) ?: return null
    return slot.overlayTop(layoutInfo.viewportStartOffset) + slot.size / 2f
}

private fun LazyListState.dragSide(draggedItem: LazyListDragItem): DragSide {
    val slotCenter = dragSlotCenter(draggedItem) ?: return DragSide.Neutral
    return when {
        draggedItem.center > slotCenter -> DragSide.After
        draggedItem.center < slotCenter -> DragSide.Before
        else -> DragSide.Neutral
    }
}

private fun LazyListState.dragAutoScrollDelta(
    draggedItem: LazyListDragItem,
    maxScrollPerFrame: Float,
): Float {
    val edgeSize = (draggedItem.size * AutoScrollEdgeFraction).coerceAtLeast(1f)
    val topEdge = edgeSize
    val bottomEdge = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset - edgeSize

    return when {
        draggedItem.top < topEdge -> {
            val progress = ((topEdge - draggedItem.top) / edgeSize).coerceIn(0f, 1f)
            -maxScrollPerFrame * progress
        }

        draggedItem.bottom > bottomEdge -> {
            val progress = ((draggedItem.bottom - bottomEdge) / edgeSize).coerceIn(0f, 1f)
            maxScrollPerFrame * progress
        }

        else -> 0f
    }
}

private fun LazyListItemInfo.overlayTop(viewportStartOffset: Int): Int {
    return offset - viewportStartOffset
}

private fun LazyListItemInfo.forwardSwapThreshold(
    viewportStartOffset: Int,
    swapThreshold: Float,
): Float {
    return overlayTop(viewportStartOffset) + size * swapThreshold.coerceIn(0f, 1f)
}

private fun LazyListItemInfo.backwardSwapThreshold(
    viewportStartOffset: Int,
    swapThreshold: Float,
): Float {
    return overlayTop(viewportStartOffset) + size * (1f - swapThreshold.coerceIn(0f, 1f))
}
