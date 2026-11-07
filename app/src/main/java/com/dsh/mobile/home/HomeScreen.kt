package com.dsh.mobile.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.Connection
import com.dsh.mobile.data.PairStatus
import com.dsh.mobile.MainViewModel

/**
 * Home: saved connections with live /api/pair/status chips, manual
 * "link + key" entry, and the scanner entry point. Compact by default so it
 * also presents well on the Z Flip 7 cover display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val connections by vm.connections.collectAsState()
    val statuses by vm.statusCache.collectAsState()
    val probing by vm.probing.collectAsState()
    val toast by vm.toast.collectAsState()

    var showManual by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Connection?>(null) }

    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it.text)
            vm.consumeToast()
        }
    }
    LaunchedEffect(Unit) { vm.probeAll() }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("DSH 随行") },
                actions = {
                    IconButton(onClick = { showManual = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "链接 + Key 手动配对")
                    }
                    IconButton(onClick = { vm.openScanner() }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "扫码配对")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "桌面端开启“远程访问”后，扫码即可把这台手机配对为全权受控设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            BigScanCard(onClick = { vm.openScanner() })

            Spacer(Modifier.height(12.dp))

            Text("已保存的连接", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))

            if (connections.isEmpty()) {
                EmptyHint()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(connections, key = { it.id }) { conn ->
                        ConnectionCard(
                            conn = conn,
                            status = statuses[conn.baseUrl],
                            probing = probing.contains(conn.baseUrl),
                            onClick = { vm.reconnect(conn) },
                            onRename = { editing = conn },
                            onForget = { vm.forget(conn) },
                            onRefresh = { vm.probe(conn) },
                        )
                    }
                }
            }
        }

        if (showManual) {
            ManualPairDialog(
                onDismiss = { showManual = false },
                onSubmit = { link, key ->
                    showManual = false
                    vm.handleIncoming(if (link.isBlank()) key else (if (key.isBlank()) link else "$link $key"))
                },
            )
        }
        editing?.let { conn ->
            RenameDialog(
                current = conn.label,
                onDismiss = { editing = null },
                onSubmit = { vm.rename(conn, it); editing = null },
            )
        }
    }
}

/** Manually enter a pair link and/or a bare 32-hex key with a host. */
@Composable
private fun ManualPairDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var link by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动配对") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = link, onValueChange = { link = it },
                    label = { Text("配对链接（扫码得到，可留空）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("或 32 位配对 Key") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("服务器地址（Key 方式必填，如 http://192.168.1.10:3080）") },
                    singleLine = true,
                )
                Text(
                    "扫码得到的链接会自动带上服务器地址；Key 需要配合服务器地址使用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSubmit(link.trim(), key.trim().let { k ->
                    if (k.isNotBlank() && host.isNotBlank()) "$host/pair-accept?pair=$k" else k
                })
            }) { Text("配对") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名连接") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onSubmit(text.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BigScanCard(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text("扫码配对", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "对准桌面端“远程访问”面板里的二维码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    conn: Connection,
    status: PairStatus?,
    probing: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(conn.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    conn.hostLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val chip = when {
                    probing -> "检测中…"
                    status == null -> "未检测"
                    status.phase == "connected" -> "已连接 · ${status.onlineCount} 台设备"
                    status.phase == "waiting" -> "等待配对 · 二维码有效"
                    status.phase == "lan-required" -> "桌面端未开启局域网/公网访问"
                    else -> "可达"
                }
                Text(
                    chip,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        status?.phase == "connected" -> MaterialTheme.colorScheme.primary
                        status?.phase == "waiting" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "检测") }
            IconButton(onClick = onRename) { Icon(Icons.Filled.Edit, "重命名") }
            IconButton(onClick = onForget) { Icon(Icons.Filled.Delete, "删除") }
        }
    }
}

@Composable
private fun EmptyHint() {
    Text(
        "还没有连接。在桌面端 dsh web 的“远程访问”面板生成二维码，用本应用扫码即可。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
}
