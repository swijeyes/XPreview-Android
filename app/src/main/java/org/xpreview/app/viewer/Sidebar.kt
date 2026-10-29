// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.painter.BitmapPainter

/** Thumbnails and table of contents (plan 5.1): side panel on tablets, sheet on phones. */
@Composable
fun Sidebar(vm: ViewerViewModel, modifier: Modifier, onNavigate: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Pages") })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Contents") })
        }
        if (tab == 0) Thumbnails(vm, onNavigate) else Outline(vm, onNavigate)
    }
}

@Composable
private fun Thumbnails(vm: ViewerViewModel, onNavigate: () -> Unit) {
    val widthPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    val current = vm.layout?.let { vm.viewport.currentPage(it) } ?: 0
    val state = rememberLazyListState(initialFirstVisibleItemIndex = current)
    LazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(vm.pageSizes) { i, ps ->
            Column(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Go to page ${i + 1}") { vm.goToPage(i); onNavigate() }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PageThumb(vm, i, widthPx, Modifier.fillMaxWidth(0.7f).aspectRatio(ps.width / ps.height), selected = i == current)
                Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun PageThumb(vm: ViewerViewModel, page: Int, widthPx: Int, modifier: Modifier, selected: Boolean = false) {
    val key = remember(page, widthPx, vm.docVersion, vm.pageSizes) {
        if (page < vm.pageSizes.size) thumbKey(vm.pageSizes, page, widthPx, vm.docVersion) else null
    }
    LaunchedEffect(key) { key?.let { vm.renderer.requestThumb(it) } }
    vm.renderer.version.intValue
    val img = key?.let { vm.renderer.get(it) }
    Box(
        modifier
            .background(Color.White)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.LightGray),
    ) {
        if (img != null) Image(BitmapPainter(img), contentDescription = "Page ${page + 1}", contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize())
    }
}

@Composable
private fun Outline(vm: ViewerViewModel, onNavigate: () -> Unit) {
    if (vm.outline.isEmpty()) {
        Text("This document has no table of contents.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn {
        itemsIndexed(vm.outline) { _, item ->
            Text(
                item.title.ifBlank { "(untitled)" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = if (item.level == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = item.page >= 0) { vm.goToPage(item.page); onNavigate() }
                    .padding(start = (16 + 16 * item.level.coerceAtMost(6)).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            )
        }
    }
}
