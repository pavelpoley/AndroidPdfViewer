package com.github.barteksc.pdfviewer.model;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;

import androidx.annotation.ColorInt;

import java.util.Objects;

public class SentencedSearchResult implements java.io.Serializable, Parcelable {
    private final int pageIndex;
    private final int searchItemIndex;
    private final String text;
    private final int startIndex;
    private final int endIndex;

    transient private SpannableString spannable;

    public SentencedSearchResult(int pageIndex,
                                 int searchItemIndex,
                                 String text,
                                 int startIndex,
                                 int endIndex
    ) {
        this.pageIndex = pageIndex;
        this.searchItemIndex = searchItemIndex;
        this.text = text;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
    }

    protected SentencedSearchResult(Parcel in) {
        pageIndex = in.readInt();
        searchItemIndex = in.readInt();
        text = in.readString();
        startIndex = in.readInt();
        endIndex = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(pageIndex);
        dest.writeInt(searchItemIndex);
        dest.writeString(text);
        dest.writeInt(startIndex);
        dest.writeInt(endIndex);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<SentencedSearchResult> CREATOR = new Creator<>() {
        @Override
        public SentencedSearchResult createFromParcel(Parcel in) {
            return new SentencedSearchResult(in);
        }

        @Override
        public SentencedSearchResult[] newArray(int size) {
            return new SentencedSearchResult[size];
        }
    };

    public long getRecordId() {
        return ((long) pageIndex << 32) | (searchItemIndex & 0xFFFFFFFFL);
    }

    public static int unpackPageIndex(long recordId) {
        return (int) (recordId >>> 32);
    }

    public static int unpackSearchItemIndex(long recordId) {
        return (int) recordId;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public String getText() {
        return text;
    }

    public int getStartIndex() {
        return startIndex;
    }

    public int getEndIndex() {
        return endIndex;
    }

    @Deprecated
    public float getxOffset() {
        return 0f;
    }

    @Deprecated
    public float getyOffset() {
        return 0f;
    }

    public int getSearchItemIndex() {
        return searchItemIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SentencedSearchResult)) return false;
        SentencedSearchResult that = (SentencedSearchResult) o;
        return pageIndex == that.pageIndex && searchItemIndex == that.searchItemIndex && startIndex == that.startIndex && endIndex == that.endIndex && Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageIndex, searchItemIndex, text, startIndex, endIndex);
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
