# Status and Phase 0 spike results (2026-10-28)

## Spikes
- **S1 PDFium on Android — GO.** bblanchon/pdfium-binaries `chromium/8100` (arm64-v8a, x86_64).
  All `LOAD` segments are 16 KB aligned (also OpenCV 4.12.0 and Tesseract4Android 4.8.0).
  Every function in plan Appendix A is exported. Verified on an Android 16 emulator: rendering,
  text search, links, ink annotation creation.
- **S2 Forms** — implemented with field-at-point + native dialogs (`FORM_*` click / SelectAllText /
  ReplaceSelection / SetIndexSelected). Not yet tested against a real AcroForm corpus.
- **S3 OCR text layer — method (a)** (invisible PDFium text objects) chosen. Uses the standard
  Helvetica font, so non-Latin-1 text is not searchable yet; method (b) or a glyphless CID font
  is the follow-up. OCR itself verified on-device (English, tessdata_fast).
- **S4 edge detection dataset, S5 isolated-process latency, S6 OCR timing on hardware** — not run
  (need a photo set and physical devices).

## Decisions taken
- DI: a small manual container (`AppContainer`) instead of Hilt/Koin.
- minSdk 28, target/compile 36, ABIs arm64-v8a + x86_64 (no armeabi-v7a).
- PDFium calls are serialised on one thread behind a global native lock (PDFium has process-wide
  state, so per-document parallelism is not safe).
- Undo: annotation creation is undone by /NM; deletions and page operations use verified
  snapshot files. Form edits are not on the undo stack.
- Tesseract4Android 4.8.0 (4.9.0 fails to build on JitPack).
- No INTERNET permission: extra OCR languages are imported as `.traineddata` files.

## Implemented (by plan phase)
1. Viewer: SAF + VIEW/SEND intents, magic-byte sniffing, password prompt, typed errors, tiled
   rendering with LRU cache and request cancellation, pinch/double-tap/fling, fit width/page,
   rotation, page jump, recents with persisted permissions.
2. Search, text selection + copy, links (http/https/mailto with confirmation), outline,
   thumbnails (side panel ≥ 840 dp, sheet on phones), document info, dark pages, keyboard and
   mouse-wheel support, open in new window; image viewer (PNG/JPEG/GIF/WebP/BMP/HEIC/SVG),
   swipeable sets.
3. Scanner: CameraX, live detection overlay (Canny → Otsu fallback), smoothing, steady/sharp
   gating, auto-capture with re-arm, hints, torch, batch, gallery import, re-detection after
   capture, corner editor with loupe, 5 filters + brightness/contrast/rotate, sessions that
   survive process death, 7-day cleanup.
4. Images/scans → PDF with JPEG pass-through (EXIF orientation → /Rotate, EXIF/GPS stripped),
   A4/Letter/fit, margins, quality presets; verified temp-file saves; share; print.
5. OCR: "Make searchable" for text-less pages, OCR in export flows, copy text from image,
   low-confidence warning, language manager.
6. Page tools: rotate, delete, duplicate, move, blank page, insert PDF, extract, split (every N /
   ranges), merge, crop (margins).
7. Annotations: highlight/underline/strikeout from selection, ink, rectangle, ellipse, notes,
   text stamps, drawn signature stamps, eraser, popup with delete, undo/redo, save, save a copy,
   flattened copy.
8. Forms: text, checkbox, radio, combo, list (multi-select); field highlight toggle; XFA and
   JavaScript notices.

## Not done yet
- Phase 9 sandbox (isolatedProcess PdfEngine), fuzzing, Macrobenchmark/Baseline Profiles.
- WorkManager/foreground service for long OCR jobs (runs in the ViewModel today).
- TIFF; Line/arrow tool; moving/resizing annotations; annotation list panel; drag-and-drop reorder
  (buttons are used instead); edge nudging in the corner editor; autosave recovery copy;
  "reduce file size"; Hough-line detection fallback (strategy C).
- String resources / translations (UI strings are inline English), full TalkBack audit.
- Interop matrix (Acrobat/Chrome/Preview/Okular), golden-image tests vs desktop, device matrix.
- OpenCV is the full 23 MB build; a trimmed core/imgproc build would cut ~15 MB per ABI.
  Current arm64 release APK: 53 MB on disk, ~27 MB compressed download.
