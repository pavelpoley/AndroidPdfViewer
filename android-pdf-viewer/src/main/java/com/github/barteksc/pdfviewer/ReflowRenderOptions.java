package com.github.barteksc.pdfviewer;

final class ReflowRenderOptions {
    final int targetWidth;
    final int minOutputHeight;
    final int pageSpacingPx;
    final int targetTextHeightPx;
    final float sourceScale;
    final int maxSourceWidthPx;
    final int maxSourcePixels;

    ReflowRenderOptions(
            int targetWidth,
            int minOutputHeight,
            int pageSpacingPx,
            int targetTextHeightPx,
            float sourceScale,
            int maxSourceWidthPx,
            int maxSourcePixels
    ) {
        this.targetWidth = targetWidth;
        this.minOutputHeight = minOutputHeight;
        this.pageSpacingPx = pageSpacingPx;
        this.targetTextHeightPx = targetTextHeightPx;
        this.sourceScale = sourceScale;
        this.maxSourceWidthPx = maxSourceWidthPx;
        this.maxSourcePixels = maxSourcePixels;
    }
}
