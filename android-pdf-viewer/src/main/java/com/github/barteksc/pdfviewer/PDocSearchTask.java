package com.github.barteksc.pdfviewer;


import com.github.barteksc.pdfviewer.model.SearchRecord;
import com.vivlio.android.pdfium.PdfiumCore;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("unused")
public class PDocSearchTask implements Runnable {

    private static final String TAG = "PDocSearchTask";
    private final ArrayList<SearchRecord> arr = new ArrayList<>();

    private final WeakReference<PDFView> pdfViewRef;
    public final AtomicBoolean abort = new AtomicBoolean();
    public final String key;
    private final String query;
    private Thread t;
    public final int flag = 0;
    private long keyStr;

    private boolean finished;

    public PDocSearchTask(PDFView pdfView, String key) {
        var trimmedQuery = key.trim();
        this.pdfViewRef = new WeakReference<>(pdfView);
        this.key = trimmedQuery + "\0";
        this.query = trimmedQuery;
    }

    public long getKeyStr() {
        if (keyStr == 0) {
            keyStr = PdfiumCore.nativeGetStringChars(key);
        }
        return keyStr;
    }

    @Override
    public void run() {
        PDFView pdfView = this.pdfViewRef.get();
        if (pdfView == null) {
            return;
        }
        if (finished) {
            pdfView.endSearch(arr);
        } else {
            for (int pageIndex = 0; pageIndex < pdfView.getPageCount(); pageIndex++) {
                if (abort.get()) {
                    break;
                }
                SearchRecord schRecord = pdfView.findPageCached(key, pageIndex, 0);
                if (schRecord != null) {
                    pdfView.notifyItemAdded(
                            this,
                            arr,
                            schRecord,
                            pageIndex,
                            query
                    );
                }
            }

            finished = true;
            t = null;
            pdfView.post(this);
        }
    }

    public void start() {
        if (finished) {
            return;
        }
        if (t == null) {
            PDFView pdfView = this.pdfViewRef.get();
            if (pdfView != null) {
                pdfView.startSearch(arr, key, flag);
            }
            t = new Thread(this);
            t.start();
        }
    }

    public void abort() {
        abort.set(true);
        PdfiumCore.nativeReleaseStringChars(keyStr);
    }

    public boolean isAborted() {
        return abort.get();
    }
}
