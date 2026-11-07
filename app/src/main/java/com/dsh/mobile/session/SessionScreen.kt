package com.dsh.mobile.session

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.mobile.data.Connection
import com.dsh.mobile.fold.FoldPosture

/**
 * One live session against a DSH host. The official Web GUI fills the screen;
 * on a Z Flip 7 in Flex Mode the bottom strip hosts the FlexPad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    connection: Connection,
    entryUrl: String,
    posture: FoldPosture,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: SessionViewModel = viewModel(key = "session-" + connection.id)
    vm.bind(connection)

    val load by vm.load.collectAsState()
    var webRef by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var viewportSize by remember { mutableStateOf(android.util.Size(0, 0)) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val exit by vm.routeExit.collectAsState()
    if (exit) { vm.consumeExit(); onBack() }
    val bridge = remember(connection.baseUrl) { FlexPadBridge() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(connection.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    SessionActions(
                        vm = vm,
                        connection = connection,
                        context = context,
                        web = webRef,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val state = load
            when (state) {
                is SessionLoad.Loading -> LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> {}
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { size: IntSize ->
                        // Viewport for the FlexPad cursor, in CSS pixels.
                        viewportSize = android.util.Size(size.width, size.height)
                        bridge.setViewport(size.width / density, size.height / density)
                    },
            ) {
                DshWebView(
                    entryUrl = entryUrl,
                    sessionVm = vm,
                    posture = posture,
                    flexPadBridge = bridge,
                    modifier = Modifier.fillMaxSize(),
                    onWebViewReady = { webRef = it },
                    onExternalLink = { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                )

                if (state is SessionLoad.Failed) {
                    ErrorPane(
                        state = state,
                        onRetry = { webRef?.reload(); vm.retry(null) },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            if (posture.flexPadAvailable) {
                FlexPad(
                    bridge = bridge,
                    viewport = Pair(viewportSize.width / density, viewportSize.height / density),
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun SessionActions(
    vm: SessionViewModel,
    connection: Connection,
    context: android.content.Context,
    web: android.webkit.WebView?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text("重新加载") },
            onClick = { menuOpen = false; web?.reload(); vm.retry(null) },
        )
        DropdownMenuItem(
            text = { Text("在浏览器中打开") },
            onClick = {
                menuOpen = false
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(connection.baseUrl)))
                }
            },
        )
        DropdownMenuItem(
            text = { Text("清除本机凭据并退出") },
            onClick = {
                menuOpen = false
                WebViewCache.destroyIfOrigin(connection.baseUrl)
                vm.clearCredentialsAndBack()
            },
        )
    }
}

@Composable
private fun ErrorPane(
    state: SessionLoad.Failed,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("无法连接到桌面端", style = MaterialTheme.typography.titleMedium)
        Text(
            "请确认电脑上的 dsh web 正在运行，且手机与电脑在同一网络（或公网隧道可用）。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        Button(onClick = onRetry) { Text("重试") }
    }
}
