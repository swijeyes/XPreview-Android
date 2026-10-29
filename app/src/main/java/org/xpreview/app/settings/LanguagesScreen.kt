// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xpreview.app.container
import org.xpreview.app.data.AppSettings
import org.xpreview.common.UriUtils
import org.xpreview.ocr.OcrLanguage

/**
 * Language manager (plan 8.2). This build has no INTERNET permission, so
 * extra languages are imported as .traineddata files (e.g. from
 * github.com/tesseract-ocr/tessdata_fast) through the file picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by c.settings.flow.collectAsState(initial = AppSettings())
    var installed by remember { mutableStateOf<List<OcrLanguage>>(emptyList()) }
    suspend fun refresh() { installed = withContext(Dispatchers.IO) { c.languages.installed() } }
    LaunchedEffect(Unit) { refresh() }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = UriUtils.meta(context, uri).name
            val msg = withContext(Dispatchers.IO) {
                runCatching { c.languages.import(uri, name) }.fold(
                    { (lang, sha) -> "Installed ${lang.code}. SHA-256 ${sha.take(16)}… — compare with the published checksum." },
                    { it.message ?: "Import failed" },
                )
            }
            refresh()
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("OCR languages") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { import.launch(arrayOf("*/*")) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Import .traineddata") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Tick the languages to recognise (more languages are slower). English is built in; other models can be imported from tessdata_fast or tessdata_best.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            )
            LazyColumn {
                items(installed, key = { it.code }) { lang ->
                    val on = lang.code in settings.ocrLanguages
                    ListItem(
                        headlineContent = { Text(lang.code) },
                        supportingContent = { Text("%.1f MB".format(lang.sizeBytes / 1e6) + if (lang.bundled) " · built in" else "") },
                        leadingContent = {
                            Checkbox(on, { v ->
                                scope.launch {
                                    c.settings.update { s ->
                                        val l = if (v) s.ocrLanguages + lang.code else s.ocrLanguages - lang.code
                                        s.copy(ocrLanguages = l.distinct().ifEmpty { listOf("eng") })
                                    }
                                }
                            })
                        },
                        trailingContent = {
                            if (!lang.bundled) IconButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { c.languages.remove(lang.code) }
                                    c.settings.update { s -> s.copy(ocrLanguages = (s.ocrLanguages - lang.code).ifEmpty { listOf("eng") }) }
                                    refresh()
                                }
                            }) { Icon(Icons.Default.Delete, "Remove ${lang.code}") }
                        },
                    )
                }
            }
        }
    }
}
