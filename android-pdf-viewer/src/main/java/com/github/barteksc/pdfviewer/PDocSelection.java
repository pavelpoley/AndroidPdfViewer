package com.github.barteksc.pdfviewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Magnifier;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.barteksc.pdfviewer.model.SearchRecord;
import com.github.barteksc.pdfviewer.model.SearchRecordItem;

import java.util.ArrayList;
import java.util.List;

/**
 * A View to paint PDF selections, [magnifier] and search highlights
 */

@SuppressWarnings("unused")
public class PDocSelection extends View {

    private static final String TAG = "PDocSelection";
    private Magnifier magnifier;


    public boolean suppressRecalculateInvalidate;
    PDFView pdfView;
    float drawableWidth = 60;
    float drawableHeight = 30;
    float drawableDeltaW = drawableWidth / 4;
    private Paint rectPaint;
    Paint searchedFocusedPaint;
    Paint rectHighlightPaint;

    RectF startHandleRectF;
    RectF endHandleRectF;

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
        rectPaint = new Paint();
        startHandleRectF = new RectF();
        endHandleRectF = new RectF();

        rectPaint.setColor(0X66109AFE);
        PorterDuffXfermode xfermode = new PorterDuffXfermode(PorterDuff.Mode.DARKEN);
        rectPaint.setXfermode(xfermode);

        rectHighlightPaint = new Paint();
        rectHighlightPaint.setColor(Color.YELLOW);
        rectHighlightPaint.setXfermode(xfermode);


        searchedFocusedPaint = new Paint();
        searchedFocusedPaint.setColor(0X660000FF);
        searchedFocusedPaint.setXfermode(xfermode);
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
                        canvas.drawRect(VR, rectPaint);

                        //draw start and right drag handle
                        float handleSize = 88 / pdfView.getZoom();
                        if (j == 0) {
                            int left = (int) (VR.left - handleSize);
                            int top = (int) VR.bottom;
                            int right = (int) VR.left;
                            int bottom = (int) (VR.bottom + handleSize);
                            pdfView.startSelectionHandle.setBounds(left, top, right, bottom);
                            startHandleRectF.set(left, top, right, bottom);
                            matrix.mapRect(startHandleRectF);
                            pdfView.startSelectionHandle.draw(canvas);
                        }
                        if (j == rectPage.size() - 1) {
                            int left = (int) (VR.right);
                            int top = (int) VR.bottom;
                            int right = (int) (VR.right + handleSize);
                            int bottom = (int) (VR.bottom + handleSize);
                            pdfView.endSelectionHandle.setBounds(left, top, right, bottom);
                            endHandleRectF.set(left, top, right, bottom);
                            matrix.mapRect(endHandleRectF);
                            pdfView.endSelectionHandle.draw(canvas);
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
                    canvas.drawRect(VR, pdfView.currentFocusedSearchItem == searchRecordItem ? searchedFocusedPaint : rectHighlightPaint);
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
}
