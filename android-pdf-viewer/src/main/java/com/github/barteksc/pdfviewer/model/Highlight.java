package com.github.barteksc.pdfviewer.model;

import androidx.annotation.NonNull;

import java.util.Objects;

public class Highlight {
    private final int pageIndex;
    private final long selectionId;


    public Highlight(int pageIndex, long selectionId) {
        this.pageIndex = pageIndex;
        this.selectionId = selectionId;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public long getSelectionId() {
        return selectionId;
    }

    @NonNull
    @Override
    public String toString() {
        return "Highlight{" +
                "pageIndex=" + pageIndex +
                ", selectionId=" + selectionId +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Highlight)) return false;
        Highlight highlight = (Highlight) o;
        return pageIndex == highlight.pageIndex && selectionId == highlight.selectionId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageIndex, selectionId);
    }
}
