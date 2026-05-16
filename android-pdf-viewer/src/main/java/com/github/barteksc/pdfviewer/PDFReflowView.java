package com.github.barteksc.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.barteksc.pdfviewer.reflow.ReflowBitmapProcessor;
import com.github.barteksc.pdfviewer.source.AssetSource;
import com.github.barteksc.pdfviewer.source.ByteArraySource;
import com.github.barteksc.pdfviewer.source.DocumentSource;
import com.github.barteksc.pdfviewer.source.FileSource;
import com.github.barteksc.pdfviewer.source.InputStreamSource;
import com.github.barteksc.pdfviewer.source.UriSource;
import com.vivlio.android.pdfium.PdfDocument;
import com.vivlio.android.pdfium.PdfiumCore;
import com.vivlio.android.pdfium.util.Size;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Experimental bitmap-based PDF reading mode.
 * <p>
 * This view lazily renders PDF pages, reflows their bitmap text, and stacks
 * the results in a continuous vertical scroll.
 */
@SuppressWarnings("unused")
public class PDFReflowView extends ScrollView {

    private static final String TAG = PDFReflowView.class.getSimpleName();
    private static final float DEFAULT_SOURCE_SCALE = 1.15f;
    private static final float DEFAULT_TEXT_SIZE_DP = 15f;
    private static final int DEFAULT_MAX_SOURCE_WIDTH = 1200;
    private static final int DEFAULT_MAX_SOURCE_PIXELS = 1_600_000;
    private static final int PAGE_PREFETCH_RADIUS = 1;
    private static final int MAX_RENDERED_PAGES = 4;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinearLayout pagesContainer;
    private final List<PageSlot> pageSlots = new ArrayList<>();
    private final ReflowBitmapProcessor processor = new ReflowBitmapProcessor();
    private final Runnable renderVisibleRunnable = this::renderVisiblePages;

    @Nullable
    private ExecutorService executorService;
    @Nullable
    private Configurator waitingDocumentConfigurator;
    @Nullable
    private TextView statusView;
    @Nullable
    private PdfiumCore pdfiumCore;
    @Nullable
    private PdfDocument pdfDocument;
    @Nullable
    private RenderOptions currentOptions;
    @Nullable
    private Configurator currentConfigurator;

    private volatile int loadGeneration = 0;
    private volatile int visibleStart = 0;
    private volatile int visibleEnd = 0;

    public PDFReflowView(Context context) {
        this(context, null);
    }

    public PDFReflowView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PDFReflowView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setFillViewport(true);
        setBackgroundColor(Color.WHITE);
        pagesContainer = new LinearLayout(context);
        pagesContainer.setOrientation(LinearLayout.VERTICAL);
        pagesContainer.setBackgroundColor(Color.WHITE);
        addView(pagesContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && waitingDocumentConfigurator != null) {
            Configurator configurator = waitingDocumentConfigurator;
            waitingDocumentConfigurator = null;
            load(configurator);
        } else if ((w != oldw || h != oldh) && !pageSlots.isEmpty()) {
            resetRenderedPagesForNewViewport();
            scheduleVisibleRender();
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        scheduleVisibleRender();
    }

    @Override
    protected void onDetachedFromWindow() {
        recycle();
        super.onDetachedFromWindow();
    }

    public void recycle() {
        waitingDocumentConfigurator = null;
        disposeCurrentLoad();
        pagesContainer.removeAllViews();
        pageSlots.clear();
        removeStatus();
    }

    public Configurator fromAsset(String assetName) {
        return new Configurator(new AssetSource(assetName));
    }

    public Configurator fromFile(File file) {
        return new Configurator(new FileSource(file));
    }

    public Configurator fromUri(Uri uri) {
        return new Configurator(new UriSource(uri));
    }

    public Configurator fromBytes(byte[] bytes) {
        return new Configurator(new ByteArraySource(bytes));
    }

    public Configurator fromStream(InputStream stream) {
        return new Configurator(new InputStreamSource(stream));
    }

    public Configurator fromSource(DocumentSource documentSource) {
        return new Configurator(documentSource);
    }

    private void load(@NonNull Configurator configurator) {
        if (getWidth() == 0) {
            waitingDocumentConfigurator = configurator.copy();
            return;
        }

        disposeCurrentLoad();
        pagesContainer.removeAllViews();
        pageSlots.clear();
        scrollTo(0, 0);
        visibleStart = 0;
        visibleEnd = 0;

        int generation = ++loadGeneration;
        RenderOptions options = createRenderOptions(configurator);

        showStatus("Opening PDF...");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executorService = executor;
        executor.execute(() -> openDocument(configurator.copy(), options, generation));
    }

    private RenderOptions createRenderOptions(Configurator configurator) {
        int targetWidth = getPageWidth();
        int minOutputHeight = Math.max(1, getHeight() / 2);
        return RenderOptions.from(
                configurator,
                targetWidth,
                minOutputHeight,
                dp(configurator.pageSpacingDp),
                Math.max(1, dp(configurator.textSizeDp))
        );
    }

    private void openDocument(
            @NonNull Configurator configurator,
            @NonNull RenderOptions options,
            int generation
    ) {
        PdfiumCore core = new PdfiumCore(getContext().getApplicationContext());
        PdfDocument document = null;
        try {
            document = configurator.documentSource.createDocument(
                    getContext().getApplicationContext(),
                    core,
                    configurator.password
            );
            int pageCount = core.getPageCount(document);
            if (pageCount <= 0) {
                closeDocument(core, document);
                postError(configurator, generation, new IllegalStateException("PDF has no pages"));
                return;
            }

            Size[] pageSizes = new Size[pageCount];
            for (int page = 0; page < pageCount; page++) {
                if (isCancelled(generation)) {
                    closeDocument(core, document);
                    return;
                }
                pageSizes[page] = core.getPageSize(document, page);
            }

            PdfDocument openedDocument = document;
            mainHandler.post(() -> setupDocument(
                    configurator,
                    options,
                    generation,
                    core,
                    openedDocument,
                    pageSizes
            ));
        } catch (Throwable throwable) {
            if (document != null) {
                closeDocument(core, document);
            }
            postError(configurator, generation, throwable);
        }
    }

    private void setupDocument(
            Configurator configurator,
            RenderOptions options,
            int generation,
            PdfiumCore core,
            PdfDocument document,
            Size[] pageSizes
    ) {
        if (generation != loadGeneration) {
            closeDocumentInBackground(core, document);
            return;
        }

        removeStatus();
        pdfiumCore = core;
        pdfDocument = document;
        currentOptions = options;
        currentConfigurator = configurator;
        pageSlots.clear();
        pagesContainer.removeAllViews();

        for (int page = 0; page < pageSizes.length; page++) {
            PageSlot slot = createPageSlot(page, pageSizes[page], options);
            pageSlots.add(slot);
            pagesContainer.addView(slot.container, slot.layoutParams);
        }

        if (configurator.onLoadCompleteListener != null) {
            configurator.onLoadCompleteListener.loadComplete(pageSizes.length);
        }

        pagesContainer.post(this::scheduleVisibleRender);
    }

    private PageSlot createPageSlot(int page, Size pageSize, RenderOptions options) {
        int estimatedHeight = estimatePageHeight(pageSize, options);
        FrameLayout container = new FrameLayout(getContext());
        container.setBackgroundColor(Color.WHITE);

        TextView placeholder = new TextView(getContext());
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setTextColor(Color.DKGRAY);
        placeholder.setTextSize(15f);
        placeholder.setText("Page " + (page + 1));
        int padding = dp(16);
        placeholder.setPadding(padding, padding, padding, padding);

        container.addView(placeholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                estimatedHeight
        );
        if (page > 0) {
            layoutParams.topMargin = options.pageSpacingPx;
        }

        return new PageSlot(page, pageSize, container, placeholder, layoutParams, estimatedHeight);
    }

    private int estimatePageHeight(Size pageSize, RenderOptions options) {
        if (pageSize == null || pageSize.getWidth() <= 0 || pageSize.getHeight() <= 0) {
            return Math.max(1, options.minOutputHeight);
        }
        int proportionalHeight = Math.round(options.targetWidth * (pageSize.getHeight() / (float) pageSize.getWidth()));
        return Math.max(options.minOutputHeight, proportionalHeight);
    }

    private void scheduleVisibleRender() {
        mainHandler.removeCallbacks(renderVisibleRunnable);
        mainHandler.postDelayed(renderVisibleRunnable, 80);
    }

    private void renderVisiblePages() {
        if (pageSlots.isEmpty() || executorService == null || currentOptions == null) {
            return;
        }

        int viewportTop = getScrollY();
        int viewportBottom = viewportTop + Math.max(1, getHeight());
        int firstVisible = -1;
        int lastVisible = -1;

        for (int i = 0; i < pageSlots.size(); i++) {
            FrameLayout container = pageSlots.get(i).container;
            int top = container.getTop();
            int bottom = container.getBottom();
            if (bottom >= viewportTop && top <= viewportBottom) {
                if (firstVisible < 0) {
                    firstVisible = i;
                }
                lastVisible = i;
            }
        }

        if (firstVisible < 0) {
            firstVisible = 0;
            lastVisible = 0;
        }

        visibleStart = Math.max(0, firstVisible);
        visibleEnd = Math.max(visibleStart, lastVisible);

        for (int page = visibleStart; page <= visibleEnd; page++) {
            requestRenderPage(page);
        }

        int renderStart = Math.max(0, visibleStart - PAGE_PREFETCH_RADIUS);
        int renderEnd = Math.min(pageSlots.size() - 1, visibleEnd + PAGE_PREFETCH_RADIUS);
        for (int distance = 1; distance <= PAGE_PREFETCH_RADIUS; distance++) {
            requestRenderPage(visibleStart - distance);
            requestRenderPage(visibleEnd + distance);
        }

        recycleFarPages(renderStart, renderEnd);
    }

    private void requestRenderPage(int page) {
        if (page < 0 || page >= pageSlots.size()) {
            return;
        }

        PageSlot slot = pageSlots.get(page);
        if (slot.bitmap != null || slot.renderRequested) {
            return;
        }

        PdfiumCore core = pdfiumCore;
        PdfDocument document = pdfDocument;
        RenderOptions options = currentOptions;
        Configurator configurator = currentConfigurator;
        ExecutorService executor = executorService;
        if (core == null || document == null || options == null || configurator == null || executor == null) {
            return;
        }

        slot.renderRequested = true;
        slot.failed = false;
        slot.placeholder.setText("Reflowing page " + (page + 1) + "...");
        int generation = loadGeneration;

        try {
            executor.execute(() -> renderPageIfNeeded(
                    core,
                    document,
                    options,
                    configurator,
                    page,
                    generation
            ));
        } catch (RejectedExecutionException ignored) {
            slot.renderRequested = false;
        }
    }

    private void renderPageIfNeeded(
            PdfiumCore core,
            PdfDocument document,
            RenderOptions options,
            Configurator configurator,
            int page,
            int generation
    ) {
        if (isCancelled(generation) || !isNearVisibleRange(page)) {
            postRenderSkipped(generation, page);
            return;
        }

        Bitmap pageBitmap = renderSourcePage(core, document, page, options, configurator.annotationRendering);
        if (pageBitmap == null) {
            postRenderFailed(generation, page);
            return;
        }

        Bitmap reflowed;
        try {
            reflowed = processor.reflow(
                    pageBitmap,
                    options.targetWidth,
                    options.minOutputHeight,
                    options.targetTextHeightPx
            );
        } finally {
            pageBitmap.recycle();
        }

        if (isCancelled(generation)) {
            reflowed.recycle();
            return;
        }
        postRenderedPage(generation, page, reflowed);
    }

    @Nullable
    private Bitmap renderSourcePage(
            PdfiumCore core,
            PdfDocument document,
            int page,
            RenderOptions options,
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

    private int chooseRenderWidth(Size pageSize, RenderOptions options) {
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

    private void postRenderedPage(int generation, int page, Bitmap bitmap) {
        mainHandler.post(() -> {
            if (generation != loadGeneration || page < 0 || page >= pageSlots.size()) {
                bitmap.recycle();
                return;
            }
            bindRenderedPage(pageSlots.get(page), bitmap);
            recycleFarPages(
                    Math.max(0, visibleStart - PAGE_PREFETCH_RADIUS),
                    Math.min(pageSlots.size() - 1, visibleEnd + PAGE_PREFETCH_RADIUS)
            );
        });
    }

    private void bindRenderedPage(PageSlot slot, Bitmap bitmap) {
        ScrollAnchor anchor = captureScrollAnchor();
        if (slot.bitmap != null && !slot.bitmap.isRecycled()) {
            slot.bitmap.recycle();
        }
        slot.renderRequested = false;
        slot.failed = false;
        slot.bitmap = bitmap;

        if (slot.imageView == null) {
            slot.imageView = new ImageView(getContext());
            slot.imageView.setBackgroundColor(Color.WHITE);
            slot.imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        }
        slot.imageView.setImageBitmap(bitmap);

        slot.container.removeAllViews();
        slot.container.addView(slot.imageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        updateSlotHeight(slot, Math.max(1, bitmap.getHeight()));
        restoreScrollAnchorAfterLayout(anchor);
    }

    private void postRenderSkipped(int generation, int page) {
        mainHandler.post(() -> {
            if (generation == loadGeneration && page >= 0 && page < pageSlots.size()) {
                PageSlot slot = pageSlots.get(page);
                slot.renderRequested = false;
                if (slot.bitmap == null && !slot.failed) {
                    slot.placeholder.setText("Page " + (page + 1));
                }
            }
        });
    }

    private void postRenderFailed(int generation, int page) {
        mainHandler.post(() -> {
            if (generation == loadGeneration && page >= 0 && page < pageSlots.size()) {
                PageSlot slot = pageSlots.get(page);
                slot.renderRequested = false;
                slot.failed = true;
                slot.placeholder.setText("Unable to reflow page " + (page + 1));
            }
        });
    }

    private void recycleFarPages(int keepStart, int keepEnd) {
        int renderedCount = 0;
        int visibleCenter = (visibleStart + visibleEnd) / 2;

        for (int i = 0; i < pageSlots.size(); i++) {
            PageSlot slot = pageSlots.get(i);
            if (slot.bitmap == null) {
                continue;
            }
            if (i < keepStart || i > keepEnd) {
                recycleSlotBitmap(slot);
            } else {
                renderedCount++;
            }
        }

        while (renderedCount > MAX_RENDERED_PAGES) {
            PageSlot farthest = null;
            int farthestDistance = -1;
            for (PageSlot slot : pageSlots) {
                if (slot.bitmap == null || (slot.page >= visibleStart && slot.page <= visibleEnd)) {
                    continue;
                }
                int distance = Math.abs(slot.page - visibleCenter);
                if (distance > farthestDistance) {
                    farthest = slot;
                    farthestDistance = distance;
                }
            }
            if (farthest == null) {
                break;
            }
            recycleSlotBitmap(farthest);
            renderedCount--;
        }
    }

    private void resetRenderedPagesForNewViewport() {
        Configurator configurator = currentConfigurator;
        if (configurator == null) {
            return;
        }

        ScrollAnchor anchor = captureScrollAnchor();
        loadGeneration++;
        mainHandler.removeCallbacks(renderVisibleRunnable);

        currentOptions = createRenderOptions(configurator);
        for (PageSlot slot : pageSlots) {
            slot.failed = false;
            slot.estimatedHeight = estimatePageHeight(slot.pageSize, currentOptions);
            slot.currentHeight = slot.estimatedHeight;
            recycleSlotBitmap(slot);
        }
        restoreScrollAnchorAfterLayout(anchor);
    }

    private void recycleSlotBitmap(PageSlot slot) {
        if (slot.bitmap != null && !slot.bitmap.isRecycled()) {
            slot.bitmap.recycle();
        }
        slot.bitmap = null;
        slot.renderRequested = false;
        if (slot.imageView != null) {
            slot.imageView.setImageDrawable(null);
        }
        slot.container.removeAllViews();
        slot.placeholder.setText(slot.failed ? "Unable to reflow page " + (slot.page + 1) : "Page " + (slot.page + 1));
        slot.container.addView(slot.placeholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        updateSlotHeight(slot, slot.currentHeight);
    }

    private void updateSlotHeight(PageSlot slot, int height) {
        slot.currentHeight = Math.max(1, height);
        slot.layoutParams.height = slot.currentHeight;
        slot.container.setLayoutParams(slot.layoutParams);
    }

    @Nullable
    private ScrollAnchor captureScrollAnchor() {
        if (pageSlots.isEmpty()) {
            return null;
        }

        int scrollY = getScrollY();
        for (PageSlot slot : pageSlots) {
            int top = slot.container.getTop();
            int bottom = slot.container.getBottom();
            if (bottom > scrollY) {
                return new ScrollAnchor(slot.page, Math.max(0, scrollY - top), scrollY);
            }
        }

        PageSlot lastSlot = pageSlots.get(pageSlots.size() - 1);
        return new ScrollAnchor(
                lastSlot.page,
                Math.max(0, scrollY - lastSlot.container.getTop()),
                scrollY
        );
    }

    private void restoreScrollAnchorAfterLayout(@Nullable ScrollAnchor anchor) {
        if (anchor == null) {
            return;
        }

        pagesContainer.post(() -> {
            if (anchor.page < 0 || anchor.page >= pageSlots.size()) {
                return;
            }
            if (Math.abs(getScrollY() - anchor.scrollY) > dp(8)) {
                return;
            }

            PageSlot slot = pageSlots.get(anchor.page);
            int maxOffset = Math.max(0, slot.currentHeight - 1);
            int targetScrollY = slot.container.getTop() + Math.min(anchor.offsetFromPageTop, maxOffset);
            scrollTo(0, Math.max(0, targetScrollY));
            scheduleVisibleRender();
        });
    }

    private boolean isNearVisibleRange(int page) {
        return page >= visibleStart - PAGE_PREFETCH_RADIUS && page <= visibleEnd + PAGE_PREFETCH_RADIUS;
    }

    private boolean isCancelled(int generation) {
        return generation != loadGeneration || Thread.currentThread().isInterrupted();
    }

    private int getPageWidth() {
        return Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
    }

    private void postError(Configurator configurator, int generation, Throwable throwable) {
        mainHandler.post(() -> {
            if (generation != loadGeneration) {
                return;
            }
            showStatus("Unable to reflow PDF");
            if (configurator.onErrorListener != null) {
                configurator.onErrorListener.onError(throwable);
            } else {
                Log.e(TAG, "Unable to reflow PDF", throwable);
            }
        });
    }

    private void showStatus(String message) {
        if (statusView == null) {
            statusView = new TextView(getContext());
            statusView.setGravity(Gravity.CENTER);
            statusView.setTextColor(Color.DKGRAY);
            statusView.setTextSize(16f);
            int padding = dp(24);
            statusView.setPadding(padding, padding, padding, padding);
        }
        statusView.setText(message);
        if (statusView.getParent() == null && pagesContainer.getChildCount() == 0) {
            pagesContainer.addView(statusView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
    }

    private void removeStatus() {
        if (statusView != null && statusView.getParent() == pagesContainer) {
            pagesContainer.removeView(statusView);
        }
    }

    private void disposeCurrentLoad() {
        loadGeneration++;
        mainHandler.removeCallbacks(renderVisibleRunnable);

        ExecutorService executor = executorService;
        PdfiumCore core = pdfiumCore;
        PdfDocument document = pdfDocument;

        executorService = null;
        pdfiumCore = null;
        pdfDocument = null;
        currentOptions = null;
        currentConfigurator = null;

        for (PageSlot slot : pageSlots) {
            if (slot.bitmap != null && !slot.bitmap.isRecycled()) {
                slot.bitmap.recycle();
            }
            slot.bitmap = null;
            slot.renderRequested = false;
        }

        if (core != null && document != null) {
            if (executor != null) {
                try {
                    executor.execute(() -> closeDocument(core, document));
                } catch (RejectedExecutionException ignored) {
                    closeDocumentInBackground(core, document);
                }
            } else {
                closeDocumentInBackground(core, document);
            }
        }
        if (executor != null) {
            executor.shutdown();
        }
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

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public interface OnLoadCompleteListener {
        void loadComplete(int pagesCount);
    }

    public interface OnErrorListener {
        void onError(Throwable throwable);
    }

    public class Configurator {

        private final DocumentSource documentSource;

        @Nullable
        private String password;
        @Nullable
        private OnLoadCompleteListener onLoadCompleteListener;
        @Nullable
        private OnErrorListener onErrorListener;

        private boolean annotationRendering = false;
        private int pageSpacingDp = 12;
        private float textSizeDp = DEFAULT_TEXT_SIZE_DP;
        private float sourceScale = DEFAULT_SOURCE_SCALE;
        private int maxSourceWidthPx = DEFAULT_MAX_SOURCE_WIDTH;
        private int maxSourcePixels = DEFAULT_MAX_SOURCE_PIXELS;

        private Configurator(DocumentSource documentSource) {
            this.documentSource = documentSource;
        }

        public Configurator password(@Nullable String password) {
            this.password = password;
            return this;
        }

        public Configurator enableAnnotationRendering(boolean annotationRendering) {
            this.annotationRendering = annotationRendering;
            return this;
        }

        public Configurator spacing(int spacingDp) {
            this.pageSpacingDp = Math.max(0, spacingDp);
            return this;
        }

        public Configurator textSizeDp(float textSizeDp) {
            if (textSizeDp > 0f) {
                this.textSizeDp = textSizeDp;
            }
            return this;
        }

        public Configurator sourceScale(float sourceScale) {
            if (sourceScale >= 1f) {
                this.sourceScale = sourceScale;
            }
            return this;
        }

        public Configurator maxSourceWidth(int maxSourceWidthPx) {
            if (maxSourceWidthPx > 0) {
                this.maxSourceWidthPx = maxSourceWidthPx;
            }
            return this;
        }

        public Configurator maxSourcePixels(int maxSourcePixels) {
            if (maxSourcePixels > 0) {
                this.maxSourcePixels = maxSourcePixels;
            }
            return this;
        }

        public Configurator onLoad(OnLoadCompleteListener onLoadCompleteListener) {
            this.onLoadCompleteListener = onLoadCompleteListener;
            return this;
        }

        public Configurator onError(OnErrorListener onErrorListener) {
            this.onErrorListener = onErrorListener;
            return this;
        }

        public void load() {
            PDFReflowView.this.load(this);
        }

        private Configurator copy() {
            Configurator copy = new Configurator(documentSource);
            copy.password = password;
            copy.onLoadCompleteListener = onLoadCompleteListener;
            copy.onErrorListener = onErrorListener;
            copy.annotationRendering = annotationRendering;
            copy.pageSpacingDp = pageSpacingDp;
            copy.textSizeDp = textSizeDp;
            copy.sourceScale = sourceScale;
            copy.maxSourceWidthPx = maxSourceWidthPx;
            copy.maxSourcePixels = maxSourcePixels;
            return copy;
        }
    }

    private static final class RenderOptions {
        final int targetWidth;
        final int minOutputHeight;
        final int pageSpacingPx;
        final int targetTextHeightPx;
        final float sourceScale;
        final int maxSourceWidthPx;
        final int maxSourcePixels;

        private RenderOptions(
                int targetWidth,
                int minOutputHeight,
                int pageSpacingPx,
                int targetTextHeightPx,
                float sourceScale,
                int maxSourceWidthPx,
                int maxSourcePixels
        ) {
            this.targetWidth = targetWidth;
            this.minOutputHeight = minOutputHeight;
            this.pageSpacingPx = pageSpacingPx;
            this.targetTextHeightPx = targetTextHeightPx;
            this.sourceScale = sourceScale;
            this.maxSourceWidthPx = maxSourceWidthPx;
            this.maxSourcePixels = maxSourcePixels;
        }

        static RenderOptions from(
                Configurator configurator,
                int targetWidth,
                int minOutputHeight,
                int spacingPx,
                int targetTextHeightPx
        ) {
            return new RenderOptions(
                    targetWidth,
                    minOutputHeight,
                    spacingPx,
                    targetTextHeightPx,
                    configurator.sourceScale,
                    configurator.maxSourceWidthPx,
                    configurator.maxSourcePixels
            );
        }
    }

    private static final class PageSlot {
        final int page;
        final Size pageSize;
        final FrameLayout container;
        final TextView placeholder;
        final LinearLayout.LayoutParams layoutParams;
        int estimatedHeight;
        int currentHeight;

        @Nullable
        ImageView imageView;
        @Nullable
        Bitmap bitmap;

        boolean renderRequested;
        boolean failed;

        PageSlot(
                int page,
                Size pageSize,
                FrameLayout container,
                TextView placeholder,
                LinearLayout.LayoutParams layoutParams,
                int estimatedHeight
        ) {
            this.page = page;
            this.pageSize = pageSize;
            this.container = container;
            this.placeholder = placeholder;
            this.layoutParams = layoutParams;
            this.estimatedHeight = estimatedHeight;
            this.currentHeight = estimatedHeight;
        }
    }

    private static final class ScrollAnchor {
        final int page;
        final int offsetFromPageTop;
        final int scrollY;

        ScrollAnchor(int page, int offsetFromPageTop, int scrollY) {
            this.page = page;
            this.offsetFromPageTop = offsetFromPageTop;
            this.scrollY = scrollY;
        }
    }
}
