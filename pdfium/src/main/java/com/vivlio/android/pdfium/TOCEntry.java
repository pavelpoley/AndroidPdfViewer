package com.vivlio.android.pdfium;

import androidx.annotation.NonNull;

public class TOCEntry {
    private final String title;
    private final int pageIndex;
    private final int level;
    private final int parentIndex;
    private final float x;
    private final float y;
    private final float zoom;

    // Constructor
    public TOCEntry(String title, int pageIndex, int level,
                    int parentIndex, float x, float y, float zoom) {
        this.title = title;
        this.pageIndex = pageIndex;
        this.level = level;
        this.parentIndex = parentIndex;
        this.x = x;
        this.y = y;
        this.zoom = zoom;
    }

    public String getTitle() {
        return title;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public int getLevel() {
        return level;
    }


    public int getParentIndex() {
        return parentIndex;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getZoom() {
        return zoom;
    }

    @NonNull
    @Override
    public String toString() {
        return "TOCEntry{" +
                "title='" + title + '\'' +
                ", pageIndex=" + pageIndex +
                ", level=" + level +
                ", parentIndex=" + parentIndex +
                ", x=" + x +
                ", y=" + y +
                ", zoom=" + zoom +
                '}';
    }
}