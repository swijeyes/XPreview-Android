// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val Palette = listOf(0xFFD32F2F, 0xFF1565C0, 0xFF2E7D32, 0xFF000000, 0xFFF9A825, 0xFF6A1B9A).map { it.toInt() }

private fun Tool.icon(): ImageVector = when (this) {
    Tool.None -> Icons.Default.PanTool
    Tool.Ink -> Icons.Default.Gesture
    Tool.Rect -> Icons.Default.CropSquare
    Tool.Ellipse -> Icons.Default.RadioButtonUnchecked
    Tool.Note -> Icons.AutoMirrored.Filled.StickyNote2
    Tool.TextBox -> Icons.Default.TextFields
    Tool.Signature -> Icons.Default.Draw
    Tool.Eraser -> Icons.Default.AutoFixNormal
}

/**
 * Tool palette (plan 10.3). Text markup is created from a text selection
 * (long-press in Navigate mode); the drawing tools draw with one finger or a
 * stylus while two fingers keep panning and zooming.
 */
@Composable
fun AnnotationToolbar(vm: ViewerViewModel) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tool.entries.forEach { t ->
                    FilterChip(
                        selected = vm.tool == t,
                        onClick = { vm.tool = t },
                        label = { Text(t.label) },
                        leadingIcon = { Icon(t.icon(), null, Modifier.size(18.dp)) },
                    )
                }
            }
            if (vm.tool in setOf(Tool.Ink, Tool.Rect, Tool.Ellipse, Tool.TextBox)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Palette.forEach { c ->
                        val selected = (vm.style.color or 0xFF000000.toInt()) == c
                        Box(
                            Modifier.padding(4.dp).size(28.dp).clip(CircleShape).background(Color(c))
                                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                .clickable { vm.style = vm.style.copy(color = c) }
                                .semantics { contentDescription = "Colour" },
                        )
                    }
                }
                if (vm.tool != Tool.TextBox) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Width", style = MaterialTheme.typography.labelMedium)
                        Slider(vm.style.widthPt, { vm.style = vm.style.copy(widthPt = it) }, valueRange = 0.5f..12f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        Text("Opacity", style = MaterialTheme.typography.labelMedium)
                        Slider(vm.style.opacity, { vm.style = vm.style.copy(opacity = it) }, valueRange = 0.2f..1f, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                }
            }
            val hint = when (vm.tool) {
                Tool.None -> "Long-press text to highlight, underline or strike through. Tap an annotation to see or delete it."
                Tool.Ink, Tool.Rect, Tool.Ellipse -> "Draw with one finger or a stylus. Two fingers pan and zoom."
                Tool.Note, Tool.TextBox -> "Tap where the ${vm.tool.label.lowercase()} should go."
                Tool.Signature -> "Tap to place your signature. This is a drawn signature, not a certificate signature."
                Tool.Eraser -> "Tap an annotation to delete it."
            }
            Text(hint, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
