// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.merge

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xpreview.app.Routes
import org.xpreview.app.container
import org.xpreview.app.io.SafeSave
import org.xpreview.app.viewer.dialogs.PasswordDialog
import org.xpreview.common.FormatSniffer
import org.xpreview.common.SniffedFormat
import org.xpreview.common.UriUtils
import org.xpreview.pdf.OpenResult
import org.xpreview.pdf.PdfDispatchers

data class MergeItem(val uri: Uri, val name: String, var password: String? = null)

class MergeViewModel(app: Application) : AndroidViewModel(app) {
    val items = mutableStateListOf<MergeItem>()
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var needPassword by mutableStateOf<Pair<Int, Boolean>?>(null)
    private var pendingDest: Uri? = null
    private var onDoneCb: ((Uri) -> Unit)? = null

    fun add(uris: List<Uri>) {
        val ctx = getApplication<Application>()
        for (u in uris) {
            if (FormatSniffer.sniff(UriUtils.readHead(ctx, u)) != SniffedFormat.Pdf) {
                message = "${UriUtils.meta(ctx, u).name} is not a PDF"
                continue
            }
            items += MergeItem(u, UriUtils.meta(ctx, u).name)
        }
    }

    fun move(from: Int, to: Int) { if (to in items.indices) items.add(to, items.removeAt(from)) }

    fun providePassword(index: Int, pw: String) {
        items[index] = items[index].copy(password = pw)
        needPassword = null
        pendingDest?.let { merge(it, onDoneCb ?: {}) }
    }

    /**
     * Merges in list order. Encrypted inputs need their password; the output
     * is unencrypted (PDFium cannot write encryption) and outlines are dropped.
     */
    fun merge(dest: Uri, onDone: (Uri) -> Unit) {
        pendingDest = dest
        onDoneCb = onDone
        val ctx = getApplication<Application>()
        val engine = ctx.container.engine
        val list = items.toList()
        viewModelScope.launch {
            busy = true
            try {
                withContext(PdfDispatchers.pdf) {
                    val out = engine.create()
                    try {
                        for ((i, item) in list.withIndex()) {
                            val fd = ctx.contentResolver.openFileDescriptor(item.uri, "r") ?: error("Cannot read ${item.name}")
                            fd.use {
                                when (val r = engine.open(it, item.password)) {
                                    is OpenResult.Ok -> try {
                                        check(engine.importPages(out, r.doc, IntArray(0), engine.pageCount(out))) { "Could not copy pages of ${item.name}" }
                                    } finally { engine.close(r.doc) }
                                    OpenResult.NeedsPassword -> { needPassword = i to false; return@withContext }
                                    OpenResult.WrongPassword -> { needPassword = i to true; return@withContext }
                                    else -> error("${item.name} is damaged or unsupported")
                                }
                            }
                        }
                        val f = SafeSave.writeVerified(ctx, engine, out)
                        try { SafeSave.copyTo(ctx, f, dest) } finally { f.delete() }
                    } finally {
                        engine.close(out)
                    }
                }
                if (needPassword == null) { pendingDest = null; onDone(dest) }
            } catch (e: Exception) {
                message = e.message ?: "Merging failed"
            } finally {
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(nav: NavController, onBack: () -> Unit) {
    val vm: MergeViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.add(it) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { dest ->
        if (dest != null) vm.merge(dest) { nav.navigate(Routes.pdf(it, picker = true)) { popUpTo(Routes.MERGE) { inclusive = true } } }
    }
    LaunchedEffect(Unit) { if (vm.items.isEmpty()) pick.launch(arrayOf("application/pdf")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merge PDFs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(vm.items, key = { i, it -> it.uri.toString() + i }) { i, item ->
                    ListItem(
                        headlineContent = { Text("${i + 1}. ${item.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { vm.move(i, i - 1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                                IconButton(onClick = { vm.move(i, i + 1) }, enabled = i < vm.items.size - 1) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                                IconButton(onClick = { vm.items.removeAt(i) }) { Icon(Icons.Default.Close, "Remove") }
                            }
                        },
                    )
                }
            }
            Text(
                "Bookmarks of the inputs are not kept. Password-protected inputs produce an unprotected result.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedButton(onClick = { pick.launch(arrayOf("application/pdf")) }) { Icon(Icons.Default.Add, null); Text(" Add PDFs") }
                Button(
                    onClick = { create.launch("Merged.pdf") }, enabled = vm.items.size >= 2 && !vm.busy,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                ) { Text("Merge ${vm.items.size} files") }
            }
        }
    }
    vm.needPassword?.let { (i, wrong) ->
        PasswordDialog(wrong, onSubmit = { vm.providePassword(i, it) }, onCancel = { vm.needPassword = null })
    }
}
