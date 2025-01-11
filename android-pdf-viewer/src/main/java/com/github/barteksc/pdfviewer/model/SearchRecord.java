package com.github.barteksc.pdfviewer.model;

import java.util.ArrayList;

public class SearchRecord {
    public final int pageIdx;
    public final int findStart;

    public ArrayList<SearchRecordItem> data;

    public SearchRecord(int pageIdx, int findStart) {
        this.pageIdx = pageIdx;
        this.findStart = findStart;
    }
}
