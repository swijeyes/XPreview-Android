// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.xpreview.app.BuildConfig
import org.xpreview.app.R

const val SOURCE_URL = "https://github.com/swijeyes/XPreview-Plus"

/** About: licence, AGPL source offer, third-party notices, limitations (plan 12.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var notices by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("About") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.app_logo), null, Modifier.size(96.dp))
            Text("XPreview+ for Android", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Copyright © 2026 Sanjeeva Wijeyesakere. Free software under the GNU Affero General Public License v3 or later. " +
                    "You can get the complete source code of this app at the address below.",
                Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) } }) { Text(SOURCE_URL) }
            Text("Not supported", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
            Text(
                "• OpenDocument and RTF files (use the desktop app)\n" +
                    "• TIFF images\n" +
                    "• JavaScript in PDFs, XFA forms, certificate signatures\n" +
                    "• Saving password-protected PDFs (output is unencrypted)\n" +
                    "• Handwriting recognition",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { notices = !notices }, modifier = Modifier.padding(top = 16.dp)) {
                Text(if (notices) "Hide third-party notices" else "Third-party notices")
            }
            if (notices) {
                val text = remember { runCatching { context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().readText() }.getOrDefault("") }
                Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}
