// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.images

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.xpreview.app.Routes
import org.xpreview.app.common.ExportDialog
import org.xpreview.app.common.ZoomableImage
import org.xpreview.app.io.ExportOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesScreen(nav: NavController, onBack: () -> Unit) {
    val vm: ImagesViewModel = viewModel()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    if (vm.items.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val pager = rememberPagerState { vm.items.size }
    var exportOpen by remember { mutableStateOf(false) }
    var orderOpen by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportOptions?>(null) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { dest ->
        val opts = pendingExport
        if (dest != null && opts != null) vm.exportPdf(dest, opts) { done ->
            nav.navigate(Routes.pdf(done, picker = true)) { popUpTo(Routes.IMAGES) { inclusive = true } }
        }
    }
    val addMore = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.add(it) }

    val current = vm.items.getOrNull(pager.currentPage.coerceAtMost(vm.items.size - 1))
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(current?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (vm.items.size > 1) Text("${pager.currentPage + 1} of ${vm.items.size}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    current?.let { item ->
                        IconButton(onClick = { vm.rotate(item.uri, 1) }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate") }
                    }
                    IconButton(onClick = { exportOpen = true }) { Icon(Icons.Default.PictureAsPdf, "Convert to PDF") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Copy text from image (OCR)") }, onClick = { menu = false; current?.let { vm.recognize(it.uri) } })
                            DropdownMenuItem({ Text("Page order…") }, onClick = { menu = false; orderOpen = true })
                            DropdownMenuItem({ Text("Add images…") }, onClick = { menu = false; addMore.launch(arrayOf("image/*")) })
                            DropdownMenuItem({ Text("Image info") }, onClick = {
                                menu = false
                                (current?.let { vm.states[it.uri] } as? ImageState.Ready)?.let { s ->
                                    vm.message = "${s.format.name} · ${s.width} × ${s.height} px"
                                }
                            })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            vm.busy?.let { b ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(b, Modifier.weight(1f))
                    TextButton(onClick = vm::cancel) { Text("Cancel") }
                }
                vm.progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { vm.items[it].uri.toString() + it }) { i ->
                val item = vm.items[i]
                LaunchedEffect(item.uri) { vm.load(item.uri) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when (val s = vm.states[item.uri]) {
                        is ImageState.Ready -> ZoomableImage(s.image, item.name)
                        is ImageState.Failed -> Text(s.message, Modifier.padding(32.dp))
                        else -> CircularProgressIndicator()
                    }
                }
            }
        }
    }

    if (exportOpen) {
        val base = current?.name?.substringBeforeLast('.') ?: "images"
        ExportDialog(
            title = if (vm.items.size > 1) "Convert ${vm.items.size} images to PDF" else "Convert to PDF",
            defaultName = "$base.pdf",
            ocrLanguages = vm.settings.ocrLanguages,
            onExport = { name, opts -> exportOpen = false; pendingExport = opts; create.launch(name) },
            onDismiss = { exportOpen = false },
        )
    }
    if (orderOpen) {
        AlertDialog(
            onDismissRequest = { orderOpen = false },
            title = { Text("Page order") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(vm.items, key = { i, it -> it.uri.toString() + i }) { i, item ->
                        ListItem(
                            headlineContent = { Text("${i + 1}. ${item.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { vm.move(i, i - 1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                                    IconButton(onClick = { vm.move(i, i + 1) }, enabled = i < vm.items.size - 1) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                                    IconButton(onClick = { vm.remove(i) }, enabled = vm.items.size > 1) { Icon(Icons.Default.Close, "Remove") }
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { orderOpen = false }) { Text("Done") } },
        )
    }
    vm.recognized?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.recognized = null },
            title = { Text("Recognised text") },
            text = { SelectionContainer { Text(text, Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Recognised text", text))
                    vm.recognized = null
                    vm.message = "Copied"
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { vm.recognized = null }) { Text("Close") } },
        )
    }
}
