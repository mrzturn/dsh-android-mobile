package com.dsh.mobile.fold

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.launch

/**
 * Samsung Galaxy Z Flip 7 fold posture. The inner 6.9" screen folds on the
 * HORIZONTAL axis; Flex Mode (HALF_OPENED, ~110°) leaves a usable strip at
 * the bottom that hosts the FlexPad (touchpad + quick keys) while the Web GUI
 * rides the top half. The cover screen (1048x948) gets the compact layout
 * automatically from the tiny-window logic.
 */
data class FoldPosture(
    val state: FoldingFeature.State? = null,
    val horizontalFold: Boolean = false,
) {
    val isFlex: Boolean get() = state == FoldingFeature.State.HALF_OPENED
    val isFlat: Boolean get() = state == FoldingFeature.State.FLAT
    val flexPadAvailable: Boolean get() = isFlex && horizontalFold
}

@Composable
fun rememberFoldPosture(): FoldPosture {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val activity = context as? ComponentActivity
    var posture by remember { mutableStateOf(FoldPosture()) }
    LaunchedEffect(context, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(context)
                .windowLayoutInfo(activity ?: return@repeatOnLifecycle)
                .collect { info: WindowLayoutInfo ->
                    val f = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    posture = FoldPosture(
                        state = f?.state,
                        horizontalFold = f?.orientation == FoldingFeature.Orientation.HORIZONTAL,
                    )
                }
        }
    }
    return posture
}
