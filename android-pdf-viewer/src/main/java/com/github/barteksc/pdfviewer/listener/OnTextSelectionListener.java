package com.github.barteksc.pdfviewer.listener;

import android.graphics.RectF;

public interface OnTextSelectionListener {
    /**
     * Called when the user finish to zoom page via pinch
     *
     * @param text string with the zoom level
     */
    void onSelection(String text, int pageIndex, long selectionId, RectF frame);
}
