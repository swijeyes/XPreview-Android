// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.scan

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xpreview.app.Routes
import org.xpreview.app.common.ExportDialog
import org.xpreview.app.container
import org.xpreview.app.io.ExportOptions
import org.xpreview.app.io.OcrPipeline
import org.xpreview.app.io.PdfBuilder
import org.xpreview.app.io.Quality
import org.xpreview.app.io.SafeSave
import org.xpreview.pdf.PdfDispatchers
import org.xpreview.vision.DocumentDetector
import org.xpreview.vision.Quad
import org.xpreview.vision.ScanFilter
import org.xpreview.vision.Vision

class ScanReviewViewModel(app: Application, private val sessionId: String) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.scans
    var session by mutableStateOf<ScanSession?>(null)
        private set
    val pages = mutableStateListOf<ScanPage>()
    val previews = mutableStateMapOf<String, ImageBitmap>()
    var busy by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    private val renderLock = Mutex()
    private var job: Job? = null
    var ocrLanguages = listOf("eng")
        private set

    init {
        viewModelScope.launch { ocrLanguages = c.settings.current().ocrLanguages }
    }

    /** Loads (or, after returning from the camera, refreshes) the session. */
    fun reload() {
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) { repo.load(sessionId) }
            if (s == null) { message = "This scan session no longer exists."; return@launch }
            session = s
            pages.clear(); pages += s.pages
        }
    }

    fun preview(p: ScanPage) {
        if (previews.containsKey(previewKey(p))) return
        val s = session ?: return
        viewModelScope.launch {
            val bmp = renderLock.withLock { withContext(Dispatchers.Default) { runCatching { repo.preview(s, p) }.getOrNull() } }
            if (bmp != null) previews[previewKey(p)] = bmp.asImageBitmap()
        }
    }

    fun previewKey(p: ScanPage) = "${p.id}-${p.hashCode()}"

    fun update(p: ScanPage) {
        val s = session ?: return
        val i = s.pages.indexOfFirst { it.id == p.id }
        if (i < 0) return
        s.pages[i] = p
        pages[i] = p
        repo.invalidatePreview(s, p.id)
        persist()
    }

    fun applyFilterToAll(f: ScanFilter) {
        val s = session ?: return
        s.pages.forEachIndexed { i, p -> s.pages[i] = p.copy(filter = f); pages[i] = s.pages[i]; repo.invalidatePreview(s, p.id) }
        persist()
    }

    fun delete(p: ScanPage) {
        val s = session ?: return
        s.pages.removeAll { it.id == p.id }
        pages.removeAll { it.id == p.id }
        s.original(p.id).delete()
        repo.invalidatePreview(s, p.id)
        persist()
    }

    fun move(from: Int, to: Int) {
        val s = session ?: return
        if (to !in s.pages.indices) return
        s.pages.add(to, s.pages.removeAt(from))
        pages.add(to, pages.removeAt(from))
        persist()
    }

    private fun persist() {
        val s = session ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { repo.save(s) } }
    }

    /** Downscaled upright original for the corner editor. */
    suspend fun original(p: ScanPage): Bitmap? = session?.let { s ->
        withContext(Dispatchers.IO) { runCatching { repo.decodeUpright(s.original(p.id), 2_000_000) }.getOrNull() }
    }

    suspend fun autoDetect(b: Bitmap): Quad? = withContext(Dispatchers.Default) { Vision.init(); DocumentDetector.detect(b) }

    /** Scan session → PDF (plan 6.7 "Done" → 7.3), optionally OCR'd. */
    fun export(dest: Uri, options: ExportOptions, onDone: (Uri) -> Unit) {
        val s = session ?: return
        val list = s.pages.toList()
        val ctx = getApplication<Application>()
        val engine = c.engine
        job = viewModelScope.launch {
            busy = "Creating PDF…"
            progress = 0f
            try {
                val doc = withContext(PdfDispatchers.pdf) { engine.create() }
                try {
                    val builder = PdfBuilder(ctx, engine)
                    val maxSide = if (options.quality == Quality.Original) Int.MAX_VALUE else options.quality.maxSide
                    val jpegQ = options.quality.jpegQuality
                    list.forEachIndexed { i, p ->
                        val (jpeg, w, h) = withContext(Dispatchers.Default) {
                            val b = repo.renderForExport(s, p, maxSide)
                            try { Triple(PdfBuilder.encodeJpeg(b, jpegQ), b.width, b.height) } finally { b.recycle() }
                        }
                        withContext(PdfDispatchers.pdf) { builder.addScanJpeg(doc, i, jpeg, w, h, options) }
                        progress = (i + 1f) / list.size * if (options.ocr) 0.5f else 1f
                    }
                    if (options.ocr) {
                        busy = "Recognising text…"
                        OcrPipeline(engine, c.languages).makeSearchable(doc, list.indices.toList(), ocrLanguages) { d, t ->
                            progress = 0.5f + 0.5f * d / t
                        }
                    }
                    busy = "Saving…"
                    withContext(PdfDispatchers.pdf) {
                        val f = SafeSave.writeVerified(ctx, engine, doc)
                        try { SafeSave.copyTo(ctx, f, dest) } finally { f.delete() }
                    }
                } finally {
                    withContext(PdfDispatchers.pdf) { engine.close(doc) }
                }
                withContext(Dispatchers.IO) { repo.delete(s.id) }
                onDone(dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = "Cancelled"
            } catch (e: Exception) {
                message = e.message ?: "The PDF could not be created."
            } finally {
                busy = null
                progress = null
            }
        }
    }

    fun cancel() { job?.cancel() }

    fun discard(onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(sessionId) }
            onDone()
        }
    }

    companion object {
        fun factory(app: Application, id: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ScanReviewViewModel(app, id) as T
        }
    }
}

/** Review screen (plan 6.7): page grid, reorder, delete, retake, re-crop, re-filter, add pages, export. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanReviewScreen(nav: NavController, sessionId: String, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: ScanReviewViewModel = viewModel(key = sessionId, factory = ScanReviewViewModel.factory(context.applicationContext as Application, sessionId))
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ScanPage?>(null) }
    var exportOpen by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var pendingOptions by remember { mutableStateOf<ExportOptions?>(null) }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    // Coming back from the camera (retake / add) refreshes the session.
    LaunchedEffect(Unit) { vm.reload() }
    BackHandler { onBack() }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { dest ->
        val o = pendingOptions
        if (dest != null && o != null) vm.export(dest, o) { nav.navigate(Routes.pdf(it, picker = true)) { popUpTo(Routes.HOME) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan · ${vm.pages.size} page(s)") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.scan(sessionId)) }) { Icon(Icons.Default.AddAPhoto, "Add pages") }
                    IconButton(onClick = { confirmDiscard = true }) { Icon(Icons.Default.Delete, "Discard scan") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                vm.busy?.let { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b, Modifier.weight(1f)); TextButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                    vm.progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
                }
                Button(onClick = { exportOpen = true }, enabled = vm.pages.isNotEmpty() && vm.busy == null, modifier = Modifier.fillMaxWidth()) {
                    Text("Save as PDF")
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(GridCells.Adaptive(120.dp), Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(vm.pages, key = { _, p -> p.id }) { i, p ->
                LaunchedEffect(vm.previewKey(p)) { vm.preview(p) }
                Column(Modifier.padding(8.dp).clickable(onClickLabel = "Edit page ${i + 1}") { editing = p }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.71f).background(Color.LightGray), contentAlignment = Alignment.Center) {
                        val img = vm.previews[vm.previewKey(p)]
                        if (img != null) Image(BitmapPainter(img), "Page ${i + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        else CircularProgressIndicator(Modifier.width(24.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.move(i, i - 1) }, enabled = i > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Move earlier") }
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { vm.move(i, i + 1) }, enabled = i < vm.pages.size - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Move later") }
                    }
                }
            }
        }
    }

    editing?.let { p ->
        PageEditor(
            vm, p,
            onDone = { vm.update(it); editing = null },
            onDelete = { vm.delete(p); editing = null },
            onRetake = { editing = null; nav.navigate(Routes.scan(sessionId, replace = p.id)) },
            onFilterAll = { vm.applyFilterToAll(it) },
            onCancel = { editing = null },
        )
    }
    if (exportOpen) {
        ExportDialog(
            title = "Save scan as PDF",
            defaultName = "Scan " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.getDefault()).format(java.util.Date()) + ".pdf",
            ocrLanguages = vm.ocrLanguages,
            showMargin = false,
            onExport = { name, o -> exportOpen = false; pendingOptions = o; create.launch(name) },
            onDismiss = { exportOpen = false },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this scan?") },
            text = { Text("All scanned pages in this session will be deleted.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discard(onBack) }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun PageEditor(
    vm: ScanReviewViewModel,
    page: ScanPage,
    onDone: (ScanPage) -> Unit,
    onDelete: () -> Unit,
    onRetake: () -> Unit,
    onFilterAll: (ScanFilter) -> Unit,
    onCancel: () -> Unit,
) {
    var p by remember(page.id) { mutableStateOf(page) }
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(page.id) { original = vm.original(page) }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                        FilterChip(tab == 0, { tab = 0 }, { Text("Crop") }, Modifier.padding(4.dp))
                        FilterChip(tab == 1, { tab = 1 }, { Text("Adjust") }, Modifier.padding(4.dp))
                    }
                    Button(onClick = { onDone(p) }) { Text("Done") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    val o = original
                    when {
                        o == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        tab == 0 -> {
                            val img = remember(o) { o.asImageBitmap() }
                            CornerEditor(img, p.quad, onChange = { p = p.copy(quad = it) }, modifier = Modifier.padding(16.dp))
                        }
                        else -> FilteredPreview(vm, p)
                    }
                }
                if (tab == 0) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        OutlinedButton(onClick = {
                            scope.launch { original?.let { b -> vm.autoDetect(b)?.let { p = p.copy(quad = it) } ?: run { vm.message = "No page edges found" } } }
                        }) { Text("Auto") }
                        OutlinedButton(onClick = { p = p.copy(quad = Quad.full(0.0)) }) { Text("Full page") }
                        IconButton(onClick = onRetake) { Icon(Icons.Default.CameraAlt, "Retake") }
                        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete page") }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            ScanFilter.entries.forEach { f ->
                                FilterChip(p.filter == f, { p = p.copy(filter = f) }, { Text(filterLabel(f)) }, Modifier.padding(end = 6.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { p = p.copy(rotation = (p.rotation + 3) % 4) }) { Icon(Icons.AutoMirrored.Filled.RotateLeft, "Rotate left") }
                            IconButton(onClick = { p = p.copy(rotation = (p.rotation + 1) % 4) }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate right") }
                            TextButton(onClick = { onFilterAll(p.filter) }) { Text("Apply filter to all pages") }
                        }
                        Text("Brightness", style = MaterialTheme.typography.labelMedium)
                        Slider(p.brightness.toFloat(), { p = p.copy(brightness = it.toInt()) }, valueRange = -60f..60f)
                        Text("Contrast", style = MaterialTheme.typography.labelMedium)
                        Slider(p.contrast, { p = p.copy(contrast = it) }, valueRange = 0.6f..1.8f)
                    }
                }
            }
        }
    }
}

private fun filterLabel(f: ScanFilter) = when (f) {
    ScanFilter.Original -> "Original"
    ScanFilter.Document -> "Document"
    ScanFilter.BlackWhite -> "Black & white"
    ScanFilter.Grayscale -> "Grayscale"
    ScanFilter.Color -> "Colour"
}

/** Preview of the current settings on a downscaled copy (plan 6.6). */
@Composable
private fun FilteredPreview(vm: ScanReviewViewModel, p: ScanPage) {
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    val repo = androidx.compose.ui.platform.LocalContext.current.container.scans
    LaunchedEffect(p) {
        kotlinx.coroutines.delay(120)  // debounce slider drags
        val s = vm.session ?: return@LaunchedEffect
        img = withContext(Dispatchers.Default) { runCatching { repo.render(s, p, 1200, 2_000_000).asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        img?.let { Image(BitmapPainter(it), "Filtered page", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp)) }
            ?: CircularProgressIndicator()
    }
}
