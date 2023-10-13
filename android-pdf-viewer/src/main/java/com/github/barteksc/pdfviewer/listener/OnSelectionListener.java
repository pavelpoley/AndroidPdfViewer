package com.github.barteksc.pdfviewer.listener;

import android.graphics.RectF;

public interface OnSelectionListener {
    /**
     * Called when the user finish to zoom page via pinch
     *
     * @param  text string with the zoom level
     */
    void onSelection(String text, RectF frame);
}