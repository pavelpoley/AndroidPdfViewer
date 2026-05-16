package com.github.barteksc.pdfviewer.reflow;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;

final class ReflowWordBlock {
    final Rect rect;
    int gapBeforeWord;

    ReflowWordBlock(Rect rect) {
        this.rect = rect;
    }
}

final class ReflowLineBlock {
    final List<ReflowWordBlock> words = new ArrayList<>();
    int sourceHeight;
    int gapBeforeLine;
}

final class ReflowPlacedWord {
    final Rect source;
    final Rect destination;

    ReflowPlacedWord(Rect source, Rect destination) {
        this.source = source;
        this.destination = destination;
    }
}

final class ReflowLayout {
    final List<ReflowPlacedWord> placedWords;
    final int outputHeight;

    ReflowLayout(List<ReflowPlacedWord> placedWords, int outputHeight) {
        this.placedWords = placedWords;
        this.outputHeight = outputHeight;
    }
}
