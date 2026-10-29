// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.common

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object UriUtils {
    data class Meta(val name: String, val size: Long)

    fun meta(context: Context, uri: Uri): Meta {
        var name: String? = null
        var size = -1L
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { File(it) }?.let { name = it.name; size = it.length() }
        }
        return Meta(sanitizeFileName(name ?: uri.lastPathSegment ?: "document"), size)
    }

    fun readHead(context: Context, uri: Uri, n: Int = FormatSniffer.HEAD_BYTES): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) break
                off += r
            }
            buf.copyOf(off)
        } ?: ByteArray(0)

    /**
     * Only content:// URIs and file:// URIs inside the app's own storage are
     * accepted; other file:// URIs from intents are refused (plan 12.1).
     */
    fun isAcceptable(context: Context, uri: Uri): Boolean = when (uri.scheme) {
        ContentResolver.SCHEME_CONTENT -> true
        ContentResolver.SCHEME_FILE -> {
            val path = uri.path?.let { runCatching { File(it).canonicalPath }.getOrNull() }
            path != null && (path.startsWith(context.filesDir.canonicalPath) ||
                path.startsWith(context.cacheDir.canonicalPath))
        }
        else -> false
    }

    fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(200).ifBlank { "document" }

    fun baseName(name: String): String = name.substringBeforeLast('.', name)
}
