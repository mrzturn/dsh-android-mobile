package com.dsh.mobile.session

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp

/**
 * FlexPad: Z Flip 7 Flex-Mode companion surface. Top half of the inner screen
 * shows the official Web GUI; this strip (the folded-away bottom half) hosts a
 * laptop-style touchpad plus quick keys that drive pointer/keyboard events
 * inside the WebView via the FlexPad bridge — so the fold never wastes space.
 */
@Composable
fun FlexPad(
    bridge: FlexPadBridge,
    viewport: Pair<Float, Float>,
    modifier: Modifier = Modifier,
) {
    // Virtual cursor in WebView page coordinates.
    var cursorX by remember { mutableFloatStateOf(0f) }
    var cursorY by remember { mutableFloatStateOf(0f) }
    var ctrlSticky by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }

    fun ensureCursor() {
        if (!initialized && viewport.first > 0f) {
            cursorX = viewport.first * 0.5f
            cursorY = viewport.second * 0.5f
            initialized = true
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            // Touchpad
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        RoundedCornerShape(14.dp),
                    )
                    .pointerInput(viewport) {
                        awaitEachGesture {
                            val down = awaitFirstDown(pass = PointerEventPass.Main)
                            down.consume()
                            ensureCursor()
                            var twoFinger = false
                            var moved = false
                            var startX = cursorX
                            var startY = cursorY
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val pressed = event.changes.filter { it.pressed }
                                if (event.changes.any { it.isConsumed }) continue
                                if (pressed.size >= 2) {
                                    twoFinger = true
                                    val scroll = event.changes
                                        .map { it.positionChange() }
                                        .reduce { a, b -> a + b } / pressed.size.toFloat()
                                    if (scroll != androidx.compose.ui.geometry.Offset.Zero) {
                                        bridge.scroll(
                                            cursorX.toDouble(), cursorY.toDouble(),
                                            (scroll.x * -2.2).toDouble(), (scroll.y * -2.2).toDouble(),
                                        )
                                        event.changes.forEach { it.consume() }
                                    }
                                } else if (pressed.size == 1) {
                                    val delta = pressed[0].positionChange()
                                    if (delta != androidx.compose.ui.geometry.Offset.Zero) {
                                        moved = true
                                        if (!twoFinger) {
                                            cursorX = (cursorX + delta.x * 1.9f)
                                                .coerceIn(0f, viewport.first)
                                            cursorY = (cursorY + delta.y * 1.9f)
                                                .coerceIn(0f, viewport.second)
                                            bridge.move(cursorX.toDouble(), cursorY.toDouble())
                                        }
                                        pressed[0].consume()
                                    }
                                } else {
                                    // All up: a short still press is a tap-click.
                                    if (!moved && !twoFinger) {
                                        bridge.clickAt(cursorX.toDouble(), cursorY.toDouble())
                                    }
                                    break
                                }
                                if (event.changes.all { !it.pressed }) break
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "触摸板 · 单指移动 / 点按单击 / 双指滚动",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
            // Quick keys
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QuickKey("Esc", Modifier.weight(1f)) {
                    bridge.sendKey("Escape", 27, ctrlSticky, false, false); ctrlSticky = false
                }
                QuickKey(if (ctrlSticky) "CTRL•" else "CTRL", Modifier.weight(1f)) { ctrlSticky = !ctrlSticky }
                QuickKey("Tab", Modifier.weight(1f)) {
                    bridge.sendKey("Tab", 9, ctrlSticky, false, false); ctrlSticky = false
                }
                QuickKey("↑", Modifier.weight(1f)) {
                    bridge.sendKey("ArrowUp", 38, ctrlSticky, false, false); ctrlSticky = false
                }
                QuickKey("↓", Modifier.weight(1f)) {
                    bridge.sendKey("ArrowDown", 40, ctrlSticky, false, false); ctrlSticky = false
                }
                QuickKey("输入框", Modifier.weight(1.4f)) { bridge.focusComposer() }
                QuickKey("发送 ⏎", Modifier.weight(1.4f), filled = true) {
                    bridge.focusComposer()
                    bridge.sendKey("Enter", 13, ctrlSticky, false, false)
                    ctrlSticky = false
                }
            }
        }
    }
}

@Composable
private fun QuickKey(label: String, modifier: Modifier = Modifier, filled: Boolean = false, onTap: () -> Unit) {
    Surface(
        modifier = modifier.pointerInput(onTap) {
            awaitEachGesture {
                awaitFirstDown(pass = PointerEventPass.Main).consume()
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.changes.all { !it.pressed }) {
                        onTap()
                        break
                    }
                }
            }
        },
        shape = RoundedCornerShape(10.dp),
        color = if (filled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = if (filled) 0.dp else 3.dp,
    ) {
        Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
