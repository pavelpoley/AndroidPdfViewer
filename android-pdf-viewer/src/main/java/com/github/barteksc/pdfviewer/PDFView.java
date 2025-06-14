/**
 * Copyright 2016 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer;

import static com.github.barteksc.pdfviewer.util.Constants.Pinch.MAXIMUM_ZOOM;
import static com.github.barteksc.pdfviewer.util.Constants.Pinch.MINIMUM_ZOOM;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.github.barteksc.pdfviewer.exception.PageRenderingException;
import com.github.barteksc.pdfviewer.link.DefaultLinkHandler;
import com.github.barteksc.pdfviewer.link.LinkHandler;
import com.github.barteksc.pdfviewer.listener.Callbacks;
import com.github.barteksc.pdfviewer.listener.OnDrawListener;
import com.github.barteksc.pdfviewer.listener.OnErrorListener;
import com.github.barteksc.pdfviewer.listener.OnHighlightClickListener;
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener;
import com.github.barteksc.pdfviewer.listener.OnLongPressListener;
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener;
import com.github.barteksc.pdfviewer.listener.OnPageErrorListener;
import com.github.barteksc.pdfviewer.listener.OnPageScrollListener;
import com.github.barteksc.pdfviewer.listener.OnRenderListener;
import com.github.barteksc.pdfviewer.listener.OnScaleListener;
import com.github.barteksc.pdfviewer.listener.OnSearchBeginListener;
import com.github.barteksc.pdfviewer.listener.OnSearchEndListener;
import com.github.barteksc.pdfviewer.listener.OnSearchMatchListener;
import com.github.barteksc.pdfviewer.listener.OnSelectionListener;
import com.github.barteksc.pdfviewer.listener.OnTapListener;
import com.github.barteksc.pdfviewer.listener.OnTextSelectionListener;
import com.github.barteksc.pdfviewer.model.Decoration;
import com.github.barteksc.pdfviewer.model.Highlight;
import com.github.barteksc.pdfviewer.model.PagePart;
import com.github.barteksc.pdfviewer.model.SearchRecord;
import com.github.barteksc.pdfviewer.model.SearchRecordItem;
import com.github.barteksc.pdfviewer.model.SentencedSearchResult;
import com.github.barteksc.pdfviewer.scroll.ScrollHandle;
import com.github.barteksc.pdfviewer.source.AssetSource;
import com.github.barteksc.pdfviewer.source.ByteArraySource;
import com.github.barteksc.pdfviewer.source.DocumentSource;
import com.github.barteksc.pdfviewer.source.FileSource;
import com.github.barteksc.pdfviewer.source.InputStreamSource;
import com.github.barteksc.pdfviewer.source.UriSource;
import com.github.barteksc.pdfviewer.util.ArrayUtils;
import com.github.barteksc.pdfviewer.util.Constants;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.github.barteksc.pdfviewer.util.MathUtils;
import com.github.barteksc.pdfviewer.util.SearchUtils;
import com.github.barteksc.pdfviewer.util.SentenceExtractor;
import com.github.barteksc.pdfviewer.util.SnapEdge;
import com.github.barteksc.pdfviewer.util.Util;
import com.vivlio.android.pdfium.PdfDocument;
import com.vivlio.android.pdfium.PdfiumCore;
import com.vivlio.android.pdfium.TOCEntry;
import com.vivlio.android.pdfium.util.Size;
import com.vivlio.android.pdfium.util.SizeF;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * It supports animations, zoom, cache, and swipe.
 * <p>
 * To fully understand this class you must know its principles :
 * - The PDF document is seen as if we always want to draw all the pages.
 * - The thing is that we only draw the visible parts.
 * - All parts are the same size, this is because we can't interrupt a native page rendering,
 * so we need these renderings to be as fast as possible, and be able to interrupt them
 * as soon as we can.
 * - The parts are loaded when the current offset or the current zoom level changes
 * <p>
 * Important :
 * - DocumentPage = A page of the PDF document.
 * - UserPage = A page as defined by the user.
 * By default, they're the same. But the user can change the pages order
 * using {@link #load(DocumentSource, String, int[])}. In this
 * particular case, a userPage of 5 can refer to a documentPage of 17.
 */
@SuppressWarnings("unused")
public class PDFView extends RelativeLayout {
    PointF sCursorPos = new PointF();
    final float[] srcArray = new float[8];
    final float[] dstArray = new float[8];
    private PDocSearchTask task;
    float lineHeightStart;
    float lineHeightEnd;
    private static final String TAG = PDFView.class.getSimpleName();
    final RectF handleLeftPos = new RectF(), handleRightPos = new RectF();
    boolean hasSelection;
    public boolean isSearching;
    public boolean startInDrag;
    int selPageSt = -1;
    int selPageEd;
    int selStart;
    int selEnd;

    private boolean lockHorizontalScroll = false;
    private boolean lockVerticalScroll = false;

    @Nullable
    private List<TOCEntry> tocEntries;


    final Matrix matrix = new Matrix();

    private float minZoom = MINIMUM_ZOOM,
            midZoom = (MINIMUM_ZOOM + MAXIMUM_ZOOM) / 2,
            maxZoom = MAXIMUM_ZOOM;
    public DisplayMetrics dm;
    private final double cos = 1;//Math.cos(0);
    private final double sin = 0;//Math.sin(0);
    final Map<Integer, SearchRecord> searchRecords = new ArrayMap<>();

    SearchRecordItem currentFocusedSearchItem = null;
    private int index = -1;
    //    private int currentMatchedWordIndex = 0;
    private int searchMatchedCount = 0;


    public ArrayList<Decoration> decorations = new ArrayList<>();
    private ScrollDir scrollDir;
    boolean isSelectionLineMerged = false;
    float lineThreshHoldPt;
    float verticalExpandPercent;

    public void setIsSearching(boolean isSearching) {
        this.isSearching = isSearching;
        redrawSel();
    }

    public void setMatrixArray(float[] array, float f0, float f1, float f2, float f3, float f4, float f5, float f6, float f7) {
        array[0] = f0;
        array[1] = f1;
        array[2] = f2;
        array[3] = f3;
        array[4] = f4;
        array[5] = f5;
        array[6] = f6;
        array[7] = f7;
    }

    public void notifyItemAdded(PDocSearchTask pDocSearchTask,
                                ArrayList<SearchRecord> arr,
                                SearchRecord schRecord,
                                int page,
                                String query) {
        searchRecords.put(page, schRecord);
        List<SearchRecordItem> data = getAllMatchOnPage(schRecord);
        if (pdfFile == null || pdfFile.pdfDocument == null) return;
        int totalRecord = data.size();
        if (!data.isEmpty()) {
            SearchRecordItem item = data.get(0);
            if (currentFocusedSearchItem == null) {
                currentFocusedSearchItem = item;
                index = 0;
                if (currentPage != item.pageIndex) {
                    ContextCompat.getMainExecutor(getContext()).execute(() -> jumpTo(item.pageIndex));
                }
            }
        }
        searchMatchedCount += totalRecord;
        try {
            ContextCompat.getMainExecutor(getContext())
                    .execute(
                            () -> this.callbacks.callOnSearchMatch(page, totalRecord, query)
                    );
        } catch (Exception ignored) {
        }
    }

    public int getSearchMatchedCount() {
        return searchMatchedCount;
    }

    public boolean getHasSelection() {
        return hasSelection;
    }

    /**
     * START - scrolling in first page direction
     * END - scrolling in last page direction
     * NONE - not scrolling
     */
    enum ScrollDir {
        NONE, START, END
    }

    public PDocSelection selectionPaintView;

    public void setSelectionPaintView(PDocSelection sv) {
        selectionPaintView = sv;
        sv.pdfView = this;
        sv.resetSel();
    }


    public boolean isNotCurrentPage(long tid) {
        return (dragPinchManager.currentTextPtr != 0 && tid != dragPinchManager.currentTextPtr);
    }

    public void redrawSel() {
        if (selectionPaintView != null) {
            selectionPaintView.invalidate();
        }
    }

    /**
     * Rendered parts go to the cache manager
     */
    CacheManager cacheManager;

    /**
     * Animation manager manage all offset and zoom animation
     */
    private AnimationManager animationManager;

    /**
     * Drag manager manage all touch events
     */
    DragPinchManager dragPinchManager;

    PdfFile pdfFile;

    /**
     * The index of the current sequence
     */
    public int currentPage;

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    private float currentXOffset = 0;

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    private float currentYOffset = 0;

    /**
     * The zoom level, always >= 1
     */
    private float zoom = 1f;

    /**
     * True if the PDFView has been recycled
     */
    private boolean recycled = true;

    /**
     * Current state of the view
     */
    private State state = State.DEFAULT;

    /**
     * Async task used during the loading phase to decode a PDF document
     */
    private DecodingAsyncTask decodingAsyncTask;

    /**
     * The thread {@link #renderingHandler} will run on
     */
    private HandlerThread renderingHandlerThread;
    /**
     * Handler always waiting in the background and rendering tasks
     */
    RenderingHandler renderingHandler;

    private PagesLoader pagesLoader;

    Callbacks callbacks = new Callbacks();

    /**
     * Paint object for drawing
     */
    private Paint paint;

    /**
     * Paint object for drawing debug stuff
     */
    private Paint debugPaint;

    /**
     * Policy for fitting pages to screen
     */
    private FitPolicy pageFitPolicy = FitPolicy.WIDTH;

    private boolean fitEachPage = false;

    private int defaultPage = 0;

    /**
     * True if should scroll through pages vertically instead of horizontally
     */
    private boolean swipeVertical = true;

    private boolean enableSwipe = true;

    private boolean doubleTapEnabled = true;

    private boolean nightMode = false;

    private boolean pageSnap = true;

    /**
     * Pdfium core for loading and rendering PDFs
     */
    public PdfiumCore pdfiumCore;

    public ScrollHandle scrollHandle;

    private boolean isScrollHandleInit = false;

    ScrollHandle getScrollHandle() {
        return scrollHandle;
    }

    /**
     * True if bitmap should use ARGB_8888 format and take more memory
     * False if bitmap should be compressed by using RGB_565 format and take less memory
     */
    private boolean bestQuality = false;

    /**
     * True if annotations should be rendered
     * False otherwise
     */
    private boolean annotationRendering = false;

    /**
     * True if the view should render during scaling<br/>
     * Can not be forced on older API versions (< Build.VERSION_CODES.KITKAT) as the GestureDetector does
     * not detect scrolling while scaling.<br/>
     * False otherwise
     */
    private boolean renderDuringScale = false;

    /**
     * Antialiasing and bitmap filtering
     */
    private boolean enableAntialiasing = true;
    private final PaintFlagsDrawFilter antialiasFilter =
            new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public View hideView = null;

    /**
     * Spacing between pages, in px
     */
    private int spacingPx = 0;
    public Resources mResource;
    /**
     * Add dynamic spacing to fit each page separately on the screen.
     */

    private int spacingTopPx = 0;

    private int spacingBottomPx = 0;

    private boolean initialRender = true;

    /**
     * Add dynamic spacing to fit each page separately on the screen.
     */
    private boolean autoSpacing = false;

    /**
     * Fling a single page at a time
     */
    private boolean pageFling = true;

    /**
     * Pages numbers used when calling onDrawAllListener
     */
    private final List<Integer> onDrawPagesNumbers = new ArrayList<>(10);

    /**
     * Holds info whether view has been added to layout and has width and height
     */
    private boolean hasSize = false;

    /**
     * Holds last used Configurator that should be loaded when view has size
     */
    private Configurator waitingDocumentConfigurator;

    /**
     * Construct the initial view
     */

    public PDFView(Context context) {
        super(context);
        init(context);
    }

    public PDFView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public PDFView(Context context, AttributeSet attrs, int defaultStyle) {
        super(context, attrs, defaultStyle);
        init(context);
    }

    private void init(Context context) {
        scrollDir = ScrollDir.NONE;
        renderingHandlerThread = new HandlerThread("PDF renderer");
        if (isInEditMode()) {
            return;
        }
        cacheManager = new CacheManager();
        animationManager = new AnimationManager(this);
        dragPinchManager = new DragPinchManager(this, animationManager);
        pagesLoader = new PagesLoader(this);

        paint = new Paint();
        debugPaint = new Paint();
        debugPaint.setStyle(Style.STROKE);

        pdfiumCore = new PdfiumCore(context);
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        mResource = getResources();
        dm = mResource.getDisplayMetrics();
        display.getMetrics(dm);
        setWillNotDraw(false);
    }

    public void clearSelection() {
        dragPinchManager.currentTextPtr = 0;
        hasSelection = false;
        redrawSel();
    }

    public int getScreenWidth() {
        int ret = 0;
        if (dm != null) ret = dm.widthPixels;
        return ret;
    }

    public int getScreenHeight() {
        int ret = 0;
        if (dm != null) ret = dm.heightPixels;
        return ret;
    }

    int lastSz = 0;

    public void closeTask() {
        if (task != null) {
            task.abort();
        }
        task = null;
    }

    // Search methods
    public void search(@Nullable String text) {
        searchMatchedCount = 0;
        searchRecords.clear();
        currentFocusedSearchItem = null;
        index = -1;
        if (task != null) {
            closeTask();
        }
        if (TextUtils.isEmpty(text)) return;
        setIsSearching(true);
        task = new PDocSearchTask(this, text);
        task.start();
    }

    public void clearSearch() {
        this.searchRecords.clear();
        closeTask();
        selectionPaintView.invalidate();
    }


    public boolean navigateToNextSearchItem() {
        return navigateToNextSearchItem(false);
    }

    /**
     * @return true if navigated
     */
    public boolean navigateToNextSearchItem(boolean jumpToPageWithOffset) {
        if (this.currentFocusedSearchItem == null || index == -1) return false;
        var page = this.currentFocusedSearchItem.pageIndex;
        var record = searchRecords.get(page);
        if (record == null || record.data == null) return false;
        var nextIndex = index + 1;
        if (ArrayUtils.isValidIndex(record.data, nextIndex)) {
            currentFocusedSearchItem = record.data.get(nextIndex);
            index = nextIndex;
            jumpTo(page, true);
            redrawSel();
            return true;
        }
        for (int i = page + 1; i < this.pdfFile.getPagesCount(); i++) {
            SearchRecord record1 = searchRecords.get(i);
            if (record1 != null) {
                var item = ArrayUtils.getElementSafe(record1.data, 0);
                if (item != null) {
                    currentFocusedSearchItem = item;
                    index = 0;
                    jumpTo(i, true);
                    redrawSel();
                    return true;
                }
            }
        }
        return false;
    }

    public boolean navigateToSearchItem(SentencedSearchResult result, boolean jumpToPageWithOffset) {
        return navigateToSearchItem(
                result.getPageIndex(),
                result.getSearchItemIndex(),
                jumpToPageWithOffset);
    }

    public boolean navigateToSearchItem(long recordId, boolean jumpToPageWithOffset) {
        int pageIndex = SentencedSearchResult.unpackPageIndex(recordId);
        int searchItemIndex = SentencedSearchResult.unpackSearchItemIndex(recordId);
        return navigateToSearchItem(pageIndex, searchItemIndex, jumpToPageWithOffset);
    }

    public boolean navigateToSearchItem(int pageIndex, int searchItemIndex, boolean jumpToPageWithOffset) {
        var page = pdfFile.determineValidPageNumberFrom(pageIndex);
        var record = searchRecords.get(page);
        if (record == null || record.data == null) return false;
        var item = ArrayUtils.getElementSafe(record.data, searchItemIndex);
        if (item == null) return false;
        currentFocusedSearchItem = item;
        index = searchItemIndex;
        jumpToSearchedItem(pageIndex, item, true, false);
        redrawSel();
        return true;
    }

    public boolean navigateToSearchItem(int pageIndex, int searchItemIndex) {
        return navigateToSearchItem(pageIndex, searchItemIndex, false);
    }

    public boolean navigateToPreviousSearchItem() {
        return navigateToPreviousSearchItem(false);
    }

    private void jumpToSearchedItem(int page,
                                    SearchRecordItem item,
                                    boolean withOffset,
                                    boolean withAnim) {
        if (withOffset) {
            long offset = PdfiumCore.nativeGetTextOffset(pdfFile.getTextPage(page), item.st, item.ed);
            if (offset == 0L) {
                jumpTo(page, withAnim);
            } else {
                float x = Float.intBitsToFloat((int) (offset >> 32));
                float y = Float.intBitsToFloat((int) offset);
                jumpToWithOffset(page, x, y);
            }
        } else {
            jumpTo(page, withAnim);
        }
    }


    /**
     * Finds the 0-based linear index position of the currently focused search item
     * across all pages and records.
     *
     * <p><strong>Note:</strong> This method is computationally expensive and should be used sparingly.
     * It performs a full scan through all pages and their data, which can be costly for large datasets
     * (e.g., a PDF with 1 million pages).</p>
     *
     * @return The 0-based index of the current focused search item, or -1 if not found.
     */
    public int findCurrentSearchedItemPosition() {
        int pos = -1;
        if (currentFocusedSearchItem != null) {
            for (int page = 0; page < getPageCount(); page++) {
                SearchRecord record = searchRecords.get(page);
                if (record == null || record.data == null) continue;
                for (int i = 0; i < record.data.size(); i++) {
                    SearchRecordItem item = record.data.get(i);
                    pos += 1;
                    if (item == currentFocusedSearchItem) {
                        return pos;
                    }
                }
            }
        }
        return -1;
    }


    /**
     * @return true if navigated
     */
    public boolean navigateToPreviousSearchItem(boolean jumpToPageWithOffset) {
        boolean navigated = false;

        if (currentFocusedSearchItem == null || index == -1) return false;

        var page = currentFocusedSearchItem.pageIndex;
        var record = searchRecords.get(page);
        if (record == null || record.data == null) return false;

        var prevIndex = index - 1;
        if (ArrayUtils.isValidIndex(record.data, prevIndex)) {
            currentFocusedSearchItem = record.data.get(prevIndex);
            index = prevIndex;
            jumpToSearchedItem(page, currentFocusedSearchItem, jumpToPageWithOffset, true);
            redrawSel();
            return true;
        }
        for (int i = page - 1; i >= 0; i--) {
            SearchRecord record1 = searchRecords.get(i);
            if (record1 != null && record1.data != null && !record1.data.isEmpty()) {
                var lastIndex = record1.data.size() - 1;
                var item = ArrayUtils.getElementSafe(record1.data, lastIndex);
                if (item != null) {
                    currentFocusedSearchItem = item;
                    index = lastIndex;
                    jumpToSearchedItem(i, item, jumpToPageWithOffset, true);
                    redrawSel();
                    return true;
                }
            }
        }
        return false;
    }


    public void startSearch(ArrayList<SearchRecord> arr, String key, int flag) {
        this.callbacks.callOnSearchBegin();
    }

    public void endSearch(ArrayList<SearchRecord> arr) {
        this.callbacks.callOnSearchEnd();
        selectionPaintView.invalidate();
    }

    // Decorations methods
    public void applyDecorations(ArrayList<Decoration> decorations) {
        this.decorations = decorations;
        selectionPaintView.invalidate();
    }

    public ArrayList<RectF> getAllDecorationsOnPage(int page) {
        ArrayList<RectF> rectFS = new ArrayList<>();
        for (int i = 0; i < this.decorations.size(); i++) {
            Decoration decoration = this.decorations.get(i);
            if (decoration.page == page) {
                rectFS.add(decoration.rect);
            }
        }
        return rectFS;
    }

    void setSelectionAtPage(int pageIdx, int st, int ed) {
        selPageSt = pageIdx;
        selPageEd = pageIdx;
        selStart = st;
        selEnd = ed;
        hasSelection = true;
        if (selectionPaintView != null) {
            selectionPaintView.resetSel();
        }
    }


    @NonNull
    public final PointF sourceToViewCoordinate(PointF sxy, @NonNull PointF vTarget) {
        return sourceToViewCoordinate(sxy.x, sxy.y, vTarget);
    }


    public final PointF sourceToViewCoordinate(float sx, float sy, @NonNull PointF vTarget) {
        float xPreRotate = sourceToViewX(sx);
        float yPreRotate = sourceToViewY(sy);
        vTarget.set(xPreRotate, yPreRotate);
       /* //if (rotation == 0f) {

        } else {
            // Calculate offset by rotation
            final float vCenterX = getScreenWidth() / 2;
            final float vCenterY = getScreenHeight() / 2;
            xPreRotate -= vCenterX;
            yPreRotate -= vCenterY;
            vTarget.x = (float) (xPreRotate * cos - yPreRotate * sin) + vCenterX;
            vTarget.y = (float) (xPreRotate * sin + yPreRotate * cos) + vCenterY;
        }
*/
        return vTarget;
    }

    private float sourceToViewX(float sx) {


        return (sx * getZoom());//+getCurrentXOffset(); // + vTranslate.x;
    }

    private float sourceToViewY(float sy) {
        return (sy * getZoom());//+getCurrentYOffset()) ;//+ vTranslate.y;
    }

    public void getCharPos(RectF pos, int index) {
        float mappedX = -getCurrentXOffset() + dragPinchManager.lastX;
        float mappedY = -getCurrentYOffset() + dragPinchManager.lastY;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? mappedY : mappedX, getZoom());

        Long pagePtr = pdfFile.pdfDocument.mNativePagesPtr.get(page);
        if (pagePtr == null) return;

        SizeF size = pdfFile.getPageSize(page);
        //  SizeF size = pdfFile.SizeF size = pdfView.pdfFile.getPageSize(page);(page, getZoom());
        pdfiumCore.nativeGetCharPos(pagePtr
                , 0
                , 0
                , (int) size.getWidth(), (int) size.getHeight(), pos, dragPinchManager.loadText(), index, true);

    }

    public void getCharLoosePos(RectF pos, int index) {
        float mappedX = -getCurrentXOffset() + dragPinchManager.lastX;
        float mappedY = -getCurrentYOffset() + dragPinchManager.lastY;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? mappedY : mappedX, getZoom());


        Long pagePtr = pdfFile.pdfDocument.mNativePagesPtr.get(page);
        if (pagePtr == null) return;
        SizeF size = pdfFile.getPageSize(page);
        //   SizeF size = pdfFile.getScaledPageSize(page, getZoom());
        pdfiumCore.nativeGetMixedLooseCharPos(pagePtr
                , 0
                , getLateralOffset()
                , (int) size.getWidth(), (int) size.getHeight(), pos, dragPinchManager.loadText(), index, true);

    }

    public void getCharLoose(RectF pos, int index) {
        int page = currentPage;


        Long pagePtr = pdfFile.pdfDocument.mNativePagesPtr.get(page);
        if (pagePtr == null) {
            return;
        }
        SizeF size = pdfFile.getPageSize(page);
        //   SizeF size = pdfFile.getScaledPageSize(page, getZoom());
        pdfiumCore.nativeGetMixedLooseCharPos(pagePtr
                , 0
                , getLateralOffset()
                , (int) size.getWidth(), (int) size.getHeight(), pos, dragPinchManager.loadText(), index, true);

    }


    public List<SearchRecordItem> getAllMatchOnPage(@Nullable SearchRecord record) {
        if (record == null) return List.of();
        int page = record.pageIdx;
        long tid = dragPinchManager.prepareText(page);
        if (record.data == null && tid != -1 && task != null) {
            ArrayList<SearchRecordItem> data = new ArrayList<>();
            record.data = data;
            long keyStr = task.getKeyStr();
            if (keyStr != 0) {
                long searchHandle = pdfiumCore.nativeFindTextPageStart(tid, keyStr, task.flag, record.findStart);
                if (searchHandle != 0) {
                    while (pdfiumCore.nativeFindTextPageNext(searchHandle)) {
                        int st = pdfiumCore.nativeGetFindIdx(searchHandle);
                        int ed = pdfiumCore.nativeGetFindLength(searchHandle);
                        getRectForRecordItem(data, st, ed, page);
                    }
                    pdfiumCore.nativeFindTextPageEnd(searchHandle);
                }
            }
        }
        return record.data == null ? List.of() : record.data;
    }

    private void getRectForRecordItem(ArrayList<SearchRecordItem> data, int st, int ed, int page) {
        Long tid = pdfFile.pdfDocument.mNativeTextPtr.get(page);
        Long pid = pdfFile.pdfDocument.mNativePagesPtr.get(page);
        if (tid == null || pid == null) return;
        SizeF size = pdfFile.getPageSize(page);
        if (st >= 0 && ed > 0) {
            int rectCount = pdfiumCore.nativeCountRects(tid, st, ed);
            long firstOffset = 0L;
            if (rectCount > 0) {
                RectF[] rectFS = new RectF[rectCount];
                for (int i = 0; i < rectCount; i++) {
                    RectF rI = new RectF();
                    long offset = pdfiumCore.nativeGetRect(pid, 0, 0,
                            (int) size.getWidth(), (int) size.getHeight(),
                            tid, rI, i);
                    rectFS[i] = rI;
                    if (i == 0) {
                        firstOffset = offset;
                    }
                }
                int xBits = (int) (firstOffset >> 32);
                int yBits = (int) firstOffset;
                float x = Float.intBitsToFloat(xBits);
                float y = Float.intBitsToFloat(yBits);
                data.add(new SearchRecordItem(page, st, ed, rectFS, x, y));
            }
        }
    }

    public int getLateralOffset() {
        ////if(size.getWidth()!=maxPageWidth) {
        //	return (maxPageWidth-size.getWidth())/2;
        //}
        return 0;
    }


    private void load(DocumentSource docSource, String password) {
        load(docSource, password, null);
    }

    private void load(DocumentSource docSource, String password, int[] userPages) {

        if (!recycled) {
            throw new IllegalStateException("Don't call load on a PDF View without recycling it first.");
        }

        recycled = false;
        // Start decoding document
        decodingAsyncTask = new DecodingAsyncTask(docSource, password, userPages, this, pdfiumCore);
        decodingAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Go to the given page.
     *
     * @param page Page index.
     */
    public void jumpTo(int page, boolean withAnimation) {
        if (pdfFile == null) {
            return;
        }
        page = pdfFile.determineValidPageNumberFrom(page);
        float offset = page == 0 ? 0 : -pdfFile.getPageOffset(page, zoom);
        if (page == 0 && initialRender) {
            initialRender = false;
            offset += this.spacingTopPx;
        }
        if (swipeVertical) {
            if (withAnimation) {
                animationManager.startYAnimation(currentYOffset, offset);
            } else {
                moveTo(currentXOffset, offset);
            }
        } else {
            if (withAnimation) {
                animationManager.startXAnimation(currentXOffset, offset);
            } else {
                SnapEdge edge = this.findSnapEdge(page);
                float xOffset = offset;
                if (edge == SnapEdge.CENTER) {
                    xOffset = -this.snapOffsetForPage(page, edge);
                }
                moveTo(xOffset, currentYOffset);
            }
        }
        showPage(page);
    }

    public void jumpTo(int page) {
        jumpTo(page, false);
    }

    public void jumpToWithSnap(int page) {
        jumpTo(page);
        boolean enabled = pageSnap;
        setPageSnap(true);
        performPageSnap();
        setPageSnap(enabled);
    }

    public void jumpAndHighlightArea(int page, long selectionId) {
        int startIndex = Util.unpackHigh(selectionId);
        int endIndex = Util.unpackLow(selectionId);
        if (startIndex < 0 || endIndex <= startIndex) return;
        long textPtr = pdfFile.getTextPage(page);
        if (textPtr == 0L) return;
        jumpToWithSnap(page);
        dragPinchManager.currentTextPtr = textPtr;
        setSelectionAtPage(page, startIndex, endIndex);
    }

    public void jumpToWithOffset(int page, float rawX, float rawY) {
        if (rawX == 0f && rawY == 0f) {
            jumpTo(page);
            return;
        }
        var pageIndex = pdfFile.determineValidPageNumberFrom(page);
        var originalPageSize = pdfFile.getOriginalPageSize(pageIndex);
        var pageSize = pdfFile.getPageSize(pageIndex);
        var pageY = -getPageY(pageIndex);
        var pageX = -getPageX(pageIndex);
        var dpi = getResources().getDisplayMetrics().densityDpi;
        var px = (rawX * dpi) / 72;
        var py = (rawY * dpi / 72);

        float dy = pageSize.getHeight() - (pageSize.getHeight() * py / (float) originalPageSize.getHeight());
        float dx = (pageSize.getWidth() * px / (float) originalPageSize.getWidth());
        moveTo(pageX - dx, pageY - zoom * dy);
        loadPageByOffset();
    }

    void showPage(int pageNb) {
        if (recycled) {
            return;
        }

        // On page change, clear selection
//        clearSelection();

        // Check the page number and makes the
        // difference between UserPages and DocumentPages
        pageNb = pdfFile.determineValidPageNumberFrom(pageNb);
        currentPage = pageNb;

        loadPages();

        if (scrollHandle != null && !documentFitsView()) {
            scrollHandle.setPageNum(currentPage + 1);
        }
        callbacks.callOnPageChange(currentPage, pdfFile.getPagesCount());
    }

    /**
     * Get current position as ratio of document length to visible area.
     * 0 means that document start is visible, 1 that document end is visible
     *
     * @return offset between 0 and 1
     */
    public float getPositionOffset() {
        float offset;
        if (swipeVertical) {
            offset = -currentYOffset / (pdfFile.getDocLen(zoom) - getHeight());
        } else {
            offset = -currentXOffset / (pdfFile.getDocLen(zoom) - getWidth());
        }
        return MathUtils.limit(offset, 0, 1);
    }

    /**
     * @param progress   must be between 0 and 1
     * @param moveHandle whether to move scroll handle
     * @see PDFView#getPositionOffset()
     */
    public void setPositionOffset(float progress, boolean moveHandle) {
        if (swipeVertical) {
            moveTo(currentXOffset, (-pdfFile.getDocLen(zoom) + getHeight()) * progress, moveHandle);
        } else {
            moveTo((-pdfFile.getDocLen(zoom) + getWidth()) * progress, currentYOffset, moveHandle);
        }
        loadPageByOffset();
    }

    public void setPositionOffset(float progress) {
        setPositionOffset(progress, true);
    }

    public void stopFling() {
        animationManager.stopFling();
    }

    public int getPageCount() {
        if (pdfFile == null) {
            return 0;
        }
        return pdfFile.getPagesCount();
    }

    public void setSwipeEnabled(boolean enableSwipe) {
        this.enableSwipe = enableSwipe;
    }

    public int getPageX(int page) {
        if (isSwipeVertical()) {
            return (int) pdfFile.getSecondaryPageOffset(page, getZoom());
        } else {
            return (int) pdfFile.getPageOffset(page, getZoom());
        }
    }

    public int getPageY(int page) {
        if (isSwipeVertical()) {
            return (int) pdfFile.getPageOffset(page, getZoom());
        } else {
            return (int) pdfFile.getSecondaryPageOffset(page, getZoom());
        }
    }

    void sourceToViewRectFFSearch(@NonNull RectF sRect, @NonNull RectF vTarget, int page) {
        int pageX = getPageX(page);
        int pageY = getPageY(page);
        vTarget.set(
                sRect.left * getZoom() + pageX + currentXOffset,
                sRect.top * getZoom() + pageY + currentYOffset,
                sRect.right * getZoom() + pageX + currentXOffset,
                sRect.bottom * getZoom() + pageY + currentYOffset
        );
    }

    void sourceToViewRectFF(@NonNull RectF sRect, @NonNull RectF vTarget) {
        int page = -1;
        if (pdfFile.pdfDocument != null) {
            Set<Map.Entry<Integer, Long>> entries
                    = pdfFile.pdfDocument.mNativeTextPtr.entrySet();
            for (Map.Entry<Integer, Long> entry : entries) {
                Long value = entry.getValue();
                if (value == dragPinchManager.currentTextPtr) {
                    page = entry.getKey();
                    break;
                }
            }
        }
        page = (page == -1) ? getPageNumberAtScreen(
                dragPinchManager.lastX,
                dragPinchManager.lastY
        ) : page;

        int pageX = getPageX(page);
        int pageY = getPageY(page);
        vTarget.set(
                sRect.left * getZoom() + pageX + currentXOffset,
                sRect.top * getZoom() + pageY + currentYOffset,
                sRect.right * getZoom() + pageX + currentXOffset,
                sRect.bottom * getZoom() + pageY + currentYOffset
        );
    }

    public SearchRecord findPageCached(String key, int pageIdx, int flag) {
        try {
            long tid = dragPinchManager.loadText(pageIdx);
            if (tid == -1) {
                return null;
            }
            int foundIdx = pdfiumCore.nativeFindTextPage(tid, key, flag);
            return foundIdx == -1 ? null : new SearchRecord(pageIdx, foundIdx);
        } catch (Exception ex) {
            return null;
        }
    }

    public void setNightMode(boolean nightMode) {
        this.nightMode = nightMode;
        if (nightMode) {
            ColorMatrix colorMatrixInverted = new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0
            });

            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrixInverted);
            paint.setColorFilter(filter);
        } else {
            paint.setColorFilter(null);
        }
    }

    public void setNightMode(boolean nightMode, float brightnessScale, float contrastScale) {
        if (brightnessScale == 1f && contrastScale == 1f) {
            setNightMode(nightMode);
            return;
        }
        this.nightMode = nightMode;
        if (nightMode) {
            // Invert colors
            float[] src = {
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0
            };
            ColorMatrix colorMatrixInverted = getColorMatrix(brightnessScale, contrastScale, src);

            // Apply the final filter
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrixInverted);
            paint.setColorFilter(filter);
        } else {
            // Reset to default
            paint.setColorFilter(null);
        }
    }

    @NonNull
    private static ColorMatrix getColorMatrix(float brightnessScale, float contrastScale, float[] src) {
        ColorMatrix colorMatrixInverted = new ColorMatrix(src);

        // Adjust brightness
        ColorMatrix brightnessMatrix = new ColorMatrix();
        brightnessMatrix.setScale(brightnessScale, brightnessScale, brightnessScale, 1.0f);

        // Adjust contrast
        ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
                contrastScale, 0, 0, 0, 0,
                0, contrastScale, 0, 0, 0,
                0, 0, contrastScale, 0, 0,
                0, 0, 0, 1, 0
        });

        // Combine all adjustments
        colorMatrixInverted.postConcat(brightnessMatrix);
        colorMatrixInverted.postConcat(contrastMatrix);
        return colorMatrixInverted;
    }


    void enableDoubleTap(boolean enableDoubleTap) {
        this.doubleTapEnabled = enableDoubleTap;
    }

    boolean isDoubleTapEnabled() {
        return doubleTapEnabled;
    }

    void onPageError(PageRenderingException ex) {
        if (!callbacks.callOnPageError(ex.getPage(), ex.getCause())) {
            Log.e(TAG, "Cannot open page " + ex.getPage(), ex.getCause());
        }
    }

    @SuppressWarnings("deprecation")
    public void recycle() {
        waitingDocumentConfigurator = null;
        closeTask();
        clearHighlights();

        animationManager.stopAll();
        dragPinchManager.disable();

        // Stop tasks
        if (renderingHandler != null) {
            renderingHandler.stop();
            renderingHandler.removeMessages(RenderingHandler.MSG_RENDER_TASK);
        }
        if (decodingAsyncTask != null) {
            decodingAsyncTask.cancel(true);
        }

        // Clear caches
        cacheManager.recycle();

        if (scrollHandle != null && isScrollHandleInit) {
            scrollHandle.destroyLayout();
        }

        if (pdfFile != null) {
            pdfFile.dispose();
            pdfFile = null;
        }

        renderingHandler = null;
        scrollHandle = null;
        isScrollHandleInit = false;
        currentXOffset = currentYOffset = 0;
        zoom = 1f;
        recycled = true;
        callbacks = new Callbacks();
        state = State.DEFAULT;
    }

    public boolean isRecycled() {
        return recycled;
    }

    /**
     * Handle fling animation
     */
    @Override
    public void computeScroll() {
        super.computeScroll();
        if (isInEditMode()) {
            return;
        }
        animationManager.computeFling();
    }

    @SuppressLint("ObsoleteSdkInt")
    @Override
    protected void onDetachedFromWindow() {
        recycle();
        if (renderingHandlerThread != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                renderingHandlerThread.quitSafely();
            } else {
                renderingHandlerThread.quit();
            }
            renderingHandlerThread = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        hasSize = true;
        if (waitingDocumentConfigurator != null) {
            waitingDocumentConfigurator.load();
        }
        if (isInEditMode() || state != State.SHOWN) {
            return;
        }

        // calculates the position of the point which in the center of view relative to big strip
        float centerPointInStripXOffset = -currentXOffset + oldw * 0.5f;
        float centerPointInStripYOffset = -currentYOffset + oldh * 0.5f;

        float relativeCenterPointInStripXOffset;
        float relativeCenterPointInStripYOffset;

        if (swipeVertical) {
            relativeCenterPointInStripXOffset = centerPointInStripXOffset / pdfFile.getMaxPageWidth();
            relativeCenterPointInStripYOffset = centerPointInStripYOffset / pdfFile.getDocLen(zoom);
        } else {
            relativeCenterPointInStripXOffset = centerPointInStripXOffset / pdfFile.getDocLen(zoom);
            relativeCenterPointInStripYOffset = centerPointInStripYOffset / pdfFile.getMaxPageHeight();
        }

        animationManager.stopAll();
        pdfFile.recalculatePageSizes(new Size(w, h));

        if (swipeVertical) {
            currentXOffset = -relativeCenterPointInStripXOffset * pdfFile.getMaxPageWidth() + w * 0.5f;
            currentYOffset = -relativeCenterPointInStripYOffset * pdfFile.getDocLen(zoom) + h * 0.5f;
        } else {
            currentXOffset = -relativeCenterPointInStripXOffset * pdfFile.getDocLen(zoom) + w * 0.5f;
            currentYOffset = -relativeCenterPointInStripYOffset * pdfFile.getMaxPageHeight() + h * 0.5f;
        }
        moveTo(currentXOffset, currentYOffset);
        loadPageByOffset();
    }


    @Override
    public boolean canScrollHorizontally(int direction) {
        if (pdfFile == null) {
            return true;
        }

        if (swipeVertical) {
            if (direction < 0 && currentXOffset < 0) {
                return true;
            } else
                return direction > 0 && currentXOffset + toCurrentScale(pdfFile.getMaxPageWidth()) > getWidth();
        } else {
            if (direction < 0 && currentXOffset < 0) {
                return true;
            } else return direction > 0 && currentXOffset + pdfFile.getDocLen(zoom) > getWidth();
        }
    }

    @Override
    public boolean canScrollVertically(int direction) {
        if (pdfFile == null) {
            return true;
        }

        if (swipeVertical) {
            if (direction < 0 && currentYOffset < 0) {
                return true;
            } else return direction > 0 && currentYOffset + pdfFile.getDocLen(zoom) > getHeight();
        } else {
            if (direction < 0 && currentYOffset < 0) {
                return true;
            } else
                return direction > 0 && currentYOffset + toCurrentScale(pdfFile.getMaxPageHeight()) > getHeight();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (isInEditMode()) {
            return;
        }
        // As I said in this class javadoc, we can think of this canvas as a huge
        // strip on which we draw all the images. We actually only draw the rendered
        // parts, of course, but we render them in the place they belong in this huge
        // strip.

        // That's where Canvas.translate(x, y) becomes very helpful.
        // This is the situation :
        //  _______________________________________________
        // |   			 |					 			   |
        // | the actual  |					The big strip  |
        // |	canvas	 | 								   |
        // |_____________|								   |
        // |_______________________________________________|
        //
        // If the rendered part is on the bottom right corner of the strip
        // we can draw it but we won't see it because the canvas is not big enough.

        // But if we call translate(-X, -Y) on the canvas just before drawing the object :
        //  _______________________________________________
        // |   			  					  _____________|
        // |   The big strip     			 |			   |
        // |		    					 |	the actual |
        // |								 |	canvas	   |
        // |_________________________________|_____________|
        //
        // The object will be on the canvas.
        // This technique is massively used in this method, and allows
        // abstraction of the screen position when rendering the parts.

        // Draws background

        if (enableAntialiasing) {
            canvas.setDrawFilter(antialiasFilter);
        }

        Drawable bg = getBackground();
        if (bg == null) {
            canvas.drawColor(nightMode ? Color.BLACK : Color.WHITE);
        } else {
            bg.draw(canvas);
        }

        if (recycled) {
            return;
        }

        if (state != State.SHOWN) {
            return;
        }

        // Moves the canvas before drawing any element
        float currentXOffset = this.currentXOffset;
        float currentYOffset = this.currentYOffset;
        canvas.translate(currentXOffset, currentYOffset);

        // Draws thumbnails
        for (PagePart part : cacheManager.getThumbnails()) {
            drawPart(canvas, part);

        }

        // Draws parts
        for (PagePart part : cacheManager.getPageParts()) {
            drawPart(canvas, part);
            if (callbacks.getOnDrawAll() != null
                    && !onDrawPagesNumbers.contains(part.getPage())) {
                onDrawPagesNumbers.add(part.getPage());
            }
        }

        for (Integer page : onDrawPagesNumbers) {
            drawWithListener(canvas, page, callbacks.getOnDrawAll());
        }
        onDrawPagesNumbers.clear();

        drawWithListener(canvas, currentPage, callbacks.getOnDraw());

        // Restores the canvas position
        canvas.translate(-currentXOffset, -currentYOffset);
    }

    public String getSelection() {
        if (selectionPaintView != null) {
            try {
                if (hasSelection) {
                    int pageStart = selPageSt;
                    int pageCount = selPageEd - pageStart;
                    int startCharIndex = Math.min(selStart, selEnd);
                    int endCharIndex = Math.max(selStart, selEnd);

                    if (pageCount == 0) {
                        dragPinchManager.prepareText(selPageSt);
                        int newSelEnd = Math.min(endCharIndex, dragPinchManager.allText.length());
                        return dragPinchManager.allText.substring(startCharIndex, newSelEnd);
                    }
                    StringBuilder sb = new StringBuilder();
                    int selCount = 0;
                    for (int i = 0; i <= pageCount; i++) {
                        dragPinchManager.prepareText(selPageSt + i);
                        int len = dragPinchManager.allText.length();
                        selCount += i == 0 ? len - startCharIndex : i == pageCount ? endCharIndex : len;
                    }
                    sb.ensureCapacity(selCount + 64);
                    for (int i = 0; i <= pageCount; i++) {
                        sb.append(dragPinchManager.allText.substring(i == 0 ? startCharIndex : 0, i == pageCount ? endCharIndex : dragPinchManager.allText.length()));
                    }
                    return sb.toString();
                }
            } catch (Exception e) {
                Log.e("get Selection Exception", "Exception", e);
            }
        }
        return "";
    }

    private void drawWithListener(Canvas canvas, int page, OnDrawListener listener) {
        if (listener != null) {
            float translateX, translateY;
            if (swipeVertical) {
                translateX = 0;
                translateY = pdfFile.getPageOffset(page, zoom);
            } else {
                translateY = 0;
                translateX = pdfFile.getPageOffset(page, zoom);
            }

            canvas.translate(translateX, translateY);
            SizeF size = pdfFile.getPageSize(page);
            listener.onLayerDrawn(canvas,
                    toCurrentScale(size.getWidth()),
                    toCurrentScale(size.getHeight()),
                    page);

            canvas.translate(-translateX, -translateY);
        }
    }

    /**
     * Draw a given PagePart on the canvas
     */

    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();

    private void drawPart(Canvas canvas, PagePart part) {
        // Can seem strange, but avoid lot of calls
        RectF pageRelativeBounds = part.getPageRelativeBounds();
        Bitmap renderedBitmap = part.getRenderedBitmap();

        if (renderedBitmap.isRecycled()) {
            return;
        }

        // Move to the target page
        float localTranslationX;
        float localTranslationY;
        SizeF size = pdfFile.getPageSize(part.getPage());

        if (swipeVertical) {
            localTranslationY = pdfFile.getPageOffset(part.getPage(), zoom);
            float maxWidth = pdfFile.getMaxPageWidth();
            localTranslationX = toCurrentScale(maxWidth - size.getWidth()) / 2;
        } else {
            localTranslationX = pdfFile.getPageOffset(part.getPage(), zoom);
            float maxHeight = pdfFile.getMaxPageHeight();
            localTranslationY = toCurrentScale(maxHeight - size.getHeight()) / 2;
        }
        canvas.translate(localTranslationX, localTranslationY);

        srcRect.set(0, 0, renderedBitmap.getWidth(),
                renderedBitmap.getHeight());

        float offsetX = toCurrentScale(pageRelativeBounds.left * size.getWidth());
        float offsetY = toCurrentScale(pageRelativeBounds.top * size.getHeight());
        float width = toCurrentScale(pageRelativeBounds.width() * size.getWidth());
        float height = toCurrentScale(pageRelativeBounds.height() * size.getHeight());

        // If we use float values for this rectangle, there will be
        // a possible gap between page parts, especially when
        // the zoom level is high.
        dstRect.set((int) offsetX, (int) offsetY,
                (int) (offsetX + width),
                (int) (offsetY + height));

        // Check if bitmap is in the screen
        float translationX = currentXOffset + localTranslationX;
        float translationY = currentYOffset + localTranslationY;
        if (translationX + dstRect.left >= getWidth() || translationX + dstRect.right <= 0 ||
                translationY + dstRect.top >= getHeight() || translationY + dstRect.bottom <= 0) {
            canvas.translate(-localTranslationX, -localTranslationY);
            return;
        }

        canvas.drawBitmap(renderedBitmap, srcRect, dstRect, paint);

        if (Constants.DEBUG_MODE) {
            debugPaint.setColor(part.getPage() % 2 == 0 ? Color.RED : Color.BLUE);
            canvas.drawRect(dstRect, debugPaint);
        }

        // Restore the canvas position
        canvas.translate(-localTranslationX, -localTranslationY);

    }

    /**
     * Load all the parts around the center of the screen,
     * taking into account X and Y offsets, zoom level, and
     * the current page displayed
     */
    public void loadPages() {
        if (pdfFile == null || renderingHandler == null) {
            return;
        }

        // Cancel all current tasks
        renderingHandler.removeMessages(RenderingHandler.MSG_RENDER_TASK);
        cacheManager.makeANewSet();

        pagesLoader.loadPages();
        redraw();
        redrawSel();
    }

    /**
     * Called when the PDF is loaded
     */
    void loadComplete(PdfFile pdfFile) {
        state = State.LOADED;

        this.pdfFile = pdfFile;

        //Crashlytics null pointer exception bug fix. not able repeat on device.
        if (renderingHandlerThread == null) {
            renderingHandlerThread = new HandlerThread("PDF renderer");
        }
        if (!renderingHandlerThread.isAlive()) {
            renderingHandlerThread.start();
        }
        renderingHandler = new RenderingHandler(renderingHandlerThread.getLooper(), this);
        renderingHandler.start();

        if (scrollHandle != null) {
            scrollHandle.setupLayout(this);
            isScrollHandleInit = true;
        }

        dragPinchManager.enable();

        callbacks.callOnLoadComplete(pdfFile.getPagesCount());

        jumpTo(defaultPage, false);
    }

    void loadError(Throwable t) {
        state = State.ERROR;
        // store reference, because callbacks will be cleared in recycle() method
        OnErrorListener onErrorListener = callbacks.getOnError();
        recycle();
        invalidate();
        if (onErrorListener != null) {
            onErrorListener.onError(t);
        } else {
            Log.e("PDFView", "load pdf error", t);
        }
    }

    void redraw() {
        invalidate();
    }

    /**
     * Called when a rendering task is over and
     * a PagePart has been freshly created.
     *
     * @param part The created PagePart.
     */
    public void onBitmapRendered(PagePart part) {
        // when it is first rendered part
        if (state == State.LOADED) {
            state = State.SHOWN;
            callbacks.callOnRender(pdfFile.getPagesCount());
        }

        if (part.isThumbnail()) {
            cacheManager.cacheThumbnail(part);
        } else {
            cacheManager.cachePart(part);
        }
        redraw();
    }

    public void moveTo(float offsetX, float offsetY) {
        moveTo(offsetX, offsetY, true);
    }

    /**
     * Move to the given X and Y offsets, but check them ahead of time
     * to be sure not to go outside the the big strip.
     *
     * @param offsetX    The big strip X offset to use as the left border of the screen.
     * @param offsetY    The big strip Y offset to use as the right border of the screen.
     * @param moveHandle whether to move scroll handle or not
     */
    public void moveTo(float offsetX, float offsetY, boolean moveHandle) {
        if (lockHorizontalScroll && !dragPinchManager.isScaling()) {
            offsetX = currentXOffset;
        }
        if (lockVerticalScroll && !dragPinchManager.isScaling()) {
            offsetY = currentYOffset;
        }

        if (swipeVertical) {
            // Check X offset
            float scaledPageWidth = toCurrentScale(pdfFile.getMaxPageWidth());
            if (scaledPageWidth < getWidth()) {
                offsetX = (float) getWidth() / 2 - scaledPageWidth / 2;
            } else {
                if (offsetX > 0) {
                    offsetX = 0;
                } else if (offsetX + scaledPageWidth < getWidth()) {
                    offsetX = getWidth() - scaledPageWidth;
                }
            }

            // Check Y offset
            float contentHeight = pdfFile.getDocLen(zoom);
            if (contentHeight < getHeight()) { // whole document height visible on screen
                offsetY = (getHeight() - contentHeight) / 2;
            } else {
                if (offsetY > 0) { // top visible
                    offsetY = 0;
                } else if (offsetY + contentHeight < getHeight()) { // bottom visible
                    offsetY = -contentHeight + getHeight();
                }
            }

            if (offsetY < currentYOffset) {
                scrollDir = ScrollDir.END;
            } else if (offsetY > currentYOffset) {
                scrollDir = ScrollDir.START;
            } else {
                scrollDir = ScrollDir.NONE;
            }
        } else {
            // Check Y offset
            float scaledPageHeight = toCurrentScale(pdfFile.getMaxPageHeight());
            if (scaledPageHeight < getHeight()) {
                offsetY = getHeight() / 2f - scaledPageHeight / 2;
            } else {
                if (offsetY > 0) {
                    offsetY = 0;
                } else if (offsetY + scaledPageHeight < getHeight()) {
                    offsetY = getHeight() - scaledPageHeight;
                }
            }

            // Check X offset
            float contentWidth = pdfFile.getDocLen(zoom);
            if (contentWidth < getWidth()) { // whole document width visible on screen
                offsetX = (getWidth() - contentWidth) / 2;
            } else {
                if (offsetX > 0) { // left visible
                    offsetX = 0;
                } else if (offsetX + contentWidth < getWidth()) { // right visible
                    offsetX = -contentWidth + getWidth();
                }
            }

            if (offsetX < currentXOffset) {
                scrollDir = ScrollDir.END;
            } else if (offsetX > currentXOffset) {
                scrollDir = ScrollDir.START;
            } else {
                scrollDir = ScrollDir.NONE;
            }
        }

        if (!lockHorizontalScroll || dragPinchManager.isScaling()) {
            currentXOffset = offsetX;
        }

        if (!lockVerticalScroll || dragPinchManager.isScaling()) {
            currentYOffset = offsetY;
        }


        float positionOffset = getPositionOffset();

        if (moveHandle && scrollHandle != null && !documentFitsView()) {
            scrollHandle.setScroll(positionOffset);
        }

        callbacks.callOnPageScroll(getCurrentPage(), positionOffset);

        redraw();
    }

    public void lockHorizontalScroll(boolean lockHorizontalScroll) {
        this.lockHorizontalScroll = lockHorizontalScroll;
    }

    public void lockVerticalScroll(boolean lockVerticalScroll) {
        this.lockVerticalScroll = lockVerticalScroll;
    }

    public boolean isLockHorizontalScroll() {
        return lockHorizontalScroll;
    }

    public boolean isLockVerticalScroll() {
        return lockVerticalScroll;
    }

    private boolean isScaling() {
        return dragPinchManager.isScaling();
    }

    void loadPageByOffset() {
        if (0 == pdfFile.getPagesCount()) {
            return;
        }

        float offset, screenCenter;
        if (swipeVertical) {
            offset = currentYOffset;
            screenCenter = ((float) getHeight()) / 2;
        } else {
            offset = currentXOffset;
            screenCenter = ((float) getWidth()) / 2;
        }

        int page = pdfFile.getPageAtOffset(-(offset - screenCenter), zoom);

        if (page >= 0 && page <= pdfFile.getPagesCount() - 1 && page != getCurrentPage()) {
            showPage(page);
        } else {
            loadPages();
        }
    }

    /**
     * Animate to the nearest snapping position for the current SnapPolicy
     */
    public void performPageSnap() {
        if (!pageSnap || pdfFile == null || pdfFile.getPagesCount() == 0) {
            return;
        }
        int centerPage = findFocusPage(currentXOffset, currentYOffset);
        SnapEdge edge = findSnapEdge(centerPage);
        if (edge == SnapEdge.NONE) {
            return;
        }

        float offset = snapOffsetForPage(centerPage, edge);
        if (swipeVertical) {
            animationManager.startYAnimation(currentYOffset, -offset);
        } else {
            animationManager.startXAnimation(currentXOffset, -offset);
        }

        Log.d(TAG, "performPageSnap: ");
    }

    /**
     * Find the edge to snap to when showing the specified page
     */
    SnapEdge findSnapEdge(int page) {
        if (!pageSnap || page < 0) {
            return SnapEdge.NONE;
        }
        float currentOffset = swipeVertical ? currentYOffset : currentXOffset;
        float offset = -pdfFile.getPageOffset(page, zoom);
        int length = swipeVertical ? getHeight() : getWidth();
        float pageLength = pdfFile.getPageLength(page, zoom);

        if (length >= pageLength) {
            return SnapEdge.CENTER;
        } else if (currentOffset >= offset) {
            return SnapEdge.START;
        } else if (offset - pageLength > currentOffset - length) {
            return SnapEdge.END;
        } else {
            return SnapEdge.NONE;
        }
    }

    /**
     * Get the offset to move to in order to snap to the page
     */
    float snapOffsetForPage(int pageIndex, SnapEdge edge) {
        float offset = pdfFile.getPageOffset(pageIndex, zoom);

        float length = swipeVertical ? getHeight() : getWidth();
        float pageLength = pdfFile.getPageLength(pageIndex, zoom);

        if (edge == SnapEdge.CENTER) {
            offset = offset - length / 2f + pageLength / 2f;
        } else if (edge == SnapEdge.END) {
            offset = offset - length + pageLength;
        }
        return offset;
    }

    int findFocusPage(float xOffset, float yOffset) {
        float currOffset = swipeVertical ? yOffset : xOffset;
        float length = swipeVertical ? getHeight() : getWidth();
        // make sure first and last page can be found
        if (currOffset > -1) {
            return 0;
        } else if (currOffset < -pdfFile.getDocLen(zoom) + length + 1) {
            return pdfFile.getPagesCount() - 1;
        }
        // else find page in center
        float center = currOffset - length / 2f;
        return pdfFile.getPageAtOffset(-center, zoom);
    }

    /**
     * @return true if single page fills the entire screen in the scrolling direction
     */
    public boolean pageFillsScreen() {
        float start = -pdfFile.getPageOffset(currentPage, zoom);
        float end = start - pdfFile.getPageLength(currentPage, zoom);
        if (isSwipeVertical()) {
            return start > currentYOffset && end < currentYOffset - getHeight();
        } else {
            return start > currentXOffset && end < currentXOffset - getWidth();
        }
    }

    /**
     * Move relatively to the current position.
     *
     * @param dx The X difference you want to apply.
     * @param dy The Y difference you want to apply.
     * @see #moveTo(float, float)
     */
    public void moveRelativeTo(float dx, float dy) {
        moveTo(currentXOffset + dx, currentYOffset + dy);
    }

    /**
     * Change the zoom level
     */
    public void zoomTo(float zoom) {
        this.zoom = zoom;
    }

    /**
     * Change the zoom level, relatively to a pivot point.
     * It will call moveTo() to make sure the given point stays
     * in the middle of the screen.
     *
     * @param zoom  The zoom level.
     * @param pivot The point on the screen that should stays.
     */
    public void zoomCenteredTo(float zoom, PointF pivot) {
        float dZoom = zoom / this.zoom;
        zoomTo(zoom);
        float baseX = currentXOffset * dZoom;
        float baseY = currentYOffset * dZoom;
        baseX += (pivot.x - pivot.x * dZoom);
        baseY += (pivot.y - pivot.y * dZoom);
        moveTo(baseX, baseY);
    }

    /**
     * @see #zoomCenteredTo(float, PointF)
     */
    public void zoomCenteredRelativeTo(float dZoom, PointF pivot) {
        zoomCenteredTo(zoom * dZoom, pivot);
    }

    /**
     * Checks if whole document can be displayed on screen, doesn't include zoom
     *
     * @return true if whole document can displayed at once, false otherwise
     */
    public boolean documentFitsView() {
        float len = pdfFile.getDocLen(1);
        if (swipeVertical) {
            return len < getHeight();
        } else {
            return len < getWidth();
        }
    }

    public void fitToWidth(int page) {
        if (state != State.SHOWN) {
            Log.e(TAG, "Cannot fit, document not rendered yet");
            return;
        }
        zoomTo(getWidth() / pdfFile.getPageSize(page).getWidth());
        jumpTo(page);
    }

    public SizeF getPageSize(int pageIndex) {
        if (pdfFile == null) {
            return new SizeF(0, 0);
        }
        return pdfFile.getPageSize(pageIndex);
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public float getCurrentXOffset() {
        return currentXOffset;
    }

    public float getCurrentYOffset() {
        return currentYOffset;
    }

    public int getPageNumberAtScreen(float x, float y) {
        float mappedX = -getCurrentXOffset() + x;
        float mappedY = -getCurrentYOffset() + y;
        int page = pdfFile.getPageAtOffset(isSwipeVertical() ? mappedY : mappedX, getZoom());
        return pdfFile.documentPage(page);
    }

    public float toRealScale(float size) {
        return size / zoom;
    }

    public float toCurrentScale(float size) {
        return size * zoom;
    }

    public float getZoom() {
        return zoom;
    }


    public boolean isZooming() {
        return zoom != minZoom;
    }

    private void setDefaultPage(int defaultPage) {
        this.defaultPage = defaultPage;
    }

    public void resetZoom() {
        zoomTo(minZoom);
    }

    public void resetZoomWithAnimation() {
        zoomWithAnimation(minZoom);
    }

    public void zoomWithAnimation(float centerX, float centerY, float scale) {
        animationManager.startZoomAnimation(centerX, centerY, zoom, scale);
    }

    public void zoomWithAnimation(float scale) {
        animationManager.startZoomAnimation((float) getWidth() / 2, (float) getHeight() / 2, zoom, scale);
    }

    private void setScrollHandle(ScrollHandle scrollHandle) {
        this.scrollHandle = scrollHandle;
    }

    /**
     * Get page number at given offset
     *
     * @param positionOffset scroll offset between 0 and 1
     * @return page number at given offset, starting from 0
     */
    public int getPageAtPositionOffset(float positionOffset) {
        return pdfFile.getPageAtOffset(pdfFile.getDocLen(zoom) * positionOffset, zoom);
    }

    public float getMinZoom() {
        return minZoom;
    }

    public void setMinZoom(float minZoom) {
        this.minZoom = minZoom;
    }

    public float getMidZoom() {
        return midZoom;
    }

    public void setMidZoom(float midZoom) {
        this.midZoom = midZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public void setMaxZoom(float maxZoom) {
        this.maxZoom = maxZoom;
    }

    public void useBestQuality(boolean bestQuality) {
        this.bestQuality = bestQuality;
    }

    public boolean isBestQuality() {
        return bestQuality;
    }

    public boolean isSwipeVertical() {
        return swipeVertical;
    }

    public boolean isSwipeEnabled() {
        return enableSwipe && !startInDrag;
    }

    private void setSwipeVertical(boolean swipeVertical) {
        this.swipeVertical = swipeVertical;
    }

    public void enableAnnotationRendering(boolean annotationRendering) {
        this.annotationRendering = annotationRendering;
    }

    public boolean isAnnotationRendering() {
        return annotationRendering;
    }

    public void enableRenderDuringScale(boolean renderDuringScale) {
        this.renderDuringScale = renderDuringScale;
    }

    public boolean isAntialiasing() {
        return enableAntialiasing;
    }

    public void enableAntialiasing(boolean enableAntialiasing) {
        this.enableAntialiasing = enableAntialiasing;
    }

    public int getSpacingPx() {
        return spacingPx;
    }

    public int getSpacingTopPx() {
        return spacingTopPx;
    }

    public int getSpacingBottomPx() {
        return spacingBottomPx;
    }

    public boolean isAutoSpacingEnabled() {
        return autoSpacing;
    }

    public void setPageFling(boolean pageFling) {
        this.pageFling = pageFling;
    }

    public boolean isPageFlingEnabled() {
        return pageFling;
    }

    private void setOnScrollHideView(View hideView) {
        this.hideView = hideView;
    }

    private void setSpacing(int spacingDp) {
        this.spacingPx = Util.dpToPx(getContext(), spacingDp);
    }

    private void setSpacingTop(int spacingTopDp) {
        this.spacingTopPx = Util.dpToPx(getContext(), spacingTopDp);
    }

    private void setSpacingBottom(int spacingBottomDp) {
        this.spacingBottomPx = Util.dpToPx(getContext(), spacingBottomDp);
    }

    private void setAutoSpacing(boolean autoSpacing) {
        this.autoSpacing = autoSpacing;
    }

    private void setPageFitPolicy(FitPolicy pageFitPolicy) {
        this.pageFitPolicy = pageFitPolicy;
    }

    public FitPolicy getPageFitPolicy() {
        return pageFitPolicy;
    }

    private void setFitEachPage(boolean fitEachPage) {
        this.fitEachPage = fitEachPage;
    }

    public boolean isFitEachPage() {
        return fitEachPage;
    }

    public boolean isPageSnap() {
        return pageSnap;
    }

    public void setPageSnap(boolean pageSnap) {
        this.pageSnap = pageSnap;
    }

    public void setMergedSelectionLine(boolean isSelectionLineMerged,
                                       float lineThreshHoldPt,
                                       float verticalExpandPercent) {
        this.isSelectionLineMerged = isSelectionLineMerged;
        this.lineThreshHoldPt = lineThreshHoldPt;
        this.verticalExpandPercent = verticalExpandPercent;

    }


    public boolean doRenderDuringScale() {
        return renderDuringScale;
    }

    /**
     * Returns null if document is not loaded
     */
    public PdfDocument.Meta getDocumentMeta() {
        if (pdfFile == null) {
            return null;
        }
        return pdfFile.getMetaData();
    }

    /**
     * Will be empty until document is loaded
     */
    public List<PdfDocument.Bookmark> getTableOfContents() {
        if (pdfFile == null) {
            return Collections.emptyList();
        }
        return pdfFile.getBookmarks();
    }

    /**
     * Retrieves and returns the table of contents (TOC) from the PDF file,
     * sorted using the provided comparator.
     *
     * <p>If {@code cached} is true and a previously sorted TOC is available,
     * it will be returned without fetching or sorting again.</p>
     *
     * @param comparator The comparator to use for sorting the TOC entries. Must not be {@code null}.
     * @param cached     If {@code true}, caches the sorted TOC entries for reuse. If {@code false}, bypasses cache.
     * @return A sorted list of {@link TOCEntry} objects representing the table of contents.
     * Returns an empty list if the PDF file is not loaded.
     * @throws NullPointerException if the provided comparator is {@code null}.
     */
    public List<TOCEntry> getTableOfContentsSorted(
            @NonNull Comparator<TOCEntry> comparator,
            boolean cached
    ) {
        if (tocEntries != null && !cached) return tocEntries;
        if (pdfFile == null) {
            return Collections.emptyList();
        }
        List<TOCEntry> tocEntries = pdfFile.getTableOfContentNew();
        tocEntries.sort(Objects.requireNonNull(comparator, "Comparator cannot be null"));
        if (cached) {
            this.tocEntries = tocEntries;
        }
        return tocEntries;
    }


    public List<SentencedSearchResult> getSearchResults(int page) {
        return getSearchResults(page, null);
    }

    public List<SentencedSearchResult> getSearchResults(int page, SentenceExtractor extractor) {
        SearchRecord searchRecord = searchRecords.get(page);
        if (pdfFile == null || pdfiumCore == null || searchRecord == null) {
            return Collections.emptyList();
        }
        long textPtr = pdfFile.getTextPage(searchRecord.pageIdx);
        String pageText = pdfiumCore.nativeGetText(textPtr);
        List<SearchRecordItem> items = searchRecord.data;
        return SearchUtils.extractSearchResults(pageText, items, extractor);
    }

    /**
     * Will be empty until document is loaded
     */
    public List<PdfDocument.Link> getLinks(int page) {
        if (pdfFile == null) {
            return Collections.emptyList();
        }
        return pdfFile.getPageLinks(page);
    }


    public boolean appendHighlight(int pageIndex, long selectionId) {
        if (selectionPaintView != null) {
            return selectionPaintView.appendHighlight(pageIndex, selectionId);
        }
        return false;
    }

    public boolean replaceHighlights(List<Highlight> highlights) {
        if (selectionPaintView != null) {
            return selectionPaintView.replaceHighlights(highlights);
        }
        return false;
    }

    public boolean removeHighlights(int pageIndex, long selectionId) {
        if (selectionPaintView != null) {
            return selectionPaintView.removeHighlight(pageIndex, selectionId);
        }
        return false;
    }

    public void clearHighlights() {
        if (selectionPaintView != null) {
            selectionPaintView.clearHighlight();
        }
    }


    /**
     * Use an asset file as the pdf source
     */
    public Configurator fromAsset(String assetName) {
        return new Configurator(new AssetSource(assetName));
    }

    /**
     * Use a file as the pdf source
     */
    public Configurator fromFile(File file) {
        return new Configurator(new FileSource(file));
    }

    /**
     * Use URI as the pdf source, for use with content providers
     */
    public Configurator fromUri(Uri uri) {
        return new Configurator(new UriSource(uri));
    }

    /**
     * Use bytearray as the pdf source, documents is not saved
     */
    public Configurator fromBytes(byte[] bytes) {
        return new Configurator(new ByteArraySource(bytes));
    }

    /**
     * Use stream as the pdf source. Stream will be written to bytearray, because native code does not support Java Streams
     */
    public Configurator fromStream(InputStream stream) {
        return new Configurator(new InputStreamSource(stream));
    }

    /**
     * Use custom source as pdf source
     */
    public Configurator fromSource(DocumentSource docSource) {
        return new Configurator(docSource);
    }

    private enum State {DEFAULT, LOADED, SHOWN, ERROR}

    public class Configurator {

        private final DocumentSource documentSource;

        private int[] pageNumbers = null;

        private boolean enableSwipe = true;

        private boolean enableDoubleTap = true;

        private OnDrawListener onDrawListener;

        private OnDrawListener onDrawAllListener;

        private OnLoadCompleteListener onLoadCompleteListener;

        private OnErrorListener onErrorListener;

        private OnPageChangeListener onPageChangeListener;

        private OnPageScrollListener onPageScrollListener;

        private OnRenderListener onRenderListener;

        private OnTapListener onTapListener;

        private OnScaleListener onScaleListener;

        private Runnable onSelectionListener;

        private OnSelectionListener onSelectionEndedListener;

        private OnTextSelectionListener onTextSelectionEndedListener;

        private OnSearchBeginListener onSearchBeginListener;

        private OnSearchEndListener onSearchEndListener;

        private OnSearchMatchListener onSearchMatchListener;

        private OnLongPressListener onLongPressListener;

        private OnPageErrorListener onPageErrorListener;

        private LinkHandler linkHandler = new DefaultLinkHandler(PDFView.this);

        private OnHighlightClickListener onHighlightClickListener;

        private int defaultPage = 0;

        private boolean swipeHorizontal = false;

        private boolean annotationRendering = false;

        private String password = null;

        public ScrollHandle scrollHandle = null;

        private boolean antialiasing = true;

        private int spacing = 0;

        private int spacingTop = 0;

        private int spacingBottom = 0;

        private boolean autoSpacing = false;

        private FitPolicy pageFitPolicy = FitPolicy.WIDTH;

        private boolean fitEachPage = false;

        private boolean pageFling = false;

        private boolean pageSnap = false;

        private boolean nightMode = false;
        private float contrast = 1f;
        private float brightness = 1f;

        private boolean isSelectionLineMerged = false;
        private float lineThreshHoldPt = 0f;
        private float verticalExpandPercent = 0f;

        private View hideView = null;

        private Configurator(DocumentSource documentSource) {
            this.documentSource = documentSource;
        }

        public Configurator pages(int... pageNumbers) {
            this.pageNumbers = pageNumbers;
            return this;
        }


        public Configurator enableMergedSelectionLines(boolean enable) {
            this.isSelectionLineMerged = enable;
            return this;
        }

        /**
         * @param lineThreshHoldPt      range [0f,10f] any other values will be clamped
         * @param verticalExpandPercent clamp(0f,1f) any other value will be clamped
         */
        public Configurator enableMergedSelectionLines(boolean enable, float lineThreshHoldPt, float verticalExpandPercent) {
            enableMergedSelectionLines(enable);
            setLineThreshold(lineThreshHoldPt);
            setVerticalExpandPercent(verticalExpandPercent);
            return this;
        }

        /**
         * @param lineThreshHoldPt range [0f,10f] any other values will be clamped
         */
        public Configurator setLineThreshold(float lineThreshHoldPt) {
            this.lineThreshHoldPt = lineThreshHoldPt;
            return this;
        }

        /**
         * @param verticalExpandPercent clamp(0f,1f) any other value will be clamped
         */
        public Configurator setVerticalExpandPercent(float verticalExpandPercent) {
            this.verticalExpandPercent = verticalExpandPercent;
            return this;
        }

        public Configurator enableSwipe(boolean enableSwipe) {
            this.enableSwipe = enableSwipe;
            return this;
        }

        public Configurator enableDoubleTap(boolean enableDoubleTap) {
            this.enableDoubleTap = enableDoubleTap;
            return this;
        }

        public Configurator enableAnnotationRendering(boolean annotationRendering) {
            this.annotationRendering = annotationRendering;
            return this;
        }

        public Configurator onDraw(OnDrawListener onDrawListener) {
            this.onDrawListener = onDrawListener;
            return this;
        }

        public Configurator onDrawAll(OnDrawListener onDrawAllListener) {
            this.onDrawAllListener = onDrawAllListener;
            return this;
        }

        public Configurator onLoad(OnLoadCompleteListener onLoadCompleteListener) {
            this.onLoadCompleteListener = onLoadCompleteListener;
            return this;
        }

        public Configurator onPageScroll(OnPageScrollListener onPageScrollListener) {
            this.onPageScrollListener = onPageScrollListener;
            return this;
        }

        public Configurator onError(OnErrorListener onErrorListener) {
            this.onErrorListener = onErrorListener;
            return this;
        }

        public Configurator onPageError(OnPageErrorListener onPageErrorListener) {
            this.onPageErrorListener = onPageErrorListener;
            return this;
        }

        public Configurator onPageChange(OnPageChangeListener onPageChangeListener) {
            this.onPageChangeListener = onPageChangeListener;
            return this;
        }

        public Configurator onRender(OnRenderListener onRenderListener) {
            this.onRenderListener = onRenderListener;
            return this;
        }

        public Configurator onTap(OnTapListener onTapListener) {
            this.onTapListener = onTapListener;
            return this;
        }

        public Configurator onScale(OnScaleListener onScaleListener) {
            this.onScaleListener = onScaleListener;
            return this;
        }

        public Configurator onSelectionInProgress(Runnable onSelectionListener) {
            this.onSelectionListener = onSelectionListener;
            return this;
        }

        public Configurator onSelection(OnSelectionListener onSelectionListener) {
            this.onSelectionEndedListener = onSelectionListener;
            return this;
        }

        public Configurator onTextSelection(OnTextSelectionListener onTextSelectionListener) {
            this.onTextSelectionEndedListener = onTextSelectionListener;
            return this;
        }

        public Configurator onSearchBegin(OnSearchBeginListener onSearchBeginListener) {
            this.onSearchBeginListener = onSearchBeginListener;
            return this;
        }

        public Configurator onSearchEnd(OnSearchEndListener onSearchEndListener) {
            this.onSearchEndListener = onSearchEndListener;
            return this;
        }

        public Configurator onSearchMatch(OnSearchMatchListener onSearchMatchListener) {
            this.onSearchMatchListener = onSearchMatchListener;
            return this;
        }

        public Configurator onLongPress(OnLongPressListener onLongPressListener) {
            this.onLongPressListener = onLongPressListener;
            return this;
        }

        public Configurator linkHandler(LinkHandler linkHandler) {
            this.linkHandler = linkHandler;
            return this;
        }

        public Configurator onHighlightClick(OnHighlightClickListener onHighlightClickListener) {
            this.onHighlightClickListener = onHighlightClickListener;
            return this;
        }

        public Configurator defaultPage(int defaultPage) {
            this.defaultPage = defaultPage;
            return this;
        }

        public Configurator swipeHorizontal(boolean swipeHorizontal) {
            this.swipeHorizontal = swipeHorizontal;
            return this;
        }

        public Configurator password(String password) {
            this.password = password;
            return this;
        }

        public Configurator scrollHandle(ScrollHandle scrollHandle) {
            this.scrollHandle = scrollHandle;
            return this;
        }

        public Configurator enableAntialiasing(boolean antialiasing) {
            this.antialiasing = antialiasing;
            return this;
        }

        public Configurator onScrollingHideView(View view) {
            this.hideView = view;
            return this;
        }

        public Configurator spacing(int spacing) {
            this.spacing = spacing;
            return this;
        }

        public Configurator spacingTop(int spacingTop) {
            this.spacingTop = spacingTop;
            return this;
        }

        public Configurator spacingBottom(int spacingBottom) {
            this.spacingBottom = spacingBottom;
            return this;
        }

        public Configurator autoSpacing(boolean autoSpacing) {
            this.autoSpacing = autoSpacing;
            return this;
        }

        public Configurator pageFitPolicy(FitPolicy pageFitPolicy) {
            this.pageFitPolicy = pageFitPolicy;
            return this;
        }

        public Configurator fitEachPage(boolean fitEachPage) {
            this.fitEachPage = fitEachPage;
            return this;
        }

        public Configurator pageSnap(boolean pageSnap) {
            this.pageSnap = pageSnap;
            return this;
        }

        public Configurator pageFling(boolean pageFling) {
            this.pageFling = pageFling;
            return this;
        }

        public Configurator nightMode(boolean nightMode) {
            this.nightMode = nightMode;
            return this;
        }

        public Configurator nightMode(boolean nightMode, float brightness, float contrast) {
            this.nightMode = nightMode;
            this.brightness = brightness;
            this.contrast = contrast;
            return this;
        }


        public Configurator disableLongPress() {
            PDFView.this.dragPinchManager.disableLongPress();
            return this;
        }


        /**
         * Loads the pdf using configurator
         * Make sure to setSelectionPaintView(PDocSelection) before calling load()
         *
         * @see PDFView#setSelectionPaintView(PDocSelection)
         */
        public void load() {
            if (!hasSize) {
                waitingDocumentConfigurator = this;
                return;
            }
            PDFView.this.recycle();
            PDFView.this.callbacks.setOnLoadComplete(onLoadCompleteListener);
            PDFView.this.callbacks.setOnError(onErrorListener);
            PDFView.this.callbacks.setOnDraw(onDrawListener);
            PDFView.this.callbacks.setOnDrawAll(onDrawAllListener);
            PDFView.this.callbacks.setOnPageChange(onPageChangeListener);
            PDFView.this.callbacks.setOnPageScroll(onPageScrollListener);
            PDFView.this.callbacks.setOnRender(onRenderListener);
            PDFView.this.callbacks.setOnTap(onTapListener);
            PDFView.this.callbacks.setOnScale(onScaleListener);
            PDFView.this.callbacks.setOnSelection(onSelectionListener);
            PDFView.this.callbacks.setOnSelectionEndedListener(onSelectionEndedListener);
            PDFView.this.callbacks.setOnTextSelectionEndedListener(onTextSelectionEndedListener);
            PDFView.this.callbacks.setOnSearchBegin(onSearchBeginListener);
            PDFView.this.callbacks.setOnSearchEnd(onSearchEndListener);
            PDFView.this.callbacks.setOnSearchMatch(onSearchMatchListener);
            PDFView.this.callbacks.setOnLongPress(onLongPressListener);
            PDFView.this.callbacks.setOnPageError(onPageErrorListener);
            PDFView.this.callbacks.setLinkHandler(linkHandler);
            PDFView.this.callbacks.setOnHighlightClickListener(onHighlightClickListener);
            PDFView.this.setSwipeEnabled(enableSwipe);
            if (brightness == 1f && contrast == 1f) {
                PDFView.this.setNightMode(nightMode);
            } else {
                PDFView.this.setNightMode(nightMode, brightness, contrast);
            }
            PDFView.this.enableDoubleTap(enableDoubleTap);
            PDFView.this.setDefaultPage(defaultPage);
            PDFView.this.setSwipeVertical(!swipeHorizontal);
            PDFView.this.enableAnnotationRendering(annotationRendering);
            PDFView.this.enableAntialiasing(antialiasing);
            PDFView.this.setSpacing(spacing);
            PDFView.this.setSpacingTop(spacingTop);
            PDFView.this.setSpacingBottom(spacingBottom);
            PDFView.this.setScrollHandle(scrollHandle);
            PDFView.this.setAutoSpacing(autoSpacing);
            PDFView.this.setPageFitPolicy(pageFitPolicy);
            PDFView.this.setFitEachPage(fitEachPage);
            PDFView.this.setPageSnap(pageSnap);
            PDFView.this.setPageFling(pageFling);
            PDFView.this.setOnScrollHideView(hideView);
            PDFView.this.setMergedSelectionLine(isSelectionLineMerged, lineThreshHoldPt, verticalExpandPercent);

            if (selectionPaintView == null) {
                throw new IllegalArgumentException("Did you forget to PDFView#setSelectionPaintView(PDocSelection)?");
            }

            if (pageNumbers != null) {
                PDFView.this.load(documentSource, password, pageNumbers);
            } else {
                PDFView.this.load(documentSource, password);
            }
        }
    }


}
