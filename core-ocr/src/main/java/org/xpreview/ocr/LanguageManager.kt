// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.ocr

import android.content.Context
import android.net.Uri
import org.xpreview.common.Limits
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class OcrLanguage(val code: String, val sizeBytes: Long, val bundled: Boolean)

/**
 * Installed Tesseract models (plan 8.2). English (tessdata_fast) is bundled;
 * more languages are imported as .traineddata files through the file picker,
 * which works offline and needs no INTERNET permission.
 */
class LanguageManager(private val context: Context) {
    /** Tesseract wants the parent of the "tessdata" directory. */
    val dataPath: File get() = File(context.noBackupFilesDir, "tesseract")
    private val tessdata get() = File(dataPath, "tessdata")

    fun ensureBundled() {
        tessdata.mkdirs()
        for (name in context.assets.list("tessdata").orEmpty().filter { it.endsWith(".traineddata") }) {
            val target = File(tessdata, name)
            if (target.exists() && target.length() > 0) continue
            val tmp = File(tessdata, "$name.tmp")
            context.assets.open("tessdata/$name").use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(target)
        }
    }

    fun installed(): List<OcrLanguage> {
        ensureBundled()
        val bundled = context.assets.list("tessdata").orEmpty().map { it.removeSuffix(".traineddata") }.toSet()
        return tessdata.listFiles { f -> f.name.endsWith(".traineddata") }.orEmpty()
            .map { OcrLanguage(it.name.removeSuffix(".traineddata"), it.length(), it.nameWithoutExtension in bundled) }
            .sortedBy { it.code }
    }

    /**
     * Imports a model. Imported files are untrusted: the name must be a
     * Tesseract language code, the size is capped, and the content must start
     * like a traineddata archive. Returns the SHA-256 so the user can compare
     * it with the published checksum.
     */
    fun import(uri: Uri, displayName: String): Pair<OcrLanguage, String> {
        val code = displayName.removeSuffix(".traineddata")
        if (!isValidCode(code)) throw IOException("“$displayName” is not a Tesseract language file (expected e.g. fra.traineddata).")
        tessdata.mkdirs()
        val tmp = File(tessdata, "$code.import")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > Limits.MAX_TRAINEDDATA_BYTES) {
                        tmp.delete()
                        throw IOException("The language file is too large.")
                    }
                    digest.update(buf, 0, r)
                    out.write(buf, 0, r)
                }
            }
        } ?: throw IOException("The file could not be read.")
        if (total < 1024 || !looksLikeTraineddata(tmp)) {
            tmp.delete()
            throw IOException("The file is not a valid Tesseract model.")
        }
        val target = File(tessdata, "$code.traineddata")
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("The language could not be installed.")
        }
        return OcrLanguage(code, total, false) to digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun remove(code: String): Boolean {
        if (!isValidCode(code)) return false
        val bundled = context.assets.list("tessdata").orEmpty().contains("$code.traineddata")
        return !bundled && File(tessdata, "$code.traineddata").delete()
    }

    companion object {
        private val CODE = Regex("^[a-z]{3}(_[a-z]+)*$|^[A-Z][a-z]+$")
        fun isValidCode(code: String) = code.length in 3..32 && CODE.matches(code)

        /**
         * A traineddata file is a TessdataManager archive: a little-endian
         * int32 entry count followed by that many int64 offsets.
         */
        fun looksLikeTraineddata(f: File): Boolean = runCatching {
            f.inputStream().use { s ->
                val head = ByteArray(4)
                if (s.read(head) != 4) return false
                val n = (head[0].toInt() and 0xff) or ((head[1].toInt() and 0xff) shl 8) or
                    ((head[2].toInt() and 0xff) shl 16) or ((head[3].toInt() and 0xff) shl 24)
                n in 1..64
            }
        }.getOrDefault(false)
    }
}
