// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.recentsStore by preferencesDataStore("recents")

data class RecentFile(val uri: Uri, val name: String, val isPdf: Boolean, val openedAt: Long)

class Recents(private val context: Context) {
    private val key = stringPreferencesKey("items")

    val flow: Flow<List<RecentFile>> = context.recentsStore.data.map { p -> decode(p[key]) }

    /**
     * Records an opened file. Persistable permission is taken when the
     * provider offers it (files from the system picker); temporary grants from
     * email/chat apps are not kept, because they expire.
     */
    suspend fun add(uri: Uri, name: String, isPdf: Boolean, persistable: Boolean) {
        if (persistable) {
            val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val ok = runCatching { context.contentResolver.takePersistableUriPermission(uri, rw) }.isSuccess ||
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
            if (!ok) return
        } else if (!hasPersisted(uri)) return
        context.recentsStore.edit { p ->
            val items = decode(p[key]).filter { it.uri != uri }.toMutableList()
            items.add(0, RecentFile(uri, name, isPdf, System.currentTimeMillis()))
            p[key] = encode(items.take(MAX))
        }
    }

    suspend fun remove(uri: Uri) {
        context.recentsStore.edit { p -> p[key] = encode(decode(p[key]).filter { it.uri != uri }) }
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    suspend fun clear() {
        context.recentsStore.edit { it.remove(key) }
        context.contentResolver.persistedUriPermissions.forEach { perm ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    perm.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    private fun hasPersisted(uri: Uri) = context.contentResolver.persistedUriPermissions.any { it.uri == uri }

    private fun encode(items: List<RecentFile>) = JSONArray().apply {
        items.forEach {
            put(JSONObject().put("u", it.uri.toString()).put("n", it.name).put("p", it.isPdf).put("t", it.openedAt))
        }
    }.toString()

    private fun decode(s: String?): List<RecentFile> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            RecentFile(Uri.parse(o.getString("u")), o.getString("n"), o.optBoolean("p", true), o.optLong("t"))
        }
    }.getOrDefault(emptyList())

    companion object { const val MAX = 30 }
}
