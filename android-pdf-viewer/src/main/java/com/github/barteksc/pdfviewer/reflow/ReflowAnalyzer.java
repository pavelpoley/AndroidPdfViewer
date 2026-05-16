package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

final class ReflowAnalyzer {

    List<ReflowLineBlock> collectLines(ReflowPixelMap pixels, Rect content) {
        List<ReflowLineBlock> result = new ArrayList<>();
        List<Rect> columns = detectColumns(pixels, content);

        for (Rect column : columns) {
            List<Rect> lines = splitHorizontal(pixels, column);
            Rect previousLine = null;
            for (Rect line : lines) {
                List<Rect> lineWords = splitVertical(pixels, line);
                if (!lineWords.isEmpty()) {
                    ReflowLineBlock lineBlock = new ReflowLineBlock();
                    lineBlock.sourceHeight = line.height();
                    lineBlock.gapBeforeLine = previousLine == null ? 0 : Math.max(0, line.top - previousLine.bottom);
                    Rect previousWord = null;
                    for (Rect word : lineWords) {
                        ReflowWordBlock block = new ReflowWordBlock(word);
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

    Rect findContentBounds(ReflowPixelMap pixels, Rect area) {
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

    private List<Rect> detectColumns(ReflowPixelMap pixels, Rect content) {
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

    private List<Rect> splitHorizontal(ReflowPixelMap pixels, Rect area) {
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

    private void addTrimmedLine(ReflowPixelMap pixels, List<Rect> lines, Rect line, int minLineHeight) {
        Rect trimmed = findContentBounds(pixels, line);
        if (!trimmed.isEmpty() && trimmed.height() >= minLineHeight) {
            lines.add(trimmed);
        }
    }

    private List<Rect> splitVertical(ReflowPixelMap pixels, Rect line) {
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

    private void addTrimmedWord(ReflowPixelMap pixels, List<Rect> words, Rect word) {
        Rect trimmed = findContentBounds(pixels, word);
        if (trimmed.isEmpty()) {
            return;
        }
        int darkPixels = countDarkPixels(pixels, trimmed);
        if (darkPixels >= 2 && trimmed.width() >= 1 && trimmed.height() >= 2) {
            words.add(new Rect(trimmed.left, word.top, trimmed.right, word.bottom));
        }
    }

    private int countDarkPixels(ReflowPixelMap pixels, Rect rect) {
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

    private int countDarkInRow(ReflowPixelMap pixels, int y, int left, int right) {
        int count = 0;
        for (int x = left; x < right; x++) {
            if (pixels.isDark(x, y)) {
                count++;
            }
        }
        return count;
    }

    private int countDarkInColumn(ReflowPixelMap pixels, int x, int top, int bottom) {
        int count = 0;
        for (int y = top; y < bottom; y++) {
            if (pixels.isDark(x, y)) {
                count++;
            }
        }
        return count;
    }
}
