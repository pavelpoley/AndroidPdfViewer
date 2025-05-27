package com.pp.sample;

import android.content.Context;
import android.util.Log;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.model.SentencedSearchResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SearchResultsCacheManager {
    private static final String TAG = "SearchResultsCacheManager";
    private static final String CACHE_FILENAME = "search_results.ser";

    public static void saveSearchResultsToFile(Context context, PDFView pdfView) {
        List<SentencedSearchResult> allResults = new ArrayList<>();
        for (int i = 0; i < pdfView.getPageCount(); i++) {
            List<SentencedSearchResult> pageResults = pdfView.getSearchResults(i);
            if (pageResults != null && !pageResults.isEmpty()) {
                allResults.addAll(pageResults);
            }
        }

        File file = new File(context.getCacheDir(), CACHE_FILENAME);

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            oos.writeObject(allResults);
            oos.flush();
            Log.d(TAG, "Search results saved to: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "saveSearchResultsToFile: ", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<SentencedSearchResult> readSearchResultsFromFile(Context context) {
        File file = new File(context.getCacheDir(), CACHE_FILENAME);
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return (List<SentencedSearchResult>) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            Log.e(TAG, "readSearchResultsFromFile: ", e);
            return Collections.emptyList();
        }
    }

    public static boolean deleteSearchResultsCache(Context context) {
        File file = new File(context.getCacheDir(), CACHE_FILENAME);
        if (file.exists()) {
            boolean deleted = file.delete();
            if (deleted) {
                Log.d(TAG, "Search results cache deleted.");
            } else {
                Log.w(TAG, "Failed to delete search results cache.");
            }
            return deleted;
        } else {
            Log.d(TAG, "No search results cache file found to delete.");
            return false;
        }
    }
}
