package com.github.barteksc.pdfviewer.model;

import android.graphics.RectF;

import androidx.annotation.NonNull;

import java.util.Arrays;

/**
 * Stores the highlight rects and start-end index
 * of one matching item on a page
 */
public class SearchRecordItem {

    public final int pageIndex;
    public final int st;
    public final int ed;
    public final RectF[] rectFS;

    public SearchRecordItem(int pageIndex, int st, int ed, RectF[] rectFS) {
        this.pageIndex = pageIndex;
        this.st = st;
        this.ed = ed;
        this.rectFS = rectFS;
    }

    @NonNull
    @Override
    public String toString() {
        return "SearchRecordItem{" +
                "pageIndex=" + pageIndex +
                ", st=" + st +
                ", ed=" + ed +
                ", rectFS=" + Arrays.toString(rectFS) +
                '}';
    }
}