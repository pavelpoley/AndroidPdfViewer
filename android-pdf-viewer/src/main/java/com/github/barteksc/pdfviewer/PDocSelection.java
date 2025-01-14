package com.github.barteksc.pdfviewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Magnifier;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.util.Consumer;

import com.github.barteksc.pdfviewer.model.SearchRecord;
import com.github.barteksc.pdfviewer.model.SearchRecordItem;
import com.github.barteksc.pdfviewer.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * A View to paint PDF selections, [magnifier] and search highlights
 */

@SuppressWarnings("unused")
public class PDocSelection extends View {

    private static final String TAG = "PDocSelection";
    private Magnifier magnifier;

    private PDocSelectionConfig config;


    public boolean suppressRecalculateInvalidate;
    PDFView pdfView;
    private float dragHandleHeight;
    private float dragHandleWidth;
    private Paint selectionPaint;
    private Paint searchedFocusedSelectionPaint;
    private Paint searchedSelectionPaint;

    RectF startHandleRectF;
    RectF endHandleRectF;

    private Drawable startSelectionHandle;
    private Drawable endSelectionHandle;

    /**
     * output image
     */
    private final RectF VR = new RectF();

    int rectPoolSize = 0;
    ArrayList<ArrayList<RectF>> rectPool = new ArrayList<>();

    public PDocSelection(Context context) {
        super(context);
        init();
    }

    public PDocSelection(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PDocSelection(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            magnifier = new Magnifier.Builder(this)
                    .setCornerRadius(50)
                    .setDefaultSourceToMagnifierOffset(0, -200)
                    .setInitialZoom(3f)
                    .build();
        }
        startHandleRectF = new RectF();
        endHandleRectF = new RectF();

        PorterDuffXfermode xfermode = new PorterDuffXfermode(PorterDuff.Mode.DARKEN);


        selectionPaint = new Paint();
        selectionPaint.setColor(0X66109AFE);
        selectionPaint.setXfermode(xfermode);

        searchedSelectionPaint = new Paint();
        searchedSelectionPaint.setColor(Color.YELLOW);
        searchedSelectionPaint.setXfermode(xfermode);


        searchedFocusedSelectionPaint = new Paint();
        searchedFocusedSelectionPaint.setColor(0X660000FF);
        searchedFocusedSelectionPaint.setXfermode(xfermode);
        dragHandleHeight = Util.dpToPx(getContext(), 32);
        dragHandleWidth = Util.dpToPx(getContext(), 32);

        startSelectionHandle = ResourcesCompat.getDrawable(getResources(),
                R.drawable.abc_text_select_handle_left_mtrl_dark, getContext().getTheme());
        endSelectionHandle = ResourcesCompat.getDrawable(getResources(),
                R.drawable.abc_text_select_handle_right_mtrl_dark, getContext().getTheme());

        ColorFilter colorFilter = new PorterDuffColorFilter(0XDD309AFE, PorterDuff.Mode.SRC_IN);
        ColorFilter colorFilterEnd = new PorterDuffColorFilter(0XDDbbcc02, PorterDuff.Mode.SRC_IN);
        startSelectionHandle.setColorFilter(colorFilter);
        endSelectionHandle.setColorFilter(colorFilterEnd);
        config = PDocSelectionConfig.getInstance(this);
    }


    private final int[] viewPosition = new int[2];

    void showMagnifier(float x, float y) {
        if (magnifier == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getLocationOnScreen(viewPosition);
            magnifier.show(x, y - 22);
        }
    }

    void dismissMagnifier() {
        if (magnifier == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            magnifier.dismiss();
        }
    }

    public void resetSel() {
        if (pdfView != null && pdfView.pdfFile != null && pdfView.hasSelection) {
            long tid = pdfView.dragPinchManager.loadText();
            if (pdfView.isNotCurrentPage(tid)) {
                return;
            }
            int pageCount = pdfView.selPageEd - pdfView.selPageSt;
            int sz = rectPool.size();
            ArrayList<RectF> rectPagePool;
            for (int i = 0; i <= pageCount; i++) {
                if (i >= sz) {
                    rectPool.add(rectPagePool = new ArrayList<>());
                } else {
                    rectPagePool = rectPool.get(i);
                }
                int selSt = i == 0 ? pdfView.selStart : 0;
                int selEd = i == pageCount ? pdfView.selEnd : -1;
                // PDocument.PDocPage page = pDocView.pdfFile.mPDocPages[selPageSt + i];

                pdfView.dragPinchManager.getSelRects(rectPagePool, selSt, selEd);//+10
            }
            recalculateHandles();
            rectPoolSize = pageCount + 1;
        } else {
            rectPoolSize = 0;
        }
        if (!suppressRecalculateInvalidate) {
            invalidate();
        }
    }


    public RectF fullRect(int page) {
        float top = -1.0f, bottom = -1.0f, left = -1.0f, right = -1.0f;
        if (rectPoolSize > 1) return new RectF();

        for (int i = 0; i < rectPoolSize; i++) {
            ArrayList<RectF> rectPage = rectPool.get(i);
            for (RectF rI : rectPage) {
                if (rI.top < top || top == -1.0f) {
                    top = rI.top;
                }
                if (rI.bottom > bottom || bottom == -1.0f) {
                    bottom = rI.bottom;
                }
                if (rI.left < left || left == -1.0f) {
                    left = rI.left;
                }
                if (rI.right > right || right == -1.0f) {
                    right = rI.right;
                }
            }
        }
        return new RectF(left, top, right, bottom);
    }

    private final RectF fullSelectedRectF = new RectF();
    private final RectF AR = new RectF();

    public RectF getRectFMappedToScreen() {
        float left = -1f, top = -1f, right = -1f, bottom = -1f;
        fullSelectedRectF.set(left, top, right, bottom);
        if (rectPoolSize > 1) return fullSelectedRectF;
        for (int i = 0; i < rectPool.size(); i++) {
            ArrayList<RectF> rectFS = rectPool.get(i);
            for (int j = 0; j < rectFS.size(); j++) {
                mapRect(rectFS.get(j), AR);
                if (j == 0) {
                    left = AR.left;
                    top = AR.top;
                }
                if (j == rectFS.size() - 1) {
                    bottom = AR.bottom;
                }
                if (AR.left > -1f) {
                    left = Math.min(AR.left, left);
                }
                right = Math.max(right, AR.right);
            }
            fullSelectedRectF.set(left, top, right, bottom);
        }
        return fullSelectedRectF;
    }

    private final Matrix m = new Matrix();
    private final float[] fSrc = new float[8];
    private final float[] fDes = new float[8];

    private void mapRect(RectF src, RectF dest) {
        m.reset();
        pdfView.sourceToViewRectFF(src, dest);
        int bmWidth = (int) src.width() + 2;
        int bmHeight = (int) src.height() + 2;
        pdfView.setMatrixArray(fSrc, 0, 0, bmWidth, 0, bmWidth, bmHeight, 0, bmHeight);
        pdfView.setMatrixArray(fDes, dest.left, dest.top, dest.right, dest.top, dest.right, dest.bottom, dest.left, dest.bottom);
        m.setPolyToPoly(fSrc, 0, fDes, 0, 4);
        m.postRotate(0, pdfView.getScreenWidth(), pdfView.getScreenHeight());
        dest.set(0, 0, bmWidth, bmHeight);
        m.mapRect(dest);
    }


    public void recalculateHandles() {
        long tid = pdfView.dragPinchManager.prepareText();
        if (pdfView.isNotCurrentPage(tid)) {
            return;
        }

        int st = pdfView.selStart;
        int ed = pdfView.selEnd;
        int dir = pdfView.selPageEd - pdfView.selPageSt;
        dir = (int) Math.signum(dir == 0 ? ed - st : dir);
        if (dir != 0) {
            String allText = pdfView.dragPinchManager.allText;
            int len = allText.length();
            if (st >= 0 && st < len) {
                char c;
                while (((c = allText.charAt(st)) == '\r' || c == '\n') && st + dir >= 0 && st + dir < len) {
                    st += dir;
                }
            }
            pdfView.getCharPos(pdfView.handleLeftPos, st);
            pdfView.lineHeightStart = pdfView.handleLeftPos.height() / 2;
            pdfView.getCharLoosePos(pdfView.handleLeftPos, st);

            pdfView.dragPinchManager.prepareText();
            allText = pdfView.dragPinchManager.allText;
            len = allText.length();
            int delta = -1;
            if (ed >= 0 && ed < len) {
                char c;
                dir *= -1;
                while (((c = allText.charAt(ed)) == '\r' || c == '\n') && ed + dir >= 0 && ed + dir < len) {
                    delta = 0;
                    ed += dir;
                }
            }
            pdfView.getCharPos(pdfView.handleRightPos, ed + delta);
            pdfView.lineHeightEnd = pdfView.handleRightPos.height() / 2;
            pdfView.getCharLoosePos(pdfView.handleRightPos, ed + delta);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (pdfView == null) {
            return;
        }
        super.onDraw(canvas);
        try {
            if (pdfView.isSearching && pdfView.pdfFile != null) {
                int currentPage = pdfView.currentPage;
                var record0 = pdfView.getAllMatchOnPage(getSearchRecord(currentPage - 1));
                var record = pdfView.getAllMatchOnPage(getSearchRecord(currentPage));
                var record1 = pdfView.getAllMatchOnPage(getSearchRecord(currentPage + 1));
                highlightSearch(canvas, record0);
                highlightSearch(canvas, record);
                highlightSearch(canvas, record1);
            }
            if (pdfView.hasSelection && pdfView.pdfFile != null) {
                var matrix = pdfView.matrix;
                for (int i = 0; i < rectPoolSize; i++) {
                    ArrayList<RectF> rectPage = rectPool.get(i);
                    for (int j = 0, rectPageSize = rectPage.size(); j < rectPageSize; j++) {
                        RectF rI = rectPage.get(j);
                        pdfView.sourceToViewRectFF(rI, VR);
                        matrix.reset();
                        int bmWidth = (int) rI.width() + 2;
                        int bmHeight = (int) rI.height() + 2;
                        pdfView.setMatrixArray(pdfView.srcArray, 0, 0, bmWidth, 0, bmWidth, bmHeight, 0, bmHeight);
                        pdfView.setMatrixArray(pdfView.dstArray, VR.left, VR.top, VR.right, VR.top, VR.right, VR.bottom, VR.left, VR.bottom);
                        matrix.setPolyToPoly(pdfView.srcArray, 0, pdfView.dstArray, 0, 4);
                        canvas.save();
                        canvas.concat(matrix);
                        VR.set(0, 0, bmWidth, bmHeight);
                        canvas.drawRect(VR, selectionPaint);

                        //draw start and right drag handle
                        float handleSizeW = dragHandleWidth / pdfView.getZoom();
                        float handleSizeH = dragHandleHeight / pdfView.getZoom();
                        if (j == 0) {
                            int left = (int) (VR.left - handleSizeW);
                            int top = (int) VR.bottom;
                            int right = (int) VR.left;
                            int bottom = (int) (VR.bottom + handleSizeH);
                            startSelectionHandle.setBounds(left, top, right, bottom);
                            startHandleRectF.set(left, top, right, bottom);
                            matrix.mapRect(startHandleRectF);
                            startSelectionHandle.draw(canvas);
                        }
                        if (j == rectPage.size() - 1) {
                            int left = (int) (VR.right);
                            int top = (int) VR.bottom;
                            int right = (int) (VR.right + handleSizeW);
                            int bottom = (int) (VR.bottom + handleSizeH);
                            endSelectionHandle.setBounds(left, top, right, bottom);
                            endHandleRectF.set(left, top, right, bottom);
                            matrix.mapRect(endHandleRectF);
                            endSelectionHandle.draw(canvas);
                        }
                        canvas.restore();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onDraw: ", e);
        }
    }

    private void highlightSearch(@NonNull Canvas canvas, List<SearchRecordItem> record) {
        var matrix = pdfView.matrix;
        for (int j = 0, len = record.size(); j < len; j++) {
            SearchRecordItem searchRecordItem = record.get(j);
            if (searchRecordItem != null) {
                for (RectF rI : searchRecordItem.rectFS) {
                    pdfView.sourceToViewRectFFSearch(rI, VR, searchRecordItem.pageIndex);
                    matrix.reset();
                    int bmWidth = (int) rI.width() + 2;
                    int bmHeight = (int) rI.height() + 2;
                    pdfView.setMatrixArray(pdfView.srcArray, 0, 0, bmWidth, 0, bmWidth, bmHeight, 0, bmHeight);
                    pdfView.setMatrixArray(pdfView.dstArray, VR.left, VR.top, VR.right, VR.top, VR.right, VR.bottom, VR.left, VR.bottom);
                    matrix.setPolyToPoly(pdfView.srcArray, 0, pdfView.dstArray, 0, 4);
                    canvas.save();
                    canvas.concat(matrix);
                    VR.set(0, 0, bmWidth, bmHeight);
                    canvas.drawRect(VR, pdfView.currentFocusedSearchItem == searchRecordItem ? searchedFocusedSelectionPaint : searchedSelectionPaint);
                    canvas.restore();
                }
            }
        }
    }


    /**
     * To draw search result after and before current page
     **/

    @Nullable
    private SearchRecord getSearchRecord(int page) {
        if (pdfView.searchRecords.containsKey(page)) {
            return pdfView.searchRecords.get(page);
        }
        return null;
    }

    /**
     * The `PDocSelectionConfig` class follows a "Deferred Configuration Pattern" (a variation of the Builder Pattern),
     * allowing users to configure properties of a `PDocSelection` instance incrementally.
     * <p>
     * Configurations are staged in temporary variables and applied to the actual instance only when {@link #apply()} is called.
     * This ensures that no partial changes are applied prematurely, promoting immutability during configuration.
     */
    public static class PDocSelectionConfig {
        private static PDocSelectionConfig getInstance(PDocSelection selection) {
            return new PDocSelectionConfig(selection);
        }

        private final PDocSelection selection;
        private Consumer<Paint> selectionPaintConsumer;
        private Consumer<Paint> searchedSelectionConsumer;
        private Consumer<Paint> focusedSearchedConsumer;

        private Drawable tempStartSelectionHandle;
        private Consumer<Drawable> startDragConsumer;

        private Drawable tempEndSelectionHandle;
        private Consumer<Drawable> endDargConsumer;

        private float tempDragHandleWidth;
        private float tempDragHandleHeight;

        private PDocSelectionConfig(PDocSelection selection) {
            this.selection = selection;
            this.tempStartSelectionHandle = selection.startSelectionHandle;
            this.tempEndSelectionHandle = selection.endSelectionHandle;
            this.tempDragHandleWidth = selection.dragHandleWidth;
            this.tempDragHandleHeight = selection.dragHandleHeight;
        }


        public PDocSelectionConfig setEndSelectionHandleColor(@ColorInt int color) {
            if (tempEndSelectionHandle != null) {
                tempEndSelectionHandle.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            }
            return this;
        }

        public PDocSelectionConfig setStartSelectionHandleColor(@ColorInt int color) {
            if (tempStartSelectionHandle != null) {
                tempStartSelectionHandle.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            }
            return this;
        }

        public PDocSelectionConfig setSelectionHandleColor(@ColorInt int color) {
            return setStartSelectionHandleColor(color)
                    .setEndSelectionHandleColor(color);

        }


        public PDocSelectionConfig setDragHandleSizeDp(int widthDpValue, int heightDpValue) {
            this.tempDragHandleWidth = Util.dpToPx(selection.getContext(), (float) widthDpValue);
            this.tempDragHandleHeight = Util.dpToPx(selection.getContext(), (float) heightDpValue);
            return this;
        }

        public PDocSelectionConfig setDragHandleSize(float widthPx, float heightPx) {
            this.tempDragHandleWidth = widthPx;
            this.tempDragHandleHeight = heightPx;
            return this;
        }

        public PDocSelectionConfig setDragHandleHeightDp(int dpValue) {
            this.tempDragHandleHeight = Util.dpToPx(selection.getContext(), (float) dpValue);
            return this;
        }

        public PDocSelectionConfig setDragHandleHeight(float pixels) {
            this.tempDragHandleHeight = pixels;
            return this;
        }

        public PDocSelectionConfig setDragHandleWidthDp(int dpValue) {
            this.tempDragHandleWidth = Util.dpToPx(selection.getContext(), (float) dpValue);
            return this;
        }

        public PDocSelectionConfig setDragHandleWidth(float pixels) {
            this.tempDragHandleWidth = pixels;
            return this;
        }

        public PDocSelectionConfig setEndSelectionDragHandle(Drawable endSelectionHandle) {
            this.tempEndSelectionHandle = endSelectionHandle;
            return this;
        }

        public PDocSelectionConfig setEndSelectionDragHandle(@DrawableRes int resId) {
            this.tempEndSelectionHandle = ResourcesCompat.getDrawable(selection.getResources(),
                    resId, selection.getContext().getTheme());
            return this;
        }

        public PDocSelectionConfig setStartSelectionHandle(Drawable startSelectionHandle) {
            this.tempStartSelectionHandle = startSelectionHandle;
            return this;
        }

        public PDocSelectionConfig setStartSelectionHandle(@DrawableRes int resId) {
            this.tempStartSelectionHandle = ResourcesCompat.getDrawable(selection.getResources(),
                    resId, selection.getContext().getTheme());
            return this;
        }

        /**
         * Updates the paint configuration for the selection.
         * This method accepts a consumer function that modifies the selection paint.
         * <p>
         * Example usage:
         * <pre>
         * PDocSelectionConfig config = new PDocSelectionConfig();
         * config.updateSelectionPaint(paint -> paint.setColor(Color.RED));
         * </pre>
         * This will set the selection paint color to red.
         *
         * @param paintConsumer A consumer that accepts a {@link Paint} object and modifies it.
         * @return The current {@link PDocSelectionConfig} instance, allowing for method chaining.
         */
        public PDocSelectionConfig updateSelectionPaint(Consumer<Paint> paintConsumer) {
            this.selectionPaintConsumer = paintConsumer;
            return this;
        }

        /**
         * Updates the paint configuration for the searched selection.
         * This method accepts a consumer function that modifies the searched selection paint.
         * <p>
         * Example usage:
         * <pre>
         * PDocSelectionConfig config = ...
         * config.updateSearchedSelectionPaint(paint -> paint.setAlpha(128));
         * </pre>
         * This will set the searched selection paint to have 50% transparency.
         *
         * @param paintConsumer A consumer that accepts a {@link Paint} object and modifies it.
         * @return The current {@link PDocSelectionConfig} instance, allowing for method chaining.
         */
        public PDocSelectionConfig updateSearchedSelectionPaint(Consumer<Paint> paintConsumer) {
            this.searchedSelectionConsumer = paintConsumer;
            return this;
        }

        /**
         * Updates the paint configuration for the searched focused selection.
         * This method accepts a consumer function that modifies the searched focused selection paint.
         * <p>
         * Example usage:
         * <pre>
         * PDocSelectionConfig config = ...
         * config.updateSearchedFocusedSelectionPaint(paint -> paint.setStyle(Paint.Style.STROKE));
         * </pre>
         * This will set the searched focused selection paint style to stroke.
         *
         * @param paintConsumer A consumer that accepts a {@link Paint} object and modifies it.
         * @return The current {@link PDocSelectionConfig} instance, allowing for method chaining.
         */
        public PDocSelectionConfig updateSearchedFocusedSelectionPaint(Consumer<Paint> paintConsumer) {
            this.focusedSearchedConsumer = paintConsumer;
            return this;
        }

        /**
         * Updates the drawable configuration for the start drag handle.
         * This method accepts a consumer function that modifies the drawable used for the start drag handle.
         * <p>
         * Example usage:
         * <pre>
         * PDocSelectionConfig config = ...
         * config.updateStartDragHandleDrawable(drawable -> drawable.setTint(Color.BLUE));
         * </pre>
         * This will set the start drag handle drawable tint color to blue.
         *
         * @param drawableConsumer A consumer that accepts a {@link Drawable} object and modifies it.
         * @return The current {@link PDocSelectionConfig} instance, allowing for method chaining.
         */
        public PDocSelectionConfig updateStartDragHandleDrawable(Consumer<Drawable> drawableConsumer) {
            this.startDragConsumer = drawableConsumer;
            return this;
        }

        /**
         * Updates the drawable configuration for the end drag handle.
         * This method accepts a consumer function that modifies the drawable used for the end drag handle.
         * <p>
         * Example usage:
         * <pre>
         * PDocSelectionConfig config = ...
         * config.updateEndDragHandleDrawable(drawable -> drawable.setTint(Color.GREEN));
         * </pre>
         * This will set the end drag handle drawable tint color to green.
         *
         * @param drawableConsumer A consumer that accepts a {@link Drawable} object and modifies it.
         * @return The current {@link PDocSelectionConfig} instance, allowing for method chaining.
         */
        public PDocSelectionConfig updateEndDragHandleDrawable(Consumer<Drawable> drawableConsumer) {
            this.endDargConsumer = drawableConsumer;
            return this;
        }


        /**
         * Applies all the pending configurations to the selection instance.
         * <p>
         * This method applies various configuration changes to the {@link PDocSelection} instance, including
         * the start and end selection handles, drag handle dimensions, and any registered consumers
         * that modify the appearance or behavior of the selection. Consumers are applied last, after the
         * selection attributes are updated.
         * </p>
         *
         * The order of operations is as follows:
         * 1. Setters are applied.
         * 2. The consumers (if they are non-null) are invoked to further modify the attributes.
         * 3. Finally, the selection is invalidated (typically causing a redraw or refresh).
         * <p>
         * Example:
         * <pre>
         *     PDocSelectionConfig config = pDocSelection.modifySelectionUi();
         *     .updateSelectionPaint(paint -> paint.setColor(Color.RED))
         *     .updateStartDragHandleDrawable(drawable -> drawable.setTint(Color.BLUE))
         *     .apply();
         * </pre>
         * In this example:
         * 1. The selection paint color is set to red.
         * 2. The start drag handle drawable's tint color is set to blue.
         * After calling {@link  #apply()}, the changes are applied to the {@link PDocSelection} instance.
         * </pre>
         */
        public void apply() {
            selection.startSelectionHandle = this.tempStartSelectionHandle;
            selection.endSelectionHandle = this.tempEndSelectionHandle;
            selection.dragHandleWidth = this.tempDragHandleWidth;
            selection.dragHandleHeight = this.tempDragHandleHeight;
            applyLambda();
            selection.invalidate();
        }

        private void applyLambda() {
            if (selectionPaintConsumer != null) {
                selectionPaintConsumer.accept(selection.selectionPaint);
            }
            if (searchedSelectionConsumer != null) {
                searchedSelectionConsumer.accept(selection.searchedSelectionPaint);
            }
            if (focusedSearchedConsumer != null) {
                focusedSearchedConsumer.accept(selection.searchedFocusedSelectionPaint);
            }
            if (startDragConsumer != null) {
                startDragConsumer.accept(tempStartSelectionHandle);
            }
            if (endDargConsumer != null) {
                endDargConsumer.accept(tempEndSelectionHandle);
            }
        }
    }

    /**
     * Provides access to the configuration object for modifying the UI of the `PDocSelection`.
     * <p>
     * This method returns the `PDocSelectionConfig` instance, allowing users to customize various
     * aspects of the selection UI, such as paint colors, handle sizes, and handle drawables.
     * The actual changes are applied to the `PDocSelection` instance only when {@link PDocSelectionConfig#apply()} is invoked.
     * <p>
     * Example usage:
     * <pre>
     *     pDocSelection.modifySelectionUi()
     *                  .updateSelectionPaint(paint->{
     *                      paint.setColor(Color.RED);
     *                      paint.setXFrameMode(null); // clears default mode
     *                  })
     *                  .setDragHandleSizeDp(20, 30)
     *                  .apply();
     * </pre>
     *
     * @return the `PDocSelectionConfig` instance for configuring the selection UI
     */
    public PDocSelectionConfig modifySelectionUi() {
        return config;
    }


}
