package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Bitmap;
import android.graphics.Color;

final class ReflowPixelMap {
    private static final int DARK_LUMINANCE_THRESHOLD = 190;

    final int width;
    final int height;
    private final int[] pixels;

    ReflowPixelMap(Bitmap bitmap) {
        width = bitmap.getWidth();
        height = bitmap.getHeight();
        pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
    }

    boolean isDark(int x, int y) {
        int color = pixels[y * width + x];
        if (Color.alpha(color) < 32) {
            return false;
        }
        int luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
        return luminance < DARK_LUMINANCE_THRESHOLD;
    }
}
