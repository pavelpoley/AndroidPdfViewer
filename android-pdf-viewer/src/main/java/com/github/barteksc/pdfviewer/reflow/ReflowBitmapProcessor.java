package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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

    private static final int DARK_LUMINANCE_THRESHOLD = 190;
    private static final int BACKGROUND_COLOR = Color.WHITE;
    private static final int MAX_OUTPUT_HEIGHT = 16000;
    private static final float MIN_LAYOUT_SCALE = 0.85f;
    private static final float MAX_LAYOUT_SCALE = 3f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public Bitmap reflow(@NonNull Bitmap source, int targetWidth, int minOutputHeight) {
        return reflow(source, targetWidth, minOutputHeight, 0);
    }

    public Bitmap reflow(@NonNull Bitmap source, int targetWidth, int minOutputHeight, int targetTextHeightPx) {
        if (source.getWidth() <= 0 || source.getHeight() <= 0 || targetWidth <= 0) {
            return source;
        }

        PixelMap pixels = new PixelMap(source);
        Rect content = findContentBounds(pixels, new Rect(0, 0, pixels.width, pixels.height));
        if (content.isEmpty()) {
            return scaleToWidth(source, targetWidth, Math.max(1, minOutputHeight));
        }

        List<LineBlock> lines = collectLines(pixels, content);
        if (lines.isEmpty()) {
            return scaleToWidth(source, targetWidth, Math.max(1, minOutputHeight));
        }

        return layoutLines(source, lines, targetWidth, minOutputHeight, targetTextHeightPx);
    }

    private List<LineBlock> collectLines(PixelMap pixels, Rect content) {
        List<LineBlock> result = new ArrayList<>();
        List<Rect> columns = detectColumns(pixels, content);

        for (Rect column : columns) {
            List<Rect> lines = splitHorizontal(pixels, column);
            Rect previousLine = null;
            for (Rect line : lines) {
                List<Rect> lineWords = splitVertical(pixels, line);
                if (!lineWords.isEmpty()) {
                    LineBlock lineBlock = new LineBlock();
                    lineBlock.sourceHeight = line.height();
                    lineBlock.gapBeforeLine = previousLine == null ? 0 : Math.max(0, line.top - previousLine.bottom);
                    Rect previousWord = null;
                    for (Rect word : lineWords) {
                        WordBlock block = new WordBlock(word);
                        block.gapBeforeWord = previousWord == null ? 0 : Math.max(0, word.left - previousWord.right);
                        lineBlock.words.add(block);
                        previousWord = word;
                    }
                    result.add(lineBlock);
                }
                previousLine = line;
            }
        }

        return result;
    }

    private Bitmap layoutLines(
            Bitmap source,
            List<LineBlock> lines,
            int targetWidth,
            int minOutputHeight,
            int targetTextHeightPx
    ) {
        int padding = Math.max(8, targetWidth / 34);
        int contentRight = Math.max(padding + 1, targetWidth - padding);
        int contentWidth = contentRight - padding;
        float layoutScale = calculateLayoutScale(lines, targetTextHeightPx);

        List<PlacedWord> placedWords = new ArrayList<>();
        List<PlacedWord> currentRow = new ArrayList<>();
        int x = padding;
        int y = padding;
        int rowHeight = 0;
        int previousSourceLineHeight = 0;
        boolean first = true;

        for (LineBlock line : lines) {
            if (!first) {
                flushRow(currentRow, placedWords);
                int completedRowHeight = Math.max(1, rowHeight);
                int lineGap = estimateLineGap(line.gapBeforeLine, completedRowHeight, layoutScale);
                y += rowHeight + lineGap;
                if (isParagraphGap(line.gapBeforeLine, Math.max(1, previousSourceLineHeight))) {
                    int paragraphGap = Math.round((line.gapBeforeLine * layoutScale) / 2f);
                    y += Math.min(paragraphGap, completedRowHeight * 2);
                }
                x = padding;
                rowHeight = 0;
            }

            for (int i = 0; i < line.words.size(); i++) {
                WordBlock word = line.words.get(i);
                int sourceWidth = Math.max(1, word.rect.width());
                int sourceHeight = Math.max(1, word.rect.height());
                int drawWidth = Math.max(1, Math.round(sourceWidth * layoutScale));
                int drawHeight = Math.max(1, Math.round(sourceHeight * layoutScale));
                if (drawWidth > contentWidth) {
                    float fitScale = contentWidth / (float) drawWidth;
                    drawWidth = Math.max(1, Math.round(drawWidth * fitScale));
                    drawHeight = Math.max(1, Math.round(drawHeight * fitScale));
                }
                int wordGap = x == padding ? 0 : estimateWordGap(word.gapBeforeWord, drawHeight, layoutScale);

                if (x + wordGap + drawWidth > contentRight && x > padding) {
                    flushRow(currentRow, placedWords);
                    y += rowHeight + Math.max(3, rowHeight / 5);
                    x = padding;
                    rowHeight = 0;
                    wordGap = 0;
                }

                int left = x + wordGap;
                Rect destination = new Rect(left, y, left + drawWidth, y + drawHeight);
                currentRow.add(new PlacedWord(word.rect, destination));
                x = destination.right;
                rowHeight = Math.max(rowHeight, drawHeight);
            }
            previousSourceLineHeight = Math.max(1, line.sourceHeight);
            first = false;
        }

        flushRow(currentRow, placedWords);
        int outputHeight = Math.max(minOutputHeight, y + rowHeight + padding);
        outputHeight = Math.max(1, Math.min(outputHeight, MAX_OUTPUT_HEIGHT));

        Bitmap output = Bitmap.createBitmap(targetWidth, outputHeight, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(BACKGROUND_COLOR);
        for (PlacedWord placedWord : placedWords) {
            if (placedWord.destination.top >= outputHeight) {
                continue;
            }
            Rect destination = new Rect(placedWord.destination);
            if (destination.bottom > outputHeight) {
                destination.bottom = outputHeight;
            }
            canvas.drawBitmap(source, placedWord.source, destination, paint);
        }
        return output;
    }

    private float calculateLayoutScale(List<LineBlock> lines, int targetTextHeightPx) {
        if (targetTextHeightPx <= 0) {
            return 1f;
        }

        List<Integer> heights = new ArrayList<>();
        for (LineBlock line : lines) {
            if (line.sourceHeight > 0) {
                heights.add(line.sourceHeight);
            }
        }
        if (heights.isEmpty()) {
            return 1f;
        }

        Collections.sort(heights);
        int medianHeight = heights.get(heights.size() / 2);
        if (medianHeight <= 0) {
            return 1f;
        }

        return clamp(targetTextHeightPx / (float) medianHeight, MIN_LAYOUT_SCALE, MAX_LAYOUT_SCALE);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void flushRow(List<PlacedWord> row, List<PlacedWord> output) {
        if (row.isEmpty()) {
            return;
        }
        output.addAll(row);
        row.clear();
    }

    private int estimateLineGap(int sourceGap, int rowHeight, float layoutScale) {
        int defaultGap = Math.max(3, rowHeight / 5);
        if (sourceGap <= 0) {
            return defaultGap;
        }
        int scaledSourceGap = Math.max(1, Math.round(sourceGap * layoutScale));
        return Math.max(defaultGap, Math.min(scaledSourceGap, Math.max(defaultGap, rowHeight)));
    }

    private int estimateWordGap(int sourceGap, int wordHeight, float layoutScale) {
        int minGap = Math.max(3, wordHeight / 4);
        if (sourceGap <= 0) {
            return minGap;
        }
        int scaledSourceGap = Math.max(1, Math.round(sourceGap * layoutScale));
        return Math.max(minGap, Math.min(scaledSourceGap, Math.max(minGap, wordHeight)));
    }

    private boolean isParagraphGap(int sourceGap, int wordHeight) {
        return sourceGap > wordHeight * 2;
    }

    private Bitmap scaleToWidth(Bitmap source, int targetWidth, int minOutputHeight) {
        int targetHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
        targetHeight = Math.max(targetHeight, minOutputHeight);
        Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(BACKGROUND_COLOR);
        canvas.drawBitmap(source, null, new Rect(0, 0, targetWidth, targetHeight), paint);
        return output;
    }

    private Rect findContentBounds(PixelMap pixels, Rect area) {
        int left = area.right;
        int top = area.bottom;
        int right = area.left;
        int bottom = area.top;

        for (int y = area.top; y < area.bottom; y++) {
            for (int x = area.left; x < area.right; x++) {
                if (pixels.isDark(x, y)) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x + 1);
                    bottom = Math.max(bottom, y + 1);
                }
            }
        }

        if (left >= right || top >= bottom) {
            return new Rect();
        }
        return new Rect(left, top, right, bottom);
    }

    private List<Rect> detectColumns(PixelMap pixels, Rect content) {
        List<Rect> columns = new ArrayList<>(2);
        int searchLeft = content.left + content.width() / 3;
        int searchRight = content.left + (content.width() * 2) / 3;
        int minGapWidth = Math.max(12, content.width() / 22);
        int noise = Math.max(1, content.height() / 250);

        int bestStart = -1;
        int bestEnd = -1;
        int runStart = -1;
        for (int x = searchLeft; x < searchRight; x++) {
            boolean blank = countDarkInColumn(pixels, x, content.top, content.bottom) <= noise;
            if (blank && runStart < 0) {
                runStart = x;
            } else if (!blank && runStart >= 0) {
                if (x - runStart > bestEnd - bestStart) {
                    bestStart = runStart;
                    bestEnd = x;
                }
                runStart = -1;
            }
        }
        if (runStart >= 0 && searchRight - runStart > bestEnd - bestStart) {
            bestStart = runStart;
            bestEnd = searchRight;
        }

        if (bestStart >= 0 && bestEnd - bestStart >= minGapWidth) {
            Rect left = findContentBounds(pixels, new Rect(content.left, content.top, bestStart, content.bottom));
            Rect right = findContentBounds(pixels, new Rect(bestEnd, content.top, content.right, content.bottom));
            if (!left.isEmpty() && !right.isEmpty()) {
                columns.add(left);
                columns.add(right);
                return columns;
            }
        }

        columns.add(content);
        return columns;
    }

    private List<Rect> splitHorizontal(PixelMap pixels, Rect area) {
        List<Rect> lines = new ArrayList<>();
        int noise = Math.max(1, area.width() / 300);
        int minLineHeight = Math.max(2, area.height() / 500);
        int minBlankRows = Math.max(3, Math.min(6, area.height() / 420));
        int lineStart = -1;
        int blankRun = 0;

        for (int y = area.top; y < area.bottom; y++) {
            boolean blank = countDarkInRow(pixels, y, area.left, area.right) <= noise;
            if (!blank) {
                if (lineStart < 0) {
                    lineStart = y;
                }
                blankRun = 0;
                continue;
            }

            if (lineStart >= 0) {
                blankRun++;
                if (blankRun >= minBlankRows) {
                    int lineEnd = y - blankRun + 1;
                    addTrimmedLine(pixels, lines, new Rect(area.left, lineStart, area.right, lineEnd), minLineHeight);
                    lineStart = -1;
                    blankRun = 0;
                }
            }
        }
        if (lineStart >= 0) {
            addTrimmedLine(pixels, lines, new Rect(area.left, lineStart, area.right, area.bottom), minLineHeight);
        }
        return lines;
    }

    private void addTrimmedLine(PixelMap pixels, List<Rect> lines, Rect line, int minLineHeight) {
        Rect trimmed = findContentBounds(pixels, line);
        if (!trimmed.isEmpty() && trimmed.height() >= minLineHeight) {
            lines.add(trimmed);
        }
    }

    private List<Rect> splitVertical(PixelMap pixels, Rect line) {
        List<Rect> words = new ArrayList<>();
        int minBlankColumns = Math.max(4, line.height() / 3);
        int noise = Math.max(0, line.height() / 20);
        int wordStart = -1;
        int blankRun = 0;

        for (int x = line.left; x < line.right; x++) {
            boolean blank = countDarkInColumn(pixels, x, line.top, line.bottom) <= noise;
            if (!blank) {
                if (wordStart < 0) {
                    wordStart = x;
                }
                blankRun = 0;
                continue;
            }

            if (wordStart >= 0) {
                blankRun++;
                if (blankRun >= minBlankColumns) {
                    int wordEnd = x - blankRun + 1;
                    addTrimmedWord(pixels, words, new Rect(wordStart, line.top, wordEnd, line.bottom));
                    wordStart = -1;
                    blankRun = 0;
                }
            }
        }

        if (wordStart >= 0) {
            addTrimmedWord(pixels, words, new Rect(wordStart, line.top, line.right, line.bottom));
        }
        return words;
    }

    private void addTrimmedWord(PixelMap pixels, List<Rect> words, Rect word) {
        Rect trimmed = findContentBounds(pixels, word);
        if (trimmed.isEmpty()) {
            return;
        }
        int darkPixels = countDarkPixels(pixels, trimmed);
        if (darkPixels >= 2 && trimmed.width() >= 1 && trimmed.height() >= 2) {
            words.add(new Rect(trimmed.left, word.top, trimmed.right, word.bottom));
        }
    }

    private int countDarkPixels(PixelMap pixels, Rect rect) {
        int count = 0;
        for (int y = rect.top; y < rect.bottom; y++) {
            for (int x = rect.left; x < rect.right; x++) {
                if (pixels.isDark(x, y)) {
                    count++;
                }
            }
        }
        return count;
    }

    private int countDarkInRow(PixelMap pixels, int y, int left, int right) {
        int count = 0;
        for (int x = left; x < right; x++) {
            if (pixels.isDark(x, y)) {
                count++;
            }
        }
        return count;
    }

    private int countDarkInColumn(PixelMap pixels, int x, int top, int bottom) {
        int count = 0;
        for (int y = top; y < bottom; y++) {
            if (pixels.isDark(x, y)) {
                count++;
            }
        }
        return count;
    }

    private static final class PixelMap {
        final int width;
        final int height;
        private final int[] pixels;

        PixelMap(Bitmap bitmap) {
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

    private static final class WordBlock {
        final Rect rect;
        int gapBeforeWord;

        WordBlock(Rect rect) {
            this.rect = rect;
        }
    }

    private static final class LineBlock {
        final List<WordBlock> words = new ArrayList<>();
        int sourceHeight;
        int gapBeforeLine;
    }

    private static final class PlacedWord {
        final Rect source;
        final Rect destination;

        PlacedWord(Rect source, Rect destination) {
            this.source = source;
            this.destination = destination;
        }
    }
}
