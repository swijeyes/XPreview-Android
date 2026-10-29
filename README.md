# XPreview+ for Android (XPreview-Android)

A light weight PDF/image viewer and scanner for Android.

Native Kotlin + Jetpack Compose port of [XPreview+](https://github.com/swijeyes/XPreview-Plus),
focused on PDFs and images (ODF/RTF dropped). PDFium (same engine as desktop), CameraX + OpenCV
scanner, on-device Tesseract OCR. No network permission, no analytics. AGPL-3.0-or-later.

Standalone repository; it can also be dropped into the desktop repo as `android/` (see below).

## Build

Requirements: JDK 17, Android SDK (platform 36), NDK 28.2.13676358, CMake 3.22.1.

```sh
scripts/fetch-android-deps.sh      # PDFium, tessdata, fonts — pinned + SHA-256 verified
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # JVM unit tests
./gradlew assembleRelease          # per-ABI APKs (arm64-v8a, x86_64), R8-minified
./gradlew bundleRelease            # AAB for Play
```

Release signing reads `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword);
without it release APKs are unsigned.

## Modules

| Module | Contents |
|---|---|
| `:core-common` | Limits (mirrors desktop `Limits.h`, parity-tested), content-based format sniffing, URI helpers |
| `:core-pdf` | `PdfEngine` interface + `PdfiumEngine`; C++20 JNI layer `src/main/cpp/jni_pdfium.cpp` |
| `:core-image` | Safe decoding (ImageDecoder with pixel budget, AndroidSVG with no external resolver) |
| `:core-vision` | OpenCV pipeline: edge detection, perspective warp, enhancement filters; pure geometry |
| `:core-ocr` | Tesseract wrapper, language manager (import + validation) |
| `:app` | Compose UI: home, viewer, organizer, annotations, forms, images, merge, scanner, settings |

Feature modules from the plan (`:feature-*`) are packages inside `:app` for now; the native
code lives in `:core-pdf` rather than a separate `:native` module.

See `docs/status.md` for what is implemented, spike results, and what is left.

## Repository layout

This folder is self-contained and can be pushed as its own repository:

```sh
git init -b main && git add -A && git commit -m "Initial import"
git remote add origin git@github.com:<you>/XPreview-Plus-Android.git && git push -u origin main
```

Not committed (see `.gitignore`): PDFium binaries, tessdata and fonts (re-fetched by
`scripts/fetch-android-deps.sh`), build output, `local.properties`, signing keys.

CI (`.github/workflows/android.yml`) runs the fetch script, unit tests and `assembleDebug`,
and uploads the debug APK. If this folder instead goes into the desktop repo as `android/`,
move the workflow to the repo root's `.github/workflows/` and add
`defaults: { run: { working-directory: android } }` plus the `android/` prefix on the
artifact path.
