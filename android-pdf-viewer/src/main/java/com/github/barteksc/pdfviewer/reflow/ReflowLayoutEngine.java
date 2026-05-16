package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ReflowLayoutEngine {
    private static final float MIN_LAYOUT_SCALE = 0.85f;
    private static final float MAX_LAYOUT_SCALE = 3f;

    ReflowLayout layout(
            List<ReflowLineBlock> lines,
            int targetWidth,
            int minOutputHeight,
            int targetTextHeightPx
    ) {
        int padding = Math.max(8, targetWidth / 34);
        int contentRight = Math.max(padding + 1, targetWidth - padding);
        int contentWidth = contentRight - padding;
        float layoutScale = calculateLayoutScale(lines, targetTextHeightPx);

        List<ReflowPlacedWord> placedWords = new ArrayList<>();
        List<ReflowPlacedWord> currentRow = new ArrayList<>();
        int x = padding;
        int y = padding;
        int rowHeight = 0;
        int previousSourceLineHeight = 0;
        boolean first = true;

        for (ReflowLineBlock line : lines) {
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
                ReflowWordBlock word = line.words.get(i);
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
                currentRow.add(new ReflowPlacedWord(word.rect, destination));
                x = destination.right;
                rowHeight = Math.max(rowHeight, drawHeight);
            }
            previousSourceLineHeight = Math.max(1, line.sourceHeight);
            first = false;
        }

        flushRow(currentRow, placedWords);
        int outputHeight = Math.max(minOutputHeight, y + rowHeight + padding);
        outputHeight = Math.max(1, outputHeight);
        return new ReflowLayout(placedWords, outputHeight);
    }

    private float calculateLayoutScale(List<ReflowLineBlock> lines, int targetTextHeightPx) {
        if (targetTextHeightPx <= 0) {
            return 1f;
        }

        List<Integer> heights = new ArrayList<>();
        for (ReflowLineBlock line : lines) {
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

    private void flushRow(List<ReflowPlacedWord> row, List<ReflowPlacedWord> output) {
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
}
