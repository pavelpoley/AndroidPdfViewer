package com.github.barteksc.pdfviewer;


import com.github.barteksc.pdfviewer.model.SearchRecord;
import com.github.barteksc.pdfviewer.listener.Callbacks.*;
import com.vivlio.android.pdfium.PdfiumCore;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class PDocSearchTask implements Runnable {
    private final ArrayList<SearchRecord> arr = new ArrayList<>();

    private final WeakReference<PDFView> pdoc;
    public final AtomicBoolean abort = new AtomicBoolean();
    public final String key;
    private Thread t;
    public final int flag = 0;
    private long keyStr;

    private boolean finished;

    public PDocSearchTask(PDFView pdoc, String key) {
        this.pdoc = new WeakReference<>(pdoc);
        this.key = key + "\0";
    }

    public long getKeyStr( ) {
        if(keyStr==0) {
            keyStr = PdfiumCore.nativeGetStringChars(key);
        }
        return keyStr;
    }

    @Override
    public void run() {
        PDFView pdfView = this.pdoc.get();
        if (pdfView == null) {
            return;
        }
        if (finished) {
            pdfView.endSearch(arr);
        } else {
            SearchRecord schRecord;
            for (int i = 0; i < pdfView.getPageCount(); i++) {
                if (abort.get()) {
                    break;
                }
                schRecord = pdfView.findPageCached(key, i, 0);

                if (schRecord != null) {
                    pdfView.notifyItemAdded(this, arr, schRecord, i);
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
            PDFView pdfView = this.pdoc.get();
            if (pdfView != null) {
                pdfView.startSearch(arr, key, flag);
            }
            t = new Thread(this);
            t.start();
        }
    }

    public void abort() {
        abort.set(true);
    }

    public boolean isAborted() {
        return abort.get();
    }
}
