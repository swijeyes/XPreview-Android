// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.viewer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import org.xpreview.app.io.PageRanges

/** Page organizer (plan 9.1): thumbnail grid with multi-select and page actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizerScreen(vm: ViewerViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val selected = remember { mutableStateListOf<Int>() }
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<String?>(null) }
    var splitParts by remember { mutableStateOf<List<List<Int>>>(emptyList()) }
    val count = vm.pageSizes.size
    LaunchedEffect(count) { selected.removeAll { it >= count } }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    val insertPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) vm.insertFromPdf(u, (selected.maxOrNull() ?: (count - 1)) + 1)
    }
    val extract = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        if (u != null) {
            val pages = selected.sorted()
            vm.runBusy("Extracting…") { vm.writePages(pages, u); vm.message = "Extracted ${pages.size} page(s)" }
        }
    }
    val splitTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree == null) return@rememberLauncherForActivityResult
        val parts = splitParts
        vm.runBusy("Splitting…") {
            val dir = DocumentFile.fromTreeUri(context, tree) ?: error("That folder cannot be written.")
            val base = vm.name.removeSuffix(".pdf")
            parts.forEachIndexed { i, pages ->
                val f = dir.createFile("application/pdf", "$base-${i + 1}.pdf") ?: error("Could not create a file in that folder.")
                vm.writePages(pages, f.uri)
            }
            vm.message = "Created ${parts.size} PDF(s)"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "Organize pages" else "${selected.size} selected") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to document") } },
                actions = {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(onClick = {
                        if (selected.size == count) selected.clear() else { selected.clear(); selected.addAll(0 until count) }
                    }) { Icon(Icons.Default.SelectAll, "Select all") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    if (vm.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp)) {
                        val any = selected.isNotEmpty()
                        IconButton(onClick = { vm.rotatePages(selected.toList(), -1) }, enabled = any) { Icon(Icons.AutoMirrored.Filled.RotateLeft, "Rotate left") }
                        IconButton(onClick = { vm.rotatePages(selected.toList(), 1) }, enabled = any) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate right") }
                        IconButton(onClick = { vm.deletePages(selected.toList()); selected.clear() }, enabled = any) { Icon(Icons.Default.Delete, "Delete") }
                        IconButton(onClick = { vm.duplicatePages(selected.toList()); selected.clear() }, enabled = any) { Icon(Icons.Default.ContentCopy, "Duplicate") }
                        IconButton(onClick = { dialog = "crop" }, enabled = any) { Icon(Icons.Default.Crop, "Crop") }
                        AssistChip(onClick = { dialog = "move" }, label = { Text("Move") }, enabled = any, modifier = Modifier.padding(horizontal = 4.dp))
                        AssistChip(onClick = { vm.insertBlank(selected.maxOrNull() ?: (count - 1)) }, label = { Text("Blank page") }, modifier = Modifier.padding(horizontal = 4.dp))
                        AssistChip(onClick = { insertPdf.launch(arrayOf("application/pdf")) }, label = { Text("Insert PDF") }, modifier = Modifier.padding(horizontal = 4.dp))
                        AssistChip(onClick = { extract.launch(vm.name.removeSuffix(".pdf") + " (extract).pdf") }, label = { Text("Extract") }, enabled = any, modifier = Modifier.padding(horizontal = 4.dp))
                        AssistChip(onClick = { dialog = "split" }, label = { Text("Split") }, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    Text(
                        "$count pages" + (if (vm.dirty) " · unsaved changes" else "") + " · Long-press or tap to select",
                        Modifier.padding(start = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    ) { padding ->
        val widthPx = with(LocalDensity.current) { 140.dp.roundToPx() }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(vm.pageSizes, key = { i, _ -> "$i-${vm.docVersion}" }) { i, ps ->
                val isSel = i in selected
                Column(
                    Modifier.padding(8.dp).combinedClickable(
                        onClickLabel = if (isSel) "Deselect page ${i + 1}" else "Select page ${i + 1}",
                        onClick = { if (isSel) selected.remove(i) else selected.add(i) },
                        onLongClick = { if (!isSel) selected.add(i) },
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        PageThumb(vm, i, widthPx, Modifier.fillMaxWidth().aspectRatio(ps.width / ps.height), selected = isSel)
                        if (isSel) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(4.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    when (dialog) {
        "move" -> MoveDialog(count, selected.size, onMove = { dest -> vm.movePages(selected.toList(), dest); selected.clear(); dialog = null }) { dialog = null }
        "crop" -> CropDialog(onCrop = { l, t, r, b -> vm.cropPages(selected.toList(), l, t, r, b); dialog = null }) { dialog = null }
        "split" -> SplitDialog(count, onSplit = { parts -> splitParts = parts; dialog = null; splitTree.launch(null) }) { dialog = null }
    }
}

@Composable
private fun MoveDialog(count: Int, selectedCount: Int, onMove: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("1") }
    val n = text.toIntOrNull()
    val max = count - selectedCount + 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move pages") },
        text = {
            Column {
                Text("New position of the first selected page (1 – $max):")
                OutlinedTextField(text, { text = it.filter(Char::isDigit).take(6) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onMove(n!! - 1) }, enabled = n != null && n in 1..max) { Text("Move") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CropDialog(onCrop: (Float, Float, Float, Float) -> Unit, onDismiss: () -> Unit) {
    val m = remember { mutableStateListOf(0f, 0f, 0f, 0f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop margins") },
        text = {
            Column {
                listOf("Left", "Top", "Right", "Bottom").forEachIndexed { i, label ->
                    Text("$label: ${(m[i] * 100).toInt()}%")
                    Slider(m[i], { m[i] = it }, valueRange = 0f..0.4f)
                }
                Text("Cropping sets the page's crop box; content outside it is hidden, not deleted.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onCrop(m[0], m[1], m[2], m[3]) }) { Text("Crop") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SplitDialog(count: Int, onSplit: (List<List<Int>>) -> Unit, onDismiss: () -> Unit) {
    var byRanges by remember { mutableStateOf(false) }
    var every by remember { mutableFloatStateOf(1f) }
    var ranges by remember { mutableStateOf("") }
    val parts = if (byRanges) PageRanges.parseGroups(ranges, count) else PageRanges.everyN(every.toInt(), count)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split into several PDFs") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!byRanges, { byRanges = false }); Text("Every ${every.toInt()} page(s)")
                }
                if (!byRanges) Slider(every, { every = it }, valueRange = 1f..maxOf(1f, count.toFloat()), steps = maxOf(0, count - 2))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(byRanges, { byRanges = true }); Text("Page ranges")
                }
                if (byRanges) OutlinedTextField(ranges, { ranges = it }, singleLine = true, placeholder = { Text("e.g. 1-3, 4-6, 7-") })
                Text(
                    parts?.let { "Creates ${it.size} file(s). Bookmarks are not carried into the parts." } ?: "Invalid page ranges",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSplit(parts!!) }, enabled = !parts.isNullOrEmpty()) { Text("Choose folder…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
