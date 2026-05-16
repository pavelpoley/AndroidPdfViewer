package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Bitmap;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Clean-room bitmap reflow implementation inspired by the observed behavior of
 * reader apps that re-layout rendered page pixels.
 */
public class ReflowBitmapProcessor {

    private final ReflowAnalyzer analyzer = new ReflowAnalyzer();
    private final ReflowLayoutEngine layoutEngine = new ReflowLayoutEngine();
    private final ReflowBitmapRenderer renderer = new ReflowBitmapRenderer();

    public Bitmap reflow(@NonNull Bitmap source, int targetWidth, int minOutputHeight) {
        return reflow(source, targetWidth, minOutputHeight, 0);
    }

    public Bitmap reflow(@NonNull Bitmap source, int targetWidth, int minOutputHeight, int targetTextHeightPx) {
        if (source.getWidth() <= 0 || source.getHeight() <= 0 || targetWidth <= 0) {
            return source;
        }

        ReflowPixelMap pixels = new ReflowPixelMap(source);
        Rect content = analyzer.findContentBounds(pixels, new Rect(0, 0, pixels.width, pixels.height));
        if (content.isEmpty()) {
            return renderer.scaleToWidth(source, targetWidth, Math.max(1, minOutputHeight));
        }

        List<ReflowLineBlock> lines = analyzer.collectLines(pixels, content);
        if (lines.isEmpty()) {
            return renderer.scaleToWidth(source, targetWidth, Math.max(1, minOutputHeight));
        }

        ReflowLayout layout = layoutEngine.layout(lines, targetWidth, minOutputHeight, targetTextHeightPx);
        return renderer.render(source, layout, targetWidth);
    }
}
