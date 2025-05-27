#include "util.hpp"


#define HAVE_PTHREADS true;
extern "C" {
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <string.h>
#include <stdio.h>
}

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/bitmap.h>
#include <utils/Mutex.h>


#include <fpdf_doc.h>
#include <fpdfview.h>
#include <fpdf_doc.h>
#include <fpdf_edit.h>
#include <string>
#include <vector>
#include <fpdf_text.h>

#include "toc-helper.h"

using namespace android;

static Mutex sLibraryLock;

static int sLibraryReferenceCount = 0;

static void initLibraryIfNeed() {
    Mutex::Autolock lock(sLibraryLock);
    if (sLibraryReferenceCount == 0) {
        LOGD("Init FPDF library");
        FPDF_InitLibrary();
    }
    sLibraryReferenceCount++;
}

static void destroyLibraryIfNeed() {
    Mutex::Autolock lock(sLibraryLock);
    sLibraryReferenceCount--;
    if (sLibraryReferenceCount == 0) {
        LOGD("Destroy FPDF library");
        FPDF_DestroyLibrary();
    }
}

struct rgb {
    uint8_t red;
    uint8_t green;
    uint8_t blue;
};

class DocumentFile {
private:
    int fileFd;

public:
    FPDF_DOCUMENT pdfDocument = nullptr;
    size_t fileSize;

    DocumentFile() {
        initLibraryIfNeed();
        fileSize = 0;
        fileFd = 0;
    }

    explicit DocumentFile(int fileFd, size_t fileSize) {
        initLibraryIfNeed();
        this->fileSize = fileSize;
        this->fileFd = fileFd;
    }

    ~DocumentFile();
};

DocumentFile::~DocumentFile() {
    if (pdfDocument != nullptr) {
        FPDF_CloseDocument(pdfDocument);
    }

    destroyLibraryIfNeed();
}

template<class string_type>
inline typename string_type::value_type *WriteInto(string_type *str, size_t length_with_null) {
    str->reserve(length_with_null);
    str->resize(length_with_null - 1);
    return &((*str)[0]);
}

inline long getFileSize(int fd) {
    struct stat file_state{};

    if (fstat(fd, &file_state) >= 0) {
        return (long) (file_state.st_size);
    } else {
        LOGE("Error getting file size");
        return 0;
    }
}

static char *getErrorDescription(const long error) {
    char *description = nullptr;
    switch (error) {
        case FPDF_ERR_SUCCESS:
            asprintf(&description, "No error.");
            break;
        case FPDF_ERR_FILE:
            asprintf(&description, "File not found or could not be opened.");
            break;
        case FPDF_ERR_FORMAT:
            asprintf(&description, "File not in PDF format or corrupted.");
            break;
        case FPDF_ERR_PASSWORD:
            asprintf(&description, "Incorrect password.");
            break;
        case FPDF_ERR_SECURITY:
            asprintf(&description, "Unsupported security scheme.");
            break;
        case FPDF_ERR_PAGE:
            asprintf(&description, "Page not found or content error.");
            break;
        default:
            asprintf(&description, "Unknown error.");
    }

    return description;
}

static bool init_classes = true;
static jclass arrList;
static jmethodID arrList_add;
static jmethodID arrList_get;
static jmethodID arrList_size;
static jmethodID arrList_enssurecap;

static jclass integer;
[[maybe_unused]] static jmethodID integer_;

static jclass rectF;
static jmethodID rectF_;
static jmethodID rectF_set;
[[maybe_unused]] static jfieldID rectF_left;
[[maybe_unused]] static jfieldID rectF_top;
[[maybe_unused]] static jfieldID rectF_right;
[[maybe_unused]] static jfieldID rectF_bottom;

void initClasses(JNIEnv *env) {
    LOGE("fatal initClasses");
    jclass arrListTmp = env->FindClass("java/util/ArrayList");
    arrList = (jclass) env->NewGlobalRef(arrListTmp);
    env->DeleteLocalRef(arrListTmp);
    arrList_add = env->GetMethodID(arrList, "add", "(Ljava/lang/Object;)Z");
    arrList_get = env->GetMethodID(arrList, "get", "(I)Ljava/lang/Object;");
    arrList_size = env->GetMethodID(arrList, "size", "()I");
    arrList_enssurecap = env->GetMethodID(arrList, "ensureCapacity", "(I)V");

    jclass integerTmp = env->FindClass("java/lang/Integer");
    integer = (jclass) env->NewGlobalRef(integerTmp);
    env->DeleteLocalRef(integerTmp);
    integer_ = env->GetMethodID(integer, "<init>", "(I)V");

    jclass rectFTmp = env->FindClass("android/graphics/RectF");
    rectF = (jclass) env->NewGlobalRef(rectFTmp);
    env->DeleteLocalRef(rectFTmp);
    rectF_ = env->GetMethodID(rectF, "<init>", "(FFFF)V");
    rectF_set = env->GetMethodID(rectF, "set", "(FFFF)V");
    rectF_left = env->GetFieldID(rectF, "left", "F");
    rectF_top = env->GetFieldID(rectF, "top", "F");
    rectF_right = env->GetFieldID(rectF, "right", "F");
    rectF_bottom = env->GetFieldID(rectF, "bottom", "F");


    init_classes = false;
}

int jniThrowException(JNIEnv *env, const char *className, const char *message) {
    jclass exClass = env->FindClass(className);
    if (exClass == nullptr) {
        LOGE("Unable to find exception class %s", className);
        return -1;
    }

    if (env->ThrowNew(exClass, message) != JNI_OK) {
        LOGE("Failed throwing '%s' '%s'", className, message);
        return -1;
    }

    return 0;
}

int jniThrowExceptionFmt(JNIEnv *env, const char *className, const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    char msgBuf[512];
    vsnprintf(msgBuf, sizeof(msgBuf), fmt, args);
    return jniThrowException(env, className, msgBuf);
    va_end(args);
}

jobject NewLong(JNIEnv *env, jlong value) {
    jclass cls = env->FindClass("java/lang/Long");
    jmethodID methodID = env->GetMethodID(cls, "<init>", "(J)V");
    return env->NewObject(cls, methodID, value);
}

jobject NewInteger(JNIEnv *env, jint value) {
    jclass cls = env->FindClass("java/lang/Integer");
    jmethodID methodID = env->GetMethodID(cls, "<init>", "(I)V");
    return env->NewObject(cls, methodID, value);
}


jobject NewTocEntry(JNIEnv *env, const PdfDoc::TOCEntry &entry) {
    jclass tocEntryClass = env->FindClass("com/vivlio/android/pdfium/TOCEntry");
    if (!tocEntryClass) return nullptr;

    jmethodID constructor = env->GetMethodID(
            tocEntryClass, "<init>",
            "(Ljava/lang/String;IIIFFF)V"
    );
    jstring javaTitle = env->NewString((jchar *) entry.title.c_str(),
                                       (jsize) entry.bufferLen / 2 - 1);
    if (javaTitle == nullptr) {
        return nullptr;
    }

    if (!constructor) return nullptr;
    jobject javaTOCEntry = env->NewObject(
            tocEntryClass, constructor, javaTitle,
            entry.pageIndex, entry.level,
            entry.parentIndex, entry.x, entry.y, entry.zoom
    );
    env->DeleteLocalRef(javaTitle);
    return javaTOCEntry;
}

uint16_t rgbTo565(rgb *color) {
    return ((color->red >> 3) << 11) | ((color->green >> 2) << 5) | (color->blue >> 3);
}

void rgbBitmapTo565(void *source, int sourceStride, void *dest, AndroidBitmapInfo *info) {
    rgb *srcLine;
    uint16_t *dstLine;
    int y, x;
    for (y = 0; y < info->height; y++) {
        srcLine = (rgb *) source;
        dstLine = (uint16_t *) dest;
        for (x = 0; x < info->width; x++) {
            dstLine[x] = rgbTo565(&srcLine[x]);
        }
        source = (char *) source + sourceStride;
        dest = (char *) dest + info->stride;
    }
}

extern "C" { //For JNI support

static int getBlock(void *param, unsigned long position, unsigned char *outBuffer,
                    unsigned long size) {
    const int fd = reinterpret_cast<intptr_t>(param);
    const int readCount = pread(fd, outBuffer, size, (off_t) position);
    if (readCount < 0) {
        LOGE("Cannot read from file descriptor. Error:%d", errno);
        return 0;
    }
    return 1;
}

#pragma clang diagnostic push
#pragma ide diagnostic ignored "MemoryLeak"
JNI_FUNC(jlong, PdfiumCore, nativeOpenDocument)(JNI_ARGS, jint fd, jstring password) {

    auto fileLength = (size_t) getFileSize(fd);
    if (fileLength <= 0) {
        jniThrowException(env, "java/io/IOException",
                          "File is empty");
        return -1;
    }

    auto *docFile = new DocumentFile(fd, fileLength);

    FPDF_FILEACCESS loader;
    loader.m_FileLen = fileLength;
    loader.m_Param = reinterpret_cast<void *>(intptr_t(fd));
    loader.m_GetBlock = &getBlock;

    const char *cPassword = nullptr;
    if (password != nullptr) {
        cPassword = env->GetStringUTFChars(password, nullptr);
    }

    FPDF_DOCUMENT document = FPDF_LoadCustomDocument(&loader, cPassword);

    if (cPassword != nullptr) {
        env->ReleaseStringUTFChars(password, cPassword);
    }

    if (!document) {
        delete docFile;
        const long errorNum = (long) FPDF_GetLastError();
        if (errorNum == FPDF_ERR_PASSWORD) {
            jniThrowException(env, "com/vivlio/android/pdfium/PdfPasswordException",
                              "Password required or incorrect password.");
        } else {
            char *error = getErrorDescription(errorNum);
            jniThrowExceptionFmt(env, "java/io/IOException",
                                 "cannot create document: %s", error);

            free(error);
        }
        return -1;
    }
    docFile->pdfDocument = document;
    return reinterpret_cast<jlong>(docFile);
}
#pragma clang diagnostic pop

#pragma clang diagnostic push
#pragma ide diagnostic ignored "MemoryLeak"
JNI_FUNC(jlong, PdfiumCore, nativeOpenMemDocument)(JNI_ARGS, jbyteArray data, jstring password) {
    auto *docFile = new DocumentFile();

    const char *cpassword = nullptr;
    if (password != nullptr) {
        cpassword = env->GetStringUTFChars(password, nullptr);
    }

    jbyte *cData = env->GetByteArrayElements(data, nullptr);
    int size = env->GetArrayLength(data);
    docFile->fileSize = size;


    auto *cDataCopy = new jbyte[size];
    memcpy(cDataCopy, cData, size);
    FPDF_DOCUMENT document = FPDF_LoadMemDocument(reinterpret_cast<const void *>(cDataCopy),
                                                  size, cpassword);
    env->ReleaseByteArrayElements(data, cData, JNI_ABORT);

    if (cpassword != nullptr) {
        env->ReleaseStringUTFChars(password, cpassword);
    }

    if (!document) {
        delete docFile;

        const long errorNum = (long) FPDF_GetLastError();
        if (errorNum == FPDF_ERR_PASSWORD) {
            jniThrowException(env, "com/vivlio/android/pdfium/PdfPasswordException",
                              "Password required or incorrect password.");
        } else {
            char *error = getErrorDescription(errorNum);
            jniThrowExceptionFmt(env, "java/io/IOException",
                                 "cannot create document: %s", error);

            free(error);
        }

        return -1;
    }

    docFile->pdfDocument = document;

    return reinterpret_cast<jlong>(docFile);
}
#pragma clang diagnostic pop


JNI_FUNC(void, PdfiumCore, nativeCloseDocument)(JNI_ARGS, jlong documentPtr) {
    auto *doc = reinterpret_cast<DocumentFile *>(documentPtr);
    delete doc;
}

JNI_FUNC(jint, PdfiumCore, nativeGetPageCount)(JNI_ARGS, jlong documentPtr) {
    auto *doc = reinterpret_cast<DocumentFile *>(documentPtr);
    return (jint) FPDF_GetPageCount(doc->pdfDocument);
}

JNI_FUNC(jlong, PdfiumCore, nativeFileSize)(JNI_ARGS, jlong documentPtr) {
    auto *doc = reinterpret_cast<DocumentFile *>(documentPtr);
    return doc->fileSize;
}


static jlong loadPageInternal(JNIEnv *env, DocumentFile *doc, int pageIndex) {
    try {
        if (doc == nullptr) throw std::runtime_error("Get page document null");

        FPDF_DOCUMENT pdfDoc = doc->pdfDocument;
        if (pdfDoc != nullptr) {
            FPDF_PAGE page = FPDF_LoadPage(pdfDoc, pageIndex);
            if (page == nullptr) {
                throw std::runtime_error("Loaded page is null");
            }
            return reinterpret_cast<jlong>(page);
        } else {
            throw std::runtime_error("Get page pdf document null");
        }

    } catch (const std::exception &e) {
        LOGE("%s", e.what());

        jniThrowException(env, "java/lang/IllegalStateException",
                          "cannot load page");

        return -1;
    }
}

static void closePageInternal(jlong pagePtr) {
    FPDF_ClosePage(reinterpret_cast<FPDF_PAGE>(pagePtr));
}

JNI_FUNC(jlong, PdfiumCore, nativeLoadPage)(JNI_ARGS, jlong docPtr, jint pageIndex) {
    auto *doc = reinterpret_cast<DocumentFile *>(docPtr);
    return loadPageInternal(env, doc, (int) pageIndex);
}
JNI_FUNC(jlongArray, PdfiumCore, nativeLoadPages)(JNI_ARGS, jlong docPtr, jint fromIndex,
                                                  jint toIndex) {
    auto *doc = reinterpret_cast<DocumentFile *>(docPtr);

    if (toIndex < fromIndex) return nullptr;
    jlong pages[toIndex - fromIndex + 1];

    int i;
    for (i = 0; i <= (toIndex - fromIndex); i++) {
        pages[i] = loadPageInternal(env, doc, (int) (i + fromIndex));
    }

    jlongArray javaPages = env->NewLongArray((jsize) (toIndex - fromIndex + 1));
    env->SetLongArrayRegion(javaPages, 0, (jsize) (toIndex - fromIndex + 1), (const jlong *) pages);

    return javaPages;
}

JNI_FUNC(void, PdfiumCore, nativeClosePage)(JNI_ARGS, jlong pagePtr) { closePageInternal(pagePtr); }
JNI_FUNC(void, PdfiumCore, nativeClosePages)(JNI_ARGS, jlongArray pagesPtr) {
    int length = (int) (env->GetArrayLength(pagesPtr));
    jlong *pages = env->GetLongArrayElements(pagesPtr, nullptr);

    int i;
    for (i = 0; i < length; i++) { closePageInternal(pages[i]); }
}

JNI_FUNC(jint, PdfiumCore, nativeGetPageWidthPixel)(JNI_ARGS, jlong pagePtr, jint dpi) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    return (jint) (FPDF_GetPageWidth(page) * dpi / 72);
}
JNI_FUNC(jint, PdfiumCore, nativeGetPageHeightPixel)(JNI_ARGS, jlong pagePtr, jint dpi) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    return (jint) (FPDF_GetPageHeight(page) * dpi / 72);
}
JNI_FUNC(jint, PdfiumCore, nativeGetPageWidthPoint)(JNI_ARGS, jlong pagePtr) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    return (jint) FPDF_GetPageWidth(page);
}
JNI_FUNC(jint, PdfiumCore, nativeGetPageHeightPoint)(JNI_ARGS, jlong pagePtr) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    return (jint) FPDF_GetPageHeight(page);
}
JNI_FUNC(jobject, PdfiumCore, nativeGetPageSizeByIndex)(JNI_ARGS, jlong docPtr, jint pageIndex,
                                                        jint dpi) {
    auto *doc = reinterpret_cast<DocumentFile *>(docPtr);
    if (doc == nullptr) {
        LOGE("Document is null");

        jniThrowException(env, "java/lang/IllegalStateException",
                          "Document is null");
        return nullptr;
    }

    double width, height;
    int result = FPDF_GetPageSizeByIndex(doc->pdfDocument, pageIndex, &width, &height);

    if (result == 0) {
        width = 0;
        height = 0;
    }

    jint widthInt = (jint) (width * dpi / 72);
    jint heightInt = (jint) (height * dpi / 72);

    jclass clazz = env->FindClass("com/vivlio/android/pdfium/util/Size");
    jmethodID constructorID = env->GetMethodID(clazz, "<init>", "(II)V");
    return env->NewObject(clazz, constructorID, widthInt, heightInt);
}

static void renderPageInternal(FPDF_PAGE page,
                               ANativeWindow_Buffer *windowBuffer,
                               int startX, int startY,
                               int canvasHorSize, int canvasVerSize,
                               int drawSizeHor, int drawSizeVer,
                               bool renderAnnot) {

    FPDF_BITMAP pdfBitmap = FPDFBitmap_CreateEx(canvasHorSize, canvasVerSize,
                                                FPDFBitmap_BGRA,
                                                windowBuffer->bits,
                                                (int) (windowBuffer->stride) * 4);

    /*LOGD("Start X: %d", startX);
    LOGD("Start Y: %d", startY);
    LOGD("Canvas Hor: %d", canvasHorSize);
    LOGD("Canvas Ver: %d", canvasVerSize);
    LOGD("Draw Hor: %d", drawSizeHor);
    LOGD("Draw Ver: %d", drawSizeVer);*/

    if (drawSizeHor < canvasHorSize || drawSizeVer < canvasVerSize) {
        FPDFBitmap_FillRect(pdfBitmap, 0, 0, canvasHorSize, canvasVerSize,
                            0x848484FF); //Gray
    }

    int baseHorSize = (canvasHorSize < drawSizeHor) ? canvasHorSize : drawSizeHor;
    int baseVerSize = (canvasVerSize < drawSizeVer) ? canvasVerSize : drawSizeVer;
    int baseX = (startX < 0) ? 0 : startX;
    int baseY = (startY < 0) ? 0 : startY;
    int flags = FPDF_REVERSE_BYTE_ORDER;

    if (renderAnnot) {
        flags |= FPDF_ANNOT;
    }

    FPDFBitmap_FillRect(pdfBitmap, baseX, baseY, baseHorSize, baseVerSize,
                        0xFFFFFFFF); //White

    FPDF_RenderPageBitmap(pdfBitmap, page,
                          startX, startY,
                          drawSizeHor, drawSizeVer,
                          0, flags);
}

JNI_FUNC(void, PdfiumCore, nativeRenderPage)(JNI_ARGS, jlong pagePtr, jobject objSurface,
                                             jint dpi, jint startX, jint startY,
                                             jint drawSizeHor, jint drawSizeVer,
                                             jboolean renderAnnot) {
    if (!dpi) {
        LOGD("NO DPI");
    }

    ANativeWindow *nativeWindow = ANativeWindow_fromSurface(env, objSurface);
    if (nativeWindow == nullptr) {
        LOGE("native window pointer null");
        return;
    }
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);

    if (page == nullptr) {
        LOGE("Render page pointers invalid");
        return;
    }

    if (ANativeWindow_getFormat(nativeWindow) != WINDOW_FORMAT_RGBA_8888) {
        LOGD("Set format to RGBA_8888");
        ANativeWindow_setBuffersGeometry(nativeWindow,
                                         ANativeWindow_getWidth(nativeWindow),
                                         ANativeWindow_getHeight(nativeWindow),
                                         WINDOW_FORMAT_RGBA_8888);
    }

    ANativeWindow_Buffer buffer;
    int ret;
    if ((ret = ANativeWindow_lock(nativeWindow, &buffer, nullptr)) != 0) {
        LOGE("Locking native window failed: %s", strerror(ret * -1));
        return;
    }

    renderPageInternal(page, &buffer,
                       (int) startX, (int) startY,
                       buffer.width, buffer.height,
                       (int) drawSizeHor, (int) drawSizeVer,
                       (bool) renderAnnot);

    ANativeWindow_unlockAndPost(nativeWindow);
    ANativeWindow_release(nativeWindow);
}

JNI_FUNC(jstring, PdfiumCore, nativeGetLinkTarget)(JNI_ARGS, jlong docPtr, jlong linkPtr) {
    auto *doc = reinterpret_cast<DocumentFile *>(docPtr);
    auto link = reinterpret_cast<FPDF_LINK>(linkPtr);
    FPDF_DEST dest = FPDFLink_GetDest(doc->pdfDocument, link);
    if (dest != nullptr) {
        long pageIdx = FPDFDest_GetDestPageIndex(doc->pdfDocument, dest);
        char buffer[16] = {0};
        buffer[0] = '@';
        sprintf(buffer + 1, "%d", (int) pageIdx);
        return env->NewStringUTF(buffer);
    }
    FPDF_ACTION action = FPDFLink_GetAction(link);
    if (action == nullptr) {
        return nullptr;
    }
    size_t bufferLen = FPDFAction_GetURIPath(doc->pdfDocument, action, nullptr, 0);
    if (bufferLen <= 0) {
        return nullptr;
    }
    std::string uri;
    FPDFAction_GetURIPath(doc->pdfDocument, action, WriteInto(&uri, bufferLen), bufferLen);
    return env->NewStringUTF(uri.c_str());
}
JNI_FUNC(jlong, PdfiumCore, nativeGetLinkAtCoord)(JNI_ARGS, jlong pagePtr, jdouble width,
                                                  jdouble height, jdouble posX, jdouble posY) {
    double px, py;
    FPDF_DeviceToPage((FPDF_PAGE) pagePtr, 0, 0, (int) width, (int) height, 0, (int) posX,
                      (int) posY, &px,
                      &py);
    return (jlong) FPDFLink_GetLinkAtPoint((FPDF_PAGE) pagePtr, px, py);
}
JNI_FUNC(jint, PdfiumCore, nativeGetCharIndexAtCoord)(JNI_ARGS, jlong pagePtr, jdouble width,
                                                      jdouble height, jlong textPtr, jdouble posX,
                                                      jdouble posY, jdouble tolX, jdouble tolY) {
    double px, py;
    FPDF_DeviceToPage((FPDF_PAGE) pagePtr, 0, 0, (int) width, (int) height, 0,
                      (int) posX,
                      (int) posY,
                      &px,
                      &py);
    return FPDFText_GetCharIndexAtPos((FPDF_TEXTPAGE) textPtr, px, py, tolX, tolY);
}
JNI_FUNC(jstring, PdfiumCore, nativeGetText)(JNI_ARGS, jlong textPtr) {
    int len = FPDFText_CountChars((FPDF_TEXTPAGE) textPtr);
    //unsigned short* buffer = malloc(len*sizeof(unsigned short));
    auto *buffer = new unsigned short[len + 1];
    FPDFText_GetText((FPDF_TEXTPAGE) textPtr, 0, len, buffer);
    jstring ret = env->NewString(buffer, len);
    delete[]buffer;
    return ret;
}

JNI_FUNC(jstring, PdfiumCore, nativeGetTextPart)(JNI_ARGS, jlong textPtr, int start, int len) {
    auto *buffer = new unsigned short[len + 1];
    FPDFText_GetText((FPDF_TEXTPAGE) textPtr, start, len, buffer);
    jstring ret = env->NewString(buffer, len);
    delete[]buffer;
    return ret;
}

JNI_FUNC(void, PdfiumCore, nativeRenderPageBitmap)(JNI_ARGS, jlong pagePtr, jobject bitmap,
                                                   jint dpi, jint startX, jint startY,
                                                   jint drawSizeHor, jint drawSizeVer,
                                                   jboolean renderAnnot) {
    if (!dpi) {
        LOGD("NO DPI");
    }

    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);

    if (page == nullptr || bitmap == nullptr) {
        LOGE("Render page pointers invalid");
        return;
    }

    AndroidBitmapInfo info;
    int ret;
    if ((ret = AndroidBitmap_getInfo(env, bitmap, &info)) < 0) {
        LOGE("Fetching bitmap info failed: %s", strerror(ret * -1));
        return;
    }

    int canvasHorSize = (int) info.width;
    int canvasVerSize = (int) info.height;

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 &&
        info.format != ANDROID_BITMAP_FORMAT_RGB_565) {
        LOGE("Bitmap format must be RGBA_8888 or RGB_565");
        return;
    }

    void *addr;
    if ((ret = AndroidBitmap_lockPixels(env, bitmap, &addr)) != 0) {
        LOGE("Locking bitmap failed: %s", strerror(ret * -1));
        return;
    }

    void *tmp;
    int format;
    int sourceStride;
    if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        tmp = malloc(canvasVerSize * canvasHorSize * sizeof(rgb));
        sourceStride = canvasHorSize * (int) sizeof(rgb);
        format = FPDFBitmap_BGR;
    } else {
        tmp = addr;
        sourceStride = (int) info.stride;
        format = FPDFBitmap_BGRA;
    }

    FPDF_BITMAP pdfBitmap = FPDFBitmap_CreateEx((int) canvasHorSize, (int) canvasVerSize,
                                                format, tmp, (int) sourceStride);


    if (drawSizeHor < canvasHorSize || drawSizeVer < canvasVerSize) {
        FPDFBitmap_FillRect(pdfBitmap, 0, 0, canvasHorSize, canvasVerSize,
                            0x848484FF); //Gray
    }

    int baseHorSize = (canvasHorSize < drawSizeHor) ? canvasHorSize : (int) drawSizeHor;
    int baseVerSize = (canvasVerSize < drawSizeVer) ? canvasVerSize : (int) drawSizeVer;
    int baseX = (startX < 0) ? 0 : (int) startX;
    int baseY = (startY < 0) ? 0 : (int) startY;
    int flags = FPDF_REVERSE_BYTE_ORDER;

    if (renderAnnot) {
        flags |= FPDF_ANNOT;
    }

    FPDFBitmap_FillRect(pdfBitmap, baseX, baseY, baseHorSize, baseVerSize,
                        0xFFFFFFFF); //White

    FPDF_RenderPageBitmap(pdfBitmap, page,
                          startX, startY,
                          (int) drawSizeHor,
                          (int) drawSizeVer,
                          0,
                          flags);

    if (info.format == ANDROID_BITMAP_FORMAT_RGB_565) {
        rgbBitmapTo565(tmp, sourceStride, addr, &info);
        free(tmp);
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

//Start Bookmarks
JNI_FUNC(jstring, PdfiumCore, nativeGetDocumentMetaText)(JNI_ARGS, jlong docPtr, jstring tag) {
    const char *ctag = env->GetStringUTFChars(tag, nullptr);
    if (ctag == nullptr) {
        return env->NewStringUTF("");
    }
    auto *doc = reinterpret_cast<DocumentFile *>(docPtr);

    size_t bufferLen = FPDF_GetMetaText(doc->pdfDocument, ctag, nullptr, 0);
    if (bufferLen <= 2) {
        return env->NewStringUTF("");
    }
    std::wstring text;
    FPDF_GetMetaText(doc->pdfDocument, ctag, WriteInto(&text, bufferLen + 1), bufferLen);
    env->ReleaseStringUTFChars(tag, ctag);
    return env->NewString((jchar *) text.c_str(), (int) bufferLen / 2 - 1);
}

JNI_FUNC(jobject, PdfiumCore, nativeGetFirstChildBookmark)(JNI_ARGS, jlong docPtr,
                                                           jobject bookmarkPtr) {
    auto *doc = reinterpret_cast<DocumentFile *>(docPtr);
    FPDF_BOOKMARK parent;
    if (bookmarkPtr == nullptr) {
        parent = nullptr;
    } else {
        jclass longClass = env->GetObjectClass(bookmarkPtr);
        jmethodID longValueMethod = env->GetMethodID(longClass, "longValue", "()J");

        jlong ptr = env->CallLongMethod(bookmarkPtr, longValueMethod);
        parent = reinterpret_cast<FPDF_BOOKMARK>(ptr);
    }
    FPDF_BOOKMARK bookmark = FPDFBookmark_GetFirstChild(doc->pdfDocument, parent);
    if (bookmark == nullptr) {
        return nullptr;
    }
    return NewLong(env, reinterpret_cast<jlong>(bookmark));
}

JNI_FUNC(jobject, PdfiumCore, nativeGetSiblingBookmark)(JNI_ARGS, jlong docPtr, jlong bookmarkPtr) {
    auto *doc = reinterpret_cast<DocumentFile *>(docPtr);
    auto parent = reinterpret_cast<FPDF_BOOKMARK>(bookmarkPtr);
    FPDF_BOOKMARK bookmark = FPDFBookmark_GetNextSibling(doc->pdfDocument, parent);
    if (bookmark == nullptr) {
        return nullptr;
    }
    return NewLong(env, reinterpret_cast<jlong>(bookmark));
}

JNI_FUNC(jstring, PdfiumCore, nativeGetBookmarkTitle)(JNI_ARGS, jlong bookmarkPtr) {
    auto bookmark = reinterpret_cast<FPDF_BOOKMARK>(bookmarkPtr);
    size_t bufferLen = FPDFBookmark_GetTitle(bookmark, nullptr, 0);
    if (bufferLen <= 2) {
        return env->NewStringUTF("");
    }
    std::wstring title;
    FPDFBookmark_GetTitle(bookmark, WriteInto(&title, bufferLen + 1), bufferLen);
    return env->NewString((jchar *) title.c_str(), (jsize) bufferLen / 2 - 1);
}

JNI_FUNC(jlong, PdfiumCore, nativeGetBookmarkDestIndex)(JNI_ARGS, jlong docPtr, jlong bookmarkPtr) {
    auto *doc = reinterpret_cast<DocumentFile *>(docPtr);
    auto bookmark = reinterpret_cast<FPDF_BOOKMARK>(bookmarkPtr);

    FPDF_DEST dest = FPDFBookmark_GetDest(doc->pdfDocument, bookmark);
    if (dest == nullptr) {
        return -1;
    }
    return (jlong) FPDFDest_GetDestPageIndex(doc->pdfDocument, dest);
}

JNI_FUNC(void, PdfiumCore, nativeSetBookmarkCoordinates)(JNI_ARGS, jlong docPtr, jlong bookmarkPtr,
                                                         jobject bookmarkObj) {
    auto *doc = reinterpret_cast<DocumentFile *>(docPtr);
    auto bookmark = reinterpret_cast<FPDF_BOOKMARK>(bookmarkPtr);

    FPDF_DEST dest = FPDFBookmark_GetDest(doc->pdfDocument, bookmark);
    if (dest == nullptr) {
        return;
    }
    auto pageIndex = FPDFDest_GetDestPageIndex(doc->pdfDocument, dest);
    int hasX = 0, hasY = 0, hasZoom = 0;
    float x = -1, y = -1, zoom = -1;

    FPDFDest_GetLocationInPage(dest, &hasX, &hasY, &hasZoom, &x, &y, &zoom);

    jclass bookmarkClass = env->GetObjectClass(bookmarkObj);
    jmethodID bookmark_set = env->GetMethodID(bookmarkClass, "setValue", "(IFFF)V");
    env->CallVoidMethod(bookmarkObj, bookmark_set, pageIndex, x, y, zoom);
}


JNI_FUNC(jlongArray, PdfiumCore, nativeGetPageLinks)(JNI_ARGS, jlong pagePtr) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    int pos = 0;
    std::vector<jlong> links;
    FPDF_LINK link;
    while (FPDFLink_Enumerate(page, &pos, &link)) {
        links.push_back(reinterpret_cast<jlong>(link));
    }

    jlongArray result = env->NewLongArray((jsize) links.size());
    env->SetLongArrayRegion(result, 0, (jsize) links.size(), &links[0]);
    return result;
}

JNI_FUNC(jobject, PdfiumCore, nativeGetDestPageIndex)(JNI_ARGS, jlong docPtr, jlong linkPtr) {
    auto *doc = reinterpret_cast<DocumentFile *>(docPtr);
    auto link = reinterpret_cast<FPDF_LINK>(linkPtr);
    FPDF_DEST dest = FPDFLink_GetDest(doc->pdfDocument, link);
    if (dest == nullptr) {
        return nullptr;
    }
    unsigned long index = FPDFDest_GetDestPageIndex(doc->pdfDocument, dest);
    return NewInteger(env, (jint) index);
}


JNI_FUNC(void, PdfiumCore, nativeGetBookmarksArrayList)(JNI_ARGS,
                                                        jlong docPtr,
                                                        jobject arrayList) {
    try {
        if (init_classes) {
            initClasses(env);
        }
        auto document = reinterpret_cast<DocumentFile *>(docPtr);
        auto toc = PdfDoc::ExtractTOC(document->pdfDocument);
        for (const auto &item: toc) {

            jobject arr = NewTocEntry(env, item);
            jboolean result = env->CallBooleanMethod(arrayList, arrList_add, arr);
            env->DeleteLocalRef(arr);
            if (result == JNI_FALSE) {
                LOGE("Failed to add title to ArrayList");
            }
        }
    } catch (...) {
        LOGE("Exception in nativeGetBookmarksArrayList");
    }

}

//END bookmark


JNI_FUNC(jstring, PdfiumCore, nativeGetLinkURI)(JNI_ARGS, jlong docPtr, jlong linkPtr) {
    auto *doc = reinterpret_cast<DocumentFile *>(docPtr);
    auto link = reinterpret_cast<FPDF_LINK>(linkPtr);
    FPDF_ACTION action = FPDFLink_GetAction(link);
    if (action == nullptr) {
        return nullptr;
    }
    size_t bufferLen = FPDFAction_GetURIPath(doc->pdfDocument, action, nullptr, 0);
    if (bufferLen <= 0) {
        return env->NewStringUTF("");
    }
    std::string uri;
    FPDFAction_GetURIPath(doc->pdfDocument, action, WriteInto(&uri, bufferLen), bufferLen);
    return env->NewStringUTF(uri.c_str());
}
JNI_FUNC(void, PdfiumCore, nativeGetCharPos)(JNI_ARGS, jlong pagePtr, jint offsetY, jint offsetX,
                                             jint width, jint height, jobject pt, jlong textPtr,
                                             jint idx, jboolean loose) {

    if (init_classes)initClasses(env);
    double left, top, right, bottom;
    if (loose) {
        FS_RECTF res = {0};
        if (!FPDFText_GetLooseCharBox((FPDF_TEXTPAGE) textPtr, idx, &res)) {
            return;
        }
        left = res.left;
        top = res.top;
        right = res.right;
        bottom = res.bottom;
    } else {
        if (!FPDFText_GetCharBox((FPDF_TEXTPAGE) textPtr, idx, &left, &right, &bottom, &top)) {
            return;
        }
    }
    int deviceX, deviceY;
    FPDF_PageToDevice((FPDF_PAGE) pagePtr, 0, 0, width, height, 0, left, top, &deviceX, &deviceY);
    width = (jint) (right - left);
    height = (jint) (top - bottom);
    left = deviceX + offsetX;
    top = deviceY + offsetY;
    right = left + width;
    bottom = top + height;
    //env->CallVoidMethod(pt, point_set, left, top);
    env->CallVoidMethod(pt, rectF_set, (float) left, (float) top, (float) right, (float) bottom);
}


JNI_FUNC(jboolean, PdfiumCore, nativeGetMixedLooseCharPos)(JNI_ARGS, jlong pagePtr, jint offsetY,
                                                           jint offsetX, jint width, jint height,
                                                           jobject pt, jlong textPtr, jint idx,
                                                           jboolean loose) {
    if (!loose) {
        LOGD("NO LOOSE");
    }
    if (init_classes)initClasses(env);

    double left, top, right, bottom;
    if (!FPDFText_GetCharBox((FPDF_TEXTPAGE) textPtr, idx, &left, &right, &bottom, &top)) {
        return false;
    }
    FS_RECTF res = {0};
    if (!FPDFText_GetLooseCharBox((FPDF_TEXTPAGE) textPtr, idx, &res)) {
        return false;
    }
    top = fmax(res.top, top);
    bottom = fmin(res.bottom, bottom);
    left = fmin(res.left, left);
    right = fmax(res.right,
                 right);//width=1080,height=1527,left=365,top=621,right=686,bottom=440,deviceX=663,deviceY=400,ptr=543663849984
    int deviceX, deviceY;
    FPDF_PageToDevice((FPDF_PAGE) pagePtr, 0, 0, width, height, 0, left, top, &deviceX, &deviceY);

    /* width = right - left;
     height = top - bottom;*/
    height = (jint) (top - bottom);
    top = deviceY + offsetY;
    left = deviceX + offsetX;

    FPDF_PageToDevice((FPDF_PAGE) pagePtr, 0, 0, width, height, 0, right, bottom, &deviceX,
                      &deviceY);

    width = (jint) (deviceX - left);


    right = left + width;
    bottom = top + height;
    env->CallVoidMethod(pt, rectF_set, (float) left, (float) top, (float) right, (float) bottom);
    return true;
}

JNI_FUNC(jint, PdfiumCore, nativeCountAndGetRects)(JNI_ARGS, jlong pagePtr, jint offsetY,
                                                   jint offsetX, jint width, jint height,
                                                   jobject arr, jlong textPtr, jint st, jint ed) {
    if (init_classes) initClasses(env);

    int rectCount = FPDFText_CountRects((FPDF_TEXTPAGE) textPtr, (int) st, (int) ed);
    env->CallVoidMethod(arr, arrList_enssurecap, rectCount);
    double left, top, right, bottom;//width=1080,height=1527,left=365,top=621,right=686,bottom=440,deviceX=663,deviceY=400,ptr=543663849984
    int arraySize = env->CallIntMethod(arr, arrList_size);
    int deviceX, deviceY;
    int deviceRight, deviceBottom;
    for (int i = 0; i < rectCount; i++) {//"RectF(373.0, 405.0, 556.0, 434.0)"
        if (FPDFText_GetRect((FPDF_TEXTPAGE) textPtr, i, &left, &top, &right, &bottom)) {
            FPDF_PageToDevice((FPDF_PAGE) pagePtr, 0, 0, (int) width, (int) height, 0, left, top,
                              &deviceX,
                              &deviceY);

            FPDF_PageToDevice((FPDF_PAGE) pagePtr, 0, 0, (int) width, (int) height, 0, right,
                              bottom, &deviceRight,
                              &deviceBottom);
            /*int new_width = right - left;
            int new_height = top - bottom;*/
            left = deviceX + offsetX;
            top = deviceY + offsetY;

            jint new_width = (jint) (deviceRight - left);
            jint new_height = (jint) (deviceBottom - top);

            right = left + new_width;
            bottom = top + new_height;
            if (i >= arraySize) {
                env->CallBooleanMethod(arr, arrList_add,
                                       env->NewObject(rectF, rectF_, (float) left, (float) top,
                                                      (float) right, (float) bottom));
            } else {
                jobject rI = env->CallObjectMethod(arr, arrList_get, i);
                env->CallVoidMethod(rI, rectF_set, (float) left, (float) top, (float) right,
                                    (float) bottom);
            }
        }
    }
    return rectCount;
}


JNI_FUNC(jobject, PdfiumCore, nativeGetLinkRect)(JNI_ARGS, jlong linkPtr) {
    auto link = reinterpret_cast<FPDF_LINK>(linkPtr);
    FS_RECTF fsRectF;
    FPDF_BOOL result = FPDFLink_GetAnnotRect(link, &fsRectF);

    if (!result) {
        return nullptr;
    }

    jclass clazz = env->FindClass("android/graphics/RectF");
    jmethodID constructorID = env->GetMethodID(clazz, "<init>", "(FFFF)V");
    return env->NewObject(clazz, constructorID, (jfloat) fsRectF.left, (jfloat) fsRectF.top,
                          (jfloat) fsRectF.right,
                          (jfloat) fsRectF.bottom);
}
JNI_FUNC(jint, PdfiumCore, nativeGetFindIdx)(JNI_ARGS, jlong searchPtr) {
    return FPDFText_GetSchResultIndex((FPDF_SCHHANDLE) searchPtr);
}
JNI_FUNC(jint, PdfiumCore, nativeCountRects)(JNI_ARGS, jlong textPtr, jint st, jint ed) {
    return FPDFText_CountRects((FPDF_TEXTPAGE) textPtr, st, ed);
}

JNI_FUNC(jlong, PdfiumCore, nativeGetTextOffset)(JNI_ARGS, jlong textPtr, jint st, jint ed) {
    auto textPage = reinterpret_cast<FPDF_TEXTPAGE>(textPtr);
    int rects = FPDFText_CountRects(textPage, st, ed);
    if (rects == 0) {
//        LOGE("No rects found");
        return 0;
    }

    double left, top, right, bottom;
    if (FPDFText_GetRect(textPage, 0, &left, &top, &right, &bottom)) {
        auto centerX = static_cast<float>(left);
        auto yBottom = static_cast<float>(top);
        int xBits = *reinterpret_cast<int *>(&centerX);
        int yBits = *reinterpret_cast<int *>(&yBottom);
        auto res = (static_cast<jlong>(xBits) << 32) | (yBits & 0xFFFFFFFFL);
//        LOGD("%f %f %lld", centerX, yBottom, res);
        return res;
    }
    return 0;
}

JNI_FUNC(jboolean, PdfiumCore, nativeGetRect)(JNI_ARGS, jlong pagePtr, jint offsetY, jint offsetX,
                                              jint width, jint height, jlong textPtr, jobject rect,
                                              jint idx) {
    if (init_classes) initClasses(env);
    double left, top, right, bottom;


    bool ret = FPDFText_GetRect((FPDF_TEXTPAGE) textPtr, idx, &left, &top, &right, &bottom);
    if (ret) {
        int deviceX, deviceY;
        int deviceRight, deviceBottom;
        FPDF_PageToDevice((FPDF_PAGE) pagePtr, 0, 0, width, height, 0, left, top, &deviceX,
                          &deviceY);
        FPDF_PageToDevice((FPDF_PAGE) pagePtr, 0, 0, (int) width, (int) height, 0, right, bottom,
                          &deviceRight,
                          &deviceBottom);


        // int width = right-left;
        // int height = top-bottom;
        left = deviceX + offsetX;
        top = deviceY + offsetY;
        int new_width = (int) (deviceRight - left);
        int new_height = (int) (deviceBottom - top);

        right = left + new_width;
        bottom = top + new_height;
        /* right=left+width;
         bottom=top+height;*/
        env->CallVoidMethod(rect, rectF_set, (float) left, (float) top, (float) right,
                            (float) bottom);
    }
    return ret;
}

JNI_FUNC(void, PdfiumCore, nativeFindTextPageEnd)(JNI_ARGS, jlong searchPtr) {
    FPDFText_FindClose((FPDF_SCHHANDLE) searchPtr);
}
JNI_FUNC(jint, PdfiumCore, nativeGetFindLength)(JNI_ARGS, jlong searchPtr) {
    return FPDFText_GetSchCount((FPDF_SCHHANDLE) searchPtr);
}

#include <cstring>

JNI_FUNC(jlong, PdfiumCore, nativeGetStringChars)(JNI_ARGS, jstring key) {
    if (key == nullptr) {
        return 0;
    }
    const jchar *chars = env->GetStringChars(key, nullptr);
    if (chars == nullptr) {
        return 0;
    }
    jsize length = env->GetStringLength(key);
#pragma clang diagnostic push
#pragma ide diagnostic ignored "MemoryLeak"
    auto *nativeChars = new jchar[length];
#pragma clang diagnostic pop
    std::memcpy(nativeChars, chars, length * sizeof(jchar));
    env->ReleaseStringChars(key, chars);
    return reinterpret_cast<jlong>(nativeChars);
}

JNI_FUNC(void, PdfiumCore, nativeReleaseStringChars)(JNI_ARGS, jlong keyPtr) {
    if (keyPtr == 0) {
        return;
    }
    auto *nativeChars = reinterpret_cast<jchar *>(keyPtr);
    delete[] nativeChars;
}


JNI_FUNC(jint, PdfiumCore, nativeFindTextPage)(JNI_ARGS, jlong textPtr, jstring key, jint flag) {
    const unsigned short *keyStr = env->GetStringChars(key, nullptr);
    auto text = (FPDF_TEXTPAGE) textPtr;
    int foundIdx = -1;
    if (text) {
        FPDF_SCHHANDLE findHandle = FPDFText_FindStart(text, keyStr, flag, 0);
        bool ret = FPDFText_FindNext(findHandle);
        if (ret) {
            foundIdx = FPDFText_GetSchResultIndex(findHandle);
        }
        FPDFText_FindClose(findHandle);
    }
    env->ReleaseStringChars(key, keyStr);
    return foundIdx;
}
JNI_FUNC(jboolean, PdfiumCore, nativeFindTextPageNext)(JNI_ARGS, jlong searchPtr) {
    return FPDFText_FindNext((FPDF_SCHHANDLE) searchPtr);
}
JNI_FUNC(jlong, PdfiumCore, nativeFindTextPageStart)(JNI_ARGS, jlong textPtr, jlong keyStr,
                                                     jint flag, jint startIdx) {
    //const unsigned short * keyStr = env->GetStringChars(key, 0);
    FPDF_SCHHANDLE findHandle = FPDFText_FindStart((FPDF_TEXTPAGE) textPtr, (const jchar *) keyStr,
                                                   flag, startIdx);
    return (jlong) findHandle;
}
JNI_FUNC(jobject, PdfiumCore, nativePageCoordsToDevice)(JNI_ARGS, jlong pagePtr, jint startX,
                                                        jint startY, jint sizeX,
                                                        jint sizeY, jint rotate, jdouble pageX,
                                                        jdouble pageY) {
    auto page = reinterpret_cast<FPDF_PAGE>(pagePtr);
    int deviceX, deviceY;

    FPDF_PageToDevice(page, startX, startY, sizeX, sizeY, rotate, pageX, pageY, &deviceX, &deviceY);

    jclass clazz = env->FindClass("android/graphics/Point");
    jmethodID constructorID = env->GetMethodID(clazz, "<init>", "(II)V");
    return env->NewObject(clazz, constructorID, deviceX, deviceY);
}
static jlong loadTextPageInternal(JNIEnv *env, FPDF_PAGE page) {
    try {
        FPDF_TEXTPAGE text = FPDFText_LoadPage(page);
        if (page == nullptr) {
            return -1;
        }
        return reinterpret_cast<jlong>(text);
    } catch (const char *msg) {
        LOGE("%s", msg);

        jniThrowException(env, "java/lang/IllegalStateException",
                          "cannot load text");

        return -1;
    }
}
JNI_FUNC(jlong, PdfiumCore, nativeLoadTextPage)(JNI_ARGS, jlong pagePtr) {
    return loadTextPageInternal(env, (FPDF_PAGE) pagePtr);
}
JNI_FUNC(jboolean, PdfiumCore, closeTextPage)(JNI_ARGS, jlong pagePtr) {
    FPDFText_ClosePage((FPDF_TEXTPAGE) pagePtr);
    return JNI_TRUE;
}


jobject createBitmap(JNIEnv *env, int w, int h) {
    // Get the Bitmap class and createBitmap method
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmapMethod = env->GetStaticMethodID(
            bitmapClass,
            "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );

    // Get the Bitmap.Config class and ARGB_8888 field
    jclass bitmapConfigClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(bitmapConfigClass, "ARGB_8888",
                                                   "Landroid/graphics/Bitmap$Config;");
    jobject argb8888Config = env->GetStaticObjectField(bitmapConfigClass, argb8888Field);

    // Create a Bitmap using Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, w, h,
                                                 argb8888Config);

    if (!bitmap) {
        // Throw an exception if bitmap creation fails
        jclass runtimeExceptionClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(runtimeExceptionClass, "Failed to create Bitmap");
        return nullptr;
    }

    return bitmap;
}


static jobject NewTextImage(JNIEnv *env, jfloat left, jfloat top, jfloat right, jfloat bottom,
                            jboolean isImage, jobject bitmap, jstring text) {
    jclass textImageClass = env->FindClass("com/vivlio/android/pdfium/TextImage");
    if (textImageClass == nullptr) {
        LOGE("Failed to find TextImage class");
        return nullptr; // Handle class not found error
    }
    jmethodID constructor = env->GetMethodID(textImageClass, "<init>",
                                             "(FFFFZLjava/lang/String;Landroid/graphics/Bitmap;)V");
    if (constructor == nullptr) {
        LOGE("Failed to find TextImage constructor");
        return nullptr;
    }
    jobject textImage = env->NewObject(textImageClass,
                                       constructor,
                                       left, top, right, bottom,
                                       isImage,
                                       text,
                                       bitmap
    );
    return textImage;
}

JNI_FUNC(jobjectArray, PdfiumCore, nativeFindBitmaps)(JNI_ARGS, jlong pagePtr) {
    auto page = (FPDF_PAGE) pagePtr;
    if (!page) return nullptr;

    // Get the JNIEnv and Bitmap class
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (!bitmapClass) {
        LOGE("Failed to find android/graphics/Bitmap class");
        return nullptr;
    }

    // Create an empty vector for textImages
    std::vector<jobject> textImages;

    // Iterate through page objects
    for (int i = 0; i < FPDFPage_CountObjects(page); i++) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        int objType = FPDFPageObj_GetType(obj);
        if (objType == FPDF_PAGEOBJ_IMAGE) {
            FPDF_BITMAP pdfBitmap = FPDFImageObj_GetBitmap(obj);

            if (!pdfBitmap) {
                LOGE("Failed to get FPDF bitmap");
                continue;
            }
            float left, top, right, bottom;
            FPDFPageObj_GetBounds(obj, &left, &bottom, &right, &top);
            // Get bitmap dimensions and buffer
            int width = FPDFBitmap_GetWidth(pdfBitmap);
            int height = FPDFBitmap_GetHeight(pdfBitmap);
            int stride = FPDFBitmap_GetStride(pdfBitmap);
            auto format = FPDFBitmap_GetFormat(pdfBitmap);
            if (format == FPDFBitmap_Unknown) {
                LOGE("Unsupported bitmap format");
                FPDFBitmap_Destroy(pdfBitmap);
                continue;
            }

            auto *srcBuffer = (unsigned char *) FPDFBitmap_GetBuffer(pdfBitmap);
            if (!srcBuffer) {
                LOGE("Failed to get buffer from FPDF bitmap");
                FPDFBitmap_Destroy(pdfBitmap);
                continue;
            }

            // Create a Java Bitmap object
            jobject androidBitmap = createBitmap(env, width, height);
            if (!androidBitmap) {
                LOGE("Failed to create Android Bitmap");
                FPDFBitmap_Destroy(pdfBitmap);
                continue;
            }

            // Access and fill pixel data into the Java Bitmap
            AndroidBitmapInfo info;
            if (AndroidBitmap_getInfo(env, androidBitmap, &info) == ANDROID_BITMAP_RESULT_SUCCESS) {
                void *dstPixels;
                if (AndroidBitmap_lockPixels(env, androidBitmap, &dstPixels) ==
                    ANDROID_BITMAP_RESULT_SUCCESS) {

                    if (format == FPDFBitmap_Gray) {
                        for (int y = 0; y < height; ++y) {
                            unsigned char *srcRow = srcBuffer + y * stride;
                            auto *dstRow = (uint32_t *) ((char *) dstPixels + y * info.stride);

                            for (int x = 0; x < width; ++x) {
                                unsigned char gray = srcRow[x];
                                unsigned char a = 0xFF; // Full opacity
                                dstRow[x] = (a << 24) | (gray << 16) | (gray << 8) | gray;
                            }
                        }
                    }

                    if (format == FPDFBitmap_BGR) {
                        for (int y = 0; y < height; ++y) {
                            unsigned char *srcRow = srcBuffer + y * stride;
                            auto *dstRow = (uint32_t *) ((char *) dstPixels + y * info.stride);

                            for (int x = 0; x < width; ++x) {
                                unsigned char r = srcRow[x * 3 + 0];
                                unsigned char g = srcRow[x * 3 + 1];
                                unsigned char b = srcRow[x * 3 + 2];
                                unsigned char a = 0xFF; // Full opacity
                                dstRow[x] = (a << 24) | (r << 16) | (g << 8) | b;
                            }
                        }
                    }

                    if (format == FPDFBitmap_BGRA) {
                        for (int y = 0; y < height; ++y) {
                            unsigned char *srcRow = srcBuffer + y * stride;
                            auto *dstRow = (uint32_t *) ((char *) dstPixels + y * info.stride);

                            for (int x = 0; x < width; ++x) {
                                unsigned char b = srcRow[x * 4 + 0];
                                unsigned char g = srcRow[x * 4 + 1];
                                unsigned char r = srcRow[x * 4 + 2];
                                unsigned char a = srcRow[x * 4 + 3];
                                dstRow[x] = (a << 24) | (r << 16) | (g << 8) | b;
                            }
                        }
                    }

                    if (format == FPDFBitmap_BGRx) {
                        for (int y = 0; y < height; ++y) {
                            unsigned char *srcRow = srcBuffer + y * stride;
                            auto *dstRow = (uint32_t *) ((char *) dstPixels + y * info.stride);

                            for (int x = 0; x < width; ++x) {
                                unsigned char b = srcRow[x * 4 + 0];
                                unsigned char g = srcRow[x * 4 + 1];
                                unsigned char r = srcRow[x * 4 + 2];
                                unsigned char a = 0xFF; // Full opacity
                                dstRow[x] = (a << 24) | (r << 16) | (g << 8) | b;
                            }
                        }
                    }


                    AndroidBitmap_unlockPixels(env, androidBitmap);
                } else {
                    LOGE("Failed to lock pixels for Android Bitmap");
                }
            } else {
                LOGE("Failed to get info for Android Bitmap");
            }

            // Add the Android Bitmap to the vector
            textImages.push_back(NewTextImage(env, left, top, right, bottom,
                                              JNI_TRUE,
                                              androidBitmap,
                                              nullptr));
            env->DeleteLocalRef(androidBitmap);
            FPDFBitmap_Destroy(pdfBitmap);
        }
    }

    // Create a Java array of Bitmaps
    jobjectArray textImageArray = env->NewObjectArray(
            (jsize) textImages.size(),
            env->FindClass("com/vivlio/android/pdfium/TextImage"), nullptr);
    for (jsize i = 0; i < textImages.size(); i++) {
        env->SetObjectArrayElement(textImageArray, (jsize) i, textImages[i]);
        env->DeleteLocalRef(textImages[i]);
    }

    return textImageArray;
}


JNI_FUNC(jobjectArray, PdfiumCore, extractText)(JNI_ARGS, jlong textPtr) {
    if (!textPtr) return nullptr;
    auto textPage = (FPDF_TEXTPAGE) textPtr;
    auto total = FPDFText_CountChars(textPage);
    auto rectS = FPDFText_CountRects(textPage, 0, total);
    double left, top, right, bottom;
    std::vector<jobject> textImages(rectS);
    for (int i = 0; i < rectS; ++i) {
        FPDFText_GetRect(textPage, i, &left, &top, &right, &bottom);
        auto len = FPDFText_GetBoundedText(textPage,
                                           left,
                                           top, right, bottom,
                                           nullptr, 0) + 1;
        std::vector<unsigned short> buffer(len);
        FPDFText_GetBoundedText(textPage, left, top, right, bottom, buffer.data(), len);
        std::u16string utf16Text(reinterpret_cast<char16_t *>(buffer.data()), len);
        std::string utf8Text;
        utf8Text.reserve(len);
        for (char16_t c: utf16Text) {
            if (c < 0x80) {
                utf8Text += static_cast<char>(c);
            } else if (c < 0x800) {
                utf8Text += static_cast<char>(0xC0 | ((c >> 6) & 0x1F));
                utf8Text += static_cast<char>(0x80 | (c & 0x3F));
            } else {
                utf8Text += static_cast<char>(0xE0 | ((c >> 12) & 0x0F));
                utf8Text += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
                utf8Text += static_cast<char>(0x80 | (c & 0x3F));
            }
        }
        textImages[i] = NewTextImage(
                env,
                (jfloat) left,
                (jfloat) top,
                (jfloat) right,
                (jfloat) bottom,
                JNI_FALSE,
                nullptr,
                env->NewStringUTF(utf8Text.c_str())
        );
    }

    jobjectArray arr = env->NewObjectArray((jsize) textImages.size(),
                                           env->FindClass("com/vivlio/android/pdfium/TextImage"),
                                           nullptr);

    for (int i = 0; i < textImages.size(); ++i) {
        env->SetObjectArrayElement(arr, i, textImages[i]);
        env->DeleteLocalRef(textImages[i]);
    }

    return arr;
}

}//extern C
