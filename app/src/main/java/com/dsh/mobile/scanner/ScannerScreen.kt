package com.dsh.mobile.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dsh.mobile.MainViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * QR scanner for the desktop's pairing QR (and any pair link shown as text).
 * CameraX analysis -> ZXing; also offers manual paste fallback (useful on the
 * Z Flip cover screen where scanning + typing at once is awkward).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) { if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA) }

    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }

    fun onScan(text: String) {
        if (result == null) {
            result = text
            vm.handleIncoming(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫码配对") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        torchOn = !torchOn
                        camera?.cameraControl?.enableTorch(torchOn)
                    }) {
                        Icon(
                            when {
                                torchOn -> Icons.Filled.FlashlightOn
                                else -> Icons.Filled.FlashlightOff
                            },
                            "手电筒",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black, RoundedCornerShape(18.dp)),
            ) {
                if (hasPermission) {
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val executor = Executors.newSingleThreadExecutor()
                            val reader = MultiFormatReader().apply {
                                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                            }
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                            providerFuture.addListener({
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                analysis.setAnalyzer(executor) { image: ImageProxy ->
                                    val text = decode(image, reader)
                                    image.close()
                                    text?.let { t ->
                                        previewView.post { onScan(t) }
                                    }
                                }
                                try {
                                    provider.unbindAll()
                                    camera = provider.bindToLifecycle(
                                        owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                                    )
                                } catch (_: Exception) { }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Reticle(Modifier.align(Alignment.Center))
                } else {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("需要相机权限才能扫码", color = Color.White)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("授权") }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("也可以粘贴配对链接 / 32 位 Key", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://192.168.1.10:3080/pair-accept?pair=… 或 32 位 key") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrBlank()) manual = text
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("粘贴") }
                Button(
                    onClick = { if (manual.isNotBlank()) vm.handleIncoming(manual) },
                    modifier = Modifier.weight(2f),
                ) { Text("连接") }
            }
        }
    }
}

/** ZXing decode of a CameraX YUV frame. */
private fun decode(image: ImageProxy, reader: MultiFormatReader): String? {
    return runCatching {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val source = PlanarYUVLuminanceSource(
            bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        reader.decodeWithState(bitmap)?.text
    }.getOrNull().also { reader.reset() }
}

/** Viewfinder reticle. */
@Composable
private fun Reticle(modifier: Modifier = Modifier) {
    Canvas(modifier.size(230.dp)) {
        val stroke = 6f
        val r = 28f
        val c = Color.White.copy(alpha = 0.9f)
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawArc(
                color = c, startAngle = when {
                    dx > 0 && dy > 0 -> 180f
                    dx < 0 && dy > 0 -> 270f
                    dx < 0 && dy < 0 -> 0f
                    else -> 90f
                }, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(
                    if (dx > 0) x else x - 2 * r,
                    if (dy > 0) y else y - 2 * r,
                ),
                style = Stroke(stroke),
            )
        }
        corner(0f, 0f, 1f, 1f)
        corner(size.width, 0f, -1f, 1f)
        corner(0f, size.height, 1f, -1f)
        corner(size.width, size.height, -1f, -1f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.08f),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(stroke),
        )
    }
}
