// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.viewer

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.xpreview.app.viewer.dialogs.AnnotationPopupDialog
import org.xpreview.app.viewer.dialogs.FormFieldDialog
import org.xpreview.app.viewer.dialogs.GoToPageDialog
import org.xpreview.app.viewer.dialogs.InfoDialog
import org.xpreview.app.viewer.dialogs.PasswordDialog
import org.xpreview.app.viewer.dialogs.PlacementDialog
import org.xpreview.app.viewer.dialogs.UnsavedDialog
import org.xpreview.pdf.AnnotType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(uri: Uri, fromPicker: Boolean, onBack: () -> Unit, onOpenInNewWindow: (Uri) -> Unit) {
    val context = LocalContext.current
    val vm: ViewerViewModel = viewModel(
        key = uri.toString(),
        factory = ViewerViewModel.factory(context.applicationContext as Application, uri, fromPicker),
    )
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val wide = LocalConfiguration.current.screenWidthDp >= 840

    var searchOpen by remember { mutableStateOf(false) }
    var sidebarOpen by remember { mutableStateOf(wide) }
    var annotating by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    var askUnsaved by remember { mutableStateOf<(() -> Unit)?>(null) }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { dest ->
        if (dest != null) vm.saveAs(dest) { askUnsaved?.invoke(); askUnsaved = null }
    }
    val saveFlat = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { dest ->
        if (dest != null) vm.saveFlattened(dest)
    }

    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }

    fun leave() {
        if (vm.dirty) askUnsaved = onBack else onBack()
    }
    BackHandler(enabled = organizing) { organizing = false }
    BackHandler(enabled = !organizing && (vm.dirty || vm.tool != org.xpreview.app.viewer.Tool.None || searchOpen)) {
        when {
            searchOpen -> { searchOpen = false; vm.clearSearch() }
            vm.tool != org.xpreview.app.viewer.Tool.None -> vm.tool = org.xpreview.app.viewer.Tool.None
            else -> leave()
        }
    }

    if (organizing && vm.loadState == LoadState.Ready) {
        OrganizerScreen(vm, onClose = { organizing = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (if (vm.dirty) "• " else "") + vm.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (vm.loadState == LoadState.Ready) {
                        if (annotating) {
                            IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                            IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                        }
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) vm.clearSearch() }) {
                            Icon(Icons.Default.Search, "Search")
                        }
                        IconButton(onClick = {
                            annotating = !annotating
                            if (!annotating) vm.tool = org.xpreview.app.viewer.Tool.None
                        }) { Icon(if (annotating) Icons.Default.EditNote else Icons.Default.Draw, "Annotate") }
                        IconButton(onClick = { sidebarOpen = !sidebarOpen }) { Icon(Icons.Default.ViewSidebar, "Pages and contents") }
                        OverflowMenu(
                            vm,
                            onOrganize = { organizing = true },
                            onInfo = { showInfo = true },
                            onGoTo = { showGoTo = true },
                            onSaveAs = { saveAs.launch(vm.name.ensurePdf()) },
                            onSaveFlat = { saveFlat.launch(vm.name.removeSuffix(".pdf") + " (flattened).pdf") },
                            onShare = { scope.launch { share(context, vm) } },
                            onPrint = { scope.launch { print(context, vm) } },
                            onNewWindow = { onOpenInNewWindow(vm.uri) },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searchOpen && vm.loadState == LoadState.Ready) SearchBar(vm) { searchOpen = false; vm.clearSearch() }
            vm.busy?.let { b ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (b.job != null) TextButton(onClick = vm::cancelBusy) { Text("Cancel") }
                    }
                    if (b.progress != null) LinearProgressIndicator({ b.progress }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            vm.notices.forEach { n ->
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(n, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.weight(1f)) {
                if (wide && sidebarOpen && vm.loadState == LoadState.Ready) {
                    Sidebar(vm, Modifier.width(220.dp).fillMaxHeight()) { /* stays open on tablets */ }
                }
                Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    when (val s = vm.loadState) {
                        LoadState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                        is LoadState.Error -> ErrorPane(s.message, Modifier.align(Alignment.Center))
                        is LoadState.Password -> PasswordDialog(s.wrong, onSubmit = vm::open, onCancel = onBack)
                        LoadState.Ready -> DocumentPane(vm, onShowGoTo = { showGoTo = true }, openSearch = { searchOpen = true })
                    }
                }
            }
            if (vm.selection != null) SelectionBar(vm, context)
            if (annotating && vm.loadState == LoadState.Ready) AnnotationToolbar(vm)
        }
    }

    if (!wide && sidebarOpen && vm.loadState == LoadState.Ready) {
        ModalBottomSheet(onDismissRequest = { sidebarOpen = false }) {
            Sidebar(vm, Modifier.fillMaxWidth().fillMaxHeight(0.7f)) { sidebarOpen = false }
        }
    }
    if (showInfo) InfoDialog(vm) { showInfo = false }
    if (showGoTo) GoToPageDialog(vm.pageSizes.size, onGo = { vm.goToPage(it); showGoTo = false }) { showGoTo = false }
    vm.formEdit?.let { FormFieldDialog(vm, it) }
    vm.annotPopup?.let { AnnotationPopupDialog(vm, it) }
    vm.placement?.let { PlacementDialog(vm, it) }
    vm.externalLink?.let { link ->
        AlertDialog(
            onDismissRequest = { vm.externalLink = null },
            title = { Text("Open link?") },
            text = { Text(link) },
            confirmButton = {
                TextButton(onClick = {
                    vm.externalLink = null
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { vm.externalLink = null }) { Text("Cancel") } },
        )
    }
    askUnsaved?.let { then ->
        UnsavedDialog(
            canSaveInPlace = vm.canSaveInPlace,
            onSave = { vm.save { askUnsaved = null; then() } },
            onSaveCopy = { saveAs.launch(vm.name.ensurePdf()) },
            onDiscard = { askUnsaved = null; vm.discardChanges(); then() },
            onCancel = { askUnsaved = null },
        )
    }
}

private fun String.ensurePdf() = if (endsWith(".pdf", ignoreCase = true)) this else "$this.pdf"

@Composable
private fun DocumentPane(vm: ViewerViewModel, onShowGoTo: () -> Unit, openSearch: () -> Unit) {
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e -> handleKey(vm, e, openSearch) },
    ) {
        val vp = vm.viewport
        val width = vp.width
        val layout = remember(vm.pageSizes, vm.viewRotation, width) {
            if (width <= 0f) null else with(density) {
                DocLayout(vm.pageSizes, vm.viewRotation, width, gap = 8.dp.toPx(), margin = 4.dp.toPx())
            }
        }
        LaunchedEffect(layout) {
            vm.layout = layout
            if (layout != null) {
                vp.maxZoom = (12f / layout.basePxPerPt).coerceAtLeast(2f)
                vm.pendingAnchor?.let { vp.restore(layout, it); vm.pendingAnchor = null } ?: vp.clamp(layout)
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
        if (layout == null) {
            // First pass only measures the viewport.
            Box(Modifier.fillMaxSize().onSizeChangedToViewport(vp))
        } else {
            val visible = vp.visiblePages(layout)
            LaunchedEffect(visible, vm.searchResults, vm.docVersion) { vm.ensureTransforms(visible) }
            PdfView(
                layout = layout,
                viewport = vp,
                renderer = vm.renderer,
                docVersion = vm.docVersion,
                renderFlags = vm.renderFlags,
                darkPages = vm.settings.darkPages,
                inputMode = vm.inputMode,
                callbacks = remember(vm) {
                    object : PdfViewCallbacks {
                        override fun onTap(hit: PageHit?, screen: Offset) = vm.onTap(hit)
                        override fun onLongPress(hit: PageHit) = vm.onLongPress(hit)
                        override fun onSelectDrag(hit: PageHit) = vm.onSelectDrag(hit)
                        override fun onDrawStart(hit: PageHit, screen: Offset) = vm.onDrawStart(hit, screen)
                        override fun onDrawMove(screen: Offset) = vm.onDrawMove(screen)
                        override fun onDrawEnd() = vm.onDrawEnd()
                        override fun onDrawCancel() = vm.onDrawCancel()
                    }
                },
                modifier = Modifier.semantics { contentDescription = "Document pages" },
            ) { page, rect -> drawOverlays(vm, page, rect) }
            // Live stroke / shape preview in screen space.
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().clipToBounds()) { drawLiveTool(vm, layout) }
            PageIndicator(vm, layout, Modifier.align(Alignment.BottomStart).padding(12.dp), onShowGoTo)
        }
    }
}

private fun Modifier.onSizeChangedToViewport(vp: Viewport) = onSizeChanged {
    vp.width = it.width.toFloat()
    vp.height = it.height.toFloat()
}

private fun handleKey(vm: ViewerViewModel, e: androidx.compose.ui.input.key.KeyEvent, openSearch: () -> Unit): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val l = vm.layout ?: return false
    val vp = vm.viewport
    when {
        e.isCtrlPressed && e.key == Key.F -> openSearch()
        e.isCtrlPressed && (e.key == Key.Equals || e.key == Key.Plus || e.key == Key.NumPadAdd) ->
            vp.zoomBy(1.25f, Offset(vp.width / 2, vp.height / 2), l)
        e.isCtrlPressed && (e.key == Key.Minus || e.key == Key.NumPadSubtract) ->
            vp.zoomBy(0.8f, Offset(vp.width / 2, vp.height / 2), l)
        e.isCtrlPressed && e.key == Key.Zero -> vp.fitWidth(l)
        e.isCtrlPressed && e.key == Key.Nine -> vp.fitPage(l)
        e.isCtrlPressed && e.key == Key.Z -> vm.undo()
        e.isCtrlPressed && e.key == Key.Y -> vm.redo()
        e.key == Key.DirectionDown -> vp.panBy(Offset(0f, -80f), l)
        e.key == Key.DirectionUp -> vp.panBy(Offset(0f, 80f), l)
        e.key == Key.DirectionLeft -> vp.panBy(Offset(80f, 0f), l)
        e.key == Key.DirectionRight -> vp.panBy(Offset(-80f, 0f), l)
        e.key == Key.PageDown || e.key == Key.Spacebar -> vp.panBy(Offset(0f, -vp.height * 0.9f), l)
        e.key == Key.PageUp -> vp.panBy(Offset(0f, vp.height * 0.9f), l)
        e.key == Key.MoveHome -> vm.goToPage(0)
        e.key == Key.MoveEnd -> vm.goToPage(vm.pageSizes.size - 1)
        else -> return false
    }
    return true
}

private val SearchColor = Color(0x66FFEB3B)
private val CurrentColor = Color(0x88FF9800)
private val SelectColor = Color(0x553D7BFD)

private fun DrawScope.drawOverlays(vm: ViewerViewModel, page: Int, r: Rect) {
    val t = vm.cachedTransform(page) ?: return
    fun toScreen(pr: android.graphics.RectF): Rect {
        val n = t.toNormalized(pr)
        return Rect(r.left + n.left * r.width, r.top + n.top * r.height, r.left + n.right * r.width, r.top + n.bottom * r.height)
    }
    val current = vm.searchResults.getOrNull(vm.searchIndex)
    for (m in vm.searchResults) {
        if (m.page != page) continue
        val c = if (m === current) CurrentColor else SearchColor
        for (rect in m.rects) toScreen(rect).let { drawRect(c, it.topLeft, it.size) }
    }
    val sel = vm.selection
    if (sel != null && sel.page == page) {
        for (rect in vm.selectionRects) toScreen(rect).let { drawRect(SelectColor, it.topLeft, it.size) }
    }
}

private fun DrawScope.drawLiveTool(vm: ViewerViewModel, layout: DocLayout) {
    val scale = layout.basePxPerPt * vm.viewport.zoom
    val color = Color(vm.style.argb)
    val pts = vm.stroke
    if (pts.size > 1) {
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        drawPath(path, color, style = Stroke(vm.style.widthPt * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    val a = vm.shapeStart
    val b = vm.shapeEnd
    if (a != null && b != null) {
        val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
        val size = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
        val st = Stroke(vm.style.widthPt * scale)
        if (vm.tool == org.xpreview.app.viewer.Tool.Ellipse) drawOval(color, tl, size, style = st)
        else drawRect(color, tl, size, style = st)
    }
}

@Composable
private fun PageIndicator(vm: ViewerViewModel, layout: DocLayout, modifier: Modifier, onClick: () -> Unit) {
    val page = vm.viewport.currentPage(layout) + 1
    Surface(
        modifier = modifier.clickable(onClickLabel = "Go to page", onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text("$page / ${vm.pageSizes.size}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ErrorPane(message: String, modifier: Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Cannot open this file", style = MaterialTheme.typography.titleLarge)
        Text(message, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SearchBar(vm: ViewerViewModel, onClose: () -> Unit) {
    var text by remember { mutableStateOf(vm.searchQuery) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text("Find in document") },
            modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                    if (text == vm.searchQuery) vm.nextResult(1) else vm.search(text); true
                } else false
            },
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                if (text == vm.searchQuery) vm.nextResult(1) else vm.search(text)
            }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            supportingText = {
                val n = vm.searchResults.size
                Text(
                    when {
                        vm.searching && n == 0 -> "Searching…"
                        n > 0 -> "${vm.searchIndex + 1} of $n" + if (vm.searching) "+" else ""
                        else -> ""
                    },
                )
            },
        )
        IconButton(onClick = { vm.nextResult(-1) }, enabled = vm.searchResults.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowUp, "Previous match") }
        IconButton(onClick = { if (text != vm.searchQuery) vm.search(text) else vm.nextResult(1) }) { Icon(Icons.Default.KeyboardArrowDown, "Next match") }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close search") }
    }
}

@Composable
private fun SelectionBar(vm: ViewerViewModel, context: Context) {
    val scope = rememberCoroutineScope()
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = {
                scope.launch {
                    val text = vm.selectedText()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("PDF text", text))
                    vm.clearSelection()
                    vm.message = "Copied"
                }
            }) { Icon(Icons.Default.ContentCopy, null); Text(" Copy") }
            IconButton(onClick = { vm.markupSelection(AnnotType.Highlight, 0x80FFEB3B.toInt()) }) { Icon(Icons.Default.Highlight, "Highlight") }
            IconButton(onClick = { vm.markupSelection(AnnotType.Underline, 0xFF1565C0.toInt()) }) { Icon(Icons.Default.FormatUnderlined, "Underline") }
            IconButton(onClick = { vm.markupSelection(AnnotType.StrikeOut, 0xFFD32F2F.toInt()) }) { Icon(Icons.Default.FormatStrikethrough, "Strikethrough") }
            IconButton(onClick = vm::clearSelection) { Icon(Icons.Default.Close, "Clear selection") }
        }
    }
}

@Composable
private fun OverflowMenu(
    vm: ViewerViewModel,
    onOrganize: () -> Unit,
    onInfo: () -> Unit,
    onGoTo: () -> Unit,
    onSaveAs: () -> Unit,
    onSaveFlat: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onNewWindow: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val l = vm.layout
            DropdownMenuItem({ Text("Fit width") }, onClick = { open = false; l?.let { vm.viewport.fitWidth(it) } })
            DropdownMenuItem({ Text("Fit page") }, onClick = { open = false; l?.let { vm.viewport.fitPage(it) } })
            DropdownMenuItem({ Text("Rotate left") }, onClick = { open = false; vm.rotateView(-1) })
            DropdownMenuItem({ Text("Rotate right") }, onClick = { open = false; vm.rotateView(1) })
            DropdownMenuItem({ Text("Go to page…") }, onClick = { open = false; onGoTo() })
            HorizontalDivider()
            DropdownMenuItem({ Text("Organize pages") }, onClick = { open = false; onOrganize() })
            DropdownMenuItem({ Text("Make searchable (OCR)") }, onClick = { open = false; vm.makeSearchable() })
            DropdownMenuItem({ Text("Document info") }, onClick = { open = false; onInfo() })
            HorizontalDivider()
            DropdownMenuItem({ Text("Save") }, enabled = vm.dirty, onClick = {
                open = false
                if (vm.canSaveInPlace) vm.save() else onSaveAs()
            })
            DropdownMenuItem({ Text("Save a copy…") }, onClick = { open = false; onSaveAs() })
            DropdownMenuItem({ Text("Save flattened copy…") }, onClick = { open = false; onSaveFlat() })
            DropdownMenuItem({ Text("Share") }, onClick = { open = false; onShare() })
            DropdownMenuItem({ Text("Print") }, onClick = { open = false; onPrint() })
            DropdownMenuItem({ Text("Open in new window") }, onClick = { open = false; onNewWindow() })
        }
    }
}

private suspend fun share(context: Context, vm: ViewerViewModel) {
    val f = runCatching { vm.exportFile() }.getOrElse { vm.message = it.message; return }
    val u = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, u)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share PDF"))
}

private suspend fun print(context: Context, vm: ViewerViewModel) {
    val f = runCatching { vm.exportFile() }.getOrElse { vm.message = it.message; return }
    PdfPrint.print(context, f, vm.name)
}
