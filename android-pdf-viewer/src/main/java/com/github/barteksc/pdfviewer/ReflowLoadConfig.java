package com.github.barteksc.pdfviewer;

import androidx.annotation.Nullable;

import com.github.barteksc.pdfviewer.source.DocumentSource;

final class ReflowLoadConfig {
    final DocumentSource documentSource;
    @Nullable
    final String password;
    @Nullable
    final PDFReflowView.OnLoadCompleteListener onLoadCompleteListener;
    @Nullable
    final PDFReflowView.OnErrorListener onErrorListener;
    final boolean annotationRendering;
    final int pageSpacingDp;
    final float textSizeDp;
    final float sourceScale;
    final int maxSourceWidthPx;
    final int maxSourcePixels;

    ReflowLoadConfig(
            DocumentSource documentSource,
            @Nullable String password,
            @Nullable PDFReflowView.OnLoadCompleteListener onLoadCompleteListener,
            @Nullable PDFReflowView.OnErrorListener onErrorListener,
            boolean annotationRendering,
            int pageSpacingDp,
            float textSizeDp,
            float sourceScale,
            int maxSourceWidthPx,
            int maxSourcePixels
    ) {
        this.documentSource = documentSource;
        this.password = password;
        this.onLoadCompleteListener = onLoadCompleteListener;
        this.onErrorListener = onErrorListener;
        this.annotationRendering = annotationRendering;
        this.pageSpacingDp = pageSpacingDp;
        this.textSizeDp = textSizeDp;
        this.sourceScale = sourceScale;
        this.maxSourceWidthPx = maxSourceWidthPx;
        this.maxSourcePixels = maxSourcePixels;
    }
}
