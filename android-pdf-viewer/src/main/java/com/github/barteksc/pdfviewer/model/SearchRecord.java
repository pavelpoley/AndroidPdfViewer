package com.github.barteksc.pdfviewer.model;

public class SearchRecord {
	public final int pageIdx;
	public  int currentPage=-1;
	public final int findStart;
	public Object data;
	public SearchRecord(int pageIdx, int findStart) {
		this.pageIdx = pageIdx;
		this.findStart = findStart;
	}
}
