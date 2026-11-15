// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

package com.juancams.rviz2mini.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.juancams.rviz2mini.dashboard.TopicDrop
import com.juancams.rviz2mini.platform.Platform
import kotlin.math.roundToInt

/**
 * Drag a topic (from the topic list, the echo...) and drop it onto a dashboard widget or into an empty slot.
 * It is pure Compose, the same on the phone (long press and drag) and on the PC (drag with the mouse): the
 * coordinates are window coordinates, so it works even if the source is in another ComposeView (the Android widgets).
 */
object TopicDrag {
    var payload by mutableStateOf<TopicDrop.Payload?>(null); private set
    /** Where the finger or mouse is (window). */
    var pointer by mutableStateOf(Offset.Zero); private set
    /** The target underneath (to highlight it). */
    var hovered by mutableStateOf<Any?>(null); private set
    /** What happened on drop (notice for a few seconds). */
    var message by mutableStateOf<String?>(null)

    internal class Target(var bounds: Rect, var accepts: (TopicDrop.Payload) -> Boolean, var onDrop: (TopicDrop.Payload, Offset) -> String?)
    internal val targets = LinkedHashMap<Any, Target>()

    private fun targetAt(p: Offset, payload: TopicDrop.Payload): Map.Entry<Any, Target>? =
        // The smallest one containing it: a widget before the dashboard background.
        targets.entries.filter { it.value.bounds.contains(p) && it.value.accepts(payload) }.minByOrNull { it.value.bounds.width * it.value.bounds.height }

    internal fun start(p: TopicDrop.Payload, at: Offset) { payload = p; pointer = at; hovered = targetAt(at, p)?.key }
    internal fun moveTo(at: Offset) { pointer = at; payload?.let { hovered = targetAt(at, it)?.key } }
    internal fun cancel() { payload = null; hovered = null }
    internal fun drop() {
        val p = payload ?: return
        val t = targetAt(pointer, p)
        payload = null; hovered = null
        if (t == null) return
        message = runCatching { t.value.onDrop(p, pointer) }.getOrElse { it.message }
    }
}

/** This row can be dragged as a topic. */
fun Modifier.topicDragSource(topic: String, type: String, field: String? = null): Modifier = composed {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val payload = TopicDrop.Payload(topic, type, field)
    // On the phone, after a long press (otherwise the list could not be scrolled); on the PC, as soon as it is dragged.
    val longPress = Platform.name == "android"
    onGloballyPositioned { coords = it }.pointerInput(topic, type, field) {
        val onStart = { off: Offset -> coords?.takeIf { it.isAttached }?.let { TopicDrag.start(payload, it.localToWindow(off)) }; Unit }
        val onDrag = { ch: androidx.compose.ui.input.pointer.PointerInputChange -> ch.consume(); coords?.takeIf { it.isAttached }?.let { TopicDrag.moveTo(it.localToWindow(ch.position)) }; Unit }
        if (longPress) detectDragGesturesAfterLongPress(onDragStart = onStart, onDragEnd = { TopicDrag.drop() }, onDragCancel = { TopicDrag.cancel() }) { ch, _ -> onDrag(ch) }
        else detectDragGestures(onDragStart = onStart, onDragEnd = { TopicDrag.drop() }, onDragCancel = { TopicDrag.cancel() }) { ch, _ -> onDrag(ch) }
    }
}

/**
 * A topic can be dropped here: `onDrop` receives what is dropped and the point (window) and returns the notice. While
 * something acceptable is dragged over it, it is highlighted.
 */
fun Modifier.topicDropTarget(key: Any, accepts: (TopicDrop.Payload) -> Boolean = { true }, onDrop: (TopicDrop.Payload, Offset) -> String?): Modifier = composed {
    val target = remember(key) { TopicDrag.Target(Rect.Zero, accepts, onDrop) }
    target.accepts = accepts; target.onDrop = onDrop // with the latest (sizes, settings)
    DisposableEffect(key) {
        TopicDrag.targets[key] = target
        onDispose { if (TopicDrag.targets[key] === target) TopicDrag.targets.remove(key) }
    }
    val over = TopicDrag.payload != null && TopicDrag.hovered == key
    onGloballyPositioned { target.bounds = it.boundsInWindow() }
        .then(if (over) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
}

/** What is being dragged, following the finger, and the notice on drop. Sits above everything (in the dashboard). */
@Composable
fun BoxScope.TopicDragOverlay() {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInWindow() }.zIndex(5000f)) {
        TopicDrag.payload?.let { p ->
            val at = TopicDrag.pointer - origin
            Row(Modifier.offset { IntOffset(at.x.roundToInt() + 12, at.y.roundToInt() - 40) }.clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DragIndicator, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(p.topic + (p.field?.let { " · $it" } ?: ""), color = Color.White, fontSize = 13.sp)
            }
        }
        TopicDrag.message?.let { m ->
            LaunchedEffect(m) { kotlinx.coroutines.delay(3500); if (TopicDrag.message == m) TopicDrag.message = null }
            Text(m, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                .background(Color(0xDD000000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}
