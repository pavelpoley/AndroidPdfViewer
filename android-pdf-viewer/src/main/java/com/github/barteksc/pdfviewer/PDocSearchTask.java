package com.github.barteksc.pdfviewer;

import com.github.barteksc.pdfviewer.model.SearchRecord;
import com.vivlio.android.pdfium.PdfiumCore;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


@SuppressWarnings("unused")
public class PDocSearchTask implements Runnable, AutoCloseable {


    private static final String TAG = "PDocSearchTask";
    private final ArrayList<SearchRecord> arr = new ArrayList<>();
    private final WeakReference<PDFView> pdfViewRef;
    private final AtomicBoolean abort = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final String query;
    private final String key;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile long keyStr;
    int flag = 0;

    public PDocSearchTask(PDFView pdfView, String key) {
        this.pdfViewRef = new WeakReference<>(pdfView);
        this.query = key.trim();
        this.key = query + "\0";
    }

    synchronized long getKeyStr() {
        if (keyStr == 0) {
            keyStr = PdfiumCore.nativeGetStringChars(key);
            if (keyStr == 0) {
                throw new IllegalStateException("Failed to get native string chars");
            }
        }
        return keyStr;
    }

    @Override
    public void run() {
        PDFView pdfView = pdfViewRef.get();
        if (pdfView == null || finished.get()) return;
        for (int pageIndex = 0; pageIndex < pdfView.getPageCount(); pageIndex++) {
            if (abort.get()) break;
            SearchRecord schRecord = pdfView.findPageCached(key, pageIndex, 0);
            if (schRecord != null) {
                pdfView.notifyItemAdded(this, arr, schRecord, pageIndex, query);
            }
        }
        finished.set(true);
        pdfView.getHandler().post(() -> pdfView.endSearch(arr));
    }

    public void start() {
        if (!finished.get() && !executor.isShutdown()) {
            executor.submit(this);
        }
    }

    public void abort() {
        abort.set(true);
        close();
        executor.shutdownNow();
    }

    @Override
    public void close() {
        if (keyStr != 0) {
            PdfiumCore.nativeReleaseStringChars(keyStr);
            keyStr = 0;
        }
    }

    public boolean isAborted() {
        return abort.get();
    }

    public boolean isFinished() {
        return finished.get();
    }
}
