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
    private static final Bitmap.Config REFLOW_SOURCE_CONFIG = Bitmap.Config.ARGB_8888;
    private static final Bitmap.Config REFLOW_SOURCE_FALLBACK_CONFIG = Bitmap.Config.RGB_565;
    private static final float QUALITY_RERENDER_SCALE_THRESHOLD = 1.08f;
    private static final float QUALITY_RERENDER_MARGIN = 1.12f;
    private static final float OOM_WIDTH_RETRY_SCALE = 0.85f;

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
        SourceBitmap sourceBitmap = renderSourcePage(core, document, page, options, annotationRendering);
        traceTiming("page " + page + " source render", stageStart);
        if (sourceBitmap == null) {
            if (cancellationSignal.isCanceled()) {
                postRenderSkipped(callback, page, cancellationSignal);
                return;
            }
            postRenderFailed(callback, page, cancellationSignal);
            return;
        }
        if (shouldCancelRender(page, callback, cancellationSignal)) {
            sourceBitmap.bitmap.recycle();
            postRenderSkipped(callback, page, cancellationSignal);
            return;
        }

        ReflowBitmapProcessor.Result reflowed;
        try {
            stageStart = traceStart();
            reflowed = reflowSourceBitmap(processor, sourceBitmap.bitmap, options, cancellationSignal);
            traceTiming("page " + page + " bitmap reflow", stageStart);

            if (shouldRerenderForQuality(reflowed, sourceBitmap, options)) {
                SourceBitmap sharperSourceBitmap = renderSourcePage(
                        core,
                        document,
                        page,
                        options,
                        annotationRendering,
                        chooseSharperRenderWidth(sourceBitmap, options, reflowed.layoutScale)
                );
                if (sharperSourceBitmap != null) {
                    reflowed.recycle();
                    sourceBitmap.bitmap.recycle();
                    sourceBitmap = sharperSourceBitmap;

                    if (shouldCancelRender(page, callback, cancellationSignal)) {
                        postRenderSkipped(callback, page, cancellationSignal);
                        return;
                    }

                    stageStart = traceStart();
                    reflowed = reflowSourceBitmap(processor, sourceBitmap.bitmap, options, cancellationSignal);
                    traceTiming("page " + page + " sharper bitmap reflow", stageStart);
                }
            }
        } catch (OperationCanceledException ignored) {
            postRenderSkipped(callback, page, cancellationSignal);
            return;
        } finally {
            sourceBitmap.bitmap.recycle();
        }

        if (shouldCancelRender(page, callback, cancellationSignal)) {
            reflowed.recycle();
            postRenderSkipped(callback, page, cancellationSignal);
            return;
        }
        ReflowBitmapProcessor.Result finalReflowed = reflowed;
        mainHandler.post(() -> callback.onRendered(page, finalReflowed, cancellationSignal));
    }

    private ReflowBitmapProcessor.Result reflowSourceBitmap(
            ReflowBitmapProcessor processor,
            Bitmap pageBitmap,
            ReflowRenderOptions options,
            CancellationSignal cancellationSignal
    ) {
        return processor.reflowToResult(
                pageBitmap,
                options.targetWidth,
                options.minOutputHeight,
                options.targetTextHeightPx,
                cancellationSignal
        );
    }

    private boolean shouldRerenderForQuality(
            ReflowBitmapProcessor.Result result,
            SourceBitmap sourceBitmap,
            ReflowRenderOptions options
    ) {
        return result.layoutScale > QUALITY_RERENDER_SCALE_THRESHOLD
                && chooseSharperRenderWidth(sourceBitmap, options, result.layoutScale) > sourceBitmap.renderWidth;
    }

    private int chooseSharperRenderWidth(
            SourceBitmap sourceBitmap,
            ReflowRenderOptions options,
            float layoutScale
    ) {
        int requestedWidth = Math.round(sourceBitmap.renderWidth * layoutScale * QUALITY_RERENDER_MARGIN);
        return chooseRenderWidth(sourceBitmap.pageSize, options, requestedWidth);
    }

    @Nullable
    private SourceBitmap renderSourcePage(
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

            return renderSourcePageAtWidth(
                    core,
                    document,
                    page,
                    pageSize,
                    chooseRenderWidth(pageSize, options),
                    options,
                    annotationRendering
            );
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to render page " + page + " for reflow", throwable);
            return null;
        }
    }

    @Nullable
    private SourceBitmap renderSourcePage(
            PdfiumCore core,
            PdfDocument document,
            int page,
            ReflowRenderOptions options,
            boolean annotationRendering,
            int preferredRenderWidth
    ) {
        try {
            core.openPage(document, page);
            Size pageSize = core.getPageSize(document, page);
            if (pageSize.getWidth() <= 0 || pageSize.getHeight() <= 0) {
                return null;
            }

            return renderSourcePageAtWidth(
                    core,
                    document,
                    page,
                    pageSize,
                    chooseRenderWidth(pageSize, options, preferredRenderWidth),
                    options,
                    annotationRendering
            );
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to render sharper page " + page + " for reflow", throwable);
            return null;
        }
    }

    @Nullable
    private SourceBitmap renderSourcePageAtWidth(
            PdfiumCore core,
            PdfDocument document,
            int page,
            Size pageSize,
            int preferredRenderWidth,
            ReflowRenderOptions options,
            boolean annotationRendering
    ) {
        int renderWidth = Math.max(1, preferredRenderWidth);
        while (renderWidth >= options.targetWidth) {
            int renderHeight = Math.max(1, Math.round(renderWidth * (pageSize.getHeight() / (float) pageSize.getWidth())));
            Bitmap bitmap = null;
            try {
                bitmap = createSourceBitmap(renderWidth, renderHeight);
                bitmap.eraseColor(Color.WHITE);
                core.renderPageBitmap(document, bitmap, page, 0, 0, renderWidth, renderHeight, annotationRendering);
                return new SourceBitmap(bitmap, pageSize, renderWidth);
            } catch (OutOfMemoryError error) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                if (renderWidth <= options.targetWidth) {
                    throw error;
                }
                renderWidth = Math.max(options.targetWidth, Math.round(renderWidth * OOM_WIDTH_RETRY_SCALE));
            } catch (RuntimeException exception) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                throw exception;
            }
        }
        return null;
    }

    private Bitmap createSourceBitmap(int width, int height) {
        try {
            return Bitmap.createBitmap(width, height, REFLOW_SOURCE_CONFIG);
        } catch (OutOfMemoryError ignored) {
            return Bitmap.createBitmap(width, height, REFLOW_SOURCE_FALLBACK_CONFIG);
        }
    }

    private int chooseRenderWidth(Size pageSize, ReflowRenderOptions options) {
        int requested = Math.max(options.targetWidth, Math.round(options.targetWidth * options.sourceScale));
        return chooseRenderWidth(pageSize, options, requested);
    }

    private int chooseRenderWidth(Size pageSize, ReflowRenderOptions options, int requestedWidth) {
        int requested = Math.max(options.targetWidth, requestedWidth);
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

    private static final class SourceBitmap {
        final Bitmap bitmap;
        final Size pageSize;
        final int renderWidth;

        SourceBitmap(Bitmap bitmap, Size pageSize, int renderWidth) {
            this.bitmap = bitmap;
            this.pageSize = pageSize;
            this.renderWidth = renderWidth;
        }
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
