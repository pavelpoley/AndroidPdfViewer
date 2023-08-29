package com.github.barteksc.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
     * Small Canvas for magnifier.
     * {@link Canvas#clipPath ClipPath} fails if the canvas it too high. ( will never happen in this project. )
     * see <a href="https://issuetracker.google.com/issues/132402784">issuetracker</a>)
     */
    Canvas cc;
    Bitmap PageCache;
    BitmapDrawable PageCacheDrawable;

    Path magClipper;
    RectF magClipperR;
    float magFactor = 1.5f;
    int magW = 560;
    int magH = 280;
    /**
     * output image
     */
    Drawable frameDrawable;
    private float framew;
    private final PointF vCursorPos = new PointF();

    private final RectF tmpPosRct = new RectF();


    //public PDocPageResultsProvider searchCtx;

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
        //rectPaint.setColor(0xffffff00);
        rectHighlightPaint = new Paint();
        rectHighlightPaint.setColor(getResources().getColor(R.color.heightlight_color));
        rectPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DARKEN));
        rectHighlightPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DARKEN));
        rectFramePaint = new Paint();
        rectFramePaint.setColor(0xccc7ab21);
        rectFramePaint.setStyle(Paint.Style.STROKE);
        rectFramePaint.setStrokeWidth(0.5f);
    }


    private void initMagnifier() {
        //setLayerType(LAYER_TYPE_NONE,null);
        cc = new Canvas(PageCache = Bitmap.createBitmap(magW, magH, Bitmap.Config.ARGB_8888));
        PageCacheDrawable = new BitmapDrawable(getResources(), PageCache);
        frameDrawable = getResources().getDrawable(R.drawable.frame);
        framew = getResources().getDimension(R.dimen.framew);
        magClipper = new Path();
        magClipperR = new RectF(PageCacheDrawable.getBounds());
        magClipper.reset();
        magClipperR.set(0, 0, magW, magH);
        magClipper.addRoundRect(magClipperR, framew + 5, framew + 5, Path.Direction.CW);
    }

    int rectPoolSize = 0;

    ArrayList<ArrayList<RectF>> rectPool = new ArrayList<>();

    ArrayList<RectF> magSelBucket = new ArrayList<>();

    public void resetSel() {
        //  CMN.Log("resetSel", pDocView.selPageSt, pDocView.selPageEd, pDocView.selStart, pDocView.selEnd);

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
        float mappedX = -pDocView.getCurrentXOffset() + pDocView.dragPinchManager.lastX;
        float mappedY = -pDocView.getCurrentYOffset() + pDocView.dragPinchManager.lastY;
        int pageIndex = pDocView.pdfFile.getPageAtOffset(pDocView.isSwipeVertical() ? mappedY : mappedX, pDocView.getZoom());

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
            }//"RectF(373.0, 405.0, 556.0, 434.0)"
            //CMN.Log("getCharPos", page.allText.substring(ed+delta, ed+delta+1));
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
        try {
            RectF VR = tmpPosRct;
            Matrix matrix = pDocView.matrix;

            if (pDocView.isSearching && pDocView.pdfFile != null) {
                // SearchRecord record =  pDocView.searchRecords.get(pDocView.getCurrentPage());
                ArrayList<SearchRecord> searchRecordList = getSearchRecords();

                for (SearchRecord record : searchRecordList) {
                    if (record != null) {
                        pDocView.getAllMatchOnPage(record);
                        int page = record.currentPage != -1 ? record.currentPage : pDocView.currentPage;
                        ArrayList<SearchRecordItem> data = (ArrayList<SearchRecordItem>) record.data;
                        for (int j = 0, len = data.size(); j < len; j++) {
                            RectF[] rects = data.get(j).rects;
                            if (rects != null) {
                                for (RectF rI : rects) {
                                    pDocView.sourceToViewRectFFSearch(rI, VR, page);
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
            }

            if (pDocView.hasSelection && pDocView.pdfFile != null) {
                pDocView.sourceToViewRectFF(pDocView.handleLeftPos, VR);
                float left = VR.left + drawableDeltaW;
                pDocView.handleLeft.setBounds((int) (left - drawableWidth), (int) VR.bottom, (int) left, (int) (VR.bottom + drawableHeight));
                pDocView.handleLeft.draw(canvas);
                //canvas.drawRect(pDocView.handleLeft.getBounds(), rectPaint);

                pDocView.sourceToViewRectFF(pDocView.handleRightPos, VR);
                left = VR.right - drawableDeltaW;
                pDocView.handleRight.setBounds((int) left, (int) VR.bottom, (int) (left + drawableWidth), (int) (VR.bottom + drawableHeight));
                pDocView.handleRight.draw(canvas);

                // canvas.drawRect(pDocView.handleRight.getBounds(), rectPaint);
                pDocView.sourceToViewCoord(pDocView.sCursorPos, vCursorPos);

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
    private ArrayList<SearchRecord> getSearchRecords() {
        ArrayList<SearchRecord> list = new ArrayList<>();
        int currentPage = pDocView.getCurrentPage();
        if (Util.indexExists(pDocView.getPageCount(), currentPage - 1)) {
            int index = currentPage - 1;

            if (pDocView.searchRecords.containsKey(index)) {
                SearchRecord searchRecordPrev = pDocView.searchRecords.get(index);
                if (searchRecordPrev != null)
                    searchRecordPrev.currentPage = index;
                list.add(searchRecordPrev);
            }
        }
        list.add(pDocView.searchRecords.get(currentPage));

        if (Util.indexExists(pDocView.getPageCount(), currentPage + 1)) {
            int indexNext = currentPage + 1;
            if (pDocView.searchRecords.containsKey(indexNext)) {
                SearchRecord searchRecordNext = pDocView.searchRecords.get(indexNext);
                if (searchRecordNext != null)
                    searchRecordNext.currentPage = indexNext;
                list.add(pDocView.searchRecords.get(indexNext));
            }
        }


        return list;
    }


}
