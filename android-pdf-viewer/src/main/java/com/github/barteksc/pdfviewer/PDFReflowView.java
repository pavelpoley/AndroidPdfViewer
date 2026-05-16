package com.github.barteksc.pdfviewer;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.barteksc.pdfviewer.source.AssetSource;
import com.github.barteksc.pdfviewer.source.ByteArraySource;
import com.github.barteksc.pdfviewer.source.DocumentSource;
import com.github.barteksc.pdfviewer.source.FileSource;
import com.github.barteksc.pdfviewer.source.InputStreamSource;
import com.github.barteksc.pdfviewer.source.UriSource;
import com.vivlio.android.pdfium.util.Size;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Experimental bitmap-based PDF reading mode.
 * <p>
 * This view lazily renders PDF pages, reflows their bitmap text, and stacks
 * the results in a continuous vertical scroll.
 */
@SuppressWarnings("unused")
public class PDFReflowView extends ScrollView {

    private static final String TAG = PDFReflowView.class.getSimpleName();
    private static final float DEFAULT_SOURCE_SCALE = 1.6f;
    private static final float DEFAULT_TEXT_SIZE_DP = 15f;
    private static final int DEFAULT_MAX_SOURCE_WIDTH = 2200;
    private static final int DEFAULT_MAX_SOURCE_PIXELS = 3_200_000;
    private static final float DEFAULT_MIN_TEXT_SIZE_DP = 12f;
    private static final float DEFAULT_MAX_TEXT_SIZE_DP = 28f;
    private static final float MIN_TEXT_SIZE_COMMIT_DELTA_DP = 0.1f;
    private static final long MIN_REFLOW_CACHE_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_REFLOW_CACHE_BYTES = 48L * 1024L * 1024L;
    private static final int NO_PENDING_JUMP_PAGE = -1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LinearLayout pagesContainer;
    private final List<ReflowPageSlot> pageSlots = new ArrayList<>();
    private final ScaleGestureDetector scaleGestureDetector;

    @Nullable
    private Configurator waitingDocumentConfigurator;
    @Nullable
    private TextView statusView;
    @Nullable
    private ProgressBar statusLoadingIndicator;
    @Nullable
    private ReflowDocumentSession documentSession;
    @Nullable
    private ReflowRenderCoordinator renderCoordinator;
    @Nullable
    private ReflowLoadConfig currentConfig;
    @Nullable
    private ViewTreeObserver.OnPreDrawListener pendingJumpPreDrawListener;
    @Nullable
    private Configurator loadedDocumentConfigurator;
    @Nullable
    private OnTextSizeChangedListener onTextSizeChangedListener;

    private int pendingJumpPage = NO_PENDING_JUMP_PAGE;
    private boolean pendingJumpSmooth;
    private boolean reflowZoomEnabled;
    private boolean reflowScaling;
    private boolean multiTouchActive;
    private float minTextSizeDp = DEFAULT_MIN_TEXT_SIZE_DP;
    private float maxTextSizeDp = DEFAULT_MAX_TEXT_SIZE_DP;
    private float currentTextSizeDp = DEFAULT_TEXT_SIZE_DP;
    private float gestureStartTextSizeDp = DEFAULT_TEXT_SIZE_DP;
    private float gestureScale = 1f;
    private int gestureStartPage;
    private volatile int loadGeneration = 0;

    public PDFReflowView(Context context) {
        this(context, null);
    }

    public PDFReflowView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PDFReflowView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        scaleGestureDetector = new ScaleGestureDetector(context, new ReflowScaleListener());
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
        if (!reflowScaling) {
            scheduleVisibleRender();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (reflowZoomEnabled && currentConfig != null) {
            int action = event.getActionMasked();
            scaleGestureDetector.onTouchEvent(event);
            if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN) {
                multiTouchActive = true;
                requestParentDisallowIntercept(true);
            }

            if (multiTouchActive || reflowScaling) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    finishTemporaryReflowScale();
                    multiTouchActive = false;
                    requestParentDisallowIntercept(false);
                }
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        recycle();
        super.onDetachedFromWindow();
    }

    public void recycle() {
        waitingDocumentConfigurator = null;
        loadedDocumentConfigurator = null;
        pendingJumpPage = NO_PENDING_JUMP_PAGE;
        clearPendingJumpRetry();
        resetTemporaryReflowScale();
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

    public int getPageCount() {
        return pageSlots.size();
    }

    public int getCurrentPage() {
        if (pageSlots.isEmpty()) {
            return Math.max(0, pendingJumpPage);
        }

        int viewportCenter = getScrollY() + Math.max(1, getHeight()) / 2;
        for (ReflowPageSlot slot : pageSlots) {
            if (viewportCenter <= slot.container.getBottom()) {
                return slot.page;
            }
        }
        return pageSlots.get(pageSlots.size() - 1).page;
    }

    public void jumpTo(int page) {
        jumpTo(page, false);
    }

    public void jumpTo(int page, boolean smooth) {
        int targetPage = clampPage(page);
        if (!canResolvePagePosition(targetPage)) {
            pendingJumpPage = targetPage;
            pendingJumpSmooth = smooth;
            requestPendingJumpRetry();
            return;
        }

        pendingJumpPage = NO_PENDING_JUMP_PAGE;
        int targetScrollY = pageSlots.get(targetPage).container.getTop();
        if (smooth) {
            smoothScrollTo(0, targetScrollY);
        } else {
            scrollTo(0, targetScrollY);
        }
        scheduleVisibleRender();
    }

    public void setReflowZoomEnabled(boolean enabled) {
        reflowZoomEnabled = enabled;
        if (!enabled) {
            multiTouchActive = false;
            finishTemporaryReflowScale();
        }
    }

    public void setTextSizeRangeDp(float minTextSizeDp, float maxTextSizeDp) {
        if (minTextSizeDp <= 0f || maxTextSizeDp <= 0f || minTextSizeDp > maxTextSizeDp) {
            return;
        }
        this.minTextSizeDp = minTextSizeDp;
        this.maxTextSizeDp = maxTextSizeDp;
        setTextSizeDp(currentTextSizeDp);
    }

    public void setTextSizeDp(float textSizeDp) {
        commitTextSizeDp(clampTextSize(textSizeDp), getCurrentPage());
    }

    public float getTextSizeDp() {
        return currentTextSizeDp;
    }

    public void setOnTextSizeChangedListener(@Nullable OnTextSizeChangedListener listener) {
        onTextSizeChangedListener = listener;
    }

    private void load(@NonNull Configurator configurator) {
        Configurator loadConfigurator = configurator.copy();
        loadConfigurator.textSizeDp = clampTextSize(loadConfigurator.textSizeDp);
        currentTextSizeDp = loadConfigurator.textSizeDp;
        if (getWidth() == 0) {
            waitingDocumentConfigurator = loadConfigurator.copy();
            loadedDocumentConfigurator = loadConfigurator.copy();
            return;
        }

        clearPendingJumpRetry();
        disposeCurrentLoad();
        loadedDocumentConfigurator = loadConfigurator.copy();
        pagesContainer.removeAllViews();
        pageSlots.clear();
        scrollTo(0, 0);

        int generation = ++loadGeneration;
        ReflowLoadConfig loadConfig = loadConfigurator.toLoadConfig();
        ReflowRenderOptions options = createRenderOptions(loadConfig);

        showStatusLoading();
        ReflowDocumentSession session = new ReflowDocumentSession(
                getContext().getApplicationContext(),
                mainHandler,
                loadConfig
        );
        documentSession = session;
        session.open(new ReflowDocumentSession.OpenCallback() {
            @Override
            public void onOpened(ReflowDocumentSession openedSession, Size[] pageSizes) {
                setupDocument(loadConfig, options, generation, openedSession, pageSizes);
            }

            @Override
            public void onError(Throwable throwable) {
                postError(loadConfig, generation, throwable);
            }

            @Override
            public boolean isCancelled() {
                return generation != loadGeneration || Thread.currentThread().isInterrupted();
            }
        });
    }

    private ReflowRenderOptions createRenderOptions(ReflowLoadConfig loadConfig) {
        int targetWidth = getPageWidth();
        int minOutputHeight = Math.max(1, getHeight() / 2);
        return new ReflowRenderOptions(
                targetWidth,
                minOutputHeight,
                dp(loadConfig.pageSpacingDp),
                Math.max(1, dp(loadConfig.textSizeDp)),
                loadConfig.sourceScale,
                loadConfig.maxSourceWidthPx,
                loadConfig.maxSourcePixels,
                calculateMaxCachedBitmapBytes()
        );
    }

    private void setupDocument(
            ReflowLoadConfig loadConfig,
            ReflowRenderOptions options,
            int generation,
            ReflowDocumentSession session,
            Size[] pageSizes
    ) {
        if (generation != loadGeneration) {
            session.close();
            return;
        }

        removeStatus();
        currentConfig = loadConfig;
        pageSlots.clear();
        pagesContainer.removeAllViews();

        for (int page = 0; page < pageSizes.length; page++) {
            ReflowPageSlot slot = ReflowPageSlot.create(getContext(), page, pageSizes[page], options, dp(16));
            pageSlots.add(slot);
            pagesContainer.addView(slot.container, slot.layoutParams);
        }

        renderCoordinator = new ReflowRenderCoordinator(
                mainHandler,
                this,
                pagesContainer,
                pageSlots,
                session,
                loadConfig,
                options
        );

        if (loadConfig.onLoadCompleteListener != null) {
            loadConfig.onLoadCompleteListener.loadComplete(pageSizes.length);
        }

        if (pendingJumpPage != NO_PENDING_JUMP_PAGE) {
            requestPendingJumpRetry();
        }
        pagesContainer.post(() -> {
            if (pendingJumpPage != NO_PENDING_JUMP_PAGE) {
                applyPendingJumpIfReady();
            } else {
                scheduleVisibleRender();
            }
        });
    }

    private void scheduleVisibleRender() {
        if (renderCoordinator != null) {
            renderCoordinator.scheduleVisibleRender();
        }
    }

    private void resetRenderedPagesForNewViewport() {
        ReflowLoadConfig loadConfig = currentConfig;
        ReflowRenderCoordinator coordinator = renderCoordinator;
        if (loadConfig == null || coordinator == null) {
            return;
        }
        coordinator.resetForViewport(createRenderOptions(loadConfig));
    }

    private void reloadCurrentDocumentAtTextSize(int page) {
        Configurator configurator = loadedDocumentConfigurator;
        if (configurator == null) {
            return;
        }
        Configurator reloadConfigurator = configurator.copy();
        reloadConfigurator.textSizeDp = currentTextSizeDp;
        load(reloadConfigurator);
        jumpTo(page);
    }

    private int getPageWidth() {
        return Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
    }

    private void requestParentDisallowIntercept(boolean disallowIntercept) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    private void beginTemporaryReflowScale(ScaleGestureDetector detector) {
        if (pageSlots.isEmpty()) {
            return;
        }
        reflowScaling = true;
        gestureScale = 1f;
        gestureStartTextSizeDp = currentTextSizeDp;
        gestureStartPage = getCurrentPage();
        setBackgroundColor(Color.BLACK);
        updateTemporaryReflowScalePivot(detector);
    }

    private void updateTemporaryReflowScale(ScaleGestureDetector detector) {
        updateTemporaryReflowScalePivot(detector);
        float minScale = minTextSizeDp / Math.max(0.1f, gestureStartTextSizeDp);
        float maxScale = maxTextSizeDp / Math.max(0.1f, gestureStartTextSizeDp);
        gestureScale = clamp(gestureScale * detector.getScaleFactor(), minScale, maxScale);
        pagesContainer.setScaleX(gestureScale);
        pagesContainer.setScaleY(gestureScale);
    }

    private void updateTemporaryReflowScalePivot(ScaleGestureDetector detector) {
        pagesContainer.setPivotX(detector.getFocusX() + getScrollX() - pagesContainer.getLeft());
        pagesContainer.setPivotY(detector.getFocusY() + getScrollY() - pagesContainer.getTop());
    }

    private void finishTemporaryReflowScale() {
        if (!reflowScaling) {
            return;
        }
        reflowScaling = false;
        float committedTextSizeDp = clampTextSize(gestureStartTextSizeDp * gestureScale);
        int targetPage = gestureStartPage;
        resetTemporaryReflowScale();

        if (Math.abs(committedTextSizeDp - currentTextSizeDp) < MIN_TEXT_SIZE_COMMIT_DELTA_DP) {
            jumpTo(targetPage);
            return;
        }
        commitTextSizeDp(committedTextSizeDp, targetPage);
    }

    private void resetTemporaryReflowScale() {
        reflowScaling = false;
        gestureScale = 1f;
        pagesContainer.setScaleX(1f);
        pagesContainer.setScaleY(1f);
        pagesContainer.setPivotX(0f);
        pagesContainer.setPivotY(0f);
        setBackgroundColor(Color.WHITE);
    }

    private void commitTextSizeDp(float textSizeDp, int targetPage) {
        float nextTextSizeDp = clampTextSize(textSizeDp);
        if (Math.abs(nextTextSizeDp - currentTextSizeDp) < MIN_TEXT_SIZE_COMMIT_DELTA_DP) {
            return;
        }
        currentTextSizeDp = nextTextSizeDp;
        if (loadedDocumentConfigurator != null) {
            reloadCurrentDocumentAtTextSize(targetPage);
        }
        if (onTextSizeChangedListener != null) {
            onTextSizeChangedListener.onTextSizeChanged(currentTextSizeDp);
        }
    }

    private float clampTextSize(float textSizeDp) {
        return clamp(textSizeDp, minTextSizeDp, maxTextSizeDp);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampPage(int page) {
        if (pageSlots.isEmpty()) {
            return Math.max(0, page);
        }
        return Math.max(0, Math.min(pageSlots.size() - 1, page));
    }

    private boolean canResolvePagePosition(int page) {
        return !pageSlots.isEmpty()
                && page >= 0
                && page < pageSlots.size()
                && pagesContainer.isLaidOut()
                && pageSlots.get(page).container.isLaidOut();
    }

    private void requestPendingJumpRetry() {
        if (pageSlots.isEmpty() || pendingJumpPreDrawListener != null) {
            return;
        }
        pendingJumpPreDrawListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                clearPendingJumpRetry();
                applyPendingJumpIfReady();
                return true;
            }
        };
        pagesContainer.getViewTreeObserver().addOnPreDrawListener(pendingJumpPreDrawListener);
    }

    private void clearPendingJumpRetry() {
        if (pendingJumpPreDrawListener == null) {
            return;
        }
        ViewTreeObserver observer = pagesContainer.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnPreDrawListener(pendingJumpPreDrawListener);
        }
        pendingJumpPreDrawListener = null;
    }

    private void applyPendingJumpIfReady() {
        if (pendingJumpPage == NO_PENDING_JUMP_PAGE) {
            return;
        }
        int targetPage = clampPage(pendingJumpPage);
        if (!canResolvePagePosition(targetPage)) {
            return;
        }
        boolean smooth = pendingJumpSmooth;
        pendingJumpPage = NO_PENDING_JUMP_PAGE;
        clearPendingJumpRetry();
        jumpTo(targetPage, smooth);
    }

    private void postError(ReflowLoadConfig loadConfig, int generation, Throwable throwable) {
        mainHandler.post(() -> {
            if (generation != loadGeneration) {
                return;
            }
            showStatus("Unable to reflow PDF");
            if (loadConfig.onErrorListener != null) {
                loadConfig.onErrorListener.onError(throwable);
            } else {
                Log.e(TAG, "Unable to reflow PDF", throwable);
            }
        });
    }

    private void showStatus(String message) {
        removeStatusLoading();
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

    private void showStatusLoading() {
        removeStatus();
        if (statusLoadingIndicator == null) {
            statusLoadingIndicator = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleSmall);
            statusLoadingIndicator.setIndeterminate(true);
        }
        if (statusLoadingIndicator.getParent() == null && pagesContainer.getChildCount() == 0) {
            pagesContainer.addView(statusLoadingIndicator, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
    }

    private void removeStatus() {
        if (statusView != null && statusView.getParent() == pagesContainer) {
            pagesContainer.removeView(statusView);
        }
        removeStatusLoading();
    }

    private void removeStatusLoading() {
        if (statusLoadingIndicator != null && statusLoadingIndicator.getParent() == pagesContainer) {
            pagesContainer.removeView(statusLoadingIndicator);
        }
    }

    private void disposeCurrentLoad() {
        loadGeneration++;

        if (renderCoordinator != null) {
            renderCoordinator.dispose();
            renderCoordinator = null;
        }

        ReflowDocumentSession session = documentSession;
        documentSession = null;
        currentConfig = null;

        for (ReflowPageSlot slot : pageSlots) {
            slot.releaseBitmap();
        }

        if (session != null) {
            session.close();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private long calculateMaxCachedBitmapBytes() {
        long runtimeBudget = Runtime.getRuntime().maxMemory() / 8L;
        return Math.max(MIN_REFLOW_CACHE_BYTES, Math.min(MAX_REFLOW_CACHE_BYTES, runtimeBudget));
    }

    public interface OnLoadCompleteListener {
        void loadComplete(int pagesCount);
    }

    public interface OnErrorListener {
        void onError(Throwable throwable);
    }

    public interface OnTextSizeChangedListener {
        void onTextSizeChanged(float textSizeDp);
    }

    private class ReflowScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            beginTemporaryReflowScale(detector);
            return reflowScaling;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (!reflowScaling) {
                return false;
            }
            updateTemporaryReflowScale(detector);
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            finishTemporaryReflowScale();
        }
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

        private ReflowLoadConfig toLoadConfig() {
            return new ReflowLoadConfig(
                    documentSource,
                    password,
                    onLoadCompleteListener,
                    onErrorListener,
                    annotationRendering,
                    pageSpacingDp,
                    textSizeDp,
                    sourceScale,
                    maxSourceWidthPx,
                    maxSourcePixels
            );
        }
    }
}
