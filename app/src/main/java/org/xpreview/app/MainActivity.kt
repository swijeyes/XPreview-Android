// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import org.xpreview.app.ui.theme.XPreviewTheme
import org.xpreview.common.UriUtils

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = if (savedInstanceState == null) startTarget(intent) else StartTarget.Home
        setContent { XPreviewTheme { AppNav(start) } }
    }

    /**
     * Intent hardening (plan 12.1): only the data/stream URIs are read, extras
     * are otherwise ignored, and file:// URIs outside app storage are refused.
     */
    private fun startTarget(intent: Intent?): StartTarget {
        intent ?: return StartTarget.Home
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }.filter { UriUtils.isAcceptable(this, it) }.take(MAX_SHARED)
        return when {
            uris.isEmpty() -> StartTarget.Home
            uris.size == 1 -> StartTarget.Open(uris[0])
            else -> StartTarget.Images(uris)
        }
    }

    companion object { const val MAX_SHARED = 500 }
}

sealed interface StartTarget {
    data object Home : StartTarget
    data class Open(val uri: Uri) : StartTarget
    data class Images(val uris: List<Uri>) : StartTarget
}
