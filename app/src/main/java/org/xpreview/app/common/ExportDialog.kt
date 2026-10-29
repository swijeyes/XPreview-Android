// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.xpreview.app.io.ExportOptions
import org.xpreview.app.io.PageSizeOption
import org.xpreview.app.io.Quality

/** Export options (plan 7.2): page size, margin, quality preset, file name, OCR. */
@Composable
fun ExportDialog(
    title: String,
    defaultName: String,
    ocrLanguages: List<String>,
    showMargin: Boolean = true,
    onExport: (name: String, options: ExportOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var size by remember { mutableStateOf(PageSizeOption.Fit) }
    var quality by remember { mutableStateOf(Quality.High) }
    var margin by remember { mutableStateOf(0f) }
    var ocr by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("File name") }, modifier = Modifier.fillMaxWidth())
                Text("Page size", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
                Row { PageSizeOption.entries.forEach { o -> FilterChip(size == o, { size = o }, { Text(o.label) }, Modifier.padding(end = 6.dp)) } }
                if (showMargin && size != PageSizeOption.Fit || showMargin && margin > 0) {
                    Text("Margin: ${(margin / 72f * 25.4f).toInt()} mm", style = MaterialTheme.typography.labelLarge)
                    Slider(margin, { margin = it }, valueRange = 0f..72f)
                }
                Text("Quality", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                Row { Quality.entries.forEach { q -> FilterChip(quality == q, { quality = q }, { Text(q.label) }, Modifier.padding(end = 6.dp)) } }
                Text(
                    when (quality) {
                        Quality.Original -> "JPEGs are embedded unchanged; other images losslessly."
                        Quality.High -> "Up to ~300 DPI on A4, JPEG quality 85."
                        Quality.Small -> "Smaller file: lower resolution and JPEG quality."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Make text searchable (OCR)", style = MaterialTheme.typography.bodyLarge)
                        Text("On-device · ${ocrLanguages.joinToString(" + ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(ocr, { ocr = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim().ifEmpty { defaultName }
                onExport(if (n.endsWith(".pdf", true)) n else "$n.pdf", ExportOptions(size, margin, quality, ocr))
            }) { Text("Save…") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
