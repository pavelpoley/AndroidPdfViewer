package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

final class ReflowBitmapRenderer {
    private static final int BACKGROUND_COLOR = Color.WHITE;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    Bitmap render(Bitmap source, ReflowLayout layout, int targetWidth) {
        Bitmap output = Bitmap.createBitmap(targetWidth, layout.outputHeight, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(BACKGROUND_COLOR);
        for (ReflowPlacedWord placedWord : layout.placedWords) {
            if (placedWord.destination.top >= layout.outputHeight) {
                continue;
            }
            Rect destination = new Rect(placedWord.destination);
            if (destination.bottom > layout.outputHeight) {
                destination.bottom = layout.outputHeight;
            }
            canvas.drawBitmap(source, placedWord.source, destination, paint);
        }
        return output;
    }

    Bitmap scaleToWidth(Bitmap source, int targetWidth, int minOutputHeight) {
        int targetHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
        targetHeight = Math.max(targetHeight, minOutputHeight);
        Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(BACKGROUND_COLOR);
        canvas.drawBitmap(source, null, new Rect(0, 0, targetWidth, targetHeight), paint);
        return output;
    }
}
