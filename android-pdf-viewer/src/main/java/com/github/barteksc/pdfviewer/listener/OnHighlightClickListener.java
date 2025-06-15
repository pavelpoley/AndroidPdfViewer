package com.github.barteksc.pdfviewer.listener;

import android.graphics.RectF;

public interface OnHighlightClickListener {

    void onClick(String string, int pageIndex, long selectionId, RectF rectF);
}
