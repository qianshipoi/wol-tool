package com.qianshi.woltool.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qianshi.woltool.remote.QrDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val ScannerBackground = Color(0xFF0E1513)
private val ScannerAccent = Color(0xFF75F8E4)

/** 相册图片解码前把长边压到这个上限，兼顾识别率与内存占用。 */
private const val MAX_GALLERY_EDGE = 2000

/**
 * 全屏二维码扫描界面：CameraX 实时取景 + ZXing 解码，相机不可用时可用相册导入兜底。
 *
 * 只把识别到的**原始文本**回传给调用方，分享码的解析与校验由调用方完成，
 * 避免扫描端与分享码格式互相绑定。
 *
 * @param onResult 识别成功后的原始文本（调用方负责解析）。
 * @param onDismiss 用户关闭扫描界面（返回键 / 左上角箭头）。
 */
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // 返回键只关闭这个 Dialog，不会触发 MainActivity 的页面返回手势。
            dismissOnBackPress = true,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = ScannerBackground,
        ) {
            QrScannerContent(onResult = onResult, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun QrScannerContent(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var permissionGranted by remember { mutableStateOf(hasCameraPermission(context)) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var albumMessage by remember { mutableStateOf<String?>(null) }
    var albumLoading by remember { mutableStateOf(false) }

    // 从系统设置页返回时刷新权限状态：授予后不必重开扫描页。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasCameraPermission(context)
                permissionGranted = granted
                if (granted) permanentlyDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionRequested = true
        permissionGranted = granted
        if (!granted) {
            val activity = context.findActivity()
            permanentlyDenied = activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        }
    }

    fun decodeFromGallery(uri: Uri) {
        if (albumLoading) return
        scope.launch {
            albumLoading = true
            albumMessage = null
            val text = withContext(Dispatchers.IO) {
                runCatching { decodeQrFromGallery(context, uri) }.getOrNull()
            }
            albumLoading = false
            if (text != null) {
                onResult(text)
            } else {
                albumMessage = "没有识别到二维码，换一张更清晰、完整的图片试试"
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) decodeFromGallery(uri)
    }

    val pickFromGallery: () -> Unit = {
        runCatching {
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }.onFailure { albumMessage = "无法打开相册" }
    }

    LaunchedEffect(albumMessage) {
        val message = albumMessage ?: return@LaunchedEffect
        delay(2600)
        if (albumMessage == message) albumMessage = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (permissionGranted) {
            CameraScanner(
                onDecoded = onResult,
                onPickFromGallery = pickFromGallery,
            )
        } else {
            PermissionRequestContent(
                permissionRequested = permissionRequested,
                permanentlyDenied = permanentlyDenied,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onPickFromGallery = pickFromGallery,
                onOpenSettings = { openAppSettings(context) },
            )
        }

        ScannerTopBar(
            onClose = onDismiss,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        albumMessage?.let { message ->
            AlbumMessageCard(
                message = message,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (albumLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = ScannerAccent)
            }
        }
    }
}

@Composable
private fun ScannerTopBar(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "关闭",
                tint = Color.White,
            )
        }
        Text(
            text = "扫描二维码",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun PermissionRequestContent(
    permissionRequested: Boolean,
    permanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onPickFromGallery: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .background(Color.White.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.QrCodeScanner,
                contentDescription = null,
                tint = ScannerAccent,
                modifier = Modifier.size(46.dp),
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            text = "需要相机权限",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "扫描中继手机上的二维码需要打开相机取景。画面只在本机实时处理，不会上传或保存。",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.72f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (permanentlyDenied) {
            Text(
                text = "相机权限已被拒绝，请到系统设置中允许「相机」权限后再试。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("去设置")
            }
        } else {
            Button(
                onClick = onRequestPermission,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (permissionRequested) "重新请求相机权限" else "授予相机权限")
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onPickFromGallery,
            shape = RoundedCornerShape(16.dp),
            // 深色底上使用浅色描边与文字，避免主题的 onSurface 在深色背景下看不清。
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.48f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        ) {
            Icon(
                imageVector = Icons.Rounded.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("从相册选择二维码")
        }
    }
}

/**
 * 相机取景 + 帧分析。相机会绑定到宿主 Activity 的 lifecycle，
 * 界面销毁（DisposableEffect.onDispose）时解绑并关闭线程池，重新进入可再次工作。
 */
@Composable
private fun CameraScanner(
    onDecoded: (String) -> Unit,
    onPickFromGallery: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = rememberCameraLifecycleOwner()
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    // 保证一帧成功只回调一次：成功后立即置位，并停止分析 / 解绑相机。
    val handled = remember { AtomicBoolean(false) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            // TextureView 实现，在 Dialog 这类独立窗口里行为更稳定。
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(previewView, lifecycleOwner) {
        val analyzerExecutor = Executors.newSingleThreadExecutor()
        var cameraProvider: ProcessCameraProvider? = null
        var disposed = false

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (disposed) return@addListener
            val provider = runCatching { providerFuture.get() }.getOrNull()
            if (provider == null) {
                cameraError = "相机初始化失败，可以改用相册导入二维码"
                return@addListener
            }
            cameraProvider = provider

            val rotation = previewView.display?.rotation ?: currentDisplayRotation(context)
            val preview = Preview.Builder()
                .build()
                .apply {
                    targetRotation = rotation
                    setSurfaceProvider(previewView.surfaceProvider)
                }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.targetRotation = rotation
            analysis.setAnalyzer(analyzerExecutor) { image ->
                if (handled.get()) {
                    image.close()
                    return@setAnalyzer
                }
                val text = decodeFrame(image)
                // ImageProxy 必须关闭，否则会阻塞后续帧。
                image.close()
                if (text != null && handled.compareAndSet(false, true)) {
                    // 切主线程再停止分析并回调，避免在分析线程里操作 CameraX 状态。
                    mainExecutor.execute {
                        runCatching { analysis.clearAnalyzer() }
                        runCatching { provider.unbindAll() }
                        onDecoded(text)
                    }
                }
            }

            // 没有后置相机的设备（部分平板/模拟器）回退到前置相机，避免直接报错。
            val cameraSelector = when {
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                    CameraSelector.DEFAULT_BACK_CAMERA
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else -> null
            }
            val bound = cameraSelector?.let { selector ->
                runCatching {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        analysis,
                    )
                }.getOrNull()
            }
            if (bound == null) {
                cameraError = "无法打开相机，可以改用相册导入二维码"
                return@addListener
            }
            camera = bound
            torchAvailable = bound.cameraInfo.hasFlashUnit()

            // 视图真正 attach 到窗口后 display 才有值，这里再校正一次取景方向。
            previewView.post {
                if (disposed) return@post
                val attachedRotation = previewView.display?.rotation ?: return@post
                runCatching {
                    preview.targetRotation = attachedRotation
                    analysis.targetRotation = attachedRotation
                }
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            handled.set(true)
            runCatching { camera?.cameraControl?.enableTorch(false) }
            runCatching { cameraProvider?.unbindAll() }
            analyzerExecutor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val frameSize = minOf(maxWidth, maxHeight) * 0.68f
            ScanFrameOverlay(
                frameSize = frameSize,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                text = "把中继手机上的二维码放入框内",
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = frameSize / 2 + 28.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (torchAvailable) {
                ScannerActionButton(
                    icon = if (torchOn) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                    label = if (torchOn) "关闭手电筒" else "打开手电筒",
                    active = torchOn,
                    onClick = {
                        val next = !torchOn
                        torchOn = next
                        runCatching { camera?.cameraControl?.enableTorch(next) }
                    },
                )
            }
            ScannerActionButton(
                icon = Icons.Rounded.PhotoLibrary,
                label = "从相册选择",
                active = false,
                onClick = onPickFromGallery,
            )
        }

        cameraError?.let { message ->
            CameraErrorCard(
                message = message,
                onPickFromGallery = onPickFromGallery,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun ScannerActionButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .background(
                color = if (active) {
                    ScannerAccent.copy(alpha = 0.24f)
                } else {
                    Color.White.copy(alpha = 0.12f)
                },
                shape = CircleShape,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) ScannerAccent else Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 中央取景框：四周压暗 + 四角装饰，帮助用户把二维码放进框内。 */
@Composable
private fun ScanFrameOverlay(
    frameSize: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cornerLength = with(density) { 28.dp.toPx() }
    val strokeWidth = with(density) { 3.dp.toPx() }
    Canvas(modifier = modifier) {
        val frame = frameSize.toPx()
        val left = (size.width - frame) / 2f
        val top = (size.height - frame) / 2f
        val right = left + frame
        val bottom = top + frame

        val scrim = Color.Black.copy(alpha = 0.45f)
        drawRect(scrim, topLeft = Offset.Zero, size = Size(size.width, top))
        drawRect(
            scrim,
            topLeft = Offset(0f, bottom),
            size = Size(size.width, size.height - bottom),
        )
        drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, frame))
        drawRect(
            scrim,
            topLeft = Offset(right, top),
            size = Size(size.width - right, frame),
        )

        fun line(start: Offset, end: Offset) {
            drawLine(
                color = ScannerAccent,
                start = start,
                end = end,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        // 左上
        line(Offset(left, top), Offset(left + cornerLength, top))
        line(Offset(left, top), Offset(left, top + cornerLength))
        // 右上
        line(Offset(right - cornerLength, top), Offset(right, top))
        line(Offset(right, top), Offset(right, top + cornerLength))
        // 左下
        line(Offset(left, bottom), Offset(left + cornerLength, bottom))
        line(Offset(left, bottom - cornerLength), Offset(left, bottom))
        // 右下
        line(Offset(right - cornerLength, bottom), Offset(right, bottom))
        line(Offset(right, bottom - cornerLength), Offset(right, bottom))
    }
}

@Composable
private fun CameraErrorCard(
    message: String,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.padding(horizontal = 28.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF262D2B)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onPickFromGallery) {
                Text("从相册选择二维码", color = ScannerAccent)
            }
        }
    }
}

@Composable
private fun AlbumMessageCard(
    message: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 96.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF262D2B)),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
        )
    }
}

// ---- 相机帧与相册图片 ----

/**
 * 把 ImageProxy 的 Y 平面（第一个 plane）按 rowStride / pixelStride 复制成紧凑灰度数组。
 * rowStride 可能大于 width，所以不能直接 `buffer.get(bytes)`。
 */
private fun decodeFrame(image: ImageProxy): String? = runCatching {
    val plane = image.planes.firstOrNull() ?: return@runCatching null
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return@runCatching null
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    if (rowStride < width || pixelStride <= 0) return@runCatching null

    val buffer = plane.buffer
    buffer.rewind()
    val gray = ByteArray(width * height)

    if (pixelStride == 1 && rowStride == width) {
        val count = minOf(gray.size, buffer.remaining())
        if (count <= 0) return@runCatching null
        buffer.get(gray, 0, count)
    } else {
        val row = ByteArray(rowStride)
        var destination = 0
        for (rowIndex in 0 until height) {
            val remaining = buffer.remaining()
            if (remaining <= 0) return@runCatching null
            val toRead = minOf(rowStride, remaining)
            buffer.get(row, 0, toRead)
            var column = 0
            var source = 0
            while (column < width && source < toRead) {
                gray[destination + column] = row[source]
                column++
                source += pixelStride
            }
            destination += width
        }
    }

    QrDecoder.decodeGray(gray, width, height, image.imageInfo.rotationDegrees)
}.getOrNull()

/** 相册图片：先降采样再解码，避免大图 OOM。 */
private fun decodeQrFromGallery(context: Context, uri: Uri): String? {
    val bitmap = loadGalleryBitmap(context, uri) ?: return null
    return try {
        QrDecoder.decodeBitmap(bitmap)
    } finally {
        bitmap.recycle()
    }
}

private fun loadGalleryBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // 硬件位图无法 getPixels，这里强制使用软件内存。
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longestEdge = maxOf(info.size.width, info.size.height)
            if (longestEdge > MAX_GALLERY_EDGE) {
                decoder.setTargetSampleSize((longestEdge + MAX_GALLERY_EDGE - 1) / MAX_GALLERY_EDGE)
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longestEdge <= 0) return@runCatching null

        var sampleSize = 1
        while (longestEdge / (sampleSize * 2) >= MAX_GALLERY_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }
}.getOrNull()

// ---- 系统能力 ----

@Composable
private fun rememberCameraLifecycleOwner(): LifecycleOwner {
    val context = LocalContext.current
    val compositionLocalOwner = LocalLifecycleOwner.current
    return remember(context, compositionLocalOwner) {
        // Dialog 内的 ViewTreeLifecycleOwner 在个别机型上不稳定；
        // 相机绑定宿主 Activity 的 lifecycle 更可靠，onDispose 里再解绑。
        context.findActivity() as? LifecycleOwner ?: compositionLocalOwner
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Suppress("DEPRECATION")
private fun currentDisplayRotation(context: Context): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // 取不到 display 时（例如非显示上下文）继续走下面的兜底逻辑。
        runCatching { return context.display.rotation }
    }
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    return windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
}
