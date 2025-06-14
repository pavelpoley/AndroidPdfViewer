package com.vivlio.android.pdfium;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.vivlio.android.pdfium.util.Size;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@SuppressWarnings("unused")
public class PdfiumCore {


    private static final String TAG = PdfiumCore.class.getName();
    private static final Class<FileDescriptor> FD_CLASS = FileDescriptor.class;
    private static final String FD_FIELD_NAME = "descriptor";
    /* synchronize native methods */
    private static final Object lock = new Object();
    private final int mCurrentDpi;

    static {
        try {
//            System.loadLibrary("pdfsdk");
            System.loadLibrary("pdfium");
            System.loadLibrary("libpdfium");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native libraries failed to load - " + e);
        }
    }

    /**
     * Context needed to get screen density
     */
    public PdfiumCore(Context ctx) {
        mCurrentDpi = ctx.getResources().getDisplayMetrics().densityDpi;
        Log.d(TAG, "Starting PdfiumAndroid ");
    }

    @Nullable
    private native TextImage[] nativeFindBitmaps(long pagePtr);

    @Nullable
    private native TextImage[] extractText(long textPtr);


    public List<TextImage> getTextImages(long pagePtr, long textPtr) {
        TextImage[] texts = extractText(textPtr);
        if (texts == null) {
            texts = new TextImage[0];
        }
        TextImage[] images = nativeFindBitmaps(pagePtr);
        if (images == null) {
            images = new TextImage[0];
        }

        var im = new TextImage[texts.length + images.length];
        System.arraycopy(texts, 0, im, 0, texts.length);
        System.arraycopy(images, 0, im, texts.length, images.length);
        Log.d(TAG, "getTextImages: " + images.length + " " + texts.length);
        return Arrays.asList(im);
    }


    private native long nativeOpenDocument(int fd, String password);

    private native long nativeOpenMemDocument(byte[] data, String password);

    private native void nativeCloseDocument(long docPtr);

    private native int nativeGetPageCount(long docPtr);

    private native long nativeLoadPage(long docPtr, int pageIndex);

    private native long[] nativeLoadPages(long docPtr, int fromIndex, int toIndex);

    private native void nativeClosePage(long pagePtr);

    private native void nativeClosePages(long[] pagesPtr);

    private native int nativeGetPageWidthPixel(long pagePtr, int dpi);

    private native int nativeGetPageHeightPixel(long pagePtr, int dpi);

    private native int nativeGetPageWidthPoint(long pagePtr);

    private native int nativeGetPageHeightPoint(long pagePtr);

    public native int nativeGetMixedLooseCharPos(long pagePtr, int offsetY, int offsetX, int width, int height, RectF pt, long tid, int index, boolean loose);


    /*For escape// private native long nativeGetNativeWindow(Surface surface);
      private native void nativeRenderPage(long pagePtr, long nativeWindowPtr);*/
    private native void nativeRenderPage(long pagePtr, Surface surface, int dpi,
                                         int startX, int startY,
                                         int drawSizeHor, int drawSizeVer,
                                         boolean renderAnnot);

    private native void nativeRenderPageBitmap(long pagePtr, Bitmap bitmap, int dpi,
                                               int startX, int startY,
                                               int drawSizeHor, int drawSizeVer,
                                               boolean renderAnnot);


    private native void nativeGetBookmarksArrayList(long docPtr, ArrayList<TOCEntry> out);

    public ArrayList<TOCEntry> getTableOfContentsNew(PdfDocument document) {
        var list = new ArrayList<TOCEntry>();
        nativeGetBookmarksArrayList(document.mNativeDocPtr, list);
        return list;
    }

    private native String nativeGetDocumentMetaText(long docPtr, String tag);

    private native Long nativeGetFirstChildBookmark(long docPtr, Long bookmarkPtr);

    private native Long nativeGetSiblingBookmark(long docPtr, long bookmarkPtr);

    private native String nativeGetBookmarkTitle(long bookmarkPtr);

    private native long nativeGetBookmarkDestIndex(long docPtr, long bookmarkPtr);

    private native void nativeSetBookmarkCoordinates(long docPtr, long bookmarkPtr,
                                                     PdfDocument.Bookmark bookmark);

    public native String nativeGetText(long textPtr);

    public native String nativeGetTextPart(long textPtr, int start, int len);

    public native int nativeGetCharIndexAtCoord(long pagePtr, double width, double height, long textPtr, double posX, double posY, double tolX, double tolY);

    private native void nativeRenderPageBitmap(long docPtr, long pagePtr, Bitmap bitmap, int dpi,
                                               int startX, int startY,
                                               int drawSizeHor, int drawSizeVer,
                                               boolean renderAnnot);

    public native long nativeGetLinkAtCoord(long pagePtr, double width, double height, double posX, double posY);

    public native String nativeGetLinkTarget(long docPtr, long linkPtr);

    public native long nativeLoadTextPage(long pagePtr);

    public native boolean closeTextPage(long pagePtr);

    private native Size nativeGetPageSizeByIndex(long docPtr, int pageIndex, int dpi);

    private native int nativeCountAndGetRects(long pagePtr, int offsetY, int offsetX, int width, int height, ArrayList<RectF> arr, long tid, int selSt, int selEd,float verticalExpandPercent);

    private native int nativeCountAndGetLineRects(long pagePtr,
                                                  int offsetY,
                                                  int offsetX,
                                                  int width,
                                                  int height,
                                                  ArrayList<RectF> arr,
                                                  long tid,
                                                  int selSt,
                                                  int selEd,
                                                  float lineThreshold,
                                                  float expandPercent);

    private native long[] nativeGetPageLinks(long pagePtr);

    private native Integer nativeGetDestPageIndex(long docPtr, long linkPtr);

    private native String nativeGetLinkURI(long docPtr, long linkPtr);

    private native RectF nativeGetLinkRect(long linkPtr);

    public native int nativeGetCharPos(long pagePtr, int offsetY, int offsetX, int width, int height, RectF pt, long tid, int index, boolean loose);

    public native long nativeFindTextPageStart(long textPtr, long keyStr, int flag, int startIdx);

    public native boolean nativeFindTextPageNext(long searchPtr);

    public native int nativeGetFindIdx(long searchPtr);

    public native int nativeGetFindLength(long searchPtr);

    public native void nativeFindTextPageEnd(long searchPtr);

    public native int nativeCountRects(long textPtr, int st, int ed);

    /**
     * Example unpacking:
     * <pre>
     *     int xBits = (int) (offset >> 32);
     *     int yBits = (int) offset;
     *     float x = Float.intBitsToFloat(xBits);
     *     float y = Float.intBitsToFloat(yBits);
     * </pre>
     *
     * @return two floats: (x,y)
     * @see #nativeGetTextOffset(long, int, int, PointF)
     */
    public static native long nativeGetTextOffset(long textPtr, int st, int ed);

    public static void nativeGetTextOffset(long textPtr, int st, int ed, PointF out) {
        long offset = nativeGetTextOffset(textPtr, st, ed);
        int xBits = (int) (offset >> 32);
        int yBits = (int) offset;
        out.x = Float.intBitsToFloat(xBits);
        out.y = Float.intBitsToFloat(yBits);
    }


    /**
     * Example unpacking:
     * <pre>
     *     int xBits = (int) (offset >> 32);
     *     int yBits = (int) offset;
     *     float x = Float.intBitsToFloat(xBits);
     *     float y = Float.intBitsToFloat(yBits);
     * </pre>
     *
     * @return two floats: (x,y)
     */
    public native long nativeGetRect(long pagePtr, int offsetY, int offsetX, int width, int height, long textPtr, RectF rect, int idx);

    public native int nativeFindTextPage(long pagePtr, String key, int flag);

    public static native long nativeGetStringChars(String key);

    public static native void nativeReleaseStringChars(long stringCharPtr);

    private native Point nativePageCoordsToDevice(long pagePtr, int startX, int startY, int sizeX,
                                                  int sizeY, int rotate, double pageX, double pageY);

    private static Field mFdField = null;

    /**
     * @noinspection JavaReflectionMemberAccess
     */
    @SuppressLint("DiscouragedPrivateApi")
    public static int getNumFd(ParcelFileDescriptor fdObj) {
        try {
            if (mFdField == null) {
                mFdField = FD_CLASS.getDeclaredField(FD_FIELD_NAME);
                mFdField.setAccessible(true);
            }

            return mFdField.getInt(fdObj.getFileDescriptor());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "getNumFd: Failed to get FileDescriptor (int)", e);
            return -1;
        }
    }

    public long openText(long pagePtr) {
        synchronized (lock) {
            return nativeLoadTextPage(pagePtr);
        }
    }

    public int getTextRects(long pagePtr, int offsetY, int offsetX, Size size, ArrayList<RectF> arr, long textPtr, int selSt, int selEd, boolean isSelectionLineMerged, float lineThreshHoldPt, float verticalExpandPercent) {
        synchronized (lock) {
            if (!isSelectionLineMerged)
                return nativeCountAndGetRects(pagePtr, offsetY, offsetX, size.getWidth(), size.getHeight(), arr, textPtr, selSt, selEd,verticalExpandPercent);

            return nativeCountAndGetLineRects(pagePtr, offsetY, offsetX, size.getWidth(), size.getHeight(), arr, textPtr, selSt, selEd,
                    lineThreshHoldPt, verticalExpandPercent);
        }
    }


    /**
     * Create new document from file
     */
    public PdfDocument newDocument(ParcelFileDescriptor fd) throws IOException {
        return newDocument(fd, null);
    }

    /**
     * Create new document from file with password
     */
    public PdfDocument newDocument(ParcelFileDescriptor fd, String password) throws IOException {
        PdfDocument document = new PdfDocument();
        document.parcelFileDescriptor = fd;
        synchronized (lock) {
            document.mNativeDocPtr = nativeOpenDocument(getNumFd(fd), password);
        }

        if (document.mNativeDocPtr == 0) {
            throw new IOException("Cannot open document");
        }

        return document;
    }

    /**
     * Create new document from bytearray
     */
    public PdfDocument newDocument(byte[] data) throws IOException {
        return newDocument(data, null);
    }

    /**
     * Create new document from bytearray with password
     */
    public PdfDocument newDocument(byte[] data, String password) throws IOException {
        PdfDocument document = new PdfDocument();
        synchronized (lock) {
            document.mNativeDocPtr = nativeOpenMemDocument(data, password);
        }
        if (document.mNativeDocPtr == 0) {
            throw new IOException("Cannot open document");
        }
        return document;
    }

    /**
     * Get total number of pages in document
     */
    public int getPageCount(PdfDocument doc) {
        synchronized (lock) {
            return nativeGetPageCount(doc.mNativeDocPtr);
        }
    }

    /**
     * Open page and store native pointer in {@link PdfDocument}
     */
    public long openPage(PdfDocument doc, int pageIndex) {
        long pagePtr;
        synchronized (lock) {
            pagePtr = nativeLoadPage(doc.mNativeDocPtr, pageIndex);
            doc.mNativePagesPtr.put(pageIndex, pagePtr);
            return pagePtr;
        }

    }

    /**
     * Open range of pages and store native pointers in {@link PdfDocument}
     */
    public long[] openPage(PdfDocument doc, int fromIndex, int toIndex) {
        long[] pagesPtr;
        synchronized (lock) {
            pagesPtr = nativeLoadPages(doc.mNativeDocPtr, fromIndex, toIndex);
            int pageIndex = fromIndex;
            for (long page : pagesPtr) {
                if (pageIndex > toIndex) break;
                doc.mNativePagesPtr.put(pageIndex, page);
                pageIndex++;
            }

            return pagesPtr;
        }
    }

    /**
     * Get page width in pixels. <br>
     * This method requires page to be opened.
     */
    public int getPageWidth(PdfDocument doc, int index) {
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageWidthPixel(pagePtr, mCurrentDpi);
            }
            return 0;
        }
    }

    /**
     * Get page height in pixels. <br>
     * This method requires page to be opened.
     */
    public int getPageHeight(PdfDocument doc, int index) {
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageHeightPixel(pagePtr, mCurrentDpi);
            }
            return 0;
        }
    }

    /**
     * Get page width in PostScript points (1/72th of an inch).<br>
     * This method requires page to be opened.
     */
    public int getPageWidthPoint(PdfDocument doc, int index) {
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageWidthPoint(pagePtr);
            }
            return 0;
        }
    }

    /**
     * Get page height in PostScript points (1/72th of an inch).<br>
     * This method requires page to be opened.
     */
    public int getPageHeightPoint(PdfDocument doc, int index) {
        synchronized (lock) {
            Long pagePtr;
            if ((pagePtr = doc.mNativePagesPtr.get(index)) != null) {
                return nativeGetPageHeightPoint(pagePtr);
            }
            return 0;
        }
    }

    /**
     * Get size of page in pixels.<br>
     * This method does not require given page to be opened.
     */
    public Size getPageSize(PdfDocument doc, int index) {
        synchronized (lock) {
            return nativeGetPageSizeByIndex(doc.mNativeDocPtr, index, mCurrentDpi);
        }
    }

    /**
     * Render page fragment on {@link Surface}.<br>
     * Page must be opened before rendering.
     */
    public void renderPage(PdfDocument doc, Surface surface, int pageIndex,
                           int startX, int startY, int drawSizeX, int drawSizeY) {
        renderPage(doc, surface, pageIndex, startX, startY, drawSizeX, drawSizeY, false);
    }

    /**
     * Render page fragment on {@link Surface}. This method allows to render annotations.<br>
     * Page must be opened before rendering.
     */
    public void renderPage(PdfDocument doc, Surface surface, int pageIndex,
                           int startX, int startY, int drawSizeX, int drawSizeY,
                           boolean renderAnnot) {
        synchronized (lock) {
            try {
                //nativeRenderPage(doc.mNativePagesPtr.get(pageIndex), surface, mCurrentDpi);
                Long pagePtr = doc.mNativePagesPtr.get(pageIndex);
                if (pagePtr == null) return;
                nativeRenderPage(pagePtr, surface, mCurrentDpi,
                        startX, startY, drawSizeX, drawSizeY, renderAnnot);
            } catch (NullPointerException e) {
                Log.e(TAG, "mContext may be null");
            } catch (Exception e) {
                Log.e(TAG, "Exception throw from native");
            }
        }
    }

    /**
     * Render page fragment on {@link Bitmap}.<br>
     * Page must be opened before rendering.
     * <p>
     * Supported bitmap configurations:
     * <ul>
     * <li>ARGB_8888 - best quality, high memory usage, higher possibility of OutOfMemoryError
     * <li>RGB_565 - little worse quality, twice less memory usage
     * </ul>
     */
    public void renderPageBitmap(PdfDocument doc, Bitmap bitmap, int pageIndex,
                                 int startX, int startY, int drawSizeX, int drawSizeY) {
        renderPageBitmap(doc, bitmap, pageIndex, startX, startY, drawSizeX, drawSizeY, false);
    }

    /**
     * Render page fragment on {@link Bitmap}. This method allows to render annotations.<br>
     * Page must be opened before rendering.
     * <p>
     * For more info see {@link PdfiumCore#renderPageBitmap(PdfDocument, Bitmap, int, int, int, int, int)}
     */
    public void renderPageBitmap(@NonNull PdfDocument doc,
                                 @NonNull Bitmap bitmap,
                                 int pageIndex,
                                 int startX, int startY,
                                 int drawSizeX, int drawSizeY,
                                 boolean renderAnnot) {
        synchronized (lock) {
            try {
                Objects.requireNonNull(bitmap);
                long pagePtr = Objects.requireNonNull(doc.mNativePagesPtr.get(pageIndex));
                nativeRenderPageBitmap(pagePtr, bitmap, mCurrentDpi,
                        startX, startY, drawSizeX, drawSizeY, renderAnnot);
            } catch (NullPointerException e) {
                Log.e(TAG, "renderPageBitmap: ", e);
            } catch (Exception e) {
                Log.e(TAG, "Exception throw from native", e);
            }
        }
    }

    /**
     * Release native resources and opened file
     */
    public void closeDocument(PdfDocument doc) {
        synchronized (lock) {
            for (Integer index : doc.mNativeTextPtr.keySet()) {
                Long textPtr = doc.mNativeTextPtr.get(index);
                if (textPtr != null) {
                    closeTextPage(textPtr);
                }
            }
            doc.mNativeTextPtr.clear();
            for (Integer index : doc.mNativePagesPtr.keySet()) {
                Long pagePtr = doc.mNativePagesPtr.get(index);
                if (pagePtr != null) {
                    nativeClosePage(pagePtr);
                }
            }
            doc.mNativePagesPtr.clear();

            nativeCloseDocument(doc.mNativeDocPtr);

            if (doc.parcelFileDescriptor != null) { //if document was loaded from file
                try {
                    doc.parcelFileDescriptor.close();
                } catch (IOException e) {
                    /* ignore */
                }
                doc.parcelFileDescriptor = null;
            }
        }
    }

    /**
     * Get metadata for given document
     */
    public PdfDocument.Meta getDocumentMeta(PdfDocument doc) {
        synchronized (lock) {
            PdfDocument.Meta meta = new PdfDocument.Meta();
            meta.title = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Title");
            meta.author = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Author");
            meta.subject = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Subject");
            meta.keywords = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Keywords");
            meta.creator = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Creator");
            meta.producer = nativeGetDocumentMetaText(doc.mNativeDocPtr, "Producer");
            meta.creationDate = nativeGetDocumentMetaText(doc.mNativeDocPtr, "CreationDate");
            meta.modDate = nativeGetDocumentMetaText(doc.mNativeDocPtr, "ModDate");

            return meta;
        }
    }

    /**
     * Get table of contents (bookmarks) for given document
     */
    public List<PdfDocument.Bookmark> getTableOfContents(PdfDocument doc) {
        synchronized (lock) {
            List<PdfDocument.Bookmark> topLevel = new ArrayList<>();
            Long first = nativeGetFirstChildBookmark(doc.mNativeDocPtr, null);
            if (first != null) {
                recursiveGetBookmark(topLevel, doc, first);
            }
            return topLevel;
        }
    }

    private void recursiveGetBookmark(List<PdfDocument.Bookmark> tree, PdfDocument doc, long bookmarkPtr) {
        PdfDocument.Bookmark bookmark = new PdfDocument.Bookmark();
        bookmark.mNativePtr = bookmarkPtr;
        bookmark.title = nativeGetBookmarkTitle(bookmarkPtr);
        nativeSetBookmarkCoordinates(doc.mNativeDocPtr, bookmarkPtr, bookmark);
        tree.add(bookmark);

        Long child = nativeGetFirstChildBookmark(doc.mNativeDocPtr, bookmarkPtr);
        if (child != null) {
            recursiveGetBookmark(bookmark.getChildren(), doc, child);
        }

        Long sibling = nativeGetSiblingBookmark(doc.mNativeDocPtr, bookmarkPtr);
        if (sibling != null) {
            recursiveGetBookmark(tree, doc, sibling);
        }
    }

    /**
     * Get all links from given page
     */
    public List<PdfDocument.Link> getPageLinks(PdfDocument doc, int pageIndex) {
        synchronized (lock) {
            List<PdfDocument.Link> links = new ArrayList<>();
            Long nativePagePtr = doc.mNativePagesPtr.get(pageIndex);
            if (nativePagePtr == null) {
                return links;
            }
            long[] linkPtrs = nativeGetPageLinks(nativePagePtr);
            for (long linkPtr : linkPtrs) {
                Integer index = nativeGetDestPageIndex(doc.mNativeDocPtr, linkPtr);
                String uri = nativeGetLinkURI(doc.mNativeDocPtr, linkPtr);

                RectF rect = nativeGetLinkRect(linkPtr);
                if (rect != null && (index != null || uri != null)) {
                    links.add(new PdfDocument.Link(rect, index, uri));
                }

            }
            return links;
        }
    }

    /**
     * Map page coordinates to device screen coordinates
     *
     * @param doc       pdf document
     * @param pageIndex index of page
     * @param startX    left pixel position of the display area in device coordinates
     * @param startY    top pixel position of the display area in device coordinates
     * @param sizeX     horizontal size (in pixels) for displaying the page
     * @param sizeY     vertical size (in pixels) for displaying the page
     * @param rotate    page orientation: 0 (normal), 1 (rotated 90 degrees clockwise),
     *                  2 (rotated 180 degrees), 3 (rotated 90 degrees counter-clockwise)
     * @param pageX     X value in page coordinates
     * @param pageY     Y value in page coordinate
     * @return mapped coordinates
     */
    public Point mapPageCoordsToDevice(PdfDocument doc, int pageIndex, int startX, int startY, int sizeX,
                                       int sizeY, int rotate, double pageX, double pageY) {
        long pagePtr = Objects.requireNonNull(doc.mNativePagesPtr.get(pageIndex));
        return nativePageCoordsToDevice(pagePtr, startX, startY, sizeX, sizeY, rotate, pageX, pageY);
    }

    /**
     * @return mapped coordinates
     * @see PdfiumCore#mapPageCoordsToDevice(PdfDocument, int, int, int, int, int, int, double, double)
     */
    public RectF mapRectToDevice(PdfDocument doc, int pageIndex, int startX, int startY, int sizeX,
                                 int sizeY, int rotate, RectF coords) {

        Point leftTop = mapPageCoordsToDevice(doc, pageIndex, startX, startY, sizeX, sizeY, rotate,
                coords.left, coords.top);
        Point rightBottom = mapPageCoordsToDevice(doc, pageIndex, startX, startY, sizeX, sizeY, rotate,
                coords.right, coords.bottom);
        return new RectF(leftTop.x, leftTop.y, rightBottom.x, rightBottom.y);
    }


}
