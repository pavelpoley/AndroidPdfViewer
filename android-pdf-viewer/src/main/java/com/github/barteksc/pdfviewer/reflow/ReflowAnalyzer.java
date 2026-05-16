package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Rect;
import android.os.CancellationSignal;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

final class ReflowAnalyzer {

    List<ReflowLineBlock> collectLines(ReflowPixelMap pixels, Rect content) {
        return collectLines(pixels, content, null);
    }

    List<ReflowLineBlock> collectLines(ReflowPixelMap pixels, Rect content, @Nullable CancellationSignal cancellationSignal) {
        List<ReflowLineBlock> result = new ArrayList<>();
        List<Rect> columns = detectColumns(pixels, content, cancellationSignal);

        for (Rect column : columns) {
            pixels.throwIfCanceled(cancellationSignal);
            List<Rect> lines = splitHorizontal(pixels, column, cancellationSignal);
            Rect previousLine = null;
            for (int i = 0; i < lines.size(); i++) {
                if ((i & 31) == 0) {
                    pixels.throwIfCanceled(cancellationSignal);
                }
                Rect line = lines.get(i);
                List<Rect> lineWords = splitVertical(pixels, line, cancellationSignal);
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
        return findContentBounds(pixels, area, null);
    }

    Rect findContentBounds(ReflowPixelMap pixels, Rect area, @Nullable CancellationSignal cancellationSignal) {
        Rect bounded = boundArea(pixels, area);
        if (bounded.isEmpty()) {
            return new Rect();
        }

        int top = bounded.top;
        while (top < bounded.bottom && pixels.countDarkInRow(top, bounded.left, bounded.right) == 0) {
            if ((top & 31) == 0) {
                pixels.throwIfCanceled(cancellationSignal);
            }
            top++;
        }
        if (top >= bounded.bottom) {
            return new Rect();
        }

        int bottom = bounded.bottom - 1;
        while (bottom >= top && pixels.countDarkInRow(bottom, bounded.left, bounded.right) == 0) {
            if ((bottom & 31) == 0) {
                pixels.throwIfCanceled(cancellationSignal);
            }
            bottom--;
        }

        int left = bounded.left;
        while (left < bounded.right && pixels.countDarkInColumn(left, top, bottom + 1) == 0) {
            if ((left & 31) == 0) {
                pixels.throwIfCanceled(cancellationSignal);
            }
            left++;
        }

        int right = bounded.right - 1;
        while (right >= left && pixels.countDarkInColumn(right, top, bottom + 1) == 0) {
            if ((right & 31) == 0) {
                pixels.throwIfCanceled(cancellationSignal);
            }
            right--;
        }

        if (left > right || top > bottom) {
            return new Rect();
        }
        return new Rect(left, top, right + 1, bottom + 1);
    }

    private List<Rect> detectColumns(ReflowPixelMap pixels, Rect content, @Nullable CancellationSignal cancellationSignal) {
        List<Rect> columns = new ArrayList<>(2);
        int searchLeft = content.left + content.width() / 3;
        int searchRight = content.left + (content.width() * 2) / 3;
        int minGapWidth = Math.max(12, content.width() / 22);
        int noise = Math.max(1, content.height() / 250);

        int bestStart = -1;
        int bestEnd = -1;
        int runStart = -1;
        for (int x = searchLeft; x < searchRight; x++) {
            if ((x & 31) == 0) {
                pixels.throwIfCanceled(cancellationSignal);
            }
            boolean blank = pixels.countDarkInColumn(x, content.top, content.bottom) <= noise;
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
            Rect left = findContentBounds(pixels, new Rect(content.left, content.top, bestStart, content.bottom), cancellationSignal);
            Rect right = findContentBounds(pixels, new Rect(bestEnd, content.top, content.right, content.bottom), cancellationSignal);
            if (!left.isEmpty() && !right.isEmpty()) {
                columns.add(left);
                columns.add(right);
                return columns;
            }
        }

        columns.add(content);
        return columns;
    }

    private List<Rect> splitHorizontal(ReflowPixelMap pixels, Rect area, @Nullable CancellationSignal cancellationSignal) {
        List<Rect> lines = new ArrayList<>();
        int noise = Math.max(1, area.width() / 300);
        int minLineHeight = Math.max(2, area.height() / 500);
        int minBlankRows = Math.max(3, Math.min(6, area.height() / 420));
        int lineStart = -1;
        int blankRun = 0;

        for (int y = area.top; y < area.bottom; y++) {
            if ((y & 31) == 0) {
                pixels.throwIfCanceled(cancellationSignal);
            }
            boolean blank = pixels.countDarkInRow(y, area.left, area.right) <= noise;
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
                    addTrimmedLine(pixels, lines, new Rect(area.left, lineStart, area.right, lineEnd), minLineHeight, cancellationSignal);
                    lineStart = -1;
                    blankRun = 0;
                }
            }
        }
        if (lineStart >= 0) {
            addTrimmedLine(pixels, lines, new Rect(area.left, lineStart, area.right, area.bottom), minLineHeight, cancellationSignal);
        }
        return lines;
    }

    private void addTrimmedLine(
            ReflowPixelMap pixels,
            List<Rect> lines,
            Rect line,
            int minLineHeight,
            @Nullable CancellationSignal cancellationSignal
    ) {
        Rect trimmed = findContentBounds(pixels, line, cancellationSignal);
        if (!trimmed.isEmpty() && trimmed.height() >= minLineHeight) {
            lines.add(trimmed);
        }
    }

    private List<Rect> splitVertical(ReflowPixelMap pixels, Rect line, @Nullable CancellationSignal cancellationSignal) {
        List<Rect> words = new ArrayList<>();
        int minBlankColumns = Math.max(4, line.height() / 3);
        int noise = Math.max(0, line.height() / 20);
        int wordStart = -1;
        int blankRun = 0;

        for (int x = line.left; x < line.right; x++) {
            if ((x & 31) == 0) {
                pixels.throwIfCanceled(cancellationSignal);
            }
            boolean blank = pixels.countDarkInColumn(x, line.top, line.bottom) <= noise;
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
                    addTrimmedWord(pixels, words, new Rect(wordStart, line.top, wordEnd, line.bottom), cancellationSignal);
                    wordStart = -1;
                    blankRun = 0;
                }
            }
        }

        if (wordStart >= 0) {
            addTrimmedWord(pixels, words, new Rect(wordStart, line.top, line.right, line.bottom), cancellationSignal);
        }
        return words;
    }

    private void addTrimmedWord(
            ReflowPixelMap pixels,
            List<Rect> words,
            Rect word,
            @Nullable CancellationSignal cancellationSignal
    ) {
        Rect trimmed = findContentBounds(pixels, word, cancellationSignal);
        if (trimmed.isEmpty()) {
            return;
        }
        int darkPixels = pixels.countDark(trimmed);
        if (darkPixels >= 2 && trimmed.width() >= 1 && trimmed.height() >= 2) {
            words.add(new Rect(trimmed.left, word.top, trimmed.right, word.bottom));
        }
    }

    private Rect boundArea(ReflowPixelMap pixels, Rect area) {
        int left = Math.max(0, Math.min(pixels.width, area.left));
        int top = Math.max(0, Math.min(pixels.height, area.top));
        int right = Math.max(left, Math.min(pixels.width, area.right));
        int bottom = Math.max(top, Math.min(pixels.height, area.bottom));
        return new Rect(left, top, right, bottom);
    }
}
