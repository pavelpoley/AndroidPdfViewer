package com.github.barteksc.pdfviewer.util;

import com.github.barteksc.pdfviewer.model.SearchRecordItem;
import com.github.barteksc.pdfviewer.model.SentencedSearchResult;
import com.vivlio.android.pdfium.PdfiumCore;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for processing PDF search results with context-aware sentence extraction.
 */
public class SearchUtils {

    /**
     * Default sentence extractor using punctuation marks and whitespace rules.
     */
    private static class DefaultSentenceExtractor implements SentenceExtractor {
        @Override
        public int[] findBounds(String pageText, int start, int end) {
            return findSentenceBounds(pageText, start, end);
        }
    }

    private static final SentenceExtractor DEFAULT_SENTENCE_EXTRACTOR = new DefaultSentenceExtractor();

    /**
     * Extracts search results with sentence context using a custom or default sentence extractor.
     *
     * @param textPtr           Native text page pointer.
     * @param pageText          Full text of the page.
     * @param searchItems       List of search result entries.
     * @param sentenceExtractor Optional custom sentence extractor implementation.
     * @return A list of {@link SentencedSearchResult} containing match data with context.
     * @throws IllegalArgumentException if the sentence extractor returns invalid bounds.
     */
    public static List<SentencedSearchResult> extractSearchResults(
            long textPtr,
            String pageText,
            List<SearchRecordItem> searchItems,
            SentenceExtractor sentenceExtractor
    ) {
        if (pageText == null) {
            throw new IllegalArgumentException("Page text must not be null.");
        }
        if (searchItems == null) {
            throw new IllegalArgumentException("Search items must not be null.");
        }

        List<SentencedSearchResult> results = new ArrayList<>();
        for (SearchRecordItem item : searchItems) {
            int start = item.st;
            int end = start + item.ed;

            if (start < 0 || end > pageText.length() || start >= end) {
                // Skip invalid bounds safely
                continue;
            }

            int[] bounds;
            try {
                bounds = sentenceExtractor == null
                        ? DEFAULT_SENTENCE_EXTRACTOR.findBounds(pageText, start, end)
                        : sentenceExtractor.findBounds(pageText, start, end);
            } catch (Exception e) {
                throw new IllegalArgumentException("Custom sentence extractor failed", e);
            }

            if (bounds == null || bounds.length != 2) {
                throw new IllegalArgumentException("Sentence extractor must return exactly two elements.");
            }

            int sentenceStart = bounds[0];
            int sentenceEnd = bounds[1];

            if (sentenceStart < 0 || sentenceEnd > pageText.length() || sentenceStart >= sentenceEnd) {
                throw new IllegalArgumentException("Sentence extractor returned invalid bounds: [" +
                        sentenceStart + ", " + sentenceEnd + "] in text length " + pageText.length());
            }

            String snippet = pageText.substring(sentenceStart, sentenceEnd);
            int relativeStartIndex = Math.max(0, start - sentenceStart);
            int relativeEndIndex = Math.min(snippet.length(), end - sentenceStart);

            long offset = PdfiumCore.nativeGetTextOffset(textPtr, item.st, item.ed);
            int xBits = (int) (offset >> 32);
            int yBits = (int) offset;
            float x = Float.intBitsToFloat(xBits);
            float y = Float.intBitsToFloat(yBits);

            results.add(new SentencedSearchResult(
                    item.pageIndex,
                    x,
                    y,
                    snippet,
                    relativeStartIndex,
                    relativeEndIndex
            ));
        }

        return results;
    }


    /**
     * Finds sentence bounds around a match in the given text using basic punctuation and newline characters.
     *
     * @param text  Full page text.
     * @param start Start index of the match.
     * @param end   End index of the match.
     * @return An array of two integers representing sentence start and end indices.
     */
    private static int[] findSentenceBounds(String text, int start, int end) {
        char[] delimiters = {'.', '\n', '?', '!'};
        int sentenceStart = lastIndexOfAny(text, delimiters, start - 1);
        int sentenceEnd = indexOfAny(text, delimiters, end);

        if (sentenceStart == -1) {
            sentenceStart = 0;
        } else {
            sentenceStart += 1;
        }

        if (sentenceEnd == -1) {
            sentenceEnd = text.length();
        }

        // Trim whitespace
        while (sentenceStart < text.length() && Character.isWhitespace(text.charAt(sentenceStart))) {
            sentenceStart++;
        }

        while (sentenceEnd > 0 && Character.isWhitespace(text.charAt(sentenceEnd - 1))) {
            sentenceEnd--;
        }

        return new int[]{sentenceStart, sentenceEnd};
    }

    /**
     * Searches backwards for the last occurrence of any of the specified characters before the given index.
     *
     * @param text      The full text to search.
     * @param chars     The characters to search for.
     * @param fromIndex The index to start searching backward from.
     * @return The index of the last occurrence or -1 if none found.
     */
    private static int lastIndexOfAny(String text, char[] chars, int fromIndex) {
        for (int i = fromIndex; i >= 0; i--) {
            for (char c : chars) {
                if (text.charAt(i) == c) return i;
            }
        }
        return -1;
    }

    /**
     * Searches forwards for the first occurrence of any of the specified characters starting from the given index.
     *
     * @param text      The full text to search.
     * @param chars     The characters to search for.
     * @param fromIndex The index to start searching from.
     * @return The index of the first occurrence or -1 if none found.
     */
    private static int indexOfAny(String text, char[] chars, int fromIndex) {
        for (int i = fromIndex; i < text.length(); i++) {
            for (char c : chars) {
                if (text.charAt(i) == c) return i;
            }
        }
        return -1;
    }
}

