package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clean-room bitmap reflow implementation inspired by the observed behavior of
 * reader apps that re-layout rendered page pixels.
 */
public class ReflowBitmapProcessor {
    private static final int DEFAULT_TILE_HEIGHT = 2048;

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

        Result result = reflowToResult(source, targetWidth, minOutputHeight, targetTextHeightPx);
        if (result.tiles.isEmpty()) {
            return source;
        }
        if (result.tiles.size() == 1) {
            return result.tiles.get(0);
        }
        Bitmap output = Bitmap.createBitmap(targetWidth, Math.max(1, result.totalHeight), Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(output);
        int y = 0;
        for (Bitmap tile : result.tiles) {
            canvas.drawBitmap(tile, 0, y, null);
            y += tile.getHeight();
        }
        result.recycle();
        return output;
    }

    public Result reflowToResult(@NonNull Bitmap source, int targetWidth, int minOutputHeight) {
        return reflowToResult(source, targetWidth, minOutputHeight, 0);
    }

    public Result reflowToResult(@NonNull Bitmap source, int targetWidth, int minOutputHeight, int targetTextHeightPx) {
        if (source.getWidth() <= 0 || source.getHeight() <= 0 || targetWidth <= 0) {
            return Result.empty();
        }

        ReflowPixelMap pixels = new ReflowPixelMap(source);
        Rect content = analyzer.findContentBounds(pixels, new Rect(0, 0, pixels.width, pixels.height));
        if (content.isEmpty()) {
            return renderer.scaleToWidthTiles(source, targetWidth, Math.max(1, minOutputHeight), DEFAULT_TILE_HEIGHT);
        }

        List<ReflowLineBlock> lines = analyzer.collectLines(pixels, content);
        if (lines.isEmpty()) {
            return renderer.scaleToWidthTiles(source, targetWidth, Math.max(1, minOutputHeight), DEFAULT_TILE_HEIGHT);
        }

        ReflowLayout layout = layoutEngine.layout(lines, targetWidth, minOutputHeight, targetTextHeightPx);
        return renderer.renderTiles(source, layout, targetWidth, DEFAULT_TILE_HEIGHT);
    }

    public static final class Result {
        public final List<Bitmap> tiles;
        public final int totalHeight;
        public final long byteCount;

        Result(List<Bitmap> tiles, int totalHeight) {
            this.tiles = Collections.unmodifiableList(new ArrayList<>(tiles));
            this.totalHeight = Math.max(1, totalHeight);
            this.byteCount = calculateByteCount(tiles);
        }

        static Result empty() {
            return new Result(Collections.emptyList(), 1);
        }

        public void recycle() {
            for (Bitmap tile : tiles) {
                if (tile != null && !tile.isRecycled()) {
                    tile.recycle();
                }
            }
        }

        private static long calculateByteCount(List<Bitmap> tiles) {
            long byteCount = 0L;
            for (Bitmap tile : tiles) {
                if (tile != null && !tile.isRecycled()) {
                    byteCount += tile.getAllocationByteCount();
                }
            }
            return byteCount;
        }
    }
}
