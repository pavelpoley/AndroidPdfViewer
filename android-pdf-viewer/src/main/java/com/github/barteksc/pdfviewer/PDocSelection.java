package com.github.barteksc.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;


import androidx.annotation.Nullable;

import com.github.barteksc.pdfviewer.model.SearchRecord;
import com.github.barteksc.pdfviewer.model.SearchRecordItem;
import com.github.barteksc.pdfviewer.util.Util;

import java.util.ArrayList;

/**
 * A View to paint PDF selections, [magnifier] and search highlights
 */
public class PDocSelection extends View {
    public boolean supressRecalcInval;
    PDFView pDocView;
    float drawableWidth = 60;
    float drawableHeight = 30;
    float drawableDeltaW = drawableWidth / 4;
    Paint rectPaint;
    Paint rectFramePaint;
    Paint rectHighlightPaint;

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
        rectPaint = new Paint();
        rectPaint.setColor(0x66109afe);
        rectHighlightPaint = new Paint();
        rectHighlightPaint.setColor(Color.YELLOW);
        rectPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DARKEN));
        rectHighlightPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DARKEN));
        rectFramePaint = new Paint();
        rectFramePaint.setColor(0xccc7ab21);
        rectFramePaint.setStyle(Paint.Style.STROKE);
        rectFramePaint.setStrokeWidth(0.5f);
    }

    public void resetSel() {
        if (pDocView != null && pDocView.pdfFile != null && pDocView.hasSelection) {
            long tid = pDocView.dragPinchManager.loadText();
            if (pDocView.isNotCurrentPage(tid)) {
                return;
            }

            boolean b1 = pDocView.selPageEd < pDocView.selPageSt;
            if (b1) {
                pDocView.selPageEd = pDocView.selPageSt;
                pDocView.selPageSt = pDocView.selPageEd;
            } else {
                pDocView.selPageEd = pDocView.selPageEd;
                pDocView.selPageSt = pDocView.selPageSt;
            }
            if (b1 || pDocView.selPageEd == pDocView.selPageSt && pDocView.selEnd < pDocView.selStart) {
                pDocView.selStart = pDocView.selEnd;
                pDocView.selEnd = pDocView.selStart;
            } else {
                pDocView.selStart = pDocView.selStart;
                pDocView.selEnd = pDocView.selEnd;
            }
            int pageCount = pDocView.selPageEd - pDocView.selPageSt;
            int sz = rectPool.size();
            ArrayList<RectF> rectPagePool;
            for (int i = 0; i <= pageCount; i++) {
                if (i >= sz) {
                    rectPool.add(rectPagePool = new ArrayList<>());
                } else {
                    rectPagePool = rectPool.get(i);
                }
                int selSt = i == 0 ? pDocView.selStart : 0;
                int selEd = i == pageCount ? pDocView.selEnd : -1;
                // PDocument.PDocPage page = pDocView.pdfFile.mPDocPages[selPageSt + i];

                pDocView.dragPinchManager.getSelRects(rectPagePool, selSt, selEd);//+10
            }
            recalcHandles();
            rectPoolSize = pageCount + 1;
        } else {
            rectPoolSize = 0;
        }
        if (!supressRecalcInval) {
            invalidate();
        }
    }

    public void recalcHandles() {
        PDFView page = pDocView;
        long tid = page.dragPinchManager.prepareText();
        if (pDocView.isNotCurrentPage(tid)) {
            return;
        }

        int st = pDocView.selStart;
        int ed = pDocView.selEnd;
        int dir = pDocView.selPageEd - pDocView.selPageSt;
        dir = (int) Math.signum(dir == 0 ? ed - st : dir);
        if (dir != 0) {
            String atext = page.dragPinchManager.allText;
            int len = atext.length();
            if (st >= 0 && st < len) {
                char c;
                while (((c = atext.charAt(st)) == '\r' || c == '\n') && st + dir >= 0 && st + dir < len) {
                    st += dir;
                }
            }
            page.getCharPos(pDocView.handleLeftPos, st);
            pDocView.lineHeightLeft = pDocView.handleLeftPos.height() / 2;
            page.getCharLoosePos(pDocView.handleLeftPos, st);

            page = pDocView;
            page.dragPinchManager.prepareText();
            atext = page.dragPinchManager.allText;
            len = atext.length();
            int delta = -1;
            if (ed >= 0 && ed < len) {
                char c;
                dir *= -1;
                while (((c = atext.charAt(ed)) == '\r' || c == '\n') && ed + dir >= 0 && ed + dir < len) {
                    delta = 0;
                    ed += dir;
                }
            }
            page.getCharPos(pDocView.handleRightPos, ed + delta);
            pDocView.lineHeightRight = pDocView.handleRightPos.height() / 2;
            page.getCharLoosePos(pDocView.handleRightPos, ed + delta);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (pDocView == null) {
            return;
        }

        super.onDraw(canvas);

        try {
            Matrix matrix = pDocView.matrix;

            if (pDocView.isSearching && pDocView.pdfFile != null) {
                SearchRecord record = getSearchRecord(pDocView.currentPage);
                if (record != null) {
                    pDocView.getAllMatchOnPage(record, record.pageIdx);
                    ArrayList<SearchRecordItem> data = (ArrayList<SearchRecordItem>) record.data;
                    for (int j = 0, len = data.size(); j < len; j++) {
                        RectF[] rects = data.get(j).rects;
                        if (rects != null) {
                            for (RectF rI : rects) {
                                pDocView.sourceToViewRectFFSearch(rI, VR, pDocView.currentPage);
                                matrix.reset();
                                int bmWidth = (int) rI.width();
                                int bmHeight = (int) rI.height();
                                pDocView.setMatrixArray(pDocView.srcArray, 0, 0, bmWidth, 0, bmWidth, bmHeight, 0, bmHeight);
                                pDocView.setMatrixArray(pDocView.dstArray, VR.left, VR.top, VR.right, VR.top, VR.right, VR.bottom, VR.left, VR.bottom);
                                matrix.setPolyToPoly(pDocView.srcArray, 0, pDocView.dstArray, 0, 4);
                                matrix.postRotate(0, pDocView.getScreenWidth(), pDocView.getScreenHeight());
                                canvas.save();
                                canvas.concat(matrix);
                                VR.set(0, 0, bmWidth, bmHeight);
                                canvas.drawRect(VR, rectHighlightPaint);
                                canvas.restore();
                            }
                        }
                    }
                }
            }

            if (pDocView.hasSelection && pDocView.pdfFile != null) {
                for (int i = 0; i < rectPoolSize; i++) {

                    ArrayList<RectF> rectPage = rectPool.get(i);
                    for (RectF rI : rectPage) {
                        pDocView.sourceToViewRectFF(rI, VR);
                        matrix.reset();
                        int bmWidth = (int) rI.width();
                        int bmHeight = (int) rI.height();
                        pDocView.setMatrixArray(pDocView.srcArray, 0, 0, bmWidth, 0, bmWidth, bmHeight, 0, bmHeight);
                        pDocView.setMatrixArray(pDocView.dstArray, VR.left, VR.top, VR.right, VR.top, VR.right, VR.bottom, VR.left, VR.bottom);

                        matrix.setPolyToPoly(pDocView.srcArray, 0, pDocView.dstArray, 0, 4);
                        matrix.postRotate(0, pDocView.getScreenWidth(), pDocView.getScreenHeight());

                        canvas.save();
                        canvas.concat(matrix);
                        VR.set(0, 0, bmWidth, bmHeight);
                        canvas.drawRect(VR, rectPaint);
                        canvas.restore();
                    }
                }

            }
        } catch (Exception e) {
            Log.e("PDF_TEXT_SELECTION", "onDraw: ", e);
        }
    }

    /**
     * To draw search result after and before current page
     **/
    private SearchRecord getSearchRecord(int page) {
        if (pDocView.searchRecords.containsKey(page)) {
            return pDocView.searchRecords.get(page);
        }
        return null;
    }
}
