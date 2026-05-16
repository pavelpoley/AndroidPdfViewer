package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.CancellationSignal;

import androidx.annotation.Nullable;

final class ReflowPixelMap {
    private static final int DARK_LUMINANCE_THRESHOLD = 190;

    final int width;
    final int height;
    private final byte[] darkPixels;
    private final int[] integral;

    ReflowPixelMap(Bitmap bitmap) {
        this(bitmap, null);
    }

    ReflowPixelMap(Bitmap bitmap, @Nullable CancellationSignal cancellationSignal) {
        width = bitmap.getWidth();
        height = bitmap.getHeight();
        darkPixels = new byte[width * height];
        integral = new int[(width + 1) * (height + 1)];

        try {
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            buildFromPixels(pixels, cancellationSignal);
        } catch (OutOfMemoryError ignored) {
            buildFromRows(bitmap, cancellationSignal);
        }
    }

    private void buildFromPixels(int[] pixels, @Nullable CancellationSignal cancellationSignal) {
        int stride = width + 1;
        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0) {
                throwIfCanceled(cancellationSignal);
            }
            int rowDarkCount = 0;
            int darkRowBase = y * width;
            int integralRowBase = (y + 1) * stride;
            int previousIntegralRowBase = y * stride;
            for (int x = 0; x < width; x++) {
                int dark = isDarkColor(pixels[darkRowBase + x]) ? 1 : 0;
                darkPixels[darkRowBase + x] = (byte) dark;
                rowDarkCount += dark;
                integral[integralRowBase + x + 1] = integral[previousIntegralRowBase + x + 1] + rowDarkCount;
            }
        }
    }

    private void buildFromRows(Bitmap bitmap, @Nullable CancellationSignal cancellationSignal) {
        int[] row = new int[width];
        int stride = width + 1;
        for (int y = 0; y < height; y++) {
            if ((y & 31) == 0) {
                throwIfCanceled(cancellationSignal);
            }
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            int rowDarkCount = 0;
            int darkRowBase = y * width;
            int integralRowBase = (y + 1) * stride;
            int previousIntegralRowBase = y * stride;
            for (int x = 0; x < width; x++) {
                int dark = isDarkColor(row[x]) ? 1 : 0;
                darkPixels[darkRowBase + x] = (byte) dark;
                rowDarkCount += dark;
                integral[integralRowBase + x + 1] = integral[previousIntegralRowBase + x + 1] + rowDarkCount;
            }
        }
    }

    boolean isDark(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height && darkPixels[y * width + x] != 0;
    }

    int countDark(Rect rect) {
        return countDark(rect.left, rect.top, rect.right, rect.bottom);
    }

    int countDarkInRow(int y, int left, int right) {
        return countDark(left, y, right, y + 1);
    }

    int countDarkInColumn(int x, int top, int bottom) {
        return countDark(x, top, x + 1, bottom);
    }

    void throwIfCanceled(@Nullable CancellationSignal cancellationSignal) {
        throwIfCanceledSignal(cancellationSignal);
    }

    static void throwIfCanceledSignal(@Nullable CancellationSignal cancellationSignal) {
        if (cancellationSignal != null) {
            cancellationSignal.throwIfCanceled();
        }
    }

    private int countDark(int left, int top, int right, int bottom) {
        int boundedLeft = Math.max(0, Math.min(width, left));
        int boundedTop = Math.max(0, Math.min(height, top));
        int boundedRight = Math.max(boundedLeft, Math.min(width, right));
        int boundedBottom = Math.max(boundedTop, Math.min(height, bottom));
        if (boundedLeft >= boundedRight || boundedTop >= boundedBottom) {
            return 0;
        }

        int stride = width + 1;
        int topLeft = integral[boundedTop * stride + boundedLeft];
        int topRight = integral[boundedTop * stride + boundedRight];
        int bottomLeft = integral[boundedBottom * stride + boundedLeft];
        int bottomRight = integral[boundedBottom * stride + boundedRight];
        return bottomRight - topRight - bottomLeft + topLeft;
    }

    private boolean isDarkColor(int color) {
        if (Color.alpha(color) < 32) {
            return false;
        }
        int luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
        return luminance < DARK_LUMINANCE_THRESHOLD;
    }
}
