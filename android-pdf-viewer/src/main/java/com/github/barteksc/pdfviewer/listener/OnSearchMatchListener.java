package com.github.barteksc.pdfviewer.listener;

public interface OnSearchMatchListener {

    void onSearchMatch(int page, int totalMatched, String word);
}
