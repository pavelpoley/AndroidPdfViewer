package com.github.barteksc.pdfviewer.listener;

public interface OnSelectionListener {
    /**
     * Called when the user finish to zoom page via pinch
     *
     * @param  text string with the zoom level
     */
    void onSelection(String text);
}