package com.github.barteksc.pdfviewer.listener;

public interface OnScaleListener {
    /**
     * Called when the user finish to zoom page via pinch
     *
     * @param  zoomLevel float with the zoom level
     */
    void onScale(float zoomLevel);
}