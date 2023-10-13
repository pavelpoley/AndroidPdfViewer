package com.github.barteksc.pdfviewer.model;

import android.graphics.RectF;

public class Decoration {
    public int page;
    public RectF rect;

    public Decoration(int page, RectF rect) {
        this.rect = rect;
        this.page = page;
    }
}
