package com.github.barteksc.pdfviewer.util;

/**
 * Interface to extract sentence bounds from text given start and end character positions.
 */
public interface SentenceExtractor {
    /**
     * Finds the sentence boundaries that include the specified text range.
     *
     * @param pageText The full page text.
     * @param start    The start index of the match.
     * @param end      The end index of the match.
     * @return An array of two integers: {sentenceStart, sentenceEnd}.
     */
    int[] findBounds(String pageText, int start, int end);
}