package org.anmon.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

object FastScrollHelper {
    /**
     * Extracts the uppercase alphabet initial ('A'..'Z') for a given title/name,
     * or '#' if it starts with a number, symbol, or is blank.
     */
    fun extractAlphabetInitial(text: String?): String {
        if (text.isNullOrBlank()) return "#"
        val trimmed = text.trim()
        val firstChar = trimmed.firstOrNull() ?: return "#"
        return if (firstChar.isLetter()) {
            firstChar.uppercaseChar().toString()
        } else {
            "#"
        }
    }

    /**
     * Maps a vertical drag fraction in [0f, 1f] to a target item index and its alphabet initial.
     */
    fun <T> computeFastScrollTarget(
        fraction: Float,
        items: List<T>,
        labelSelector: (T) -> String
    ): Pair<Int, String> {
        if (items.isEmpty()) return 0 to "#"
        val clamped = fraction.coerceIn(0f, 1f)
        val targetIndex = (clamped * (items.size - 1)).roundToInt().coerceIn(0, items.size - 1)
        val letter = extractAlphabetInitial(labelSelector(items[targetIndex]))
        return targetIndex to letter
    }
}

@Composable
fun <T> LazyListAlphabetFastScroller(
    listState: LazyListState,
    items: List<T>,
    labelSelector: (T) -> String,
    itemIndexOffset: Int = 0,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val scope = rememberCoroutineScope()
    val scrollProgress by remember(listState, items.size, itemIndexOffset) {
        derivedStateOf {
            if (items.isEmpty()) 0f
            else {
                val rawIdx = (listState.firstVisibleItemIndex - itemIndexOffset).coerceIn(0, (items.size - 1).coerceAtLeast(1))
                (rawIdx.toFloat() / (items.size - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    AlphabetFastScrollerCore(
        items = items,
        labelSelector = labelSelector,
        externalProgress = scrollProgress,
        onFastScrollToIndex = { targetIndex ->
            scope.launch {
                listState.scrollToItem(targetIndex + itemIndexOffset)
            }
        },
        modifier = modifier
    )
}

@Composable
fun <T> LazyGridAlphabetFastScroller(
    gridState: LazyGridState,
    items: List<T>,
    labelSelector: (T) -> String,
    itemIndexOffset: Int = 0,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    val scope = rememberCoroutineScope()
    val scrollProgress by remember(gridState, items.size, itemIndexOffset) {
        derivedStateOf {
            if (items.isEmpty()) 0f
            else {
                val rawIdx = (gridState.firstVisibleItemIndex - itemIndexOffset).coerceIn(0, (items.size - 1).coerceAtLeast(1))
                (rawIdx.toFloat() / (items.size - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    AlphabetFastScrollerCore(
        items = items,
        labelSelector = labelSelector,
        externalProgress = scrollProgress,
        onFastScrollToIndex = { targetIndex ->
            scope.launch {
                gridState.scrollToItem(targetIndex + itemIndexOffset)
            }
        },
        modifier = modifier
    )
}

@Composable
private fun <T> AlphabetFastScrollerCore(
    items: List<T>,
    labelSelector: (T) -> String,
    externalProgress: Float,
    onFastScrollToIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val currentItems by rememberUpdatedState(items)
    val currentLabelSelector by rememberUpdatedState(labelSelector)
    val currentOnScroll by rememberUpdatedState(onFastScrollToIndex)

    var trackHeightPx by remember { mutableIntStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var activeLetter by remember { mutableStateOf("#") }
    var hidePopupJob by remember { mutableStateOf<Job?>(null) }
    var lastScrolledIndex by remember { mutableIntStateOf(-1) }

    val displayedProgress = if (isDragging) dragFraction else externalProgress
    val density = LocalDensity.current
    val thumbHeightDp = 40.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 4.dp,
        animationSpec = tween(durationMillis = 150),
        label = "fast_scroll_thumb_width"
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Centered + Thumb-Tracking Alphabet Popup
        AnimatedVisibility(
            visible = isDragging && currentItems.isNotEmpty(),
            enter = fadeIn(tween(120)) + scaleIn(tween(150), initialScale = 0.8f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.Center)
                .testTag("fast_scroll_alphabet_popup")
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(84.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = activeLetter,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Right-Edge Interactive Fast Scroll Strip & Floating Side Bubble
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(28.dp)
                .padding(vertical = 8.dp)
                .onSizeChanged { size ->
                    trackHeightPx = size.height.coerceAtLeast(1)
                }
                .testTag("alphabet_fast_scroller_rail")
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        hidePopupJob?.cancel()
                        isDragging = true
                        lastScrolledIndex = -1

                        fun updateFromY(y: Float) {
                            val list = currentItems
                            if (list.isEmpty()) return
                            val fraction = (y / trackHeightPx.toFloat()).coerceIn(0f, 1f)
                            dragFraction = fraction
                            val (targetIdx, letter) = FastScrollHelper.computeFastScrollTarget(
                                fraction = fraction,
                                items = list,
                                labelSelector = currentLabelSelector
                            )
                            activeLetter = letter
                            if (targetIdx != lastScrolledIndex) {
                                lastScrolledIndex = targetIdx
                                currentOnScroll(targetIdx)
                            }
                        }

                        down.consume()
                        updateFromY(down.position.y)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            change.consume()
                            updateFromY(change.position.y)
                        }

                        hidePopupJob = scope.launch {
                            delay(350)
                            isDragging = false
                        }
                    }
                }
        ) {
            val maxThumbOffsetPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
            val thumbOffsetY = (displayedProgress.coerceIn(0f, 1f) * maxThumbOffsetPx).roundToInt()

            // Subtle vertical track line on the far right
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .width(if (isDragging) 4.dp else 2.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (isDragging) 0.24f else 0.10f
                        )
                    )
            )

            // Thumb indicator along the right edge
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 2.dp)
                    .offset { IntOffset(0, thumbOffsetY) }
                    .size(width = thumbWidth, height = thumbHeightDp)
                    .clip(CircleShape)
                    .background(
                        if (isDragging) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        }
                    )
            )

            // Side-tracking mini pill next to finger while dragging
            if (isDragging && currentItems.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            IntOffset(
                                x = with(density) { (-38).dp.roundToPx() },
                                y = thumbOffsetY
                            )
                        }
                        .size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = activeLetter,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}
