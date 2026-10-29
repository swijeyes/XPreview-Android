// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.xpreview.vision.ScanFilter

private val Context.settingsStore by preferencesDataStore("settings")

data class AppSettings(
    val authorName: String = "",
    val darkPages: Boolean = false,
    val highlightFields: Boolean = true,
    val autoCapture: Boolean = true,
    val defaultFilter: ScanFilter = ScanFilter.Document,
    val ocrLanguages: List<String> = listOf("eng"),
)

class Settings(private val context: Context) {
    private object K {
        val author = stringPreferencesKey("author")
        val dark = booleanPreferencesKey("dark_pages")
        val highlight = booleanPreferencesKey("highlight_fields")
        val auto = booleanPreferencesKey("auto_capture")
        val filter = stringPreferencesKey("scan_filter")
        val langs = stringPreferencesKey("ocr_languages")
    }

    val flow: Flow<AppSettings> = context.settingsStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(f: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val s = f(p.toSettings())
            p[K.author] = s.authorName
            p[K.dark] = s.darkPages
            p[K.highlight] = s.highlightFields
            p[K.auto] = s.autoCapture
            p[K.filter] = s.defaultFilter.name
            p[K.langs] = s.ocrLanguages.joinToString("+")
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        authorName = this[K.author] ?: "",
        darkPages = this[K.dark] ?: false,
        highlightFields = this[K.highlight] ?: true,
        autoCapture = this[K.auto] ?: true,
        defaultFilter = this[K.filter]?.let { n -> ScanFilter.entries.firstOrNull { it.name == n } } ?: ScanFilter.Document,
        ocrLanguages = this[K.langs]?.split('+')?.filter { it.isNotBlank() }?.ifEmpty { null } ?: listOf("eng"),
    )
}
