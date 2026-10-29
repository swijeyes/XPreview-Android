// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.viewer.dialogs

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.xpreview.app.viewer.AnnotPopup
import org.xpreview.app.viewer.FormEdit
import org.xpreview.app.viewer.PendingPlacement
import org.xpreview.app.viewer.Tool
import org.xpreview.app.viewer.ViewerViewModel
import org.xpreview.pdf.FieldType
import org.xpreview.pdf.FormType
import kotlin.math.max
import kotlin.math.min

@Composable
fun PasswordDialog(wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password required") },
        text = {
            Column {
                Text(if (wrong) "Incorrect password. Try again." else "This PDF is protected. Enter its password to open it.")
                OutlinedTextField(
                    pw, { pw = it }, singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = wrong, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(pw) }, enabled = pw.isNotEmpty()) { Text("Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Native editors over PDF form fields (plan 11.3). */
@Composable
fun FormFieldDialog(vm: ViewerViewModel, edit: FormEdit) {
    val f = edit.field
    val title = f.name.substringAfterLast('.').ifBlank { "Form field" }
    when (f.type) {
        FieldType.Text -> {
            var text by remember { mutableStateOf(f.value) }
            AlertDialog(
                onDismissRequest = { vm.formEdit = null },
                title = { Text(title) },
                text = {
                    OutlinedTextField(
                        text, { text = it }, singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1,
                        visualTransformation = if (f.password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = { TextButton(onClick = { vm.commitText(edit, text) }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { vm.formEdit = null }) { Text("Cancel") } },
            )
        }
        FieldType.ComboBox, FieldType.ListBox -> {
            val selected = remember { mutableStateListOf<Int>().apply { addAll(f.selected) } }
            AlertDialog(
                onDismissRequest = { vm.formEdit = null },
                title = { Text(title) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        itemsIndexed(f.options) { i, label ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (f.multiSelect) { if (i in selected) selected.remove(i) else selected.add(i) }
                                    else vm.commitOption(edit, i)
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (f.multiSelect) Checkbox(i in selected, null) else RadioButton(i in f.selected, null)
                                Text(label.ifBlank { "(empty)" }, Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    if (f.multiSelect) TextButton(onClick = {
                        f.options.indices.forEach { i ->
                            val want = i in selected
                            if (want != (i in f.selected)) vm.commitOption(edit, i, want)
                        }
                        vm.formEdit = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { vm.formEdit = null }) { Text("Cancel") } },
            )
        }
        else -> vm.formEdit = null
    }
}

@Composable
fun AnnotationPopupDialog(vm: ViewerViewModel, p: AnnotPopup) {
    AlertDialog(
        onDismissRequest = { vm.annotPopup = null },
        title = { Text(p.annot.type.name) },
        text = { Text(p.contents.ifBlank { if (p.annot.isOwn) "Added with XPreview+." else "No comment text." }) },
        confirmButton = { TextButton(onClick = { vm.annotPopup = null }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { vm.deleteAnnotation(p.page, p.annot) }) { Text("Delete") } },
    )
}

@Composable
fun PlacementDialog(vm: ViewerViewModel, p: PendingPlacement) {
    when (p.tool) {
        Tool.Note -> {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { vm.placement = null },
                title = { Text("Note") },
                text = { OutlinedTextField(text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(onClick = { vm.addNote(p, text) }, enabled = text.isNotBlank()) { Text("Add") } },
                dismissButton = { TextButton(onClick = { vm.placement = null }) { Text("Cancel") } },
            )
        }
        Tool.TextBox -> {
            var text by remember { mutableStateOf("") }
            var size by remember { mutableFloatStateOf(12f) }
            AlertDialog(
                onDismissRequest = { vm.placement = null },
                title = { Text("Text") },
                text = {
                    Column {
                        OutlinedTextField(text, { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth())
                        Text("Size ${size.toInt()} pt", Modifier.padding(top = 8.dp))
                        Slider(size, { size = it }, valueRange = 6f..36f, steps = 29)
                    }
                },
                confirmButton = { TextButton(onClick = { vm.addTextBox(p, text, size) }, enabled = text.isNotBlank()) { Text("Add") } },
                dismissButton = { TextButton(onClick = { vm.placement = null }) { Text("Cancel") } },
            )
        }
        Tool.Signature -> SignaturePad(onDone = { vm.saveSignature(it, p) }, onCancel = { vm.placement = null })
        else -> vm.placement = null
    }
}

/** Draw once, saved as a transparent PNG in app storage and reused (plan 10.1). */
@Composable
fun SignaturePad(onDone: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Draw your signature") },
        text = {
            Column {
                Canvas(
                    Modifier.fillMaxWidth().height(180.dp).background(Color.White).border(1.dp, Color.Gray)
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current = listOf(it) },
                                onDrag = { c, _ -> current = current + c.position },
                                onDragEnd = { strokes += current; current = emptyList() },
                            )
                        },
                ) {
                    (strokes + listOf(current)).filter { it.size > 1 }.forEach { s ->
                        val path = Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
                        drawPath(path, Color(0xFF0D2A6B), style = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                Text(
                    "This is a drawn image, not a certificate-based digital signature.",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                val all = strokes.flatten()
                val pad = 8f
                val l = max(0f, all.minOf { it.x } - pad)
                val t = max(0f, all.minOf { it.y } - pad)
                val r = min(size.width.toFloat(), all.maxOf { it.x } + pad)
                val b = min(size.height.toFloat(), all.maxOf { it.y } + pad)
                val bmp = Bitmap.createBitmap(max(1, (r - l).toInt()), max(1, (b - t).toInt()), Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF0D2A6B.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 5f
                    strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
                }
                strokes.filter { it.size > 1 }.forEach { s ->
                    val path = android.graphics.Path().apply { moveTo(s[0].x - l, s[0].y - t); s.drop(1).forEach { lineTo(it.x - l, it.y - t) } }
                    c.drawPath(path, paint)
                }
                onDone(bmp)
            }) { Text("Use") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear() }) { Text("Clear") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

/** Inspector-style document information (plan 5.1). */
@Composable
fun InfoDialog(vm: ViewerViewModel, onDismiss: () -> Unit) {
    val i = vm.info
    val l = vm.layout
    val page = l?.let { vm.viewport.currentPage(it) } ?: 0
    val ps = vm.pageSizes.getOrNull(page)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Document info") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                @Composable fun row(k: String, v: String) {
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(k, Modifier.weight(0.4f), style = MaterialTheme.typography.labelLarge)
                        Text(v, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                row("File", vm.name)
                row("Pages", vm.pageSizes.size.toString())
                i?.let { info ->
                    if (info.fileVersion > 0) row("PDF version", "${info.fileVersion / 10}.${info.fileVersion % 10}")
                    row("Encrypted", if (info.encrypted) "Yes" else "No")
                    row("Forms", when (info.formType) {
                        FormType.None -> "None"; FormType.AcroForm -> "AcroForm"; else -> "XFA (not supported)"
                    })
                    row("JavaScript", if (info.javascriptActions > 0) "${info.javascriptActions} action(s) — not run" else "None")
                    info.metadata.forEach { (k, v) -> row(k, v) }
                }
                ps?.let {
                    row("Page ${page + 1} size", "%.0f × %.0f pt (%.1f × %.1f cm)".format(it.width, it.height, it.width / 72 * 2.54, it.height / 72 * 2.54))
                }
                if (vm.dirty) row("Status", "Unsaved changes")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun GoToPageDialog(count: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val n = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                text, { text = it.filter(Char::isDigit).take(7) }, singleLine = true,
                label = { Text("1 – $count") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { onGo(n!! - 1) }, enabled = n != null && n in 1..count) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Unsaved-changes protection on back/close (plan 10.4). */
@Composable
fun UnsavedDialog(canSaveInPlace: Boolean, onSave: () -> Unit, onSaveCopy: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unsaved changes") },
        text = { Text("Save your changes before closing?") },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (canSaveInPlace) TextButton(onClick = onSave) { Text("Save") }
                TextButton(onClick = onSaveCopy) { Text("Save a copy…") }
                TextButton(onClick = onDiscard) { Text("Discard") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}
