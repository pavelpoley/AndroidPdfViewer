package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clean-room bitmap reflow implementation inspired by the observed behavior of
 * reader apps that re-layout rendered page pixels.
 */
public class ReflowBitmapProcessor {
    private static final String TAG = ReflowBitmapProcessor.class.getSimpleName();
    private static final boolean TRACE_REFLOW_TIMING = false;
    private static final int TINY_TILE_HEIGHT = 1024;
    private static final int SMALL_TILE_HEIGHT = 2048;
    private static final int LARGE_TILE_HEIGHT = 4096;
    private static final int RGB_565_BYTES_PER_PIXEL = 2;
    private static final int ARGB_8888_BYTES_PER_PIXEL = 4;
    private static final long MIN_REFLOW_CACHE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_REFLOW_CACHE_BYTES = 48L * 1024L * 1024L;

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
        Bitmap.Config outputConfig = result.tiles.get(0).getConfig();
        if (outputConfig == null) {
            outputConfig = Bitmap.Config.RGB_565;
        }
        Bitmap output = Bitmap.createBitmap(targetWidth, Math.max(1, result.totalHeight), outputConfig);
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
        return reflowToResult(source, targetWidth, minOutputHeight, targetTextHeightPx, null);
    }

    public Result reflowToResult(
            @NonNull Bitmap source,
            int targetWidth,
            int minOutputHeight,
            int targetTextHeightPx,
            @Nullable CancellationSignal cancellationSignal
    ) {
        if (source.getWidth() <= 0 || source.getHeight() <= 0 || targetWidth <= 0) {
            return Result.empty();
        }

        long totalStart = traceStart();
        TileOptions tileOptions = chooseTileOptions(targetWidth);
        ReflowPixelMap.throwIfCanceledSignal(cancellationSignal);
        long stageStart = traceStart();
        ReflowPixelMap pixels = new ReflowPixelMap(source, cancellationSignal);
        traceTiming("pixel map", stageStart);
        ReflowPixelMap.throwIfCanceledSignal(cancellationSignal);

        stageStart = traceStart();
        Rect content = analyzer.findContentBounds(pixels, new Rect(0, 0, pixels.width, pixels.height), cancellationSignal);
        traceTiming("content bounds", stageStart);
        if (content.isEmpty()) {
            stageStart = traceStart();
            Result result = renderer.scaleToWidthTiles(
                    source,
                    targetWidth,
                    Math.max(1, minOutputHeight),
                    tileOptions.height,
                    tileOptions.config,
                    cancellationSignal
            );
            result = result.withLayoutScale(1f);
            traceTiming("fallback render tiles", stageStart);
            traceTiming("total reflow", totalStart);
            return result;
        }

        ReflowPixelMap.throwIfCanceledSignal(cancellationSignal);
        stageStart = traceStart();
        List<ReflowLineBlock> lines = analyzer.collectLines(pixels, content, cancellationSignal);
        traceTiming("analysis", stageStart);
        if (lines.isEmpty()) {
            stageStart = traceStart();
            Result result = renderer.scaleToWidthTiles(
                    source,
                    targetWidth,
                    Math.max(1, minOutputHeight),
                    tileOptions.height,
                    tileOptions.config,
                    cancellationSignal
            );
            result = result.withLayoutScale(1f);
            traceTiming("fallback render tiles", stageStart);
            traceTiming("total reflow", totalStart);
            return result;
        }

        ReflowPixelMap.throwIfCanceledSignal(cancellationSignal);
        stageStart = traceStart();
        ReflowLayout layout = layoutEngine.layout(lines, targetWidth, minOutputHeight, targetTextHeightPx, cancellationSignal);
        traceTiming("layout", stageStart);
        ReflowPixelMap.throwIfCanceledSignal(cancellationSignal);
        stageStart = traceStart();
        Result result = renderer.renderTiles(
                source,
                layout,
                targetWidth,
                tileOptions.height,
                tileOptions.config,
                cancellationSignal
        ).withLayoutScale(layout.layoutScale);
        traceTiming("render tiles", stageStart);
        traceTiming("total reflow", totalStart);
        return result;
    }

    private static TileOptions chooseTileOptions(int targetWidth) {
        long perTileBudget = Math.max(1L, calculateDefaultCacheBudgetBytes() / 3L);
        if (estimateTileBytes(targetWidth, LARGE_TILE_HEIGHT, Bitmap.Config.ARGB_8888) <= perTileBudget) {
            return new TileOptions(LARGE_TILE_HEIGHT, Bitmap.Config.ARGB_8888);
        }
        if (estimateTileBytes(targetWidth, SMALL_TILE_HEIGHT, Bitmap.Config.ARGB_8888) <= perTileBudget) {
            return new TileOptions(SMALL_TILE_HEIGHT, Bitmap.Config.ARGB_8888);
        }
        if (estimateTileBytes(targetWidth, TINY_TILE_HEIGHT, Bitmap.Config.ARGB_8888) <= perTileBudget) {
            return new TileOptions(TINY_TILE_HEIGHT, Bitmap.Config.ARGB_8888);
        }
        if (estimateTileBytes(targetWidth, LARGE_TILE_HEIGHT, Bitmap.Config.RGB_565) <= perTileBudget) {
            return new TileOptions(LARGE_TILE_HEIGHT, Bitmap.Config.RGB_565);
        }
        if (estimateTileBytes(targetWidth, SMALL_TILE_HEIGHT, Bitmap.Config.RGB_565) <= perTileBudget) {
            return new TileOptions(SMALL_TILE_HEIGHT, Bitmap.Config.RGB_565);
        }
        return new TileOptions(TINY_TILE_HEIGHT, Bitmap.Config.RGB_565);
    }

    private static long calculateDefaultCacheBudgetBytes() {
        long runtimeBudget = Runtime.getRuntime().maxMemory() / 8L;
        return Math.max(MIN_REFLOW_CACHE_BYTES, Math.min(MAX_REFLOW_CACHE_BYTES, runtimeBudget));
    }

    private static long estimateTileBytes(int targetWidth, int tileHeight, Bitmap.Config config) {
        int bytesPerPixel = config == Bitmap.Config.ARGB_8888 ? ARGB_8888_BYTES_PER_PIXEL : RGB_565_BYTES_PER_PIXEL;
        return (long) Math.max(1, targetWidth) * Math.max(1, tileHeight) * bytesPerPixel;
    }

    private static long traceStart() {
        return TRACE_REFLOW_TIMING ? System.nanoTime() : 0L;
    }

    private static void traceTiming(String stage, long startNanos) {
        if (TRACE_REFLOW_TIMING) {
            long elapsedMicros = (System.nanoTime() - startNanos) / 1000L;
            Log.d(TAG, stage + ": " + (elapsedMicros / 1000f) + " ms");
        }
    }

    private static final class TileOptions {
        final int height;
        final Bitmap.Config config;

        TileOptions(int height, Bitmap.Config config) {
            this.height = height;
            this.config = config;
        }
    }

    public static final class Result {
        public final List<Bitmap> tiles;
        public final int totalHeight;
        public final long byteCount;
        public final float layoutScale;

        Result(List<Bitmap> tiles, int totalHeight) {
            this(tiles, totalHeight, 1f);
        }

        Result(List<Bitmap> tiles, int totalHeight, float layoutScale) {
            this.tiles = Collections.unmodifiableList(new ArrayList<>(tiles));
            this.totalHeight = Math.max(1, totalHeight);
            this.byteCount = calculateByteCount(tiles);
            this.layoutScale = layoutScale;
        }

        static Result empty() {
            return new Result(Collections.emptyList(), 1);
        }

        Result withLayoutScale(float layoutScale) {
            return new Result(tiles, totalHeight, layoutScale);
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
