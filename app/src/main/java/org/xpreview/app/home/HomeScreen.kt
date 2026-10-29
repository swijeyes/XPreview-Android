// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.home

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xpreview.app.R
import org.xpreview.app.Routes
import org.xpreview.app.container
import org.xpreview.app.scan.ScanSessionSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val recents by c.recents.flow.collectAsState(initial = emptyList())
    var sessions by remember { mutableStateOf<List<ScanSessionSummary>>(emptyList()) }
    LaunchedEffect(Unit) { sessions = withContext(Dispatchers.IO) { c.scans.cleanupAndList() } }

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) nav.navigate(Routes.open(uri, picker = true))
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { c.pendingImages = uris; nav.navigate(Routes.IMAGES) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.app_logo), null, Modifier.size(32.dp))
                        Text("  XPreview+")
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = { nav.navigate(Routes.ABOUT) }) { Icon(Icons.Default.Info, "About") }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.padding(padding)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Action(Icons.Default.FolderOpen, "Open", Modifier.weight(1f)) { openDoc.launch(arrayOf("application/pdf", "image/*")) }
                    Action(Icons.Default.DocumentScanner, "Scan", Modifier.weight(1f)) { nav.navigate(Routes.scan()) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Action(Icons.Default.Image, "Images to PDF", Modifier.weight(1f)) { pickImages.launch(arrayOf("image/*")) }
                    Action(Icons.AutoMirrored.Filled.MergeType, "Merge PDFs", Modifier.weight(1f)) { nav.navigate(Routes.MERGE) }
                }
            }
            if (sessions.isNotEmpty()) {
                item { Header("Unfinished scans") }
                items(sessions, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text("Scan · ${s.pages} page(s)") },
                        supportingContent = { Text(DateUtils.getRelativeTimeSpanString(s.modified).toString()) },
                        leadingContent = { Icon(Icons.Default.DocumentScanner, null) },
                        trailingContent = {
                            TextButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { c.scans.delete(s.id) }
                                    sessions = sessions.filter { it.id != s.id }
                                }
                            }) { Text("Discard") }
                        },
                        modifier = Modifier.clickable { nav.navigate(Routes.review(s.id)) },
                    )
                }
            }
            item { Header("Recent") }
            if (recents.isEmpty()) item {
                Text(
                    "Files you open appear here. Everything stays on this device.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(recents, key = { it.uri.toString() }) { r ->
                ListItem(
                    headlineContent = { Text(r.name, maxLines = 1) },
                    supportingContent = { Text(DateUtils.getRelativeTimeSpanString(r.openedAt).toString()) },
                    leadingContent = { Icon(if (r.isPdf) Icons.Default.PictureAsPdf else Icons.Default.Image, null) },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { c.recents.remove(r.uri) } }) { Icon(Icons.Default.Close, "Remove from recents") }
                    },
                    modifier = Modifier.clickable { nav.navigate(Routes.open(r.uri, picker = false)) },
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
}

@Composable
private fun Action(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
        }
    }
}
