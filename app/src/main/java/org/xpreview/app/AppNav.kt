// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xpreview.app.home.HomeScreen
import org.xpreview.app.images.ImagesScreen
import org.xpreview.app.merge.MergeScreen
import org.xpreview.app.scan.ScanReviewScreen
import org.xpreview.app.scan.ScannerScreen
import org.xpreview.app.settings.AboutScreen
import org.xpreview.app.settings.LanguagesScreen
import org.xpreview.app.settings.SettingsScreen
import org.xpreview.app.viewer.ViewerScreen
import org.xpreview.common.SniffedFormat
import org.xpreview.common.isImage
import org.xpreview.image.ImageLoader

object Routes {
    const val HOME = "home"
    fun open(uri: Uri, picker: Boolean) = "open?uri=${Uri.encode(uri.toString())}&picker=$picker"
    fun pdf(uri: Uri, picker: Boolean) = "pdf?uri=${Uri.encode(uri.toString())}&picker=$picker"
    const val IMAGES = "images"
    const val MERGE = "merge"
    fun scan(session: String? = null, replace: String? = null) =
        "scan?session=${session.orEmpty()}&replace=${replace.orEmpty()}"
    fun review(session: String) = "review/$session"
    const val SETTINGS = "settings"
    const val LANGUAGES = "languages"
    const val ABOUT = "about"
}

@Composable
fun AppNav(start: StartTarget) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val startRoute = remember(start) {
        when (start) {
            StartTarget.Home -> Routes.HOME
            is StartTarget.Open -> Routes.open(start.uri, false)
            is StartTarget.Images -> { context.container.pendingImages = start.uris; Routes.IMAGES }
        }
    }
    fun back() { if (!nav.popBackStack()) (context as? android.app.Activity)?.finish() }

    NavHost(nav, startDestination = startRoute) {
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(
            "open?uri={uri}&picker={picker}",
            listOf(navArgument("uri") { type = NavType.StringType }, navArgument("picker") { type = NavType.BoolType; defaultValue = false }),
        ) { e ->
            OpenRouter(nav, Uri.parse(e.arguments!!.getString("uri")), e.arguments!!.getBoolean("picker"), ::back)
        }
        composable(
            "pdf?uri={uri}&picker={picker}",
            listOf(navArgument("uri") { type = NavType.StringType }, navArgument("picker") { type = NavType.BoolType; defaultValue = false }),
        ) { e ->
            ViewerScreen(
                Uri.parse(e.arguments!!.getString("uri")), e.arguments!!.getBoolean("picker"), onBack = ::back,
                onOpenInNewWindow = { openInNewWindow(context, it) },
            )
        }
        composable(Routes.IMAGES) { ImagesScreen(nav, onBack = ::back) }
        composable(Routes.MERGE) { MergeScreen(nav, onBack = ::back) }
        composable(
            "scan?session={session}&replace={replace}",
            listOf(
                navArgument("session") { type = NavType.StringType; defaultValue = "" },
                navArgument("replace") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { e ->
            ScannerScreen(
                nav, e.arguments!!.getString("session").orEmpty().ifEmpty { null },
                e.arguments!!.getString("replace").orEmpty().ifEmpty { null }, onBack = ::back,
            )
        }
        composable("review/{session}") { e -> ScanReviewScreen(nav, e.arguments!!.getString("session")!!, onBack = ::back) }
        composable(Routes.SETTINGS) { SettingsScreen(nav, onBack = ::back) }
        composable(Routes.LANGUAGES) { LanguagesScreen(onBack = ::back) }
        composable(Routes.ABOUT) { AboutScreen(onBack = ::back) }
    }
}

/** Detects the format from content (never the extension) and routes to the right viewer. */
@Composable
private fun OpenRouter(nav: NavController, uri: Uri, picker: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        val fmt = withContext(Dispatchers.IO) { runCatching { ImageLoader.sniff(context, uri) }.getOrNull() }
        when {
            fmt == null -> error = "The file could not be read."
            fmt == SniffedFormat.Pdf -> nav.navigate(Routes.pdf(uri, picker)) { popUpTo(nav.currentDestination!!.id) { inclusive = true } }
            fmt.isImage -> {
                context.container.pendingImages = listOf(uri)
                if (picker) runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.container.recents.add(uri, org.xpreview.common.UriUtils.meta(context, uri).name, isPdf = false, persistable = picker)
                nav.navigate(Routes.IMAGES) { popUpTo(nav.currentDestination!!.id) { inclusive = true } }
            }
            fmt == SniffedFormat.Tiff -> error = "TIFF images are not supported in this version."
            fmt == SniffedFormat.Odf || fmt == SniffedFormat.Rtf -> error = "OpenDocument and RTF files are not supported on Android. Use XPreview+ on the desktop to convert them to PDF."
            else -> error = "This file type is not supported. XPreview+ opens PDFs and images."
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val e = error
        if (e == null) CircularProgressIndicator() else Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Cannot open this file", style = MaterialTheme.typography.titleLarge)
            Text(e, Modifier.padding(vertical = 12.dp))
            TextButton(onClick = onBack) { Text("Close") }
        }
    }
}

/** Multi-window replaces the desktop tabs (plan 5.3). */
fun openInNewWindow(context: Context, uri: Uri) {
    val i = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java).addFlags(
        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
    context.startActivity(i)
}
