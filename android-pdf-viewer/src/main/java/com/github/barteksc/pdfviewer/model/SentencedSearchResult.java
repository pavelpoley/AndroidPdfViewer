package com.github.barteksc.pdfviewer.model;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;

import java.util.Objects;

public class SentencedSearchResult implements java.io.Serializable {
    private final int pageIndex;
    private final float xOffset;
    private final float yOffset;

    private final String text;

    private final int startIndex;
    private final int endIndex;

    transient private SpannableString spannable;

    public SentencedSearchResult(int pageIndex,
                                 float xOffset,
                                 float yOffset,
                                 String text,
                                 int startIndex,
                                 int endIndex) {
        this.pageIndex = pageIndex;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.text = text;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
    }

    @NonNull
    @Override
    public String toString() {
        return "SentencedSearchResult{" +
                "pageIndex=" + pageIndex +
                ", xOffset=" + xOffset +
                ", yOffset=" + yOffset +
                ", text='" + text + '\'' +
                ", startIndex=" + startIndex +
                ", endIndex=" + endIndex +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SentencedSearchResult)) return false;
        SentencedSearchResult that = (SentencedSearchResult) o;
        return pageIndex == that.pageIndex && Float.compare(xOffset, that.xOffset) == 0 && Float.compare(yOffset, that.yOffset) == 0 && startIndex == that.startIndex && endIndex == that.endIndex && Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageIndex, xOffset, yOffset, text, startIndex, endIndex);
    }

    public String getText() {
        return text;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public float getyOffset() {
        return yOffset;
    }

    public float getxOffset() {
        return xOffset;
    }

    public int getEndIndex() {
        return endIndex;
    }

    public int getStartIndex() {
        return startIndex;
    }

    public SpannableString getSpannedText(@ColorInt int color) {
        if (this.spannable != null) return this.spannable;

        SpannableString spannable = new SpannableString(text);
        if (startIndex >= 0 && endIndex <= text.length() && startIndex < endIndex) {
            spannable.setSpan(
                    new BackgroundColorSpan(color),
                    startIndex,
                    endIndex,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        this.spannable = spannable;
        return this.spannable;
    }
}
