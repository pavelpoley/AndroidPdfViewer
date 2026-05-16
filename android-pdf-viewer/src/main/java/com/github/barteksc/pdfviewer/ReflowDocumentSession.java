package com.github.barteksc.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.OperationCanceledException;
import android.util.Log;

import androidx.annotation.Nullable;

import com.github.barteksc.pdfviewer.reflow.ReflowBitmapProcessor;
import com.vivlio.android.pdfium.PdfDocument;
import com.vivlio.android.pdfium.PdfiumCore;
import com.vivlio.android.pdfium.util.Size;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

final class ReflowDocumentSession {
    private static final String TAG = ReflowDocumentSession.class.getSimpleName();
    private static final boolean TRACE_REFLOW_TIMING = false;

    private final Object lock = new Object();
    private final Context appContext;
    private final Handler mainHandler;
    private final ReflowLoadConfig loadConfig;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    private PdfiumCore pdfiumCore;
    @Nullable
    private PdfDocument pdfDocument;
    private boolean closed;

    ReflowDocumentSession(Context appContext, Handler mainHandler, ReflowLoadConfig loadConfig) {
        this.appContext = appContext;
        this.mainHandler = mainHandler;
        this.loadConfig = loadConfig;
    }

    void open(OpenCallback callback) {
        try {
            executor.execute(() -> openOnExecutor(callback));
        } catch (RejectedExecutionException exception) {
            postOpenError(callback, exception);
        }
    }

    CancellationSignal renderPage(
            int page,
            ReflowRenderOptions options,
            boolean annotationRendering,
            ReflowBitmapProcessor processor,
            PageRenderCallback callback
    ) {
        CancellationSignal cancellationSignal = new CancellationSignal();
        try {
            executor.execute(() -> renderPageOnExecutor(
                    page,
                    options,
                    annotationRendering,
                    processor,
                    callback,
                    cancellationSignal
            ));
        } catch (RejectedExecutionException ignored) {
            cancellationSignal.cancel();
            postRenderSkipped(callback, page, cancellationSignal);
        }
        return cancellationSignal;
    }

    void close() {
        PdfiumCore coreToClose;
        PdfDocument documentToClose;
        synchronized (lock) {
            closed = true;
            coreToClose = pdfiumCore;
            documentToClose = pdfDocument;
            pdfiumCore = null;
            pdfDocument = null;
        }

        if (coreToClose != null && documentToClose != null) {
            try {
                executor.execute(() -> closeDocument(coreToClose, documentToClose));
            } catch (RejectedExecutionException ignored) {
                closeDocumentInBackground(coreToClose, documentToClose);
            }
        }
        executor.shutdown();
    }

    private void openOnExecutor(OpenCallback callback) {
        PdfiumCore core = new PdfiumCore(appContext);
        PdfDocument document = null;
        try {
            document = loadConfig.documentSource.createDocument(appContext, core, loadConfig.password);
            int pageCount = core.getPageCount(document);
            if (pageCount <= 0) {
                closeDocument(core, document);
                postOpenError(callback, new IllegalStateException("PDF has no pages"));
                return;
            }

            Size[] pageSizes = new Size[pageCount];
            for (int page = 0; page < pageCount; page++) {
                if (isClosed() || callback.isCancelled()) {
                    closeDocument(core, document);
                    return;
                }
                pageSizes[page] = core.getPageSize(document, page);
            }

            if (!setOpenedDocument(core, document)) {
                closeDocument(core, document);
                return;
            }

            mainHandler.post(() -> {
                if (isClosed() || callback.isCancelled()) {
                    close();
                    return;
                }
                callback.onOpened(this, pageSizes);
            });
        } catch (Throwable throwable) {
            if (document != null) {
                closeDocument(core, document);
            }
            postOpenError(callback, throwable);
        }
    }

    private void renderPageOnExecutor(
            int page,
            ReflowRenderOptions options,
            boolean annotationRendering,
            ReflowBitmapProcessor processor,
            PageRenderCallback callback,
            CancellationSignal cancellationSignal
    ) {
        if (shouldCancelRender(page, callback, cancellationSignal)) {
            postRenderSkipped(callback, page, cancellationSignal);
            return;
        }

        PdfiumCore core;
        PdfDocument document;
        synchronized (lock) {
            core = pdfiumCore;
            document = pdfDocument;
        }
        if (core == null || document == null) {
            cancellationSignal.cancel();
            postRenderSkipped(callback, page, cancellationSignal);
            return;
        }

        long stageStart = traceStart();
        Bitmap pageBitmap = renderSourcePage(core, document, page, options, annotationRendering);
        traceTiming("page " + page + " source render", stageStart);
        if (pageBitmap == null) {
            if (cancellationSignal.isCanceled()) {
                postRenderSkipped(callback, page, cancellationSignal);
                return;
            }
            postRenderFailed(callback, page, cancellationSignal);
            return;
        }
        if (shouldCancelRender(page, callback, cancellationSignal)) {
            pageBitmap.recycle();
            postRenderSkipped(callback, page, cancellationSignal);
            return;
        }

        ReflowBitmapProcessor.Result reflowed;
        try {
            stageStart = traceStart();
            reflowed = processor.reflowToResult(
                    pageBitmap,
                    options.targetWidth,
                    options.minOutputHeight,
                    options.targetTextHeightPx,
                    cancellationSignal
            );
            traceTiming("page " + page + " bitmap reflow", stageStart);
        } catch (OperationCanceledException ignored) {
            postRenderSkipped(callback, page, cancellationSignal);
            return;
        } finally {
            pageBitmap.recycle();
        }

        if (shouldCancelRender(page, callback, cancellationSignal)) {
            reflowed.recycle();
            postRenderSkipped(callback, page, cancellationSignal);
            return;
        }
        mainHandler.post(() -> callback.onRendered(page, reflowed, cancellationSignal));
    }

    @Nullable
    private Bitmap renderSourcePage(
            PdfiumCore core,
            PdfDocument document,
            int page,
            ReflowRenderOptions options,
            boolean annotationRendering
    ) {
        try {
            core.openPage(document, page);
            Size pageSize = core.getPageSize(document, page);
            if (pageSize.getWidth() <= 0 || pageSize.getHeight() <= 0) {
                return null;
            }

            int renderWidth = chooseRenderWidth(pageSize, options);
            int renderHeight = Math.max(1, Math.round(renderWidth * (pageSize.getHeight() / (float) pageSize.getWidth())));
            Bitmap bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.RGB_565);
            bitmap.eraseColor(Color.WHITE);
            core.renderPageBitmap(document, bitmap, page, 0, 0, renderWidth, renderHeight, annotationRendering);
            return bitmap;
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to render page " + page + " for reflow", throwable);
            return null;
        }
    }

    private int chooseRenderWidth(Size pageSize, ReflowRenderOptions options) {
        int requested = Math.max(options.targetWidth, Math.round(options.targetWidth * options.sourceScale));
        int maxWidth = Math.max(options.targetWidth, options.maxSourceWidthPx);
        requested = Math.min(requested, maxWidth);

        while (requested > options.targetWidth) {
            int requestedHeight = Math.max(1, Math.round(requested * (pageSize.getHeight() / (float) pageSize.getWidth())));
            if ((long) requested * requestedHeight <= options.maxSourcePixels) {
                break;
            }
            requested = Math.max(options.targetWidth, Math.round(requested * 0.9f));
        }
        return Math.max(1, requested);
    }

    private boolean setOpenedDocument(PdfiumCore core, PdfDocument document) {
        synchronized (lock) {
            if (closed) {
                return false;
            }
            pdfiumCore = core;
            pdfDocument = document;
            return true;
        }
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private boolean shouldCancelRender(
            int page,
            PageRenderCallback callback,
            CancellationSignal cancellationSignal
    ) {
        boolean shouldCancel = isClosed()
                || cancellationSignal.isCanceled()
                || callback.isCancelled()
                || !callback.shouldRender(page);
        if (shouldCancel) {
            cancellationSignal.cancel();
        }
        return shouldCancel;
    }

    private void postOpenError(OpenCallback callback, Throwable throwable) {
        mainHandler.post(() -> {
            if (!isClosed() && !callback.isCancelled()) {
                callback.onError(throwable);
            }
        });
    }

    private void postRenderSkipped(PageRenderCallback callback, int page, CancellationSignal cancellationSignal) {
        mainHandler.post(() -> callback.onSkipped(page, cancellationSignal));
    }

    private void postRenderFailed(PageRenderCallback callback, int page, CancellationSignal cancellationSignal) {
        mainHandler.post(() -> callback.onFailed(page, cancellationSignal));
    }

    private void closeDocumentInBackground(PdfiumCore core, PdfDocument document) {
        new Thread(() -> closeDocument(core, document), "PDF reflow close").start();
    }

    private void closeDocument(PdfiumCore core, PdfDocument document) {
        try {
            core.closeDocument(document);
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to close reflow document", throwable);
        }
    }

    private static long traceStart() {
        return TRACE_REFLOW_TIMING ? System.nanoTime() : 0L;
    }

    private static void traceTiming(String stage, long startNanos) {
        if (TRACE_REFLOW_TIMING) {
            long elapsedMicros = (System.nanoTime() - startNanos) / 1000L;
            Log.d(TAG, stage + ": " + (elapsedMicros / 1000f) + " ms");
        }
    }

    interface OpenCallback {
        void onOpened(ReflowDocumentSession session, Size[] pageSizes);

        void onError(Throwable throwable);

        boolean isCancelled();
    }

    interface PageRenderCallback {
        boolean shouldRender(int page);

        boolean isCancelled();

        void onRendered(int page, ReflowBitmapProcessor.Result result, CancellationSignal cancellationSignal);

        void onSkipped(int page, CancellationSignal cancellationSignal);

        void onFailed(int page, CancellationSignal cancellationSignal);
    }
}
