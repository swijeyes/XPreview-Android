// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.xpreview.app.Routes
import org.xpreview.app.container
import org.xpreview.vision.DocumentDetector
import org.xpreview.vision.Vision
import java.util.concurrent.Executors

/**
 * Camera screen (plan 6.1/6.2): CameraX preview, low-resolution analysis
 * frames for live edge detection, full-resolution capture.
 */
@Composable
fun ScannerScreen(nav: NavController, sessionId: String?, replacePageId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera access", style = MaterialTheme.typography.titleLarge)
            Text(
                "XPreview+ uses the camera only to scan documents. Images never leave this device.",
                Modifier.padding(vertical = 12.dp),
            )
            if (denied) {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text("Open settings") }
            } else {
                Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
            Text("You can also import photos from the gallery.", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall)
            GalleryImportButton(nav, sessionId)
            FilledTonalButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Back") }
        }
        return
    }
    CameraContent(nav, sessionId, replacePageId, onBack)
}

@Composable
private fun GalleryImportButton(nav: NavController, sessionId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.container.scans
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val id = withContext(Dispatchers.IO) { importInto(repo, sessionId, uris) }
            nav.navigate(Routes.review(id)) { popUpTo(Routes.HOME) }
        }
    }
    TextButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
        Text("Import from gallery")
    }
}

@Composable
private fun TextButton(onClick: () -> Unit, content: @Composable () -> Unit) =
    androidx.compose.material3.TextButton(onClick = onClick) { content() }

private fun importInto(repo: ScanRepository, sessionId: String?, uris: List<Uri>): String {
    val s = sessionId?.let { repo.load(it) } ?: repo.create()
    val filter = ScanDefaults.filter
    for (u in uris) {
        val id = runCatching { repo.importImage(s, u) }.getOrNull() ?: continue
        s.pages += ScanPage(id, repo.detect(s.original(id), null), filter)
    }
    repo.save(s)
    return s.id
}

/** Default filter, refreshed from settings when the camera opens. */
internal object ScanDefaults { @Volatile var filter = org.xpreview.vision.ScanFilter.Document }

@Composable
private fun CameraContent(nav: NavController, sessionId: String?, replacePageId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val repo = context.container.scans
    val settings = remember { mutableStateOf(org.xpreview.app.data.AppSettings()) }
    LaunchedEffect(Unit) { context.container.settings.flow.collect { settings.value = it; ScanDefaults.filter = it.defaultFilter } }

    var session by remember { mutableStateOf<ScanSession?>(null) }
    LaunchedEffect(sessionId) {
        session = withContext(Dispatchers.IO) { sessionId?.let { repo.load(it) } ?: repo.create() }
    }
    var pageCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(session) { pageCount = session?.pages?.size ?: 0 }

    var live by remember { mutableStateOf(LiveState()) }
    var torch by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(true) }
    LaunchedEffect(settings.value.autoCapture) { auto = settings.value.autoCapture }
    var batch by remember { mutableStateOf(replacePageId == null) }
    var capturing by remember { mutableStateOf(false) }
    val tracker = remember { LiveTracker() }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { ContextCompat.getMainExecutor(context) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            previewResolutionSelector = ratio
            imageAnalysisResolutionSelector = ratio
            imageCaptureResolutionSelector = ratio
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
        }
    }

    fun capture(fromAuto: Boolean) {
        val s = session ?: return
        if (capturing) return
        capturing = true
        if (!fromAuto) tracker.onManualCapture(System.currentTimeMillis())
        val liveQuad = live.quad
        val id = repo.newPageId()
        val file = s.original(id)
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        sound.play(MediaActionSound.SHUTTER_CLICK)
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(), captureExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // The page may have moved since the last analysed frame: re-detect (plan 6.3).
                    val quad = runCatching { repo.detect(file, liveQuad) }.getOrDefault(liveQuad ?: org.xpreview.vision.Quad.full())
                    val page = ScanPage(id, quad, ScanDefaults.filter)
                    val replaceIdx = replacePageId?.let { r -> s.pages.indexOfFirst { it.id == r } } ?: -1
                    if (replaceIdx >= 0) {
                        s.original(s.pages[replaceIdx].id).delete()
                        repo.invalidatePreview(s, s.pages[replaceIdx].id)
                        s.pages[replaceIdx] = page
                    } else {
                        s.pages += page
                    }
                    runCatching { repo.save(s) }
                    main.execute {
                        capturing = false
                        pageCount = s.pages.size
                        if (!batch || replaceIdx >= 0) nav.navigate(Routes.review(s.id)) { popUpTo(Routes.HOME) }
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    main.execute { capturing = false }
                }
            },
        )
    }

    DisposableEffect(lifecycle) {
        Vision.init()
        controller.setImageAnalysisAnalyzer(analysisExecutor) { proxy ->
            try {
                val plane = proxy.planes[0]
                val gray = DocumentDetector.grayFromYPlane(plane.buffer, proxy.width, proxy.height, plane.rowStride, proxy.imageInfo.rotationDegrees)
                try {
                    val q = DocumentDetector.detect(gray)
                    val sharp = DocumentDetector.sharpness(gray, q)
                    val bright = Core.mean(gray).`val`[0]
                    val st = tracker.update(q, sharp, bright, gray.cols().toFloat() / gray.rows(), auto && batchAllowsAuto(capturing), System.currentTimeMillis())
                    main.execute {
                        live = st
                        if (st.captureNow) capture(fromAuto = true)
                    }
                } finally {
                    gray.release()
                }
            } catch (_: Exception) {
            } finally {
                proxy.close()
            }
        }
        controller.bindToLifecycle(lifecycle)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analysisExecutor.shutdown()
            captureExecutor.shutdown()
            sound.release()
        }
    }
    LaunchedEffect(torch) { controller.enableTorch(torch) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Quad overlay in view coordinates for a FIT_CENTER preview.
        Canvas(Modifier.fillMaxSize()) {
            val q = live.quad ?: return@Canvas
            val a = live.frameAspect
            val w = minOf(size.width, size.height * a)
            val h = w / a
            val ox = (size.width - w) / 2
            val oy = (size.height - h) / 2
            val pts = q.points.map { Offset(ox + it.x.toFloat() * w, oy + it.y.toFloat() * h) }
            val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            val color = if (live.hint == Hint.Ready || live.hint == Hint.Captured) Color(0xFF4CAF50) else Color(0xFF2196F3)
            drawPath(path, color.copy(alpha = 0.25f))
            drawPath(path, color, style = Stroke(4.dp.toPx()))
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
            IconButton(onClick = onBack, colors = tint) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Row {
                IconButton(onClick = { torch = !torch }, colors = tint) {
                    Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, if (torch) "Torch on" else "Torch off")
                }
                IconButton(onClick = { auto = !auto }, colors = tint) {
                    Icon(Icons.Default.AutoMode, if (auto) "Auto-capture on" else "Auto-capture off", tint = if (auto) Color(0xFF4CAF50) else Color.White)
                }
                if (replacePageId == null) IconButton(onClick = { batch = !batch }, colors = tint) {
                    Icon(Icons.Default.Layers, if (batch) "Batch mode on" else "Batch mode off", tint = if (batch) Color(0xFF4CAF50) else Color.White)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.6f)) {
                Text(
                    live.hint.text + if (auto) "" else " · tap the button to capture",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(120.dp), contentAlignment = Alignment.Center) { GalleryImportButtonIcon(nav, session?.id) }
                // The manual shutter is always reachable (plan 12.2).
                Box(
                    Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(6.dp).background(Color.White, CircleShape)
                        .semantics { contentDescription = "Capture page" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (capturing) CircularProgressIndicator(Modifier.size(40.dp))
                    else IconButton(onClick = { capture(fromAuto = false) }, modifier = Modifier.fillMaxSize()) {}
                }
                Box(Modifier.width(120.dp), contentAlignment = Alignment.Center) {
                    if (pageCount > 0) Button(onClick = { session?.let { nav.navigate(Routes.review(it.id)) { popUpTo(Routes.HOME) } } }) {
                        Text("Done ($pageCount)")
                    }
                }
            }
        }
    }
}

private fun batchAllowsAuto(capturing: Boolean) = !capturing

@Composable
private fun GalleryImportButtonIcon(nav: NavController, sessionId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.container.scans
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val id = withContext(Dispatchers.IO) { importInto(repo, sessionId, uris) }
            nav.navigate(Routes.review(id)) { popUpTo(Routes.HOME) }
        }
    }
    IconButton(
        onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
    ) { Icon(Icons.Default.Collections, "Import from gallery") }
}
