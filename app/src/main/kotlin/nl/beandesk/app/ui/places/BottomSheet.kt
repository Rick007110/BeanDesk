package nl.beandesk.app.ui.places

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A sheet that slides up over the screen. It closes with the back button, a tap on the dimmed
 * screen above it, or by pulling it down; within scrolling content, a pull down first scrolls the
 * content back to its top.
 *
 * Material's ModalBottomSheet is not used: after scrolling in it, it could stay in a state where
 * it thought it was still moving, and then every touch became a drag of the sheet, so no button
 * reacted any more. Here a touch only moves the sheet once it is a pull, so taps always reach the
 * content, and the sheet's height does not depend on where it is.
 */
@Composable
fun BottomSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val scope = rememberCoroutineScope()
    val sheet = remember { SheetPosition(scope) { currentOnDismiss() } }

    // The content scrolls first; what it does not use moves the sheet.
    val pull = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                // Pushing up while the sheet is pulled down: the sheet goes back up first.
                if (available.y < 0 && sheet.offset > 0f && source == NestedScrollSource.UserInput) {
                    Offset(0f, sheet.dragBy(available.y))
                } else {
                    Offset.Zero
                }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                // Pulling down with the content at its top.
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    Offset(0f, sheet.dragBy(available.y))
                } else {
                    Offset.Zero
                }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (sheet.offset <= 0f) return Velocity.Zero
                sheet.settle(available.y)
                return available
            }
        }
    }

    Dialog(
        onDismissRequest = { sheet.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The sheet dims the screen and slides in by itself.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { window ->
            LaunchedEffect(window) {
                window.setDimAmount(0f)
                window.setWindowAnimations(0)
            }
        }
        LaunchedEffect(sheet.height > 0) {
            if (sheet.height > 0) sheet.slideIn()
        }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = sheet.shown }
                    .background(Color.Black.copy(alpha = 0.32f))
                    .pointerInput(Unit) { detectTapGestures { sheet.close() } },
            )
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Never under the status bar, whatever its position.
                    .statusBarsPadding()
                    .padding(top = 48.dp)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .onSizeChanged { sheet.height = it.height }
                    .graphicsLayer {
                        translationY = sheet.offset
                        // Out of sight until it is measured and slides in from below.
                        alpha = if (sheet.started) 1f else 0f
                    }
                    // Let go after a pull that no fling settled: back up, or away.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                            } while (event.changes.any { it.pressed })
                            if (sheet.offset > 0f && !sheet.isMoving) sheet.settle(0f)
                        }
                    }
                    // Pulls on parts that do not scroll, like the handle and the buttons below.
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta -> sheet.dragBy(delta) },
                        onDragStopped = { velocity -> sheet.settle(velocity) },
                    )
                    .nestedScroll(pull),
            ) {
                Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                    DragHandle()
                    content()
                }
            }
        }
    }
}

/** Where the sheet is: [offset] pixels down from fully open, out of sight at [height]. */
private class SheetPosition(private val scope: CoroutineScope, private val onClosed: () -> Unit) {
    var offset by mutableFloatStateOf(0f)
        private set
    var height by mutableIntStateOf(0)
    var started by mutableStateOf(false)
        private set
    private var closing = false
    private var animation: Job? = null

    /** How much of the sheet is up, from 0 to 1; dims the screen behind it as much. */
    val shown: Float
        get() = if (started && height > 0) 1f - (offset / height).coerceIn(0f, 1f) else 0f

    val isMoving: Boolean
        get() = animation?.isActive == true

    fun slideIn() {
        if (started || closing) return
        offset = height.toFloat()
        started = true
        launchAnimation(0f, durationMillis = 250)
    }

    /** Moves the sheet with a finger; returns the part of [delta] it used. */
    fun dragBy(delta: Float): Float {
        if (closing) return 0f
        animation?.cancel()
        val new = (offset + delta).coerceIn(0f, height.toFloat())
        val used = new - offset
        offset = new
        return used
    }

    /** After letting go: away when pulled down far or fast enough, otherwise back up. */
    fun settle(velocity: Float) {
        if (closing) return
        if (offset > height * 0.3f || velocity > 1_500f) close() else launchAnimation(0f)
    }

    fun close() {
        if (closing) return
        closing = true
        launchAnimation(height.toFloat(), then = onClosed)
    }

    private fun launchAnimation(target: Float, durationMillis: Int = 200, then: () -> Unit = {}) {
        animation?.cancel()
        animation = scope.launch {
            animate(offset, target, animationSpec = tween(durationMillis)) { value, _ -> offset = value }
            then()
        }
    }
}

/** The bar at the top that shows the sheet can be pulled down. */
@Composable
private fun DragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
    }
}
