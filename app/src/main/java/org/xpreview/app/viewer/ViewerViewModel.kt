// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
package org.xpreview.app.viewer

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xpreview.app.container
import org.xpreview.app.data.AppSettings
import org.xpreview.app.io.OcrPipeline
import org.xpreview.app.io.SafeSave
import org.xpreview.common.FormatSniffer
import org.xpreview.common.Limits
import org.xpreview.common.SniffedFormat
import org.xpreview.common.UriUtils
import org.xpreview.pdf.AnnotInfo
import org.xpreview.pdf.AnnotType
import org.xpreview.pdf.DocId
import org.xpreview.pdf.DocumentInfo
import org.xpreview.pdf.FieldType
import org.xpreview.pdf.FormField
import org.xpreview.pdf.FormType
import org.xpreview.pdf.OpenResult
import org.xpreview.pdf.OutlineItem
import org.xpreview.pdf.PageSize
import org.xpreview.pdf.PageTransform
import org.xpreview.pdf.PdfDispatchers
import org.xpreview.pdf.PdfLink
import org.xpreview.pdf.RenderFlags
import org.xpreview.pdf.TextMatch
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

sealed interface LoadState {
    data object Loading : LoadState
    data class Password(val wrong: Boolean) : LoadState
    data class Error(val message: String) : LoadState
    data object Ready : LoadState
}

enum class Tool(val label: String) {
    None("Navigate"), Ink("Pen"), Rect("Rectangle"), Ellipse("Ellipse"), Note("Note"),
    TextBox("Text"), Signature("Signature"), Eraser("Eraser"),
}

data class ToolStyle(val color: Int = 0xFFD32F2F.toInt(), val widthPt: Float = 2f, val opacity: Float = 1f) {
    val argb: Int get() = (color and 0x00FFFFFF) or ((opacity * 255).toInt().coerceIn(0, 255) shl 24)
}

data class Selection(val page: Int, val anchor: Int, val focus: Int) {
    val start get() = min(anchor, focus)
    val count get() = max(anchor, focus) - start + 1
}

data class Busy(val label: String, val progress: Float? = null, val job: Job? = null)
data class FormEdit(val page: Int, val field: FormField)
data class AnnotPopup(val page: Int, val annot: AnnotInfo, val contents: String)
data class PendingPlacement(val tool: Tool, val page: Int, val at: PointF)

/** Annotation subtypes the eraser and the popup may delete; others stay locked (plan 10.2). */
private val Deletable = setOf(
    AnnotType.Text, AnnotType.FreeText, AnnotType.Line, AnnotType.Square, AnnotType.Circle, AnnotType.Polygon,
    AnnotType.Polyline, AnnotType.Highlight, AnnotType.Underline, AnnotType.Squiggly, AnnotType.StrikeOut,
    AnnotType.Stamp, AnnotType.Caret, AnnotType.Ink,
)

/**
 * One open PDF (plan 2.6 "DocumentSession"): owns the engine handle, layout
 * inputs, zoom/scroll, edit history and the dirty flag.
 */
class ViewerViewModel(app: Application, initialUri: Uri, private val fromPicker: Boolean) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val engine = app.container.engine
    private val ocr by lazy { OcrPipeline(engine, app.container.languages) }

    var uri: Uri = initialUri
        private set
    var name by mutableStateOf(UriUtils.meta(app, initialUri).name)
        private set

    var loadState by mutableStateOf<LoadState>(LoadState.Loading)
        private set
    var doc: DocId? = null
        private set
    var info by mutableStateOf<DocumentInfo?>(null)
        private set
    var pageSizes by mutableStateOf<List<PageSize>>(emptyList())
        private set
    var outline by mutableStateOf<List<OutlineItem>>(emptyList())
        private set
    var docVersion by mutableIntStateOf(0)
        private set
    var viewRotation by mutableIntStateOf(0)
        private set
    var dirty by mutableStateOf(false)
        private set
    private var structural = false

    var settings by mutableStateOf(AppSettings())
        private set

    val viewport = Viewport()
    var layout: DocLayout? = null
    val renderer = PageRenderer(engine, { doc }, viewModelScope, cacheBytes(app))
    private val transforms = mutableStateMapOf<Int, PageTransform>()
    private val links = HashMap<Int, List<PdfLink>>()
    private val pageTexts = HashMap<Int, String>()

    private var pfd: ParcelFileDescriptor? = null
    private var sourceFile: File? = null

    // Search
    var searchQuery by mutableStateOf("")
    var searchResults by mutableStateOf<List<TextMatch>>(emptyList())
        private set
    var searchIndex by mutableIntStateOf(-1)
        private set
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    // Selection
    var selection by mutableStateOf<Selection?>(null)
        private set
    var selectionRects by mutableStateOf<List<RectF>>(emptyList())
        private set

    // Tools
    var tool by mutableStateOf(Tool.None)
    var style by mutableStateOf(ToolStyle())
    val stroke = mutableStateListOf<Offset>()
    var strokePage by mutableIntStateOf(-1)
        private set
    var shapeStart by mutableStateOf<Offset?>(null)
        private set
    var shapeEnd by mutableStateOf<Offset?>(null)
        private set

    // Dialogs and feedback
    var message by mutableStateOf<String?>(null)
    var externalLink by mutableStateOf<String?>(null)
    var formEdit by mutableStateOf<FormEdit?>(null)
    var annotPopup by mutableStateOf<AnnotPopup?>(null)
    var placement by mutableStateOf<PendingPlacement?>(null)
    var busy by mutableStateOf<Busy?>(null)
        private set
    var notices by mutableStateOf<List<String>>(emptyList())
        private set

    // Undo / redo (plan 10.4)
    private sealed interface Edit {
        /** An annotation this app created; undo removes it by /NM, redo re-runs the creation. */
        data class Created(val page: Int, val name: String, val redo: () -> Boolean) : Edit
        /** Full document state before an edit that has no cheap inverse. */
        data class Snapshot(val file: File, val structural: Boolean) : Edit
    }
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    val signatureFile get() = File(ctx.filesDir, "signature.png")

    init {
        viewModelScope.launch { app.container.settings.flow.collect { applySettings(it) } }
        open(null)
    }

    private fun applySettings(s: AppSettings) {
        val old = settings
        settings = s
        val d = doc ?: return
        if (old.highlightFields != s.highlightFields) viewModelScope.launch {
            withContext(PdfDispatchers.pdf) { engine.setFormHighlight(d, s.highlightFields) }
            docVersion++
        }
    }

    val renderFlags: Int get() = RenderFlags.ANNOTATIONS or RenderFlags.FORMS

    // ------------------------------------------------------------------ open

    fun open(password: String?) {
        loadState = LoadState.Loading
        viewModelScope.launch {
            val result = withContext(PdfDispatchers.pdf) { openBlocking(password) }
            when (result) {
                is OpenResult.Ok -> {
                    doc = result.doc
                    applyInfo(result.info)
                    reloadStructure()
                    withContext(PdfDispatchers.pdf) { engine.setFormHighlight(result.doc, settings.highlightFields) }
                    loadState = LoadState.Ready
                    refreshWritable()
                    app().container.recents.add(uri, name, isPdf = true, persistable = fromPicker)
                }
                OpenResult.NeedsPassword -> loadState = LoadState.Password(wrong = false)
                OpenResult.WrongPassword -> loadState = LoadState.Password(wrong = true)
                OpenResult.Corrupt -> loadState = LoadState.Error("This PDF is damaged and cannot be opened.")
                OpenResult.Unsupported -> loadState = LoadState.Error("This PDF uses an unsupported security handler.")
                is OpenResult.Failed -> loadState = LoadState.Error(result.message)
            }
        }
    }

    private fun app() = getApplication<Application>()

    private fun openBlocking(password: String?): OpenResult {
        val meta = UriUtils.meta(ctx, uri)
        if (meta.size > Limits.MAX_INPUT_FILE_BYTES) return OpenResult.Failed("The file is too large to open.")
        val head = runCatching { UriUtils.readHead(ctx, uri) }.getOrNull()
            ?: return OpenResult.Failed("The file could not be read. It may have been moved or the permission expired.")
        val fmt = FormatSniffer.sniff(head)
        if (fmt != SniffedFormat.Pdf) {
            return OpenResult.Failed(
                if (fmt == SniffedFormat.Odf || fmt == SniffedFormat.Rtf) "OpenDocument and RTF files are not supported on Android."
                else "This file is not a PDF.",
            )
        }
        pfd?.close()
        pfd = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            ?: return OpenResult.Failed("The file could not be opened.")
        val r = engine.open(pfd!!, password)
        if (r !is OpenResult.Ok) { pfd?.close(); pfd = null }
        return r
    }

    private fun applyInfo(i: DocumentInfo) {
        info = i
        notices = buildList {
            if (i.formType == FormType.XfaFull || i.formType == FormType.XfaForeground)
                add("This form type (XFA) is not supported; the page may show a placeholder.")
            if (i.javascriptActions > 0)
                add("This document contains scripts. They do not run here, so calculated or validated fields will not update.")
        }
    }

    private suspend fun reloadStructure() {
        val d = doc ?: return
        val (sizes, ol) = withContext(PdfDispatchers.pdf) { engine.pageSizes(d) to engine.outline(d) }
        pageSizes = sizes
        outline = ol
        transforms.clear()
        links.clear()
        pageTexts.clear()
        layout?.let { viewport.clamp(it) }
    }

    // ------------------------------------------------------------------ geometry helpers

    suspend fun transformFor(page: Int): PageTransform? {
        transforms[page]?.let { return it }
        val d = doc ?: return null
        val rot = viewRotation
        val t = withContext(PdfDispatchers.pdf) { engine.transform(d, page, rot) } ?: return null
        if (rot == viewRotation) transforms[page] = t
        return t
    }

    fun cachedTransform(page: Int): PageTransform? = transforms[page]

    fun ensureTransforms(pages: Iterable<Int>) {
        val missing = pages.filter { it !in transforms }
        if (missing.isEmpty()) return
        viewModelScope.launch { missing.forEach { transformFor(it) } }
    }

    private suspend fun toPage(hit: PageHit): PointF? = transformFor(hit.page)?.toPage(hit.nx, hit.ny)

    /** Points per screen pixel at the current zoom. */
    private fun ptPerPx(): Float = 1f / ((layout?.basePxPerPt ?: 1f) * viewport.zoom)

    fun rotateView(delta: Int) {
        val l = layout
        val anchor = l?.let { viewport.anchor(it) }
        viewRotation = ((viewRotation + delta) % 4 + 4) % 4
        transforms.clear()
        pendingAnchor = anchor
    }

    /** Restored by the screen after the layout for a new rotation exists. */
    var pendingAnchor: Pair<Int, Float>? = null

    fun goToPage(page: Int) {
        val l = layout ?: return
        viewport.scrollToPage(l, page.coerceIn(0, max(0, pageSizes.size - 1)))
    }

    // ------------------------------------------------------------------ search

    fun search(query: String, matchCase: Boolean = false, wholeWord: Boolean = false) {
        searchJob?.cancel()
        searchQuery = query
        searchResults = emptyList()
        searchIndex = -1
        val d = doc ?: return
        if (query.isBlank()) { searching = false; return }
        searching = true
        val start = layout?.let { viewport.currentPage(it) } ?: 0
        searchJob = viewModelScope.launch {
            val n = pageSizes.size
            val found = ArrayList<TextMatch>()
            for (p in 0 until n) {
                coroutineContext.ensureActive()
                val m = withContext(PdfDispatchers.pdf) { engine.search(d, p, query, matchCase, wholeWord) }
                if (m.isNotEmpty()) {
                    found += m
                    searchResults = found.toList()
                    if (searchIndex < 0 && p >= start) { searchIndex = found.size - m.size; showResult() }
                }
            }
            if (searchIndex < 0 && found.isNotEmpty()) { searchIndex = 0; showResult() }
            searching = false
            if (found.isEmpty()) message = "No matches for “$query”"
        }
    }

    fun nextResult(delta: Int) {
        if (searchResults.isEmpty()) return
        searchIndex = ((searchIndex + delta) % searchResults.size + searchResults.size) % searchResults.size
        showResult()
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchQuery = ""
        searchResults = emptyList()
        searchIndex = -1
        searching = false
    }

    private fun showResult() {
        val m = searchResults.getOrNull(searchIndex) ?: return
        viewModelScope.launch {
            val t = transformFor(m.page) ?: return@launch
            val l = layout ?: return@launch
            val y = m.rects.firstOrNull()?.let { t.toNormalized(it).top } ?: 0f
            val r = viewport.screenRect(l, m.page)
            val targetScreenY = r.top + y * r.height
            if (targetScreenY < viewport.height * 0.15f || targetScreenY > viewport.height * 0.75f) {
                viewport.scrollToPage(l, m.page, (y - 0.2f * viewport.height / r.height).coerceAtLeast(0f))
            }
        }
    }

    // ------------------------------------------------------------------ selection

    fun onLongPress(hit: PageHit) {
        if (tool != Tool.None) return
        viewModelScope.launch {
            val d = doc ?: return@launch
            val pt = toPage(hit) ?: return@launch
            val tol = (12f * ptPerPx() * ctx.resources.displayMetrics.density).toDouble()
            val idx = withContext(PdfDispatchers.pdf) { engine.charIndexAt(d, hit.page, pt, tol) }
            if (idx < 0) return@launch
            val text = pageText(hit.page)
            var s = idx
            var e = idx
            while (s > 0 && text.getOrNull(s - 1)?.isLetterOrDigit() == true) s--
            while (e + 1 < text.length && text[e + 1].isLetterOrDigit()) e++
            setSelection(Selection(hit.page, s, e))
        }
    }

    fun onSelectDrag(hit: PageHit) {
        val sel = selection ?: return
        if (hit.page != sel.page) return
        viewModelScope.launch {
            val d = doc ?: return@launch
            val pt = toPage(hit) ?: return@launch
            val tol = (24f * ptPerPx() * ctx.resources.displayMetrics.density).toDouble()
            val idx = withContext(PdfDispatchers.pdf) { engine.charIndexAt(d, hit.page, pt, tol) }
            if (idx >= 0 && idx != sel.focus) setSelection(sel.copy(focus = idx))
        }
    }

    private suspend fun setSelection(sel: Selection?) {
        selection = sel
        val d = doc
        selectionRects = if (sel == null || d == null) emptyList()
        else withContext(PdfDispatchers.pdf) { engine.textRects(d, sel.page, sel.start, sel.count) }
        sel?.let { transformFor(it.page) }
    }

    fun clearSelection() { selection = null; selectionRects = emptyList() }

    suspend fun selectedText(): String {
        val sel = selection ?: return ""
        val t = pageText(sel.page)
        return t.substring(sel.start.coerceIn(0, t.length), (sel.start + sel.count).coerceIn(0, t.length))
    }

    private suspend fun pageText(page: Int): String {
        pageTexts[page]?.let { return it }
        val d = doc ?: return ""
        return withContext(PdfDispatchers.pdf) { engine.pageText(d, page) }.also { pageTexts[page] = it }
    }

    /** Highlight / underline / strikeout from the current selection (plan 10.1). */
    fun markupSelection(type: AnnotType, argb: Int) {
        val sel = selection ?: return
        val rects = selectionRects
        if (rects.isEmpty()) return
        val author = settings.authorName.ifBlank { null }
        create(sel.page) { d -> engine.addMarkup(d, sel.page, type, rects, argb, author) }
        clearSelection()
    }

    // ------------------------------------------------------------------ taps

    fun onTap(hit: PageHit?) {
        if (selection != null) { clearSelection(); return }
        if (hit == null) return
        viewModelScope.launch {
            val d = doc ?: return@launch
            val pt = toPage(hit) ?: return@launch
            when (tool) {
                Tool.Note, Tool.TextBox -> placement = PendingPlacement(tool, hit.page, pt)
                Tool.Signature -> {
                    if (!signatureFile.exists()) placement = PendingPlacement(Tool.Signature, hit.page, pt)
                    else placeSignature(hit.page, pt)
                }
                Tool.Eraser -> annotationAt(d, hit.page, pt)?.let { deleteAnnotation(hit.page, it) }
                    ?: run { message = "No annotation here" }
                Tool.None -> handleNavigateTap(d, hit, pt)
                else -> Unit
            }
        }
    }

    private suspend fun handleNavigateTap(d: DocId, hit: PageHit, pt: PointF) {
        val pageLinks = links[hit.page] ?: withContext(PdfDispatchers.pdf) { engine.links(d, hit.page) }.also { links[hit.page] = it }
        val slack = 4f * ptPerPx() * ctx.resources.displayMetrics.density
        pageLinks.firstOrNull { RectF(it.rect).apply { inset(-slack, -slack) }.contains(pt.x, pt.y) }?.let { link ->
            if (link.destPage >= 0) goToPage(link.destPage)
            else link.uri?.let { u ->
                // Same rule as desktop: only http, https and mailto, always confirmed.
                val scheme = Uri.parse(u).scheme?.lowercase()
                if (scheme in setOf("http", "https", "mailto")) externalLink = u else message = "Blocked link: $u"
            }
            return
        }
        val field = withContext(PdfDispatchers.pdf) { engine.formFieldAt(d, hit.page, pt) }
        if (field != null) { handleField(d, hit.page, field); return }
        annotationAt(d, hit.page, pt)?.let { a ->
            val contents = withContext(PdfDispatchers.pdf) { engine.annotationContents(d, hit.page, a.index) }
            annotPopup = AnnotPopup(hit.page, a, contents)
        }
    }

    private suspend fun annotationAt(d: DocId, page: Int, pt: PointF): AnnotInfo? {
        val slack = 10f * ptPerPx() * ctx.resources.displayMetrics.density
        val all = withContext(PdfDispatchers.pdf) { engine.annotations(d, page) }
        return all.lastOrNull { it.type in Deletable && RectF(it.rect).apply { inset(-slack, -slack) }.contains(pt.x, pt.y) }
    }

    // ------------------------------------------------------------------ forms (plan 11)

    private suspend fun handleField(d: DocId, page: Int, f: FormField) {
        if (f.readOnly) { message = "This field is read-only"; return }
        when (f.type) {
            FieldType.Text, FieldType.ComboBox, FieldType.ListBox -> formEdit = FormEdit(page, f)
            FieldType.CheckBox, FieldType.RadioButton, FieldType.PushButton -> {
                if (withContext(PdfDispatchers.pdf) { engine.formClick(d, page, f.tapPoint) }) formChanged()
            }
            FieldType.Signature -> message = "Certificate signatures are not supported. Use the Signature tool to place a drawn signature."
            FieldType.Unknown -> Unit
        }
    }

    fun commitText(edit: FormEdit, text: String) {
        formEdit = null
        viewModelScope.launch {
            val d = doc ?: return@launch
            val ok = withContext(PdfDispatchers.pdf) { engine.formSetText(d, edit.page, edit.field.tapPoint, text) }
            if (ok) formChanged() else message = "The field could not be changed"
        }
    }

    fun commitOption(edit: FormEdit, option: Int, selected: Boolean = true) {
        formEdit = null
        viewModelScope.launch {
            val d = doc ?: return@launch
            if (withContext(PdfDispatchers.pdf) { engine.formSelectOption(d, edit.page, edit.field.tapPoint, option, selected) }) formChanged()
        }
    }

    private fun formChanged() {
        dirty = true
        docVersion++
        pageTexts.clear()
    }

    // ------------------------------------------------------------------ drawing tools

    val inputMode: InputMode get() = if (tool in setOf(Tool.Ink, Tool.Rect, Tool.Ellipse)) InputMode.Draw else InputMode.Navigate

    fun onDrawStart(hit: PageHit, screen: Offset) {
        strokePage = hit.page
        when (tool) {
            Tool.Ink -> { stroke.clear(); stroke += screen }
            Tool.Rect, Tool.Ellipse -> { shapeStart = screen; shapeEnd = screen }
            else -> Unit
        }
        viewModelScope.launch { transformFor(hit.page) }
    }

    fun onDrawMove(screen: Offset) {
        when (tool) {
            Tool.Ink -> if (stroke.isEmpty() || (stroke.last() - screen).getDistance() > 1.5f) stroke += screen
            Tool.Rect, Tool.Ellipse -> shapeEnd = screen
            else -> Unit
        }
    }

    fun onDrawCancel() {
        stroke.clear(); shapeStart = null; shapeEnd = null; strokePage = -1
    }

    fun onDrawEnd() {
        val l = layout ?: return onDrawCancel()
        val page = strokePage
        if (page < 0) return onDrawCancel()
        val pts = stroke.toList()
        val a = shapeStart
        val b = shapeEnd
        val currentTool = tool
        val st = style
        val author = settings.authorName.ifBlank { null }
        viewModelScope.launch {
            val t = transformFor(page) ?: return@launch onDrawCancel()
            fun toPagePt(o: Offset): PointF {
                val h = viewport.hitClamped(l, o, page)
                return t.toPage(h.nx, h.ny)
            }
            when (currentTool) {
                Tool.Ink -> {
                    val smoothed = smooth(pts).map(::toPagePt)
                    if (smoothed.size >= 1) {
                        val line = if (smoothed.size == 1) listOf(smoothed[0], PointF(smoothed[0].x + 0.5f, smoothed[0].y)) else smoothed
                        create(page) { d -> engine.addInk(d, page, listOf(line), st.argb, st.widthPt, author) }
                    }
                }
                Tool.Rect, Tool.Ellipse -> if (a != null && b != null && (a - b).getDistance() > 8f) {
                    val p1 = toPagePt(a)
                    val p2 = toPagePt(b)
                    val r = RectF(min(p1.x, p2.x), min(p1.y, p2.y), max(p1.x, p2.x), max(p1.y, p2.y))
                    val type = if (currentTool == Tool.Rect) AnnotType.Square else AnnotType.Circle
                    create(page) { d -> engine.addShape(d, page, type, r, st.argb, 0, st.widthPt, author) }
                }
                else -> Unit
            }
            onDrawCancel()
        }
    }

    /** Chaikin corner cutting: a cheap smoothing pass before saving the stroke. */
    private fun smooth(p: List<Offset>): List<Offset> {
        if (p.size < 3) return p
        val out = ArrayList<Offset>(p.size * 2)
        out += p.first()
        for (i in 0 until p.size - 1) {
            val a = p[i]; val b = p[i + 1]
            out += a * 0.75f + b * 0.25f
            out += a * 0.25f + b * 0.75f
        }
        out += p.last()
        return out
    }

    fun addNote(pl: PendingPlacement, text: String) {
        placement = null
        if (text.isBlank()) return
        val author = settings.authorName.ifBlank { null }
        create(pl.page) { d -> engine.addNote(d, pl.page, pl.at, text, 0xFFFFC107.toInt(), author) }
    }

    fun addTextBox(pl: PendingPlacement, text: String, fontSize: Float) {
        placement = null
        val lines = text.lines().map { it.trimEnd() }
        if (lines.all { it.isBlank() }) return
        val w = (lines.maxOf { it.length } * fontSize * 0.56f + fontSize).coerceAtLeast(fontSize * 2)
        val h = lines.size * fontSize * 1.15f + fontSize * 0.5f
        val r = RectF(pl.at.x, pl.at.y - h, pl.at.x + w, pl.at.y)
        val author = settings.authorName.ifBlank { null }
        val color = style.color or 0xFF000000.toInt()
        create(pl.page) { d -> engine.addTextStamp(d, pl.page, r, lines, fontSize, color, author) }
    }

    fun saveSignature(bitmap: Bitmap, placeAt: PendingPlacement?) {
        placement = null
        viewModelScope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                signatureFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            if (placeAt != null) placeSignature(placeAt.page, placeAt.at)
        }
    }

    private fun placeSignature(page: Int, at: PointF) {
        val bmp = BitmapFactory.decodeFile(signatureFile.path) ?: return
        val w = 160f
        val h = w * bmp.height / bmp.width
        val r = RectF(at.x - w / 2, at.y - h / 2, at.x + w / 2, at.y + h / 2)
        val author = settings.authorName.ifBlank { null }
        create(page) { d -> engine.addImageStamp(d, page, r, bmp, author) }
    }

    // ------------------------------------------------------------------ edit history

    /** Runs an annotation creation and records it for undo. */
    private fun create(page: Int, op: (DocId) -> Boolean) {
        viewModelScope.launch {
            val d = doc ?: return@launch
            val name = withContext(PdfDispatchers.pdf) {
                if (op(d)) engine.annotations(d, page).lastOrNull { it.isOwn }?.name else null
            }
            if (name == null) { message = "The annotation could not be added"; return@launch }
            undoStack.addLast(Edit.Created(page, name) { doc?.let(op) ?: false })
            clearRedo()
            edited(structuralChange = false)
        }
    }

    fun deleteAnnotation(page: Int, a: AnnotInfo) {
        annotPopup = null
        viewModelScope.launch {
            val d = doc ?: return@launch
            snapshot(structural = false)
            val ok = withContext(PdfDispatchers.pdf) {
                // Re-find by name/rect: indices shift if anything changed meanwhile.
                val list = engine.annotations(d, page)
                val idx = list.indexOfFirst { if (a.name.isNotEmpty()) it.name == a.name else it.rect == a.rect && it.type == a.type }
                idx >= 0 && engine.removeAnnotation(d, page, idx)
            }
            if (ok) edited(false) else dropLastSnapshot()
        }
    }

    private suspend fun snapshot(structural: Boolean) {
        val d = doc ?: return
        val f = withContext(PdfDispatchers.pdf) { SafeSave.writeVerified(ctx, engine, d) }
        undoStack.addLast(Edit.Snapshot(f, structural))
        clearRedo()
        trimHistory()
    }

    private fun dropLastSnapshot() {
        (undoStack.removeLastOrNull() as? Edit.Snapshot)?.file?.delete()
        updateUndoFlags()
    }

    private fun trimHistory() {
        while (undoStack.size > MAX_HISTORY) (undoStack.removeFirst() as? Edit.Snapshot)?.file?.delete()
        updateUndoFlags()
    }

    private fun clearRedo() {
        redoStack.forEach { (it as? Edit.Snapshot)?.file?.delete() }
        redoStack.clear()
        updateUndoFlags()
    }

    private fun updateUndoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    fun undo() = step(undoStack, redoStack)
    fun redo() = step(redoStack, undoStack)

    private fun step(from: ArrayDeque<Edit>, to: ArrayDeque<Edit>) {
        val e = from.removeLastOrNull() ?: return
        viewModelScope.launch {
            val d = doc ?: return@launch
            when (e) {
                is Edit.Created -> {
                    val exists = withContext(PdfDispatchers.pdf) { engine.annotations(d, e.page).any { it.name == e.name } }
                    if (exists) {
                        withContext(PdfDispatchers.pdf) {
                            val idx = engine.annotations(d, e.page).indexOfFirst { it.name == e.name }
                            if (idx >= 0) engine.removeAnnotation(d, e.page, idx)
                        }
                        to.addLast(e)
                    } else {
                        val name = withContext(PdfDispatchers.pdf) {
                            if (e.redo()) engine.annotations(d, e.page).lastOrNull { it.isOwn }?.name else null
                        }
                        if (name != null) to.addLast(Edit.Created(e.page, name, e.redo))
                    }
                    edited(false)
                }
                is Edit.Snapshot -> {
                    val current = withContext(PdfDispatchers.pdf) { SafeSave.writeVerified(ctx, engine, d) }
                    to.addLast(Edit.Snapshot(current, e.structural))
                    swapTo(e.file)
                    edited(e.structural)
                }
            }
            updateUndoFlags()
        }
    }

    /** Replaces the open document with [file] (an undo snapshot or a verified save). */
    private suspend fun swapTo(file: File) {
        val newPfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = withContext(PdfDispatchers.pdf) { engine.open(newPfd) }
        if (r !is OpenResult.Ok) {
            newPfd.close()
            message = "Could not restore the document state"
            return
        }
        val old = doc
        renderer.cancelAll()
        withContext(PdfDispatchers.pdf) {
            old?.let { engine.close(it) }
            engine.setFormHighlight(r.doc, settings.highlightFields)
        }
        pfd?.close()
        pfd = newPfd
        sourceFile?.takeIf { it != file }?.delete()
        sourceFile = file
        doc = r.doc
        info = r.info
        renderer.clearAll()
        reloadStructure()
    }

    private suspend fun edited(structuralChange: Boolean) {
        dirty = true
        if (structuralChange) {
            structural = true
            renderer.clearAll()
            reloadStructure()
        } else {
            pageTexts.clear()
        }
        clearSelection()
        docVersion++
        updateUndoFlags()
    }

    // ------------------------------------------------------------------ page tools (plan 9)

    private fun pageOp(label: String, op: suspend (DocId) -> Boolean) {
        viewModelScope.launch {
            val d = doc ?: return@launch
            busy = Busy(label)
            try {
                snapshot(structural = true)
                if (op(d)) edited(true) else { dropLastSnapshot(); message = "$label failed" }
            } catch (e: Exception) {
                message = e.message ?: "$label failed"
            } finally {
                busy = null
            }
        }
    }

    fun rotatePages(pages: Collection<Int>, delta: Int) = pageOp("Rotate") { d ->
        withContext(PdfDispatchers.pdf) { pages.forEach { engine.setRotation(d, it, engine.rotation(d, it) + delta) } }
        true
    }

    fun deletePages(pages: Collection<Int>) {
        if (pages.size >= pageSizes.size) { message = "A PDF must keep at least one page"; return }
        pageOp("Delete") { d -> withContext(PdfDispatchers.pdf) { engine.deletePages(d, pages.toIntArray()) } }
    }

    /** Moves the pages (in document order) so the first lands at [dest] (index in the result). */
    fun movePages(pages: Collection<Int>, dest: Int) = pageOp("Move") { d ->
        val sorted = pages.sorted().toIntArray()
        val target = dest.coerceIn(0, pageSizes.size - sorted.size)
        withContext(PdfDispatchers.pdf) { engine.movePages(d, sorted, target) }
    }

    fun duplicatePages(pages: Collection<Int>) = pageOp("Duplicate") { d ->
        val sorted = pages.sorted()
        withContext(PdfDispatchers.pdf) {
            withTempCopy(d) { src -> engine.importPages(d, src, sorted.toIntArray(), sorted.last() + 1) }
        }
    }

    fun insertBlank(after: Int) = pageOp("Insert page") { d ->
        val ref = pageSizes.getOrNull(after.coerceAtLeast(0)) ?: PageSize(595.28f, 841.89f)
        withContext(PdfDispatchers.pdf) { engine.newPage(d, after + 1, ref.width, ref.height) }
    }

    fun insertFromPdf(source: Uri, at: Int, password: String? = null) = pageOp("Insert pages") { d ->
        withContext(PdfDispatchers.pdf) {
            val fd = ctx.contentResolver.openFileDescriptor(source, "r") ?: return@withContext false
            fd.use {
                when (val r = engine.open(it, password)) {
                    is OpenResult.Ok -> try { engine.importPages(d, r.doc, IntArray(0), at) } finally { engine.close(r.doc) }
                    OpenResult.NeedsPassword, OpenResult.WrongPassword -> throw IllegalStateException("That PDF is password-protected; open and save it without a password first.")
                    else -> false
                }
            }
        }
    }

    /** Crops by fractions of the displayed page (left, top, right, bottom). */
    fun cropPages(pages: Collection<Int>, l: Float, t: Float, r: Float, b: Float) = pageOp("Crop") { d ->
        withContext(PdfDispatchers.pdf) {
            for (p in pages) {
                val tr = engine.transform(d, p, 0) ?: continue
                val box = tr.toPage(RectF(l, t, 1 - r, 1 - b))
                engine.setCropBox(d, p, box)
            }
            true
        }
    }

    /** Opens a verified temp copy of [d] as a second document for self-imports. */
    private fun <T> withTempCopy(d: DocId, block: (DocId) -> T): T {
        val tmp = SafeSave.writeVerified(ctx, engine, d)
        try {
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val r = engine.open(fd) as? OpenResult.Ok ?: throw IllegalStateException("Copy failed")
                try { return block(r.doc) } finally { engine.close(r.doc) }
            }
        } finally {
            tmp.delete()
        }
    }

    /** Writes the given pages to a new PDF at [dest] (extract / split). */
    suspend fun writePages(pages: List<Int>, dest: Uri) {
        val d = doc ?: return
        withContext(PdfDispatchers.pdf) {
            val out = engine.create()
            try {
                withTempCopy(d) { src -> check(engine.importPages(out, src, pages.toIntArray(), 0)) { "Copying pages failed" } }
                val f = SafeSave.writeVerified(ctx, engine, out)
                try { SafeSave.copyTo(ctx, f, dest) } finally { f.delete() }
            } finally {
                engine.close(out)
            }
        }
    }

    fun runBusy(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = Busy(label)
            try { block() } catch (e: Exception) { message = e.message ?: "$label failed" } finally { busy = null }
        }
    }

    // ------------------------------------------------------------------ OCR (plan 8.5)

    fun makeSearchable() {
        val d = doc ?: return
        val job = viewModelScope.launch {
            try {
                val pages = ocr.pagesWithoutText(d)
                if (pages.isEmpty()) { message = "Every page already contains text."; return@launch }
                busy = Busy("Recognising text…", 0f, coroutineContext[Job])
                snapshot(structural = false)
                val langs = settings.ocrLanguages
                val summary = ocr.makeSearchable(d, pages, langs) { done, total ->
                    busy = busy?.copy(progress = done.toFloat() / total, label = "Recognising text… page ${(done + 1).coerceAtMost(total)} of $total")
                }
                edited(false)
                message = buildString {
                    append("Added searchable text to ${summary.pages} page(s).")
                    if (summary.lowConfidencePages.isNotEmpty())
                        append(" Low recognition confidence on page(s) ${summary.lowConfidencePages.joinToString { (it + 1).toString() }}.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = "OCR cancelled"
                throw e
            } catch (e: Exception) {
                message = e.message ?: "OCR failed"
            } finally {
                busy = null
            }
        }
        busy = Busy("Recognising text…", 0f, job)
    }

    fun cancelBusy() { busy?.job?.cancel() }

    // ------------------------------------------------------------------ saving (plan 7.4 / 10.5)

    /**
     * Whether "Save" may overwrite the original. Probed once in the background:
     * opening a provider descriptor can be slow (cloud providers may download).
     */
    var canSaveInPlace by mutableStateOf(false)
        private set

    private fun refreshWritable() {
        val target = uri
        viewModelScope.launch {
            canSaveInPlace = withContext(kotlinx.coroutines.Dispatchers.IO) { SafeSave.canWrite(ctx, target) }
        }
    }

    /** Saves over the original after verification. Incremental unless pages were restructured. */
    fun save(onDone: () -> Unit = {}) = runBusy("Saving…") {
        val d = doc ?: return@runBusy
        if (!dirty) { message = "No changes to save"; onDone(); return@runBusy }
        val tmp = withContext(PdfDispatchers.pdf) { SafeSave.writeVerified(ctx, engine, d, incremental = !structural) }
        // Switch to the verified copy first: PDFium must not read the original while it is overwritten.
        swapTo(tmp)
        withContext(kotlinx.coroutines.Dispatchers.IO) { SafeSave.copyTo(ctx, tmp, uri) }
        markSaved()
        message = "Saved"
        onDone()
    }

    /** "Save a copy": the copy becomes the working file. Unchanged files are copied byte-for-byte. */
    fun saveAs(dest: Uri, onDone: () -> Unit = {}) = runBusy("Saving…") {
        val d = doc ?: return@runBusy
        if (!dirty && sourceFile == null) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { SafeSave.copyUri(ctx, uri, dest) }
        } else {
            val tmp = withContext(PdfDispatchers.pdf) { SafeSave.writeVerified(ctx, engine, d) }
            swapTo(tmp)
            withContext(kotlinx.coroutines.Dispatchers.IO) { SafeSave.copyTo(ctx, tmp, dest) }
        }
        uri = dest
        name = UriUtils.meta(ctx, dest).name
        refreshWritable()
        app().container.recents.add(dest, name, isPdf = true, persistable = true)
        markSaved()
        message = "Saved as $name"
        onDone()
    }

    /** Burns annotations and form fields into the page content (FPDFPage_Flatten). */
    fun saveFlattened(dest: Uri) = runBusy("Saving flattened copy…") {
        val d = doc ?: return@runBusy
        withContext(PdfDispatchers.pdf) {
            withTempCopy(d) { copy ->
                for (p in 0 until engine.pageCount(copy)) engine.flattenPage(copy, p)
                val f = SafeSave.writeVerified(ctx, engine, copy)
                try { SafeSave.copyTo(ctx, f, dest) } finally { f.delete() }
            }
        }
        message = "Flattened copy saved"
    }

    /** A file for share/print: the original bytes if unchanged, else a verified save. */
    suspend fun exportFile(): File {
        val d = doc ?: throw IllegalStateException("No document")
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, UriUtils.sanitizeFileName(name).let { if (it.endsWith(".pdf", true)) it else "$it.pdf" })
        if (!dirty && sourceFile == null) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        } else {
            val tmp = withContext(PdfDispatchers.pdf) { SafeSave.writeVerified(ctx, engine, d) }
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        return out
    }

    private fun markSaved() {
        dirty = false
        structural = false
    }

    fun discardChanges() {
        dirty = false
    }

    override fun onCleared() {
        renderer.cancelAll()
        val d = doc
        val p = pfd
        val files = (undoStack + redoStack).mapNotNull { (it as? Edit.Snapshot)?.file } + listOfNotNull(sourceFile)
        doc = null
        kotlinx.coroutines.CoroutineScope(PdfDispatchers.pdf).launch {
            d?.let { engine.close(it) }
            p?.close()
            files.forEach { it.delete() }
        }
    }

    companion object {
        private const val MAX_HISTORY = 30

        private fun cacheBytes(app: Application): Int {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mb = if (am.isLowRamDevice) am.memoryClass / 4 else am.largeMemoryClass / 3
            return mb.coerceIn(32, 256) * 1024 * 1024
        }

        fun factory(app: Application, uri: Uri, fromPicker: Boolean) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ViewerViewModel(app, uri, fromPicker) as T
        }
    }
}

