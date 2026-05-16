package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

final class ReflowBitmapRenderer {
    private static final int BACKGROUND_COLOR = Color.WHITE;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    ReflowBitmapProcessor.Result renderTiles(Bitmap source, ReflowLayout layout, int targetWidth, int tileHeight) {
        List<Bitmap> tiles = new ArrayList<>();
        int totalHeight = Math.max(1, layout.outputHeight);
        int safeTileHeight = Math.max(1, tileHeight);
        for (int tileTop = 0; tileTop < totalHeight; tileTop += safeTileHeight) {
            int currentTileHeight = Math.min(safeTileHeight, totalHeight - tileTop);
            Bitmap tile = Bitmap.createBitmap(targetWidth, currentTileHeight, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(tile);
            canvas.drawColor(BACKGROUND_COLOR);
            drawTileWords(canvas, source, layout, targetWidth, tileTop, currentTileHeight);
            tiles.add(tile);
        }
        return new ReflowBitmapProcessor.Result(tiles, totalHeight);
    }

    ReflowBitmapProcessor.Result scaleToWidthTiles(Bitmap source, int targetWidth, int minOutputHeight, int tileHeight) {
        int totalHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
        totalHeight = Math.max(totalHeight, minOutputHeight);
        int safeTileHeight = Math.max(1, tileHeight);
        List<Bitmap> tiles = new ArrayList<>();
        for (int tileTop = 0; tileTop < totalHeight; tileTop += safeTileHeight) {
            int currentTileHeight = Math.min(safeTileHeight, totalHeight - tileTop);
            Bitmap tile = Bitmap.createBitmap(targetWidth, currentTileHeight, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(tile);
            canvas.drawColor(BACKGROUND_COLOR);
            Rect sourceRect = scaledSourceRect(source, totalHeight, tileTop, currentTileHeight);
            canvas.drawBitmap(source, sourceRect, new Rect(0, 0, targetWidth, currentTileHeight), paint);
            tiles.add(tile);
        }
        return new ReflowBitmapProcessor.Result(tiles, totalHeight);
    }

    private void drawTileWords(
            Canvas canvas,
            Bitmap source,
            ReflowLayout layout,
            int targetWidth,
            int tileTop,
            int tileHeight
    ) {
        int tileBottom = tileTop + tileHeight;
        for (ReflowPlacedWord placedWord : layout.placedWords) {
            Rect destination = placedWord.destination;
            if (destination.bottom <= tileTop || destination.top >= tileBottom) {
                continue;
            }
            Rect clippedDestination = new Rect(
                    Math.max(0, destination.left),
                    Math.max(tileTop, destination.top),
                    Math.min(targetWidth, destination.right),
                    Math.min(tileBottom, destination.bottom)
            );
            if (clippedDestination.isEmpty()) {
                continue;
            }
            Rect clippedSource = clippedSourceRect(placedWord.source, destination, clippedDestination);
            clippedDestination.offset(0, -tileTop);
            canvas.drawBitmap(source, clippedSource, clippedDestination, paint);
        }
    }

    private Rect scaledSourceRect(Bitmap source, int totalHeight, int tileTop, int tileHeight) {
        int sourceTop = Math.round(tileTop * (source.getHeight() / (float) totalHeight));
        int sourceBottom = Math.round((tileTop + tileHeight) * (source.getHeight() / (float) totalHeight));
        sourceTop = Math.max(0, Math.min(source.getHeight() - 1, sourceTop));
        sourceBottom = Math.max(sourceTop + 1, Math.min(source.getHeight(), sourceBottom));
        return new Rect(0, sourceTop, source.getWidth(), sourceBottom);
    }

    private Rect clippedSourceRect(Rect source, Rect destination, Rect clippedDestination) {
        float sourceWidth = source.width();
        float sourceHeight = source.height();
        float destinationWidth = Math.max(1, destination.width());
        float destinationHeight = Math.max(1, destination.height());

        int left = source.left + Math.round((clippedDestination.left - destination.left) * sourceWidth / destinationWidth);
        int top = source.top + Math.round((clippedDestination.top - destination.top) * sourceHeight / destinationHeight);
        int right = source.left + Math.round((clippedDestination.right - destination.left) * sourceWidth / destinationWidth);
        int bottom = source.top + Math.round((clippedDestination.bottom - destination.top) * sourceHeight / destinationHeight);

        left = Math.max(source.left, Math.min(source.right - 1, left));
        top = Math.max(source.top, Math.min(source.bottom - 1, top));
        right = Math.max(left + 1, Math.min(source.right, right));
        bottom = Math.max(top + 1, Math.min(source.bottom, bottom));
        return new Rect(left, top, right, bottom);
    }
}
