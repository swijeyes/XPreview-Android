// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xpreview.app.Routes
import org.xpreview.app.container
import org.xpreview.app.data.AppSettings
import org.xpreview.vision.ScanFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val s by c.settings.flow.collectAsState(initial = AppSettings())
    var confirmClear by remember { mutableStateOf(false) }
    fun update(f: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(f) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Viewing")
            Toggle("Dark pages", "Invert page colours for reading at night", s.darkPages) { v -> update { it.copy(darkPages = v) } }
            Toggle("Highlight form fields", "Tint fillable fields in PDF forms", s.highlightFields) { v -> update { it.copy(highlightFields = v) } }

            Section("Annotations")
            var author by remember(s.authorName) { mutableStateOf(s.authorName) }
            OutlinedTextField(
                author, { author = it; update { st -> st.copy(authorName = it.take(80)) } }, singleLine = true,
                label = { Text("Author name") }, supportingText = { Text("Stored in annotations you create") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Section("Scanner")
            Toggle("Auto-capture", "Capture when the page is steady and sharp", s.autoCapture) { v -> update { it.copy(autoCapture = v) } }
            Text("Default filter", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                ScanFilter.entries.forEach { f ->
                    FilterChip(s.defaultFilter == f, { update { it.copy(defaultFilter = f) } }, { Text(f.name) }, Modifier.padding(end = 6.dp))
                }
            }

            Section("Text recognition (OCR)")
            ListItem(
                headlineContent = { Text("Languages") },
                supportingContent = { Text(s.ocrLanguages.joinToString(" + ")) },
                modifier = Modifier.clickable { nav.navigate(Routes.LANGUAGES) },
            )

            Section("Privacy")
            Text(
                "XPreview+ has no network access, analytics or accounts. Files are processed on this device only.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
            )
            ListItem(
                headlineContent = { Text("Clear app data") },
                supportingContent = { Text("Recent files, unfinished scans, caches and the saved signature") },
                modifier = Modifier.clickable { confirmClear = true },
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear app data?") },
            text = { Text("This removes recent files, unfinished scans, temporary files and your saved signature. Your documents are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        c.recents.clear()
                        withContext(Dispatchers.IO) {
                            context.filesDir.resolve("scans").deleteRecursively()
                            context.filesDir.resolve("signature.png").delete()
                            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        }
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(value, onChange) },
        modifier = Modifier.clickable { onChange(!value) },
    )
}
