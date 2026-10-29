// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sanjeeva Wijeyesakere
//
// JNI bridge to PDFium. Kotlin never sees PDFium handles: documents are
// opaque integer ids, pages are indices (plan 2.4). Every entry point takes
// one global lock because PDFium keeps process-wide state and is not
// thread-safe, even across documents.

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include "fpdf_annot.h"
#include "fpdf_doc.h"
#include "fpdf_edit.h"
#include "fpdf_flatten.h"
#include "fpdf_formfill.h"
#include "fpdf_javascript.h"
#include "fpdf_ppo.h"
#include "fpdf_save.h"
#include "fpdf_text.h"
#include "fpdf_transformpage.h"
#include "fpdfview.h"
#include "limits.h"

#define LOG_TAG "xpreview-pdf"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define JNI_FN(name) extern "C" JNIEXPORT auto JNICALL Java_org_xpreview_pdf_PdfiumNative_##name

namespace {

std::mutex gLock;
bool gInitialized = false;
std::vector<std::string> gFontPaths;
std::vector<const char*> gFontPathPtrs;

constexpr size_t kMaxCachedPages = 8;
constexpr int kMaxOutlineItems = 20000;
constexpr int kMaxOutlineDepth = 64;
constexpr unsigned kHighlightColor = 0xFFE4DD;

struct CachedPage {
    FPDF_PAGE page = nullptr;
    FPDF_TEXTPAGE text = nullptr;
    uint64_t lastUse = 0;
};

struct Document {
    FPDF_DOCUMENT doc = nullptr;
    int fd = -1;
    FPDF_FILEACCESS access{};
    FPDF_FORMFILLINFO formInfo{};
    FPDF_FORMHANDLE form = nullptr;
    std::map<int, CachedPage> pages;
    uint64_t tick = 0;

    void closePage(int index) {
        auto it = pages.find(index);
        if (it == pages.end()) return;
        if (it->second.text) FPDFText_ClosePage(it->second.text);
        if (form) FORM_OnBeforeClosePage(it->second.page, form);
        FPDF_ClosePage(it->second.page);
        pages.erase(it);
    }
    void closeAllPages() {
        if (form) FORM_ForceToKillFocus(form);
        while (!pages.empty()) closePage(pages.begin()->first);
    }
    ~Document() {
        closeAllPages();
        if (form) FPDFDOC_ExitFormFillEnvironment(form);
        if (doc) FPDF_CloseDocument(doc);
        if (fd >= 0) close(fd);
    }
};

std::map<jlong, std::unique_ptr<Document>> gDocs;
jlong gNextId = 1;

Document* findDoc(jlong id) {
    auto it = gDocs.find(id);
    return it == gDocs.end() ? nullptr : it->second.get();
}

FPDF_PAGE getPage(Document* d, int index) {
    if (!d || index < 0 || index >= FPDF_GetPageCount(d->doc)) return nullptr;
    auto it = d->pages.find(index);
    if (it != d->pages.end()) {
        it->second.lastUse = ++d->tick;
        return it->second.page;
    }
    if (d->pages.size() >= kMaxCachedPages) {
        auto lru = std::min_element(d->pages.begin(), d->pages.end(), [](auto& a, auto& b) {
            return a.second.lastUse < b.second.lastUse;
        });
        if (d->form) FORM_ForceToKillFocus(d->form);
        d->closePage(lru->first);
    }
    FPDF_PAGE p = FPDF_LoadPage(d->doc, index);
    if (!p) return nullptr;
    if (d->form) FORM_OnAfterLoadPage(p, d->form);
    d->pages[index] = CachedPage{p, nullptr, ++d->tick};
    return p;
}

FPDF_TEXTPAGE getText(Document* d, int index) {
    if (!getPage(d, index)) return nullptr;
    CachedPage& cp = d->pages[index];
    if (!cp.text) cp.text = FPDFText_LoadPage(cp.page);
    return cp.text;
}

// The text page is a snapshot; drop it after the page content changes.
void invalidateText(Document* d, int index) {
    auto it = d->pages.find(index);
    if (it != d->pages.end() && it->second.text) {
        FPDFText_ClosePage(it->second.text);
        it->second.text = nullptr;
    }
}

int readBlock(void* param, unsigned long pos, unsigned char* buf, unsigned long size) {
    const int fd = static_cast<Document*>(param)->fd;
    unsigned long done = 0;
    while (done < size) {
        ssize_t r = pread(fd, buf + done, size - done, static_cast<off_t>(pos + done));
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return 0;
        done += static_cast<unsigned long>(r);
    }
    return 1;
}

struct FdWriter : FPDF_FILEWRITE {
    int fd = -1;
    bool ok = true;
};

int writeBlock(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
    auto* w = static_cast<FdWriter*>(self);
    const auto* p = static_cast<const unsigned char*>(data);
    unsigned long done = 0;
    while (done < size) {
        ssize_t r = write(w->fd, p + done, size - done);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) { w->ok = false; return 0; }
        done += static_cast<unsigned long>(r);
    }
    return 1;
}

struct MemReader {
    const unsigned char* data;
    unsigned long size;
};

int memBlock(void* param, unsigned long pos, unsigned char* buf, unsigned long size) {
    auto* m = static_cast<MemReader*>(param);
    if (pos > m->size || size > m->size - pos) return 0;
    memcpy(buf, m->data + pos, size);
    return 1;
}

std::u16string toU16(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jsize len = env->GetStringLength(s);
    std::u16string out(static_cast<size_t>(len), u'\0');
    env->GetStringRegion(s, 0, len, reinterpret_cast<jchar*>(out.data()));
    return out;
}

FPDF_WIDESTRING wide(const std::u16string& s) {
    return reinterpret_cast<FPDF_WIDESTRING>(s.c_str());
}

jstring fromU16(JNIEnv* env, const unsigned short* buf, size_t len) {
    while (len > 0 && buf[len - 1] == 0) --len;
    return env->NewString(reinterpret_cast<const jchar*>(buf), static_cast<jsize>(len));
}

// Reads a PDFium "returns byte length of UTF-16LE incl. terminator" getter.
template <typename F>
jstring readWide(JNIEnv* env, F&& getter) {
    unsigned long bytes = getter(nullptr, 0);
    if (bytes <= 2 || bytes > (1u << 24)) return env->NewStringUTF("");
    std::vector<unsigned short> buf(bytes / 2 + 1, 0);
    getter(buf.data(), bytes);
    return fromU16(env, buf.data(), bytes / 2);
}

bool finiteRect(float l, float b, float r, float t) {
    return std::isfinite(l) && std::isfinite(b) && std::isfinite(r) && std::isfinite(t);
}

jfloatArray toFloatArray(JNIEnv* env, const std::vector<float>& v) {
    jfloatArray a = env->NewFloatArray(static_cast<jsize>(v.size()));
    if (a && !v.empty()) env->SetFloatArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return a;
}

jintArray toIntArray(JNIEnv* env, const std::vector<int>& v) {
    jintArray a = env->NewIntArray(static_cast<jsize>(v.size()));
    if (a && !v.empty()) env->SetIntArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return a;
}

std::vector<int> fromIntArray(JNIEnv* env, jintArray a) {
    std::vector<int> v(a ? static_cast<size_t>(env->GetArrayLength(a)) : 0);
    if (!v.empty()) env->GetIntArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}

std::vector<float> fromFloatArray(JNIEnv* env, jfloatArray a) {
    std::vector<float> v(a ? static_cast<size_t>(env->GetArrayLength(a)) : 0);
    if (!v.empty()) env->GetFloatArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}

jobjectArray toStringArray(JNIEnv* env, const std::vector<jstring>& v) {
    jclass cls = env->FindClass("java/lang/String");
    jobjectArray a = env->NewObjectArray(static_cast<jsize>(v.size()), cls, nullptr);
    for (size_t i = 0; i < v.size(); ++i) {
        env->SetObjectArrayElement(a, static_cast<jsize>(i), v[i]);
        if (v[i]) env->DeleteLocalRef(v[i]);
    }
    return a;
}

jobjectArray objectArray(JNIEnv* env, std::initializer_list<jobject> items) {
    jclass cls = env->FindClass("java/lang/Object");
    jobjectArray a = env->NewObjectArray(static_cast<jsize>(items.size()), cls, nullptr);
    jsize i = 0;
    for (jobject o : items) env->SetObjectArrayElement(a, i++, o);
    return a;
}

void setColor(FPDF_ANNOTATION a, FPDFANNOT_COLORTYPE type, jint argb) {
    const auto u = static_cast<uint32_t>(argb);
    FPDFAnnot_SetColor(a, type, (u >> 16) & 0xff, (u >> 8) & 0xff, u & 0xff, (u >> 24) & 0xff);
}

void setCommonAnnot(JNIEnv* env, FPDF_ANNOTATION a, jstring nm, jstring author, jstring contents) {
    FPDFAnnot_SetFlags(a, FPDF_ANNOT_FLAG_PRINT);
    if (nm) FPDFAnnot_SetStringValue(a, "NM", wide(toU16(env, nm)));
    if (author) FPDFAnnot_SetStringValue(a, "T", wide(toU16(env, author)));
    if (contents) FPDFAnnot_SetStringValue(a, "Contents", wide(toU16(env, contents)));
}

// Copies an RGBA_8888 Android bitmap into a new BGR PDFium bitmap,
// compositing transparency onto white (or keeping alpha as BGRA).
FPDF_BITMAP bitmapFromAndroid(JNIEnv* env, jobject bitmap, bool keepAlpha) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return nullptr;
    if (static_cast<int64_t>(info.width) * info.height > Limits::kMaxRenderPixels) return nullptr;
    void* px = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &px) != ANDROID_BITMAP_RESULT_SUCCESS) return nullptr;
    const int w = static_cast<int>(info.width), h = static_cast<int>(info.height);
    FPDF_BITMAP out = FPDFBitmap_CreateEx(w, h, keepAlpha ? FPDFBitmap_BGRA : FPDFBitmap_BGR, nullptr, 0);
    if (out) {
        auto* dst = static_cast<unsigned char*>(FPDFBitmap_GetBuffer(out));
        const int dstStride = FPDFBitmap_GetStride(out);
        for (int y = 0; y < h; ++y) {
            const auto* s = static_cast<const unsigned char*>(px) + static_cast<size_t>(y) * info.stride;
            unsigned char* d = dst + static_cast<size_t>(y) * dstStride;
            for (int x = 0; x < w; ++x, s += 4) {
                // Android stores premultiplied RGBA.
                const unsigned a = s[3];
                if (keepAlpha) {
                    const auto un = [a](unsigned c) { return a ? std::min(255u, c * 255u / a) : 0u; };
                    *d++ = static_cast<unsigned char>(un(s[2]));
                    *d++ = static_cast<unsigned char>(un(s[1]));
                    *d++ = static_cast<unsigned char>(un(s[0]));
                    *d++ = static_cast<unsigned char>(a);
                } else {
                    const auto onWhite = [a](unsigned c) { return c + (255u - a); };
                    *d++ = static_cast<unsigned char>(onWhite(s[2]));
                    *d++ = static_cast<unsigned char>(onWhite(s[1]));
                    *d++ = static_cast<unsigned char>(onWhite(s[0]));
                }
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return out;
}

FPDF_PAGEOBJECT imageObjectFromJpeg(Document* d, FPDF_PAGE page, JNIEnv* env, jbyteArray jpeg) {
    const jsize n = env->GetArrayLength(jpeg);
    std::vector<unsigned char> bytes(static_cast<size_t>(n));
    env->GetByteArrayRegion(jpeg, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
    MemReader mem{bytes.data(), static_cast<unsigned long>(bytes.size())};
    FPDF_FILEACCESS access{};
    access.m_FileLen = mem.size;
    access.m_GetBlock = memBlock;
    access.m_Param = &mem;
    FPDF_PAGEOBJECT obj = FPDFPageObj_NewImageObj(d->doc);
    // "Inline" loads the data now, so |bytes| may go away afterwards.
    if (!obj || !FPDFImageObj_LoadJpegFileInline(&page, 1, obj, &access)) {
        if (obj) FPDFPageObj_Destroy(obj);
        return nullptr;
    }
    return obj;
}

}  // namespace

// ---------------------------------------------------------------- library

JNI_FN(nativeInit)(JNIEnv* env, jclass, jobjectArray fontDirs) -> void {
    std::lock_guard<std::mutex> lock(gLock);
    if (gInitialized) return;
    const jsize n = fontDirs ? env->GetArrayLength(fontDirs) : 0;
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(fontDirs, i));
        const char* c = env->GetStringUTFChars(s, nullptr);
        gFontPaths.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    for (auto& p : gFontPaths) gFontPathPtrs.push_back(p.c_str());
    gFontPathPtrs.push_back(nullptr);
    FPDF_LIBRARY_CONFIG config{};
    config.version = 2;
    config.m_pUserFontPaths = gFontPathPtrs.data();
    FPDF_InitLibraryWithConfig(&config);
    gInitialized = true;
}

// ---------------------------------------------------------------- documents

// Returns a document id (> 0) or -FPDF_ERR_* on failure.
JNI_FN(nativeOpen)(JNIEnv* env, jclass, jint fd, jstring password) -> jlong {
    std::lock_guard<std::mutex> lock(gLock);
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) return -FPDF_ERR_FILE;
    auto d = std::make_unique<Document>();
    d->fd = dup(fd);
    if (d->fd < 0) return -FPDF_ERR_FILE;
    d->access.m_FileLen = static_cast<unsigned long>(st.st_size);
    d->access.m_GetBlock = readBlock;
    d->access.m_Param = d.get();
    std::string pw;
    if (password) {
        const char* c = env->GetStringUTFChars(password, nullptr);
        pw = c;
        env->ReleaseStringUTFChars(password, c);
    }
    d->doc = FPDF_LoadCustomDocument(&d->access, password ? pw.c_str() : nullptr);
    if (!d->doc) {
        const unsigned long err = FPDF_GetLastError();
        return -static_cast<jlong>(err == FPDF_ERR_SUCCESS ? FPDF_ERR_UNKNOWN : err);
    }
    d->formInfo.version = 1;
    d->form = FPDFDOC_InitFormFillEnvironment(d->doc, &d->formInfo);
    if (d->form) {
        FPDF_SetFormFieldHighlightColor(d->form, FPDF_FORMFIELD_UNKNOWN, kHighlightColor);
        FPDF_SetFormFieldHighlightAlpha(d->form, 0);
    }
    const jlong id = gNextId++;
    gDocs[id] = std::move(d);
    return id;
}

JNI_FN(nativeCreate)(JNIEnv*, jclass) -> jlong {
    std::lock_guard<std::mutex> lock(gLock);
    auto d = std::make_unique<Document>();
    d->doc = FPDF_CreateNewDocument();
    if (!d->doc) return -FPDF_ERR_UNKNOWN;
    const jlong id = gNextId++;
    gDocs[id] = std::move(d);
    return id;
}

JNI_FN(nativeClose)(JNIEnv*, jclass, jlong id) -> void {
    std::lock_guard<std::mutex> lock(gLock);
    gDocs.erase(id);
}

JNI_FN(nativePageCount)(JNIEnv*, jclass, jlong id) -> jint {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    return d ? FPDF_GetPageCount(d->doc) : 0;
}

// [w0, h0, w1, h1, ...] in points, with /Rotate applied, sanitised.
JNI_FN(nativePageSizes)(JNIEnv* env, jclass, jlong id) -> jfloatArray {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    std::vector<float> out;
    if (d) {
        const int n = FPDF_GetPageCount(d->doc);
        out.reserve(static_cast<size_t>(n) * 2);
        for (int i = 0; i < n; ++i) {
            FS_SIZEF s{612, 792};
            if (!FPDF_GetPageSizeByIndexF(d->doc, i, &s) || !std::isfinite(s.width) ||
                !std::isfinite(s.height))
                s = {612, 792};
            const auto clamp = [](float v) {
                return static_cast<float>(std::clamp<double>(v, Limits::kMinPageSide, Limits::kMaxPageSide));
            };
            out.push_back(clamp(s.width));
            out.push_back(clamp(s.height));
        }
    }
    return toFloatArray(env, out);
}

// [fileVersion, securityRevision, formType, javascriptCount, permissions]
JNI_FN(nativeDocInfo)(JNIEnv* env, jclass, jlong id) -> jintArray {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    if (!d) return toIntArray(env, {});
    int version = 0;
    FPDF_GetFileVersion(d->doc, &version);
    return toIntArray(env, {version, FPDF_GetSecurityHandlerRevision(d->doc), FPDF_GetFormType(d->doc),
                            FPDFDoc_GetJavaScriptActionCount(d->doc),
                            static_cast<int>(FPDF_GetDocPermissions(d->doc))});
}

JNI_FN(nativeMetaText)(JNIEnv* env, jclass, jlong id, jstring tag) -> jstring {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    if (!d) return env->NewStringUTF("");
    const char* t = env->GetStringUTFChars(tag, nullptr);
    std::string key = t;
    env->ReleaseStringUTFChars(tag, t);
    return readWide(env, [&](void* buf, unsigned long len) {
        return FPDF_GetMetaText(d->doc, key.c_str(), buf, len);
    });
}

JNI_FN(nativeSave)(JNIEnv*, jclass, jlong id, jint fd, jboolean incremental) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    if (!d) return JNI_FALSE;
    if (d->form) FORM_ForceToKillFocus(d->form);
    FdWriter w;
    w.version = 1;
    w.WriteBlock = writeBlock;
    w.fd = fd;
    const bool ok = FPDF_SaveAsCopy(d->doc, &w, incremental ? FPDF_INCREMENTAL : FPDF_NO_INCREMENTAL);
    return ok && w.ok ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------- rendering

// flags: 1 = annotations, 2 = grayscale, 4 = form fields
JNI_FN(nativeRender)(JNIEnv* env, jclass, jlong id, jint index, jobject bitmap, jint srcX, jint srcY,
                     jfloat scale, jint rotation, jint flags) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    FPDF_PAGE page = getPage(d, index);
    if (!page || !(scale > 0) || !std::isfinite(scale)) return JNI_FALSE;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        static_cast<int64_t>(info.width) * info.height > Limits::kMaxRenderPixels)
        return JNI_FALSE;
    double pw = FPDF_GetPageWidthF(page) * scale, ph = FPDF_GetPageHeightF(page) * scale;
    if (rotation % 2) std::swap(pw, ph);
    if (!(pw >= 1 && ph >= 1 && pw < 1e7 && ph < 1e7)) return JNI_FALSE;

    void* px = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &px) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    const int w = static_cast<int>(info.width), h = static_cast<int>(info.height);
    FPDF_BITMAP bmp = FPDFBitmap_CreateEx(w, h, FPDFBitmap_BGRA, px, static_cast<int>(info.stride));
    bool ok = bmp != nullptr;
    if (ok) {
        FPDFBitmap_FillRect(bmp, 0, 0, w, h, 0xFFFFFFFF);
        int f = 0;
        if (flags & 1) f |= FPDF_ANNOT;
        if (flags & 2) f |= FPDF_GRAYSCALE;
        const int sx = static_cast<int>(std::lround(pw)), sy = static_cast<int>(std::lround(ph));
        FPDF_RenderPageBitmap(bmp, page, -srcX, -srcY, sx, sy, rotation & 3, f);
        if ((flags & 4) && d->form) FPDF_FFLDraw(d->form, bmp, page, -srcX, -srcY, sx, sy, rotation & 3, f);
        FPDFBitmap_Destroy(bmp);
        // PDFium wrote BGRA; Android expects RGBA.
        for (int y = 0; y < h; ++y) {
            auto* row = static_cast<unsigned char*>(px) + static_cast<size_t>(y) * info.stride;
            for (int x = 0; x < w; ++x) std::swap(row[x * 4], row[x * 4 + 2]);
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Maps normalised display coordinates to page space for a view rotation:
// page = origin + nx * ux + ny * uy. Returns [ox, oy, uxx, uxy, uyx, uyy].
JNI_FN(nativePageTransform)(JNIEnv* env, jclass, jlong id, jint index, jint rotation) -> jfloatArray {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_PAGE page = getPage(findDoc(id), index);
    if (!page) return toFloatArray(env, {});
    constexpr int S = 1 << 20;
    double x0, y0, x1, y1, x2, y2;
    FPDF_DeviceToPage(page, 0, 0, S, S, rotation & 3, 0, 0, &x0, &y0);
    FPDF_DeviceToPage(page, 0, 0, S, S, rotation & 3, S, 0, &x1, &y1);
    FPDF_DeviceToPage(page, 0, 0, S, S, rotation & 3, 0, S, &x2, &y2);
    return toFloatArray(env, {static_cast<float>(x0), static_cast<float>(y0), static_cast<float>(x1 - x0),
                              static_cast<float>(y1 - y0), static_cast<float>(x2 - x0),
                              static_cast<float>(y2 - y0)});
}

// ---------------------------------------------------------------- text

JNI_FN(nativeCharCount)(JNIEnv*, jclass, jlong id, jint index) -> jint {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_TEXTPAGE t = getText(findDoc(id), index);
    return t ? FPDFText_CountChars(t) : 0;
}

JNI_FN(nativePageText)(JNIEnv* env, jclass, jlong id, jint index) -> jstring {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_TEXTPAGE t = getText(findDoc(id), index);
    const int n = t ? FPDFText_CountChars(t) : 0;
    if (n <= 0) return env->NewStringUTF("");
    std::vector<unsigned short> buf(static_cast<size_t>(n) + 1, 0);
    const int written = FPDFText_GetText(t, 0, n, buf.data());
    return fromU16(env, buf.data(), static_cast<size_t>(std::max(0, written)));
}

JNI_FN(nativeCharIndexAt)(JNIEnv*, jclass, jlong id, jint index, jdouble x, jdouble y, jdouble tol) -> jint {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_TEXTPAGE t = getText(findDoc(id), index);
    return t ? FPDFText_GetCharIndexAtPos(t, x, y, tol, tol) : -1;
}

// Rectangles [l, t, r, b]* in page space covering chars [start, start+count).
JNI_FN(nativeTextRects)(JNIEnv* env, jclass, jlong id, jint index, jint start, jint count) -> jfloatArray {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_TEXTPAGE t = getText(findDoc(id), index);
    std::vector<float> out;
    if (t && count > 0) {
        const int n = FPDFText_CountRects(t, start, count);
        for (int i = 0; i < n && i < 10000; ++i) {
            double l, tp, r, b;
            if (FPDFText_GetRect(t, i, &l, &tp, &r, &b))
                out.insert(out.end(), {static_cast<float>(l), static_cast<float>(tp), static_cast<float>(r),
                                       static_cast<float>(b)});
        }
    }
    return toFloatArray(env, out);
}

// Matches as [start, count, nRects, l, t, r, b, ...]*.
JNI_FN(nativeSearch)(JNIEnv* env, jclass, jlong id, jint index, jstring query, jboolean matchCase,
                     jboolean wholeWord) -> jfloatArray {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_TEXTPAGE t = getText(findDoc(id), index);
    std::vector<float> out;
    if (!t) return toFloatArray(env, out);
    const std::u16string q = toU16(env, query);
    unsigned long flags = 0;
    if (matchCase) flags |= FPDF_MATCHCASE;
    if (wholeWord) flags |= FPDF_MATCHWHOLEWORD;
    FPDF_SCHHANDLE h = FPDFText_FindStart(t, wide(q), flags, 0);
    if (!h) return toFloatArray(env, out);
    int matches = 0;
    while (FPDFText_FindNext(h) && matches++ < 5000) {
        const int s = FPDFText_GetSchResultIndex(h), c = FPDFText_GetSchCount(h);
        const int n = std::min(FPDFText_CountRects(t, s, c), 64);
        out.insert(out.end(), {static_cast<float>(s), static_cast<float>(c), static_cast<float>(n)});
        for (int i = 0; i < n; ++i) {
            double l = 0, tp = 0, r = 0, b = 0;
            FPDFText_GetRect(t, i, &l, &tp, &r, &b);
            out.insert(out.end(), {static_cast<float>(l), static_cast<float>(tp), static_cast<float>(r),
                                   static_cast<float>(b)});
        }
    }
    FPDFText_FindClose(h);
    return toFloatArray(env, out);
}

// ---------------------------------------------------------------- links, outline

// { float[] rects (l,b,r,t)*, int[] destPages (-1 if external), String[] uris }
JNI_FN(nativeLinks)(JNIEnv* env, jclass, jlong id, jint index) -> jobjectArray {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    FPDF_PAGE page = getPage(d, index);
    std::vector<float> rects;
    std::vector<int> dests;
    std::vector<jstring> uris;
    int pos = 0;
    FPDF_LINK link = nullptr;
    while (page && FPDFLink_Enumerate(page, &pos, &link) && dests.size() < 5000) {
        FS_RECTF r;
        if (!FPDFLink_GetAnnotRect(link, &r)) continue;
        int dest = -1;
        std::string uri;
        FPDF_DEST dst = FPDFLink_GetDest(d->doc, link);
        FPDF_ACTION act = FPDFLink_GetAction(link);
        if (!dst && act) {
            const unsigned long type = FPDFAction_GetType(act);
            if (type == PDFACTION_GOTO) dst = FPDFAction_GetDest(d->doc, act);
            else if (type == PDFACTION_URI) {
                const unsigned long n = FPDFAction_GetURIPath(d->doc, act, nullptr, 0);
                if (n > 1 && n < 8192) {
                    std::string s(n, '\0');
                    FPDFAction_GetURIPath(d->doc, act, s.data(), n);
                    s.resize(strnlen(s.c_str(), n));
                    uri = s;
                }
            }
        }
        if (dst) dest = FPDFDest_GetDestPageIndex(d->doc, dst);
        if (dest < 0 && uri.empty()) continue;
        rects.insert(rects.end(), {r.left, r.bottom, r.right, r.top});
        dests.push_back(dest);
        uris.push_back(env->NewStringUTF(uri.c_str()));
    }
    return objectArray(env, {toFloatArray(env, rects), toIntArray(env, dests), toStringArray(env, uris)});
}

// { String[] titles, int[] levels, int[] pages }
JNI_FN(nativeOutline)(JNIEnv* env, jclass, jlong id) -> jobjectArray {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    std::vector<jstring> titles;
    std::vector<int> levels, pages;
    std::set<FPDF_BOOKMARK> seen;  // malformed outlines can contain cycles
    struct Walker {
        JNIEnv* env;
        Document* d;
        std::vector<jstring>& titles;
        std::vector<int>& levels;
        std::vector<int>& pages;
        std::set<FPDF_BOOKMARK>& seen;
        void walk(FPDF_BOOKMARK parent, int depth) {
            if (depth > kMaxOutlineDepth) return;
            for (FPDF_BOOKMARK b = FPDFBookmark_GetFirstChild(d->doc, parent);
                 b && static_cast<int>(titles.size()) < kMaxOutlineItems;
                 b = FPDFBookmark_GetNextSibling(d->doc, b)) {
                if (!seen.insert(b).second) return;
                FPDF_DEST dest = FPDFBookmark_GetDest(d->doc, b);
                if (!dest) {
                    FPDF_ACTION a = FPDFBookmark_GetAction(b);
                    if (a && FPDFAction_GetType(a) == PDFACTION_GOTO) dest = FPDFAction_GetDest(d->doc, a);
                }
                titles.push_back(readWide(env, [&](void* buf, unsigned long len) {
                    return FPDFBookmark_GetTitle(b, buf, len);
                }));
                levels.push_back(depth);
                pages.push_back(dest ? FPDFDest_GetDestPageIndex(d->doc, dest) : -1);
                walk(b, depth + 1);
            }
        }
    };
    if (d) Walker{env, d, titles, levels, pages, seen}.walk(nullptr, 0);
    return objectArray(env, {toStringArray(env, titles), toIntArray(env, levels), toIntArray(env, pages)});
}

// ---------------------------------------------------------------- page operations

JNI_FN(nativeDeletePages)(JNIEnv* env, jclass, jlong id, jintArray indices) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    if (!d) return JNI_FALSE;
    d->closeAllPages();
    auto v = fromIntArray(env, indices);
    std::sort(v.rbegin(), v.rend());
    v.erase(std::unique(v.begin(), v.end()), v.end());
    for (int i : v)
        if (i >= 0 && i < FPDF_GetPageCount(d->doc)) FPDFPage_Delete(d->doc, i);
    return JNI_TRUE;
}

JNI_FN(nativeMovePages)(JNIEnv* env, jclass, jlong id, jintArray indices, jint dest) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    if (!d) return JNI_FALSE;
    d->closeAllPages();
    auto v = fromIntArray(env, indices);
    return FPDF_MovePages(d->doc, v.data(), static_cast<unsigned long>(v.size()), dest) ? JNI_TRUE : JNI_FALSE;
}

// Imports |indices| of |src| (all pages if empty) into |dst| at |at|.
JNI_FN(nativeImportPages)(JNIEnv* env, jclass, jlong dst, jlong src, jintArray indices, jint at) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* a = findDoc(dst);
    Document* b = findDoc(src);
    if (!a || !b || a == b) return JNI_FALSE;
    a->closeAllPages();
    auto v = fromIntArray(env, indices);
    return FPDF_ImportPagesByIndex(a->doc, b->doc, v.empty() ? nullptr : v.data(),
                                   static_cast<unsigned long>(v.size()), at)
               ? JNI_TRUE
               : JNI_FALSE;
}

JNI_FN(nativeNewPage)(JNIEnv*, jclass, jlong id, jint at, jfloat w, jfloat h) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    if (!d || !(w >= Limits::kMinPageSide && w <= Limits::kMaxPageSide && h >= Limits::kMinPageSide &&
                h <= Limits::kMaxPageSide))
        return JNI_FALSE;
    d->closeAllPages();
    FPDF_PAGE p = FPDFPage_New(d->doc, at, w, h);
    if (!p) return JNI_FALSE;
    FPDFPage_GenerateContent(p);
    FPDF_ClosePage(p);
    return JNI_TRUE;
}

JNI_FN(nativeGetRotation)(JNIEnv*, jclass, jlong id, jint index) -> jint {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_PAGE p = getPage(findDoc(id), index);
    return p ? FPDFPage_GetRotation(p) : 0;
}

JNI_FN(nativeSetRotation)(JNIEnv*, jclass, jlong id, jint index, jint rotation) -> void {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_PAGE p = getPage(findDoc(id), index);
    if (p) FPDFPage_SetRotation(p, rotation & 3);
}

// Crop box [l, b, r, t] (falls back to the media box).
JNI_FN(nativeGetCropBox)(JNIEnv* env, jclass, jlong id, jint index) -> jfloatArray {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_PAGE p = getPage(findDoc(id), index);
    float l = 0, b = 0, r = 0, t = 0;
    if (!p || (!FPDFPage_GetCropBox(p, &l, &b, &r, &t) && !FPDFPage_GetMediaBox(p, &l, &b, &r, &t))) {
        if (p) { r = FPDF_GetPageWidthF(p); t = FPDF_GetPageHeightF(p); }
    }
    return toFloatArray(env, {l, b, r, t});
}

JNI_FN(nativeSetCropBox)(JNIEnv*, jclass, jlong id, jint index, jfloat l, jfloat b, jfloat r, jfloat t) -> void {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    FPDF_PAGE p = getPage(d, index);
    if (!p || !finiteRect(l, b, r, t) || r - l < Limits::kMinPageSide || t - b < Limits::kMinPageSide) return;
    FPDFPage_SetCropBox(p, l, b, r, t);
    // Page size is cached in the page object; reload it.
    d->closePage(index);
}

JNI_FN(nativeFlattenPage)(JNIEnv*, jclass, jlong id, jint index) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    FPDF_PAGE p = getPage(d, index);
    if (!p) return JNI_FALSE;
    if (d->form) FORM_ForceToKillFocus(d->form);
    const int r = FPDFPage_Flatten(p, FLAT_PRINT);
    d->closePage(index);  // flattening invalidates the loaded page
    return r != FLATTEN_FAIL ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------- creation

// Adds a page of |w| x |h| points showing |jpeg| (embedded unchanged, no
// recompression) at rect (x, y, iw, ih). Returns false if the JPEG is unusable.
JNI_FN(nativeAddJpegPage)(JNIEnv* env, jclass, jlong id, jint at, jbyteArray jpeg, jfloat w, jfloat h, jfloat x,
                          jfloat y, jfloat iw, jfloat ih) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    if (!d || !jpeg) return JNI_FALSE;
    d->closeAllPages();
    FPDF_PAGE page = FPDFPage_New(d->doc, at, w, h);
    if (!page) return JNI_FALSE;
    FPDF_PAGEOBJECT img = imageObjectFromJpeg(d, page, env, jpeg);
    if (!img) {
        FPDF_ClosePage(page);
        FPDFPage_Delete(d->doc, at);
        return JNI_FALSE;
    }
    FS_MATRIX m{iw, 0, 0, ih, x, y};
    FPDFPageObj_SetMatrix(img, &m);
    FPDFPage_InsertObject(page, img);
    FPDFPage_GenerateContent(page);
    FPDF_ClosePage(page);
    return JNI_TRUE;
}

// As nativeAddJpegPage, for a decoded bitmap (Flate-compressed, lossless).
JNI_FN(nativeAddBitmapPage)(JNIEnv* env, jclass, jlong id, jint at, jobject bitmap, jfloat w, jfloat h, jfloat x,
                            jfloat y, jfloat iw, jfloat ih) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    if (!d) return JNI_FALSE;
    FPDF_BITMAP bmp = bitmapFromAndroid(env, bitmap, false);
    if (!bmp) return JNI_FALSE;
    d->closeAllPages();
    FPDF_PAGE page = FPDFPage_New(d->doc, at, w, h);
    bool ok = false;
    if (page) {
        FPDF_PAGEOBJECT img = FPDFPageObj_NewImageObj(d->doc);
        if (img && FPDFImageObj_SetBitmap(&page, 1, img, bmp)) {
            FS_MATRIX m{iw, 0, 0, ih, x, y};
            FPDFPageObj_SetMatrix(img, &m);
            FPDFPage_InsertObject(page, img);
            FPDFPage_GenerateContent(page);
            ok = true;
        } else if (img) {
            FPDFPageObj_Destroy(img);
        }
        FPDF_ClosePage(page);
        if (!ok) FPDFPage_Delete(d->doc, at);
    }
    FPDFBitmap_Destroy(bmp);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Adds invisible, searchable text (OCR layer, plan 8.3 method a). Word boxes
// are [l, t, r, b] in the pixel space of a render of the page at devW x devH
// with no extra rotation. Returns the number of words placed.
JNI_FN(nativeAddTextLayer)(JNIEnv* env, jclass, jlong id, jint index, jint devW, jint devH, jobjectArray words,
                           jintArray boxes) -> jint {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    FPDF_PAGE page = getPage(d, index);
    if (!page || devW <= 0 || devH <= 0) return 0;
    const auto box = fromIntArray(env, boxes);
    const jsize n = std::min<jsize>(env->GetArrayLength(words), static_cast<jsize>(box.size() / 4));
    FPDF_FONT font = FPDFText_LoadStandardFont(d->doc, "Helvetica");
    if (!font) return 0;
    auto toPage = [&](int x, int y, double& px, double& py) {
        FPDF_DeviceToPage(page, 0, 0, devW, devH, 0, x, y, &px, &py);
    };
    int placed = 0;
    for (jsize i = 0; i < n; ++i) {
        auto js = static_cast<jstring>(env->GetObjectArrayElement(words, i));
        std::u16string text = toU16(env, js);
        env->DeleteLocalRef(js);
        const int l = box[i * 4], t = box[i * 4 + 1], r = box[i * 4 + 2], b = box[i * 4 + 3];
        if (text.empty() || r <= l || b <= t) continue;
        // Baseline vector (left-bottom -> right-bottom) and up vector
        // (left-bottom -> left-top), in page space. Handles /Rotate.
        double bx, by, rx, ry, tx, ty;
        toPage(l, b, bx, by);
        toPage(r, b, rx, ry);
        toPage(l, t, tx, ty);
        FPDF_PAGEOBJECT obj = FPDFPageObj_CreateTextObj(d->doc, font, 1.0f);
        if (!obj) continue;
        if (!FPDFText_SetText(obj, wide(text))) {
            FPDFPageObj_Destroy(obj);
            continue;
        }
        FPDFTextObj_SetTextRenderMode(obj, FPDF_TEXTRENDERMODE_INVISIBLE);
        float ol, ob, orr, ot;
        if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &orr, &ot) || orr - ol <= 0) {
            FPDFPageObj_Destroy(obj);
            continue;
        }
        const double ux = (rx - bx) / (orr - ol), uy = (ry - by) / (orr - ol);
        // Helvetica cap height ~0.72 em; descenders below the box bottom.
        const double vx = (tx - bx) / 0.95, vy = (ty - by) / 0.95;
        const double desc = 0.2;
        FS_MATRIX m{static_cast<float>(ux), static_cast<float>(uy), static_cast<float>(vx), static_cast<float>(vy),
                    static_cast<float>(bx + vx * desc - ux * ol), static_cast<float>(by + vy * desc - uy * ol)};
        FPDFPageObj_SetMatrix(obj, &m);
        FPDFPage_InsertObject(page, obj);
        ++placed;
    }
    if (placed) {
        FPDFPage_GenerateContent(page);
        invalidateText(d, index);
    }
    return placed;
}

// ---------------------------------------------------------------- annotations

// { int[] subtypes, float[] rects (l,b,r,t)*, String[] names (/NM) }
JNI_FN(nativeAnnots)(JNIEnv* env, jclass, jlong id, jint index) -> jobjectArray {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_PAGE page = getPage(findDoc(id), index);
    std::vector<int> types;
    std::vector<float> rects;
    std::vector<jstring> names;
    const int n = page ? std::min(FPDFPage_GetAnnotCount(page), 10000) : 0;
    for (int i = 0; i < n; ++i) {
        FPDF_ANNOTATION a = FPDFPage_GetAnnot(page, i);
        FS_RECTF r{0, 0, 0, 0};
        if (a) FPDFAnnot_GetRect(a, &r);
        types.push_back(a ? FPDFAnnot_GetSubtype(a) : -1);
        rects.insert(rects.end(), {r.left, r.bottom, r.right, r.top});
        names.push_back(a ? readWide(env, [&](void* buf, unsigned long len) {
            return FPDFAnnot_GetStringValue(a, "NM", static_cast<FPDF_WCHAR*>(buf), len);
        }) : env->NewStringUTF(""));
        if (a) FPDFPage_CloseAnnot(a);
    }
    return objectArray(env, {toIntArray(env, types), toFloatArray(env, rects), toStringArray(env, names)});
}

JNI_FN(nativeAnnotContents)(JNIEnv* env, jclass, jlong id, jint index, jint annot) -> jstring {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_PAGE page = getPage(findDoc(id), index);
    FPDF_ANNOTATION a = page ? FPDFPage_GetAnnot(page, annot) : nullptr;
    if (!a) return env->NewStringUTF("");
    jstring s = readWide(env, [&](void* buf, unsigned long len) {
        return FPDFAnnot_GetStringValue(a, "Contents", static_cast<FPDF_WCHAR*>(buf), len);
    });
    FPDFPage_CloseAnnot(a);
    return s;
}

JNI_FN(nativeRemoveAnnot)(JNIEnv*, jclass, jlong id, jint index, jint annot) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    FPDF_PAGE page = getPage(d, index);
    if (!page) return JNI_FALSE;
    if (d->form) FORM_ForceToKillFocus(d->form);
    return FPDFPage_RemoveAnnot(page, annot) ? JNI_TRUE : JNI_FALSE;
}

// Highlight / underline / strikeout / squiggly from text quads
// (x1,y1 UL, x2,y2 UR, x3,y3 LL, x4,y4 LR)*.
JNI_FN(nativeAddMarkup)(JNIEnv* env, jclass, jlong id, jint index, jint subtype, jfloatArray quads, jint argb,
                        jstring nm, jstring author) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_PAGE page = getPage(findDoc(id), index);
    const auto q = fromFloatArray(env, quads);
    if (!page || q.size() < 8) return JNI_FALSE;
    FPDF_ANNOTATION a = FPDFPage_CreateAnnot(page, subtype);
    if (!a) return JNI_FALSE;
    float l = q[0], r = q[0], b = q[1], t = q[1];
    for (size_t i = 0; i + 7 < q.size(); i += 8) {
        FS_QUADPOINTSF qp{q[i], q[i + 1], q[i + 2], q[i + 3], q[i + 4], q[i + 5], q[i + 6], q[i + 7]};
        FPDFAnnot_AppendAttachmentPoints(a, &qp);
        for (size_t k = 0; k < 8; k += 2) {
            l = std::min(l, q[i + k]);
            r = std::max(r, q[i + k]);
            b = std::min(b, q[i + k + 1]);
            t = std::max(t, q[i + k + 1]);
        }
    }
    FS_RECTF rect{l, t, r, b};
    FPDFAnnot_SetRect(a, &rect);
    setColor(a, FPDFANNOT_COLORTYPE_Color, argb);
    setCommonAnnot(env, a, nm, author, nullptr);
    FPDFPage_CloseAnnot(a);
    return JNI_TRUE;
}

// Freehand ink: each stroke is [x0, y0, x1, y1, ...] in page space.
JNI_FN(nativeAddInk)(JNIEnv* env, jclass, jlong id, jint index, jobjectArray strokes, jint argb, jfloat width,
                     jstring nm, jstring author) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_PAGE page = getPage(findDoc(id), index);
    if (!page) return JNI_FALSE;
    FPDF_ANNOTATION a = FPDFPage_CreateAnnot(page, FPDF_ANNOT_INK);
    if (!a) return JNI_FALSE;
    float l = 1e9f, b = 1e9f, r = -1e9f, t = -1e9f;
    const jsize n = env->GetArrayLength(strokes);
    for (jsize i = 0; i < n; ++i) {
        auto arr = static_cast<jfloatArray>(env->GetObjectArrayElement(strokes, i));
        const auto v = fromFloatArray(env, arr);
        env->DeleteLocalRef(arr);
        std::vector<FS_POINTF> pts;
        for (size_t k = 0; k + 1 < v.size(); k += 2) {
            pts.push_back({v[k], v[k + 1]});
            l = std::min(l, v[k]);
            r = std::max(r, v[k]);
            b = std::min(b, v[k + 1]);
            t = std::max(t, v[k + 1]);
        }
        if (!pts.empty()) FPDFAnnot_AddInkStroke(a, pts.data(), pts.size());
    }
    if (r < l) {
        FPDFPage_CloseAnnot(a);
        FPDFPage_RemoveAnnot(page, FPDFPage_GetAnnotCount(page) - 1);
        return JNI_FALSE;
    }
    FS_RECTF rect{l - width, t + width, r + width, b - width};
    FPDFAnnot_SetRect(a, &rect);
    setColor(a, FPDFANNOT_COLORTYPE_Color, argb);
    FPDFAnnot_SetBorder(a, 0, 0, width);
    setCommonAnnot(env, a, nm, author, nullptr);
    FPDFPage_CloseAnnot(a);
    return JNI_TRUE;
}

// Square / circle (and sticky note when subtype == TEXT) at [l, b, r, t].
JNI_FN(nativeAddShape)(JNIEnv* env, jclass, jlong id, jint index, jint subtype, jfloat l, jfloat b, jfloat r,
                       jfloat t, jint strokeArgb, jint fillArgb, jfloat width, jstring contents, jstring nm,
                       jstring author) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    FPDF_PAGE page = getPage(findDoc(id), index);
    if (!page || !finiteRect(l, b, r, t)) return JNI_FALSE;
    FPDF_ANNOTATION a = FPDFPage_CreateAnnot(page, subtype);
    if (!a) return JNI_FALSE;
    FS_RECTF rect{std::min(l, r), std::max(b, t), std::max(l, r), std::min(b, t)};
    FPDFAnnot_SetRect(a, &rect);
    setColor(a, FPDFANNOT_COLORTYPE_Color, strokeArgb);
    if (static_cast<uint32_t>(fillArgb) >> 24) setColor(a, FPDFANNOT_COLORTYPE_InteriorColor, fillArgb);
    if (subtype != FPDF_ANNOT_TEXT) FPDFAnnot_SetBorder(a, 0, 0, width);
    setCommonAnnot(env, a, nm, author, contents);
    FPDFPage_CloseAnnot(a);
    return JNI_TRUE;
}

// Stamp annotation whose appearance is an image (drawn signature) or lines of
// text (text box). PDFium writes the appearance stream from the objects.
JNI_FN(nativeAddStamp)(JNIEnv* env, jclass, jlong id, jint index, jfloat l, jfloat b, jfloat r, jfloat t,
                       jobject bitmap, jobjectArray lines, jfloat fontSize, jint argb, jstring nm,
                       jstring author) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    FPDF_PAGE page = getPage(d, index);
    if (!page || !finiteRect(l, b, r, t) || r <= l || t <= b) return JNI_FALSE;
    FPDF_ANNOTATION a = FPDFPage_CreateAnnot(page, FPDF_ANNOT_STAMP);
    if (!a) return JNI_FALSE;
    FS_RECTF rect{l, t, r, b};
    FPDFAnnot_SetRect(a, &rect);
    bool ok = false;
    if (bitmap) {
        FPDF_BITMAP bmp = bitmapFromAndroid(env, bitmap, true);
        FPDF_PAGEOBJECT img = bmp ? FPDFPageObj_NewImageObj(d->doc) : nullptr;
        if (img && FPDFImageObj_SetBitmap(&page, 1, img, bmp)) {
            FS_MATRIX m{r - l, 0, 0, t - b, l, b};
            FPDFPageObj_SetMatrix(img, &m);
            ok = FPDFAnnot_AppendObject(a, img);
        }
        if (!ok && img) FPDFPageObj_Destroy(img);
        if (bmp) FPDFBitmap_Destroy(bmp);
    } else if (lines) {
        FPDF_FONT font = FPDFText_LoadStandardFont(d->doc, "Helvetica");
        const auto u = static_cast<uint32_t>(argb);
        const jsize n = env->GetArrayLength(lines);
        for (jsize i = 0; i < n && font; ++i) {
            auto js = static_cast<jstring>(env->GetObjectArrayElement(lines, i));
            std::u16string s = toU16(env, js);
            env->DeleteLocalRef(js);
            if (s.empty()) continue;
            FPDF_PAGEOBJECT obj = FPDFPageObj_CreateTextObj(d->doc, font, fontSize);
            if (!obj || !FPDFText_SetText(obj, wide(s))) {
                if (obj) FPDFPageObj_Destroy(obj);
                continue;
            }
            FPDFPageObj_SetFillColor(obj, (u >> 16) & 0xff, (u >> 8) & 0xff, u & 0xff, 255);
            FPDFPageObj_Transform(obj, 1, 0, 0, 1, l + fontSize * 0.25, t - fontSize * 1.15 * (i + 1));
            if (FPDFAnnot_AppendObject(a, obj)) ok = true;
            else FPDFPageObj_Destroy(obj);
        }
    }
    setCommonAnnot(env, a, nm, author, nullptr);
    FPDFPage_CloseAnnot(a);
    if (!ok) FPDFPage_RemoveAnnot(page, FPDFPage_GetAnnotCount(page) - 1);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------- forms

JNI_FN(nativeSetFormHighlight)(JNIEnv*, jclass, jlong id, jboolean on) -> void {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    if (d && d->form) FPDF_SetFormFieldHighlightAlpha(d->form, on ? 100 : 0);
}

// Field under page point, or null:
// { int[] {type, flags, annotIndex}, String value, String name, String[] options,
//   int[] selectedOptions, float[] rect (l,b,r,t) }
JNI_FN(nativeFormFieldAt)(JNIEnv* env, jclass, jlong id, jint index, jfloat x, jfloat y) -> jobjectArray {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    FPDF_PAGE page = getPage(d, index);
    if (!page || !d->form) return nullptr;
    FS_POINTF pt{x, y};
    FPDF_ANNOTATION a = FPDFAnnot_GetFormFieldAtPoint(d->form, page, &pt);
    if (!a) return nullptr;
    const int type = FPDFAnnot_GetFormFieldType(d->form, a);
    const int flags = FPDFAnnot_GetFormFieldFlags(d->form, a);
    const int annotIndex = FPDFPage_GetAnnotIndex(page, a);
    jstring value = readWide(env, [&](void* buf, unsigned long len) {
        return FPDFAnnot_GetFormFieldValue(d->form, a, static_cast<FPDF_WCHAR*>(buf), len);
    });
    jstring name = readWide(env, [&](void* buf, unsigned long len) {
        return FPDFAnnot_GetFormFieldName(d->form, a, static_cast<FPDF_WCHAR*>(buf), len);
    });
    std::vector<jstring> options;
    std::vector<int> selected;
    const int count = std::min(FPDFAnnot_GetOptionCount(d->form, a), 2000);
    for (int i = 0; i < count; ++i) {
        options.push_back(readWide(env, [&](void* buf, unsigned long len) {
            return FPDFAnnot_GetOptionLabel(d->form, a, i, static_cast<FPDF_WCHAR*>(buf), len);
        }));
        if (FPDFAnnot_IsOptionSelected(d->form, a, i)) selected.push_back(i);
    }
    FS_RECTF r{0, 0, 0, 0};
    FPDFAnnot_GetRect(a, &r);
    FPDFPage_CloseAnnot(a);
    return objectArray(env, {toIntArray(env, {type, flags, annotIndex}), value, name, toStringArray(env, options),
                             toIntArray(env, selected), toFloatArray(env, {r.left, r.bottom, r.right, r.top})});
}

// Forwards a tap (checkboxes, radio buttons, push buttons).
JNI_FN(nativeFormClick)(JNIEnv*, jclass, jlong id, jint index, jfloat x, jfloat y) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    FPDF_PAGE page = getPage(d, index);
    if (!page || !d->form) return JNI_FALSE;
    FORM_OnMouseMove(d->form, page, 0, x, y);
    const bool down = FORM_OnLButtonDown(d->form, page, 0, x, y);
    const bool up = FORM_OnLButtonUp(d->form, page, 0, x, y);
    FORM_ForceToKillFocus(d->form);
    invalidateText(d, index);
    return down || up ? JNI_TRUE : JNI_FALSE;
}

// Replaces the text of the field at (x, y); PDFium regenerates its appearance.
JNI_FN(nativeFormSetText)(JNIEnv* env, jclass, jlong id, jint index, jfloat x, jfloat y, jstring text) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    FPDF_PAGE page = getPage(d, index);
    if (!page || !d->form) return JNI_FALSE;
    FORM_OnLButtonDown(d->form, page, 0, x, y);
    FORM_OnLButtonUp(d->form, page, 0, x, y);
    FPDF_ANNOTATION focused = nullptr;
    int focusPage = -1;
    if (!FORM_GetFocusedAnnot(d->form, &focusPage, &focused) || !focused) return JNI_FALSE;
    FPDFPage_CloseAnnot(focused);
    const std::u16string s = toU16(env, text);
    FORM_SelectAllText(d->form, page);
    FORM_ReplaceSelection(d->form, page, wide(s));
    FORM_ForceToKillFocus(d->form);
    return JNI_TRUE;
}

JNI_FN(nativeFormSelectOption)(JNIEnv*, jclass, jlong id, jint index, jfloat x, jfloat y, jint option,
                               jboolean selected) -> jboolean {
    std::lock_guard<std::mutex> lock(gLock);
    Document* d = findDoc(id);
    FPDF_PAGE page = getPage(d, index);
    if (!page || !d->form) return JNI_FALSE;
    FORM_OnLButtonDown(d->form, page, 0, x, y);
    FORM_OnLButtonUp(d->form, page, 0, x, y);
    const bool ok = FORM_SetIndexSelected(d->form, page, option, selected);
    FORM_ForceToKillFocus(d->form);
    return ok ? JNI_TRUE : JNI_FALSE;
}
